package com.nextnotif.app

import android.content.Context
import org.json.JSONObject
import org.webrtc.*
import org.webrtc.audio.JavaAudioDeviceModule
import java.util.concurrent.Executors
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.Semaphore
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean

/** Audio-only peer engine selected by the live-call service.
 * Caller owns permissions, audio mode, mixer routing, session timers and signaling
 * authentication. No PCM, SDP, candidates or TURN credentials are logged here.
 */
internal class WebRtcCallPeer(
    context: Context,
    private val role: Role,
    private val sessionId: String,
    private val capability: GatewayCapability,
    private val iceServers: List<PeerConnection.IceServer>,
    private val forceRelay: Boolean = false,
    private val audioEnabled: Boolean = true,
    initiallyMuted: Boolean = false,
    private val sendSignal: (JSONObject) -> Boolean,
    private val onState: (PeerConnection.PeerConnectionState) -> Unit,
    private val onError: (String) -> Unit,
) : AutoCloseable {
    private val appContext = context.applicationContext
    private val executor = Executors.newSingleThreadExecutor { task -> Thread(task, "nextnotif-webrtc-peer") }
    // Bound admission, not only the ICE buffer after dequeue. Cleanup bypasses
    // this permit gate and closed queued actions are skipped promptly.
    private val admission = Semaphore(128)
    private val microphone = WebRtcMicrophoneState(audioEnabled, initiallyMuted)
    private val closed = AtomicBoolean(false)
    private val disposed = CountDownLatch(1)
    private var started = false
    private var remoteDescriptionStarted = false
    private var remoteDescriptionReady = false
    private var localDescriptionSent = false
    private val remoteIce = EarlyIceCandidates(sessionId)
    private val remoteIceBudget = IceCandidateBudget()
    private val localIce = EarlyIceCandidates(sessionId)
    private var factory: PeerConnectionFactory? = null
    private var module: JavaAudioDeviceModule? = null
    private var peer: PeerConnection? = null
    private var source: AudioSource? = null
    private var track: AudioTrack? = null
    private val statsPending = AtomicBoolean(false)

    /** At most one native stats request; only aggregate received audio RTP escapes. */
    fun requestReceivedPackets(onResult: (Long?) -> Unit) = dispatch {
        val current = peer ?: return@dispatch
        if (!statsPending.compareAndSet(false, true)) return@dispatch
        try { current.getStats { report ->
            statsPending.set(false)
            dispatch {
                onResult(WebRtcReceivedAudioStats.packets(report))
            }
        } } catch (error: Exception) { statsPending.set(false); throw error }
    }

    fun start() = dispatch {
        if (started) return@dispatch
        started = true
        require(!forceRelay || iceServers.isNotEmpty()) { "Relay ICE configuration unavailable" }
        WebRtcAudioDevicePolicy.forRole(role, capability)
        initialize(appContext)
        module = WebRtcAudioDeviceFactory.create(appContext, role, capability) { fail(it) }
        factory = PeerConnectionFactory.builder().setAudioDeviceModule(module).createPeerConnectionFactory()
        val config = PeerConnection.RTCConfiguration(iceServers).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE
            rtcpMuxPolicy = PeerConnection.RtcpMuxPolicy.REQUIRE
            iceTransportsType = if (forceRelay) PeerConnection.IceTransportsType.RELAY else PeerConnection.IceTransportsType.ALL
        }
        peer = checkNotNull(factory!!.createPeerConnection(config, observer)) { "Peer initialization failed" }
        // Allows negotiation before user-visible audio starts and safe native
        // transport tests. Muting a track alone does not disable device capture.
        peer!!.setAudioRecording(audioEnabled)
        peer!!.setAudioPlayout(audioEnabled)
        val constraints = MediaConstraints().apply {
            val processMic = (role == Role.RECEIVER).toString()
            optional.add(MediaConstraints.KeyValuePair("googEchoCancellation", processMic))
            optional.add(MediaConstraints.KeyValuePair("googNoiseSuppression", processMic))
            optional.add(MediaConstraints.KeyValuePair("googAutoGainControl", processMic))
        }
        source = factory!!.createAudioSource(constraints)
        track = factory!!.createAudioTrack("nextnotif-audio", source)
        track!!.setEnabled(microphone.trackEnabled)
        val transceiver = peer!!.addTransceiver(track)
        val opus = WebRtcOpusPolicy.preferences(factory!!.getRtpSenderCapabilities(
            MediaStreamTrack.MediaType.MEDIA_TYPE_AUDIO).codecs)
        check(opus.isNotEmpty()) { "Opus unavailable" }
        transceiver.setCodecPreferences(opus).throwError()
        if (role == Role.RECEIVER) createDescription(offer = true)
    }

    fun receive(data: JSONObject, from: Role) {
        if (from == role) return
        val signal = WebRtcSignal.parse(data, sessionId, from) ?: return
        dispatch {
            check(started && peer != null) { "Peer is not ready" }
            when (signal) {
                is WebRtcSignal.Candidate -> when (remoteIceBudget.admit(signal)) {
                    IceCandidateBudget.Admission.DUPLICATE -> Unit
                    IceCandidateBudget.Admission.OVERFLOW -> error("Too many remote ICE candidates")
                    IceCandidateBudget.Admission.NEW -> if (remoteDescriptionReady) addIce(signal)
                        else check(remoteIce.offer(signal)) { "Too many early ICE candidates" }
                }
                is WebRtcSignal.Description -> {
                    check(WebRtcOpusPolicy.continuous(signal.sdp)) { "Continuous Opus is required" }
                    check(!remoteDescriptionStarted) { "Duplicate remote description" }
                    check(signal.sdp.lineSequence().filter { it.startsWith("m=") }.all { it.startsWith("m=audio ") }) {
                        "Only audio negotiation is supported"
                    }
                    remoteDescriptionStarted = true
                    val description = SessionDescription(if (signal.offer) SessionDescription.Type.OFFER else SessionDescription.Type.ANSWER, signal.sdp)
                    peer!!.setRemoteDescription(sdpObserver(onSet = {
                        remoteDescriptionReady = true
                        remoteIce.drain().forEach(::addIce)
                        if (signal.offer) createDescription(offer = false)
                    }), description)
                }
            }
        }
    }

    fun setMuted(muted: Boolean) = dispatch {
        microphone.muted = muted
        track?.setEnabled(microphone.trackEnabled)
    }

    private fun createDescription(offer: Boolean) {
        val callback = sdpObserver(onCreate = { description ->
            check(WebRtcOpusPolicy.continuous(description.description)) { "Continuous Opus is required" }
            peer!!.setLocalDescription(sdpObserver(onSet = {
                send(WebRtcSignal.Description(sessionId, offer, description.description))
                localDescriptionSent = true
                localIce.drain().forEach(::send)
            }), description)
        })
        if (offer) peer!!.createOffer(callback, MediaConstraints()) else peer!!.createAnswer(callback, MediaConstraints())
    }

    private fun addIce(candidate: WebRtcSignal.Candidate) {
        peer!!.addIceCandidate(IceCandidate(candidate.mid, candidate.line, candidate.candidate), object : AddIceObserver {
            override fun onAddSuccess() = Unit
            override fun onAddFailure(error: String) = fail("WebRTC rejected an ICE candidate")
        })
    }

    private fun send(signal: WebRtcSignal) { check(sendSignal(signal.toJson())) { "Call signaling unavailable" } }

    private fun sdpObserver(onSet: () -> Unit = {}, onCreate: (SessionDescription) -> Unit = {}) = object : SdpObserver {
        override fun onCreateSuccess(description: SessionDescription) = dispatch { onCreate(description) }
        override fun onSetSuccess() = dispatch(onSet)
        override fun onCreateFailure(error: String) = fail("WebRTC could not create a description")
        override fun onSetFailure(error: String) = fail("WebRTC rejected a description")
    }

    private val observer = object : PeerConnection.Observer {
        override fun onConnectionChange(state: PeerConnection.PeerConnectionState) = dispatch {
            onState(state)
            if (state == PeerConnection.PeerConnectionState.FAILED) fail("WebRTC connection failed")
        }
        override fun onIceCandidate(candidate: IceCandidate) = dispatch {
            val signal = WebRtcSignal.Candidate(sessionId, candidate.sdpMid, candidate.sdpMLineIndex, candidate.sdp)
            if (localDescriptionSent) send(signal) else check(localIce.offer(signal)) { "Too many local ICE candidates" }
        }
        override fun onTrack(transceiver: RtpTransceiver) = dispatch {
            check(transceiver.mediaType == MediaStreamTrack.MediaType.MEDIA_TYPE_AUDIO) { "Unexpected non-audio track" }
        }
        override fun onDataChannel(channel: DataChannel) = dispatch { channel.close(); fail("Unexpected data channel") }
        override fun onSignalingChange(state: PeerConnection.SignalingState) = Unit
        override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) = Unit
        override fun onIceConnectionReceivingChange(receiving: Boolean) = Unit
        override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) = Unit
        override fun onIceCandidatesRemoved(candidates: Array<IceCandidate>) = Unit
        override fun onAddStream(stream: MediaStream) = Unit
        override fun onRemoveStream(stream: MediaStream) = Unit
        override fun onRenegotiationNeeded() = Unit
    }

    private fun dispatch(action: () -> Unit) {
        if (closed.get()) return
        if (!admission.tryAcquire()) { fail("WebRTC signaling overload"); return }
        try { executor.execute {
            try {
                if (!closed.get()) try { action() } catch (_: Exception) { fail("WebRTC call operation failed") }
            } finally { admission.release() }
        } } catch (_: RejectedExecutionException) { admission.release() /* closed concurrently */ }
    }

    private fun fail(message: String) {
        if (!closed.compareAndSet(false, true)) return
        executor.execute { try { dispose(); onError(message) } finally { disposed.countDown(); executor.shutdown() } }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        executor.execute { try { dispose() } finally { disposed.countDown(); executor.shutdown() } }
    }

    /** Never call from this peer's callback/executor thread. */
    fun awaitClosed(timeoutMs: Long): Boolean = disposed.await(timeoutMs, TimeUnit.MILLISECONDS)

    private fun dispose() {
        remoteIce.clear(); localIce.clear()
        runCatching { peer?.dispose() }; peer = null
        runCatching { track?.dispose() }; track = null
        runCatching { source?.dispose() }; source = null
        runCatching { factory?.dispose() }; factory = null
        runCatching { module?.release() }; module = null
    }

    companion object {
        private var initialized = false
        @Synchronized private fun initialize(context: Context) {
            if (!initialized) {
                PeerConnectionFactory.initialize(PeerConnectionFactory.InitializationOptions.builder(context).createInitializationOptions())
                initialized = true
            }
        }
    }
}
