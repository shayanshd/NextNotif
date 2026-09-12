package com.nextnotif.app

import org.junit.Assert.assertEquals
import org.junit.Test

class PermissionPolicyTest {
    @Test
    fun liveCallAvailabilityRequiresBothOptionalPermissions() {
        assertEquals(true, PermissionPolicy.liveCallPermissionsReady(true, true))
        assertEquals(false, PermissionPolicy.liveCallPermissionsReady(false, true))
        assertEquals(false, PermissionPolicy.liveCallPermissionsReady(true, false))
        assertEquals(false, PermissionPolicy.liveCallPermissionsReady(false, false))
    }

    @Test
    fun `no enabled roles require no permissions`() {
        assertEquals(emptySet<BaselinePermission>(), PermissionPolicy.requiredFor(emptySet(), true))
    }

    @Test
    fun `sender requires only sms and phone state`() {
        assertEquals(
            setOf(BaselinePermission.SMS, BaselinePermission.PHONE_STATE),
            PermissionPolicy.requiredFor(setOf(Role.SENDER), true),
        )
    }

    @Test
    fun `receiver requires notifications on api levels with runtime notification permission`() {
        assertEquals(
            setOf(BaselinePermission.NOTIFICATIONS),
            PermissionPolicy.requiredFor(setOf(Role.RECEIVER), true),
        )
    }

    @Test
    fun `receiver requires no runtime permission before notification permission api`() {
        assertEquals(
            emptySet<BaselinePermission>(),
            PermissionPolicy.requiredFor(setOf(Role.RECEIVER), false),
        )
    }

    @Test
    fun `mixed roles require union of their baseline permissions`() {
        assertEquals(
            setOf(
                BaselinePermission.SMS,
                BaselinePermission.PHONE_STATE,
                BaselinePermission.NOTIFICATIONS,
            ),
            PermissionPolicy.requiredFor(setOf(Role.SENDER, Role.RECEIVER), true),
        )
    }

    @Test
    fun `live calls need optional permissions only for enabled opted-in sender`() {
        val sender = PairingInfo(
            code = "111111",
            role = Role.SENDER,
            server = "wss://relay.example",
            liveCallEnabled = true,
        )

        assertEquals(true, PermissionPolicy.needsLiveCallPermissions(listOf(sender)))
        assertEquals(
            false,
            PermissionPolicy.needsLiveCallPermissions(listOf(sender.copy(liveCallEnabled = false))),
        )
        assertEquals(
            false,
            PermissionPolicy.needsLiveCallPermissions(listOf(sender.copy(enabled = false))),
        )
        assertEquals(
            false,
            PermissionPolicy.needsLiveCallPermissions(listOf(sender.copy(role = Role.RECEIVER))),
        )
    }

    @Test
    fun `live call opt-in does not change baseline sender permissions`() {
        assertEquals(
            setOf(BaselinePermission.SMS, BaselinePermission.PHONE_STATE),
            PermissionPolicy.requiredFor(setOf(Role.SENDER), true),
        )
    }

    @Test
    fun `contact name lookup is offered only on a sender`() {
        assertEquals(true, PermissionPolicy.offersContactNameLookup(Role.SENDER))
        assertEquals(false, PermissionPolicy.offersContactNameLookup(Role.RECEIVER))
        assertEquals(false, PermissionPolicy.offersContactNameLookup(null))
    }
}
