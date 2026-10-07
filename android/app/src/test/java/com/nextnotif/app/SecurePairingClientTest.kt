package com.nextnotif.app

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SecurePairingClientTest {
    private val id = "d".repeat(22)
    private val token = "t".repeat(43)

    @Test
    fun parseCreateGrantRequiresInviteAndCredentials() {
        val body = JSONObject().put("code", "123456").put("device_id", id)
            .put("device_token", token).put("invite", JSONObject()
                .put("role", "receiver").put("secret", "s".repeat(43)).put("expires_at", 999L))
        val grant = SecurePairingClient.parseGrant(body, invite = true)
        assertEquals("123456", grant.code)
        assertEquals(Role.RECEIVER, grant.inviteRole)
        assertEquals("s".repeat(43), grant.inviteSecret)
    }

    @Test
    fun parseJoinGrantHasNoReusableInvite() {
        val body = JSONObject().put("code", "123456").put("device_id", id)
            .put("device_token", token)
        val grant = SecurePairingClient.parseGrant(body, invite = false)
        assertEquals(id, grant.deviceId)
        assertNull(grant.inviteSecret)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsWeakDeviceToken() {
        SecurePairingClient.parseGrant(JSONObject().put("code", "123456")
            .put("device_id", id).put("device_token", "short"), invite = false)
    }
}
