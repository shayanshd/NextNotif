package com.nextnotif.app

internal object RelayCallUiPolicy {
    fun canAnswer(phase: AppState.CallPhase, role: Role, enabled: Boolean): Boolean =
        phase == AppState.CallPhase.RINGING && role == Role.RECEIVER && enabled

    fun finished(phase: AppState.CallPhase): Boolean = phase in setOf(
        AppState.CallPhase.IDLE, AppState.CallPhase.ENDED, AppState.CallPhase.FAILED,
    )
}
