package com.nextnotif.app

import org.junit.Assert.*
import org.junit.Test

class RelayCallUiPolicyTest {
    @Test fun duplicateAndStaleAnswerRequestsAreIgnored() {
        for (phase in AppState.CallPhase.values()) {
            assertEquals(phase == AppState.CallPhase.RINGING,
                RelayCallUiPolicy.canRequestAnswer(phase, "A", "A"))
            assertFalse(RelayCallUiPolicy.canRequestAnswer(phase, "B", "A"))
            assertEquals(phase == AppState.CallPhase.IDLE,
                RelayCallUiPolicy.canRequestAnswer(phase, null, "A"))
        }
    }
    @Test fun serviceCannotReplaceOrRestartAnExistingCall() {
        for (phase in AppState.CallPhase.values()) {
            assertEquals(phase in setOf(AppState.CallPhase.RINGING, AppState.CallPhase.ANSWERING),
                RelayCallUiPolicy.canBeginAnswer(null, phase, "A", "A"))
            assertFalse(RelayCallUiPolicy.canBeginAnswer("A", phase, "A", "A"))
            assertFalse(RelayCallUiPolicy.canBeginAnswer("B", phase, "A", "A"))
            assertFalse(RelayCallUiPolicy.canBeginAnswer(null, phase, "B", "A"))
        }
    }
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
