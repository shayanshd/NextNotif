package com.nextnotif.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.webrtc.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Opt-in native ABI/API smoke test. No call, recording, SDP application, network
 * negotiation, root commands, session preferences, or message history changes.
 */
@RunWith(AndroidJUnit4::class)
class NativeWebRtcInstrumentedTest {
    @Test fun nativeLibraryCreatesAudioOnlyOpusOfferWithoutStartingAudio() {
        assumeTrue("Explicit native WebRTC opt-in required",
            InstrumentationRegistry.getArguments().getString("nativeWebRtc") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        PeerConnectionFactory.initialize(PeerConnectionFactory.InitializationOptions.builder(context).createInitializationOptions())
        val module = WebRtcAudioDeviceFactory.create(context, Role.RECEIVER, GatewayCapability.ROOT_UNAVAILABLE) {
            fail("Unexpected audio-device activity during no-audio test")
        }
        var factory: PeerConnectionFactory? = null
        var peer: PeerConnection? = null
        var source: AudioSource? = null
        var track: AudioTrack? = null
        try {
            factory = PeerConnectionFactory.builder().setAudioDeviceModule(module).createPeerConnectionFactory()
            val config = PeerConnection.RTCConfiguration(emptyList()).apply {
                sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            }
            peer = checkNotNull(factory.createPeerConnection(config, object : PeerConnection.Observer {
                override fun onSignalingChange(state: PeerConnection.SignalingState) = Unit
                override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) = Unit
                override fun onIceConnectionReceivingChange(receiving: Boolean) = Unit
                override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) = Unit
                override fun onIceCandidate(candidate: IceCandidate) = Unit
                override fun onIceCandidatesRemoved(candidates: Array<IceCandidate>) = Unit
                override fun onAddStream(stream: MediaStream) = Unit
                override fun onRemoveStream(stream: MediaStream) = Unit
                override fun onDataChannel(channel: DataChannel) = Unit
                override fun onRenegotiationNeeded() = Unit
            }))
            peer.setAudioRecording(false)
            peer.setAudioPlayout(false)
            source = factory.createAudioSource(MediaConstraints())
            track = factory.createAudioTrack("native-smoke-audio", source)
            track.setEnabled(false)
            val transceiver = peer.addTransceiver(track)
            val opus = WebRtcOpusPolicy.preferences(factory.getRtpSenderCapabilities(
                MediaStreamTrack.MediaType.MEDIA_TYPE_AUDIO).codecs)
            assertTrue("Native Opus codec unavailable", opus.isNotEmpty())
            transceiver.setCodecPreferences(opus).throwError()
            val latch = CountDownLatch(1)
            val result = AtomicReference<SessionDescription?>()
            val error = AtomicReference<String?>()
            peer.createOffer(object : SdpObserver {
                override fun onCreateSuccess(description: SessionDescription) { result.set(description); latch.countDown() }
                override fun onCreateFailure(message: String) { error.set("Native offer creation failed"); latch.countDown() }
                override fun onSetSuccess() = Unit
                override fun onSetFailure(message: String) = Unit
            }, MediaConstraints())
            assertTrue("Native offer callback timed out", latch.await(10, TimeUnit.SECONDS))
            assertNull(error.get())
            val sdp = checkNotNull(result.get()).description
            assertTrue(sdp.contains("m=audio "))
            assertTrue(sdp.contains("opus/48000", ignoreCase = true))
            assertTrue("Native offer must disable Opus DTX", WebRtcOpusPolicy.continuous(sdp))
            assertFalse(sdp.contains("m=video "))
            assertFalse(sdp.contains("m=application "))
            assertNull("Local description must not be applied", peer.localDescription)
            assertNull(peer.remoteDescription)
        } finally {
            peer?.dispose()
            track?.dispose()
            source?.dispose()
            factory?.dispose()
            module.release()
        }
    }
}
