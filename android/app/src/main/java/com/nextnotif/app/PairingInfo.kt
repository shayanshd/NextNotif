package com.nextnotif.app

data class PairingInfo(
    val code: String,
    val role: Role,
    val server: String,
    val transport: String? = null,
    val fbConfig: String? = null,
    val secret: String? = null,
    val deviceId: String? = null,
    val deviceToken: String? = null,
    val ownsPairing: Boolean = false,
    val label: String? = null,
    val enabled: Boolean = true,
    val liveCallEnabled: Boolean = false,
) {
    // Recognize saved pairings from older releases so they can be migrated.
    val isFirebase: Boolean get() = transport == LEGACY_FIREBASE_TRANSPORT
    val isFcmOnDemand: Boolean get() = transport == FcmOnDemand.TRANSPORT
    val isWs: Boolean get() = !isFirebase && !isFcmOnDemand

    /** Human-readable name for the UI; falls back to the code. */
    val displayName: String get() = label?.trim()?.takeIf { it.isNotEmpty() } ?: "Pairing $code"
}

const val LEGACY_FIREBASE_TRANSPORT = "firebase"

enum class PairingSetupMode { CREATE, JOIN, EDIT }

internal fun liveCallEnabledFor(role: Role, requested: Boolean): Boolean =
    Config.LIVE_CALL_BETA_ENABLED && role == Role.SENDER && requested

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
    val firebase = transport == LEGACY_FIREBASE_TRANSPORT
    if (existing.isFirebase != firebase) return null
    if (firebase && existing.fbConfig != fbConfig) return null
    return existing.deviceToken
}

internal fun retainedDeviceId(
    existing: PairingInfo?, code: String, role: Role, server: String,
    transport: String?, fbConfig: String?,
): String? = if (retainedDeviceToken(existing, code, role, server, transport, fbConfig) != null)
    existing?.deviceId else null
