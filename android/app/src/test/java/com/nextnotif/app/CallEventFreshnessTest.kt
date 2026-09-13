package com.nextnotif.app

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class CallEventFreshnessTest {
    @Test fun onlyRecentOffersAreInteractive() {
        assertTrue(CallEventFreshness.permitsInteraction(10_000L, 100_000L))
        assertFalse(CallEventFreshness.permitsInteraction(9_999L, 100_000L))
        assertTrue(CallEventFreshness.permitsInteraction(130_000L, 100_000L))
        assertFalse(CallEventFreshness.permitsInteraction(130_001L, 100_000L))
    }

    @Test fun invalidAndExtremeTimesDoNotBecomeFreshByOverflow() {
        for (time in listOf(null, 0L, -1L, Long.MIN_VALUE, Long.MAX_VALUE)) {
            assertFalse(CallEventFreshness.permitsInteraction(time, 100_000L))
        }
        assertFalse(CallEventFreshness.permitsInteraction(1L, Long.MAX_VALUE))
        assertFalse(CallEventFreshness.permitsInteraction(1L, -1L))
    }

    @Test fun missingMalformedOrExpiredPayloadsRemainInformational() {
        val payload = JSONObject().put("live_call_available", true)
        assertFalse(IncomingEventHandler.interactiveLiveCallAvailable(payload, 100_000L))
        for (timestamp in listOf("100000", 100_000.0, JSONObject.NULL, -1L, 1L)) {
            payload.put("ts", timestamp)
            assertFalse(IncomingEventHandler.interactiveLiveCallAvailable(payload, 100_000L))
        }
        payload.put("ts", 100_000L)
        assertTrue(IncomingEventHandler.interactiveLiveCallAvailable(payload, 100_000L))
        payload.put("live_call_available", false)
        assertFalse(IncomingEventHandler.interactiveLiveCallAvailable(payload, 100_000L))
    }
}
