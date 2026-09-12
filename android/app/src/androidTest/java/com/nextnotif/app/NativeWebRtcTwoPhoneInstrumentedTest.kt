package com.nextnotif.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.webrtc.PeerConnection
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Two physical peers, no recording/playout, root commands, preferences or pairing writes. */
@RunWith(AndroidJUnit4::class)
class NativeWebRtcTwoPhoneInstrumentedTest {
    @Test fun physicalPeersConnectThroughLocalEphemeralSignalingWithoutAudio() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("nativeWebRtcTwoPhone") == "true")
        val role = Role.valueOf(checkNotNull(args.getString("probeRole")))
        val session = checkNotNull(args.getString("probeSession"))
        check(UUID.fromString(session).toString() == session)
        val client = OkHttpClient.Builder().callTimeout(3, TimeUnit.SECONDS).build()
        fun request(path: String, body: JSONObject? = null): JSONObject {
            val builder = Request.Builder().url("http://127.0.0.1:8769$path")
                .header("X-Probe-Role", role.name.lowercase()).header("X-Probe-Session", session)
            body?.let { builder.post(it.toString().toRequestBody("application/json".toMediaType())) }
            return client.newCall(builder.build()).execute().use {
                check(it.isSuccessful) { "Local signaling fixture failed" }
                JSONObject(checkNotNull(it.body).string())
            }
        }
        val errors = CopyOnWriteArrayList<String>()
        val connected = AtomicBoolean(false)
        val stats = AtomicBoolean(false)
        val peer = WebRtcCallPeer(InstrumentationRegistry.getInstrumentation().targetContext,
            role, session, if (role == Role.SENDER) GatewayCapability.AVAILABLE else GatewayCapability.ROOT_UNAVAILABLE,
            emptyList(), audioEnabled = false,
            sendSignal = { try { request("/signals", it); true } catch (_: Exception) { false } },
            onState = { connected.set(it == PeerConnection.PeerConnectionState.CONNECTED) },
            onError = { errors.add(it) })
        try {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(40)
            if (role == Role.RECEIVER) {
                while (!request("/ready").optBoolean("sender") && System.nanoTime() < deadline) Thread.sleep(100)
                check(System.nanoTime() < deadline) { "Sender probe was not ready" }
            }
            peer.start()
            request("/ready", JSONObject())
            var requestedStats = false
            var publishedConnected: Boolean? = null
            var bothSince: Long? = null
            var held = false
            var bothVerified = false
            while (System.nanoTime() < deadline) {
                val signals = request("/signals").getJSONArray("signals")
                for (index in 0 until signals.length()) peer.receive(signals.getJSONObject(index),
                    if (role == Role.SENDER) Role.RECEIVER else Role.SENDER)
                if (connected.get() && !requestedStats) {
                    requestedStats = true
                    peer.requestReceivedPackets { stats.set(true) }
                }
                val currentConnected = stats.get() && connected.get()
                if (publishedConnected != currentConnected) {
                    request("/connected", JSONObject().put("connected", currentConnected))
                    publishedConnected = currentConnected
                }
                if (request("/connected").optBoolean("both")) {
                    if (bothSince == null) bothSince = System.nanoTime()
                    if (!held && System.nanoTime() - bothSince >= TimeUnit.SECONDS.toNanos(2)) {
                        held = true; request("/verified", JSONObject())
                    }
                } else bothSince = null
                bothVerified = request("/verified").optBoolean("both")
                if (held && bothVerified) break
                check(errors.isEmpty()) { "Native probe error: $errors" }
                Thread.sleep(100)
            }
            assertTrue("Physical peer did not connect", connected.get())
            assertTrue("Native stats callback missing", stats.get())
            assertTrue("Both peers did not sustain the two-second hold", held && bothVerified)
            assertTrue("Native error: $errors", errors.isEmpty())
        } finally {
            peer.close()
            assertTrue("Native probe disposal timed out", peer.awaitClosed(10_000))
            client.dispatcher.executorService.shutdown()
            client.connectionPool.evictAll()
        }
    }
}
