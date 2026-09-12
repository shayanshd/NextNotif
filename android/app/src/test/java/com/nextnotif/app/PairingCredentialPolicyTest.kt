package com.nextnotif.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PairingCredentialPolicyTest {
    private val saved = PairingInfo("123456", Role.SENDER, "wss://relay.example", deviceToken = "saved-token")

    @Test
    fun sameAuthorityAndRoleMaySwitchBetweenWsAndFcm() {
        assertEquals("saved-token", retainedDeviceToken(saved, saved.code, saved.role,
            " wss://relay.example/ ", "fcm", null))
    }

    @Test
    fun changedCodeRoleOrAuthorityDiscardsCredential() {
        assertNull(retainedDeviceToken(saved, "654321", saved.role, saved.server, null, null))
        assertNull(retainedDeviceToken(saved, saved.code, Role.RECEIVER, saved.server, null, null))
        assertNull(retainedDeviceToken(saved, saved.code, saved.role, "wss://other.example", null, null))
        assertNull(retainedDeviceToken(saved, saved.code, saved.role, "ws://relay.example", null, null))
    }

    @Test
    fun changingFirebaseProjectOrBackendDiscardsCredential() {
        val firebase = saved.copy(transport = FirebaseRelay.TRANSPORT, fbConfig = "project-a")
        assertNull(retainedDeviceToken(saved, saved.code, saved.role, saved.server,
            FirebaseRelay.TRANSPORT, "project-a"))
        assertNull(retainedDeviceToken(firebase, saved.code, saved.role, saved.server,
            FirebaseRelay.TRANSPORT, "project-b"))
    }
}
