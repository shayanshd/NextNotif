package com.nextnotif.app

import org.junit.Assert.*
import org.junit.Test

class LiveCallControlPolicyTest {
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
