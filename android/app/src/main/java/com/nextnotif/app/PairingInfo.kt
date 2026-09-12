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
    val liveCallEnabled: Boolean = false,
) {
    val isFirebase: Boolean get() = transport == FirebaseRelay.TRANSPORT
    val isFcmOnDemand: Boolean get() = transport == FcmOnDemand.TRANSPORT
    val isWs: Boolean get() = !isFirebase && !isFcmOnDemand

    /** Human-readable name for the UI; falls back to the code. */
    val displayName: String get() = label?.trim()?.takeIf { it.isNotEmpty() } ?: "Pairing $code"
}

internal fun liveCallEnabledFor(role: Role, requested: Boolean): Boolean =
    role == Role.SENDER && requested

/** Local preference edits never need remote reachability; identity edits still do. */
internal fun canSavePairingPreferencesOffline(
    existing: PairingInfo?, code: String, role: Role, server: String,
    transport: String?, fbConfig: String?,
): Boolean = existing != null && existing.code == code && existing.role == role &&
    existing.server.trim().trimEnd('/') == server.trim().trimEnd('/') &&
    existing.transport == transport && existing.fbConfig == fbConfig

/** Credentials are bound to a relay authority and role, not a display code alone. */
internal fun retainedDeviceToken(
    existing: PairingInfo?, code: String, role: Role, server: String,
    transport: String?, fbConfig: String?,
): String? {
    existing ?: return null
    if (existing.code != code || existing.role != role) return null
    if (existing.server.trim().trimEnd('/') != server.trim().trimEnd('/')) return null
    val firebase = transport == FirebaseRelay.TRANSPORT
    if (existing.isFirebase != firebase) return null
    if (firebase && existing.fbConfig != fbConfig) return null
    return existing.deviceToken
}
