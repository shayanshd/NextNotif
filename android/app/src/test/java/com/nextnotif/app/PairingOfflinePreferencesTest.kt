package com.nextnotif.app

import org.junit.Assert.*
import org.junit.Test

class PairingOfflinePreferencesTest {
    private val existing = PairingInfo("123456", Role.SENDER, "wss://relay.example", transport = "fcm",
        deviceToken = "credential", liveCallEnabled = true)
    private fun allowed(pair: PairingInfo? = existing, code: String = existing.code,
                        role: Role = existing.role, server: String = existing.server,
                        transport: String? = existing.transport, config: String? = existing.fbConfig) =
        canSavePairingPreferencesOffline(pair, code, role, server, transport, config)

    @Test fun existingPreferenceEditsNeedNoConnectionOrCredential() {
        assertTrue(allowed())
        assertTrue(allowed(pair = existing.copy(deviceToken = null, liveCallEnabled = false)))
        assertTrue(allowed(server = " wss://relay.example/ "))
    }
    @Test fun identityRoleAuthorityAndBackendChangesStillRequirePreflight() {
        assertFalse(allowed(pair = null))
        assertFalse(allowed(code = "654321"))
        assertFalse(allowed(role = Role.RECEIVER))
        assertFalse(allowed(server = "ws://relay.example"))
        assertFalse(allowed(server = "wss://other.example"))
        assertFalse(allowed(transport = null))
        assertFalse(allowed(config = "another project"))
    }
    @Test fun preferenceCopyPreservesCredentialsAndDisablesCallsLocally() {
        val baseline = existing.copy(secret = "mock-secret", enabled = false)
        val changed = baseline.copy(label = "Gateway", liveCallEnabled = false)
        assertEquals(baseline.deviceToken, changed.deviceToken)
        assertEquals(baseline.secret, changed.secret)
        assertEquals(baseline.enabled, changed.enabled)
        assertEquals(baseline.code, changed.code)
        assertEquals(baseline.role, changed.role)
        assertEquals(baseline.server, changed.server)
        assertEquals(baseline.transport, changed.transport)
        assertEquals(baseline.fbConfig, changed.fbConfig)
        assertFalse(changed.liveCallEnabled)
        assertTrue(allowed(pair = changed))
    }
}
