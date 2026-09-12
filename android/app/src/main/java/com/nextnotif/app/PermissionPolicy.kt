package com.nextnotif.app

/** Baseline runtime permissions that can block an enabled pairing from working. */
enum class BaselinePermission {
    SMS,
    PHONE_STATE,
    NOTIFICATIONS,
}

/**
 * Keeps role-to-permission decisions independent of Android UI and API classes.
 * Optional enhancements such as contacts and live-call audio never belong here.
 */
object PermissionPolicy {
    fun liveCallPermissionsReady(answerGranted: Boolean, microphoneGranted: Boolean): Boolean =
        answerGranted && microphoneGranted

    fun requiredFor(
        enabledRoles: Set<Role>,
        notificationsRequireRuntimePermission: Boolean,
    ): Set<BaselinePermission> = buildSet {
        if (Role.SENDER in enabledRoles) {
            add(BaselinePermission.SMS)
            add(BaselinePermission.PHONE_STATE)
        }
        if (
            Role.RECEIVER in enabledRoles &&
            notificationsRequireRuntimePermission
        ) {
            add(BaselinePermission.NOTIFICATIONS)
        }
    }

    /** Live-call permissions are an enhancement and must never enter the baseline gate. */
    fun needsLiveCallPermissions(pairings: List<PairingInfo>): Boolean =
        pairings.any {
            it.enabled &&
                it.role == Role.SENDER &&
                it.liveCallEnabled
        }

    /** Receiver phones never need the sender's contacts for caller-name lookup. */
    fun offersContactNameLookup(role: Role?): Boolean = role == Role.SENDER
}
