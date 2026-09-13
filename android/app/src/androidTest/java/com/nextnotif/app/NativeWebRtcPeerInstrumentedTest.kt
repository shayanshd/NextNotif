package com.nextnotif.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.webrtc.PeerConnection
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.CopyOnWriteArrayList

/** Local native transport negotiation only: both device recording/playout and
 * audio tracks are disabled. No root commands, call, settings or relay writes.
 */
@RunWith(AndroidJUnit4::class)
class NativeWebRtcPeerInstrumentedTest {
    private fun optIn() = assumeTrue("Explicit no-audio native peer opt-in required",
        InstrumentationRegistry.getArguments().getString("nativeWebRtcPeer") == "true")
    private val ice = emptyList<PeerConnection.IceServer>()

    @Test fun actualPeerEnginesNegotiateAndCloseWithoutDeviceAudio() {
        optIn()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val session = WebRtcSignal.newSessionId()
        val connected = CountDownLatch(2)
        val errors = CopyOnWriteArrayList<String>()
        val senderConnected = AtomicBoolean(false)
        val receiverConnected = AtomicBoolean(false)
        val continuousOffer = AtomicBoolean(false)
        val continuousAnswer = AtomicBoolean(false)
        val twoWayOffer = AtomicBoolean(false)
        val twoWayAnswer = AtomicBoolean(false)
        lateinit var sender: WebRtcCallPeer
        lateinit var receiver: WebRtcCallPeer
        sender = WebRtcCallPeer(context, Role.SENDER, session, GatewayCapability.AVAILABLE, ice,
            audioEnabled = false, sendSignal = {
                if (it.optString("kind") == "answer") continuousAnswer.set(WebRtcOpusPolicy.continuous(it.optString("sdp")))
                if (it.optString("kind") == "answer") twoWayAnswer.set(WebRtcMediaDirection.bidirectional(it.optString("sdp")))
                receiver.receive(it, Role.SENDER); true
            },
            onState = { if (it == PeerConnection.PeerConnectionState.CONNECTED && senderConnected.compareAndSet(false, true)) connected.countDown() },
            onError = { errors.add(it) })
        receiver = WebRtcCallPeer(context, Role.RECEIVER, session, GatewayCapability.ROOT_UNAVAILABLE, ice,
            audioEnabled = false, sendSignal = {
                if (it.optString("kind") == "offer") continuousOffer.set(WebRtcOpusPolicy.continuous(it.optString("sdp")))
                if (it.optString("kind") == "offer") twoWayOffer.set(WebRtcMediaDirection.bidirectional(it.optString("sdp")))
                sender.receive(it, Role.RECEIVER); true
            },
            onState = { if (it == PeerConnection.PeerConnectionState.CONNECTED && receiverConnected.compareAndSet(false, true)) connected.countDown() },
            onError = { errors.add(it) })
        try {
            sender.start()
            receiver.start()
            // Stale messages cannot interfere with this negotiation.
            sender.receive(WebRtcSignal.Description(WebRtcSignal.newSessionId(), true, "v=0\r\n").toJson(), Role.RECEIVER)
            assertTrue("No-audio loopback negotiation timed out; errors=$errors", connected.await(20, TimeUnit.SECONDS))
            assertTrue("Native engine error: $errors", errors.isEmpty())
            assertTrue("Native offer must prefer continuous Opus", continuousOffer.get())
            assertTrue("Native answer must prefer continuous Opus", continuousAnswer.get())
            assertTrue("Native offer must negotiate two-way audio", twoWayOffer.get())
            assertTrue("Native answer must include gateway sending audio", twoWayAnswer.get())
            val stats = CountDownLatch(2)
            sender.requestReceivedPackets { stats.countDown() }
            receiver.requestReceivedPackets { stats.countDown() }
            assertTrue("Native stats callbacks timed out", stats.await(10, TimeUnit.SECONDS))
            sender.setMuted(false) // must not enable audio in the no-audio mode
            receiver.setMuted(true)
        } finally {
            sender.close(); receiver.close()
            assertTrue("Sender disposal timed out", sender.awaitClosed(10_000))
            assertTrue("Receiver disposal timed out", receiver.awaitClosed(10_000))
        }
        sender.close(); receiver.close() // idempotent after disposal
    }

    @Test fun rejectedSignalingFailsOnceAndDisposesNativeResources() {
        optIn()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val error = CountDownLatch(1)
        val errors = CopyOnWriteArrayList<String>()
        val peer = WebRtcCallPeer(context, Role.RECEIVER, WebRtcSignal.newSessionId(), GatewayCapability.ROOT_UNAVAILABLE, ice,
            audioEnabled = false, sendSignal = { false }, onState = {}, onError = { errors.add(it); error.countDown() })
        try {
            peer.start()
            assertTrue(error.await(10, TimeUnit.SECONDS))
            assertTrue(peer.awaitClosed(10_000))
            assertEquals(1, errors.size)
            peer.close()
        } finally { peer.close(); assertTrue(peer.awaitClosed(10_000)) }
    }
}
