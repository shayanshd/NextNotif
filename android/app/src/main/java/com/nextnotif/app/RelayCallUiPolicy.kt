package com.nextnotif.app

internal object RelayCallUiPolicy {
    fun canRequestAnswer(phase: AppState.CallPhase, currentCode: String?, targetCode: String): Boolean =
        (phase == AppState.CallPhase.RINGING && currentCode == targetCode) ||
            (phase == AppState.CallPhase.IDLE && currentCode == null)

    fun canBeginAnswer(activeCode: String?, phase: AppState.CallPhase,
                       currentCode: String?, targetCode: String): Boolean =
        activeCode == null && currentCode == targetCode &&
            phase in setOf(AppState.CallPhase.RINGING, AppState.CallPhase.ANSWERING)

    fun canAnswer(phase: AppState.CallPhase, role: Role, enabled: Boolean): Boolean =
        phase == AppState.CallPhase.RINGING && role == Role.RECEIVER && enabled

    fun finished(phase: AppState.CallPhase): Boolean = phase in setOf(
        AppState.CallPhase.IDLE, AppState.CallPhase.ENDED, AppState.CallPhase.FAILED,
    )
}
