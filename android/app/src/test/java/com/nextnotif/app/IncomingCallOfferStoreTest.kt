package com.nextnotif.app

import org.junit.Assert.*
import org.junit.Test

class IncomingCallOfferStoreTest {
    private val offer = IncomingCallOffer("A", "caller", null, 100_000L)

    @Test fun freshSupportedOfferSurvivesNavigationWithoutChangingSourceTime() {
        assertEquals(offer, IncomingCallOfferStore.next(null, "A", "RINGING", true,
            100_000L, "caller", null, 105_000L))
        assertEquals(offer, IncomingCallOfferStore.next(offer, "A", "OTHER", false,
            null, null, null, 110_000L))
    }

    @Test fun expiredMalformedAndUnsupportedOffersCannotCreateAnswer() {
        for (timestamp in listOf(null, 0L, 9_999L, 140_001L)) {
            assertNull(IncomingCallOfferStore.next(null, "A", "RINGING", true,
                timestamp, null, null, 100_000L))
        }
        assertNull(IncomingCallOfferStore.next(null, "A", "RINGING", false,
            100_000L, null, null, 100_000L))
    }

    @Test fun endOrConnectedClearsOnlyMatchingNonOlderOffer() {
        for (state in listOf("OFFHOOK", "IDLE")) {
            assertNull(IncomingCallOfferStore.next(offer, "A", state, false,
                100_001L, null, null, 110_000L))
            assertEquals(offer, IncomingCallOfferStore.next(offer, "B", state, false,
                100_001L, null, null, 110_000L))
            assertEquals(offer, IncomingCallOfferStore.next(offer, "A", state, false,
                99_999L, null, null, 110_000L))
        }
    }
}
