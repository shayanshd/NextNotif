package com.nextnotif.app

import android.content.Context
import android.media.AudioManager
import org.json.JSONObject
import org.webrtc.PeerConnection
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Owns device audio around WebRTC for the call service.
 * The service must still check explicit opt-in/permissions and must never run
 * this alongside the prototype CallAudioBridge. State callbacks use this
 * lifecycle executor, not Android's main thread.
 */
internal class WebRtcCallAudioBridge(
    context: Context,
    private val role: Role,
    private val sessionId: String,
    private val capability: GatewayCapability,
    private val iceServers: List<PeerConnection.IceServer>,
    private val forceRelay: Boolean = false,
    private val sendSignal: (JSONObject) -> Boolean,
    private val onState: (PeerConnection.PeerConnectionState) -> Unit,
    private val onError: (String) -> Unit,
    private val onReady: () -> Unit = {},
    private val onStopped: () -> Unit = {},
) : AutoCloseable {
    private val appContext = context.applicationContext
    private val lifecycle = Executors.newSingleThreadExecutor { task -> Thread(task, "nextnotif-webrtc-audio") }
    private val closed = AtomicBoolean(false)
    private val started = AtomicBoolean(false)
    private val muted = AtomicBoolean(false)
    private val stopped = CountDownLatch(1)
    @Volatile private var peer: WebRtcCallPeer? = null
    private var ownsLease = false
    private var audioModeChanged = false
    private var mixerAttempted = false
    private var oldMode = AudioManager.MODE_NORMAL
    private var oldSpeaker = false

    /** Accepted startup, not proof of connected media; wait for onState. */
    fun start(): Boolean {
        if (closed.get()) return false
        if (!started.compareAndSet(false, true)) return true
        try { lifecycle.execute {
            if (closed.get()) return@execute
            try {
                WebRtcAudioDevicePolicy.forRole(role, capability)
                check(lease.compareAndSet(false, true)) { "Previous WebRTC audio is still stopping" }
                ownsLease = true
                val audio = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                oldMode = audio.mode
                @Suppress("DEPRECATION")
                oldSpeaker = audio.isSpeakerphoneOn
                audioModeChanged = true
                audio.mode = AudioManager.MODE_IN_COMMUNICATION
                if (role == Role.SENDER) {
                    // Restore even if a helper partially changes the route and
                    // then fails. Route operations have bounded process waits.
                    mixerAttempted = true
                    check(GatewayUplinkRoute.enable()) { "Gateway mixer route unavailable" }
                }
                if (closed.get()) return@execute
                peer = WebRtcCallPeer(appContext, role, sessionId, capability, iceServers,
                    forceRelay = forceRelay, initiallyMuted = muted.get(), sendSignal = sendSignal,
                    onState = { state -> callback { onState(state) } },
                    onError = { message -> callback { try { onError(message) } finally { close() } } })
                peer!!.start()
                // Peer startup is already queued ahead of subsequent signals.
                // The service may now invite the receiver to negotiate.
                onReady()
            } catch (_: Exception) {
                try { onError("Could not start WebRTC device audio") } finally { close() }
            }
        } } catch (_: RejectedExecutionException) { return false }
        return true
    }

    /** False means not ready: the service must not silently discard signaling. */
    fun receive(data: JSONObject, from: Role): Boolean {
        if (closed.get()) return false
        val currentPeer = peer ?: return false
        currentPeer.receive(data, from)
        return true
    }
    fun setMuted(value: Boolean) { muted.set(value); if (!closed.get()) peer?.setMuted(value) }
    fun requestReceivedPackets(onResult: (Long?) -> Unit) {
        if (!closed.get()) peer?.requestReceivedPackets { count -> callback { onResult(count) } }
    }

    private fun callback(action: () -> Unit) {
        if (closed.get()) return
        try { lifecycle.execute {
            if (!closed.get()) try { action() } catch (_: Exception) { close() }
        } }
        catch (_: RejectedExecutionException) { /* stopped concurrently */ }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        lifecycle.execute {
            try {
                val currentPeer = peer
                peer = null
                currentPeer?.close()
                // Never restore the modem/audio route under a still-live native
                // device. If native disposal stalls, wait off the service thread
                // and keep the audio lease until it has actually completed.
                while (currentPeer != null && !currentPeer.awaitClosed(1_000)) { /* retain ownership */ }
                if (mixerAttempted && !GatewayUplinkRoute.disable()) {
                    runCatching { onError("Could not restore the sender audio route") }
                }
                if (audioModeChanged) {
                    val audio = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                    runCatching { audio.mode = oldMode }
                    @Suppress("DEPRECATION")
                    runCatching { audio.isSpeakerphoneOn = oldSpeaker }
                }
            } finally {
                if (ownsLease) lease.set(false)
                try { onStopped() } finally { stopped.countDown(); lifecycle.shutdown() }
            }
        }
    }

    /** Never call from a callback/lifecycle thread. */
    fun awaitStopped(timeoutMs: Long): Boolean = stopped.await(timeoutMs, TimeUnit.MILLISECONDS)

    companion object { private val lease = AtomicBoolean(false) }
}
