package com.nextnotif.app

import org.junit.Assert.*
import org.junit.Test

class LiveCallControlPolicyTest {
    @Test fun carrierDialRequiresBothPeersAndAuthenticatedSocketForSameSession() {
        val session = "current"
        assertTrue(LiveCallControlPolicy.outgoingDialReady(session, session, session, true))
        assertFalse(LiveCallControlPolicy.outgoingDialReady(session, null, session, true))
        assertFalse(LiveCallControlPolicy.outgoingDialReady(session, session, null, true))
        assertFalse(LiveCallControlPolicy.outgoingDialReady(session, "old", session, true))
        assertFalse(LiveCallControlPolicy.outgoingDialReady(session, session, "old", true))
        assertFalse(LiveCallControlPolicy.outgoingDialReady(session, session, session, false))
        assertFalse(LiveCallControlPolicy.outgoingDialReady(null, null, null, true))
    }
    @Test fun onDemandSenderWaitsForAuthenticatedCallSocket() {
        assertFalse(LiveCallControlPolicy.senderBridgeCanStart(true, false))
        assertTrue(LiveCallControlPolicy.senderBridgeCanStart(true, true))
        assertTrue(LiveCallControlPolicy.senderBridgeCanStart(false, false))
    }

    @Test fun supersededSocketsKeepHistoryButNeverCallControls() {
        assertFalse(LiveCallControlPolicy.acceptSocketEvent(false, "call_control"))
        assertFalse(LiveCallControlPolicy.acceptSocketEvent(false, null))
        assertTrue(LiveCallControlPolicy.acceptSocketEvent(false, "sms"))
        assertTrue(LiveCallControlPolicy.acceptSocketEvent(false, "call"))
        assertTrue(LiveCallControlPolicy.acceptSocketEvent(true, "call_control"))
    }
    @Test fun staleTerminationCannotCloseReplacementSession() {
        for (action in listOf("end", "ended", "error")) {
            assertFalse(LiveCallControlPolicy.acceptSessionControl(action, "old", "new"))
            assertTrue(LiveCallControlPolicy.acceptSessionControl(action, "new", "new"))
            assertTrue(LiveCallControlPolicy.acceptSessionControl(action, null, null))
        }
    }
    @Test fun resumeAndEndCannotStealUnselectedOrLocalCall() {
        for (action in listOf("resume", "end")) {
            assertFalse(LiveCallControlPolicy.acceptSenderControl(action, "pair", null))
            assertFalse(LiveCallControlPolicy.acceptSenderControl(action, "pair", "other"))
            assertTrue(LiveCallControlPolicy.acceptSenderControl(action, "pair", "pair"))
        }
        assertTrue(LiveCallControlPolicy.acceptSenderControl("answer", "pair", null))
    }
}
