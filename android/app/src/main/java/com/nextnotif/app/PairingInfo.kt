package com.nextnotif.app

data class PairingInfo(
    val code: String,
    val role: Role,
    val server: String,
    val transport: String? = null,
    val fbConfig: String? = null,
    val secret: String? = null,
    val deviceToken: String? = null,
    val label: String? = null,
    val enabled: Boolean = true,
) {
    val isFirebase: Boolean get() = transport == FirebaseRelay.TRANSPORT
    val isWs: Boolean get() = !isFirebase

    /** Human-readable name for the UI; falls back to the code. */
    val displayName: String get() = label?.trim()?.takeIf { it.isNotEmpty() } ?: "Pairing $code"
}
