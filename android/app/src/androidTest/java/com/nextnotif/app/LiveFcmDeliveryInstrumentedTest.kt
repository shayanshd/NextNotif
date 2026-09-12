package com.nextnotif.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Explicit lab-only test. Never changes settings or clears existing communications. */
@RunWith(AndroidJUnit4::class)
class LiveFcmDeliveryInstrumentedTest {
    @Test
    fun labelledEventUsesConfiguredDeliveryAndSurvivesReplay() = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue("Explicit live-delivery opt-in required", args.getString("liveDelivery") == "true")
        val code = requireNotNull(args.getString("pairingCode"))
        val markerId = requireNotNull(args.getString("deliveryMarker"))
        require(markerId.matches(Regex("[A-Za-z0-9_-]{1,128}")))
        val marker = "MVP delivery check $markerId"
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val session = SessionStore.load(context)
        assertTrue("Relay is stopped; test will not override user intent", session.relayEnabled)
        val pairing = session.pairings.single { it.code == code }
        assertTrue("Configured FCM pairing must be enabled", pairing.enabled && pairing.isFcmOnDemand)
        if (pairing.role == Role.SENDER) {
            val result = SenderUplink(context, pairing.server, code, pairing.deviceToken).sendDetailed(
                RelaySelfTest.EVENT_TYPE,
                JSONObject().put("message", marker).put("ts", System.currentTimeMillis()),
            )
            assertTrue("Relay did not accept labelled test", result.accepted)
        } else {
            val store = MessageStore.from(context)
            fun matches() = store.load().filter { it.tag == "TEST" && it.code == code && it.message == marker }
            // Do not force the first fetch: this checks actual push-triggered delivery.
            val deadline = android.os.SystemClock.elapsedRealtime() + 40_000L
            while (matches().isEmpty() && android.os.SystemClock.elapsedRealtime() < deadline) {
                Thread.sleep(250)
            }
            assertEquals("Push-triggered durable history must arrive once", 1, matches().size)
            assertTrue("Durable event identity missing", !matches().single().eventId.isNullOrBlank())
            assertTrue("Replay/ACK sync failed", FcmOnDemand.sync(context, code))
            assertEquals("Retry duplicated history", 1, MessageStore.from(context).load().count {
                it.tag == "TEST" && it.code == code && it.message == marker
            })
        }
    }
}
