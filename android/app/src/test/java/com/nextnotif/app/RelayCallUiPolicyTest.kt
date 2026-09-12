package com.nextnotif.app

import org.junit.Assert.*
import org.junit.Test

class RelayCallUiPolicyTest {
    @Test fun answerOnlyForEnabledRingingReceiver() {
        for (phase in AppState.CallPhase.values()) {
            assertEquals(phase == AppState.CallPhase.RINGING,
                RelayCallUiPolicy.canAnswer(phase, Role.RECEIVER, true))
            assertFalse(RelayCallUiPolicy.canAnswer(phase, Role.SENDER, true))
            assertFalse(RelayCallUiPolicy.canAnswer(phase, Role.RECEIVER, false))
        }
    }
    @Test fun idleAndTerminalCallsShowCloseNotHangup() {
        for (phase in AppState.CallPhase.values()) assertEquals(
            phase in setOf(AppState.CallPhase.IDLE, AppState.CallPhase.ENDED, AppState.CallPhase.FAILED),
            RelayCallUiPolicy.finished(phase))
    }
}
