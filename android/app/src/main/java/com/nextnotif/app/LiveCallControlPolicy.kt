package com.nextnotif.app

/** Ephemeral controls cannot inherit durable history's stale-delivery exception. */
internal object LiveCallControlPolicy {
    fun acceptSocketEvent(current: Boolean, incomingType: String?): Boolean =
        current || (incomingType != null && incomingType != "call_control")

    fun acceptSessionControl(action: String, suppliedSession: String?, activeSession: String?): Boolean =
        action !in setOf("end", "ended", "error") || suppliedSession == null || suppliedSession == activeSession

    fun acceptSenderControl(action: String, code: String, activeCode: String?): Boolean =
        action !in setOf("resume", "end") || code == activeCode
}
