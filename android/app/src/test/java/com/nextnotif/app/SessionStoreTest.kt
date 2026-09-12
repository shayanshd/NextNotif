package com.nextnotif.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionStoreTest {

    @Test
    fun saveLoadRoundTrip() {
        val prefs = FakeSharedPreferences()
        val state = SessionState(
            role = Role.SENDER,
            code = "ABC123",
            server = "ws://example.com:8000",
            deviceToken = "dev-tok-123",
            relayEnabled = false,
        )
        SessionStore.saveTo(prefs, state)

        val loaded = SessionStore.loadFrom(prefs)
        assertEquals(Role.SENDER, loaded.role)
        assertEquals("ABC123", loaded.code)
        assertEquals("ws://example.com:8000", loaded.server)
        assertEquals("dev-tok-123", loaded.deviceToken)
        assertEquals(false, loaded.relayEnabled)
    }

    @Test
    fun legacyInstallDefaultsRelayIntentOn() {
        val prefs = FakeSharedPreferences()
        prefs.edit().putString("role", "SENDER").putString("code", "123456").apply()

        assertTrue(SessionStore.loadFrom(prefs).relayEnabled)
    }

    @Test
    fun savingNullDeviceTokenRemovesStoredToken() {
        val prefs = FakeSharedPreferences()
        SessionStore.saveTo(prefs, SessionState(role = Role.SENDER, code = "C3", deviceToken = "tok-1"))
        SessionStore.saveTo(prefs, SessionState(role = Role.SENDER, code = "C3", deviceToken = null))

        val loaded = SessionStore.loadFrom(prefs)
        assertNull(loaded.deviceToken)
        assertEquals("C3", loaded.code)
    }

    @Test
    fun clearRemovesEverything() {
        val prefs = FakeSharedPreferences()
        SessionStore.saveTo(prefs, SessionState(role = Role.RECEIVER, code = "XYZ", server = "ws://elsewhere:9000"))
        SessionStore.clear(prefs)

        val loaded = SessionStore.loadFrom(prefs)
        assertNull(loaded.role)
        assertNull(loaded.code)
        assertEquals(Config.DEFAULT_SERVER, loaded.server)
    }

    @Test
    fun garbageRoleLoadsAsNull() {
        val prefs = FakeSharedPreferences()
        prefs.edit().putString("role", "NOT_A_ROLE").putString("code", "C1").apply()

        val loaded = SessionStore.loadFrom(prefs)
        assertNull(loaded.role)
        assertEquals("C1", loaded.code)
    }

    @Test
    fun missingServerKeyDefaultsToConfig() {
        val prefs = FakeSharedPreferences()
        prefs.edit().putString("role", "SENDER").putString("code", "C2").apply()

        val loaded = SessionStore.loadFrom(prefs)
        assertEquals(Config.DEFAULT_SERVER, loaded.server)
        assertEquals(Role.SENDER, loaded.role)
    }

    @Test
    fun savingNullCodeRemovesStoredCode() {
        val prefs = FakeSharedPreferences()
        SessionStore.saveTo(prefs, SessionState(role = Role.SENDER, code = "C3"))
        SessionStore.saveTo(prefs, SessionState(role = Role.SENDER, code = null))

        val loaded = SessionStore.loadFrom(prefs)
        assertNull(loaded.code)
        assertEquals(Role.SENDER, loaded.role)
    }

    @Test
    fun pairingsListRoundTrips() {
        val prefs = FakeSharedPreferences()
        val state = SessionState(
            role = Role.SENDER,
            code = "111111",
            server = "ws://a:8000",
            pairings = listOf(
                PairingInfo(code = "111111", role = Role.SENDER, server = "ws://a:8000", deviceToken = "tok-a", label = "Wife's phone"),
                PairingInfo(
                    code = "222222",
                    role = Role.RECEIVER,
                    server = "wss://b:8000",
                    transport = FcmOnDemand.TRANSPORT,
                ),
            ),
        )
        SessionStore.saveTo(prefs, state)

        val loaded = SessionStore.loadFrom(prefs)
        assertEquals(2, loaded.pairings.size)
        assertEquals("111111", loaded.pairings[0].code)
        assertEquals(Role.SENDER, loaded.pairings[0].role)
        assertEquals("tok-a", loaded.pairings[0].deviceToken)
        assertEquals("Wife's phone", loaded.pairings[0].label)
        assertEquals("Wife's phone", loaded.pairings[0].displayName)
        assertEquals(false, loaded.pairings[0].liveCallEnabled)
        assertEquals("222222", loaded.pairings[1].code)
        assertEquals(Role.RECEIVER, loaded.pairings[1].role)
        assertEquals("wss://b:8000", loaded.pairings[1].server)
        assertEquals(FcmOnDemand.TRANSPORT, loaded.pairings[1].transport)
        assertEquals(true, loaded.pairings[1].isFcmOnDemand)
        assertNull(loaded.pairings[1].label)
        assertEquals("Pairing 222222", loaded.pairings[1].displayName)
    }

    @Test
    fun legacyFieldsMigrateToSinglePairingOnLoad() {
        val prefs = FakeSharedPreferences()
        SessionStore.saveTo(
            prefs,
            SessionState(role = Role.RECEIVER, code = "424645", server = "ws://old:8000", deviceToken = "tok"),
        )
        // Simulate a pre-many-to-many install: the pairings key was never written.
        prefs.edit().remove("pairings").apply()

        val loaded = SessionStore.loadFrom(prefs)
        assertEquals(1, loaded.pairings.size)
        assertEquals("424645", loaded.pairings[0].code)
        assertEquals(Role.RECEIVER, loaded.pairings[0].role)
        assertEquals("tok", loaded.pairings[0].deviceToken)
    }

    @Test
    fun persistedPairingsWinOverLegacyFields() {
        val prefs = FakeSharedPreferences()
        SessionStore.saveTo(
            prefs,
            SessionState(
                role = Role.SENDER,
                code = "111111",
                pairings = listOf(
                    PairingInfo(code = "111111", role = Role.SENDER, server = "ws://a:8000"),
                    PairingInfo(code = "222222", role = Role.RECEIVER, server = "ws://b:8000"),
                ),
            ),
        )
        val loaded = SessionStore.loadFrom(prefs)
        assertEquals(2, loaded.pairings.size)
        assertEquals(listOf("111111", "222222"), loaded.pairings.map { it.code })
    }

    @Test
    fun corruptPairingsJsonFallsBackToLegacyMigration() {
        val prefs = FakeSharedPreferences()
        prefs.edit()
            .putString("role", "SENDER")
            .putString("code", "C9")
            .putString("pairings", "{not json")
            .apply()

        val loaded = SessionStore.loadFrom(prefs)
        assertEquals(1, loaded.pairings.size)
        assertEquals("C9", loaded.pairings[0].code)
    }

    @Test
    fun clearingRemovesPairingsList() {
        val prefs = FakeSharedPreferences()
        SessionStore.saveTo(
            prefs,
            SessionState(role = Role.SENDER, code = "111111", pairings = listOf(PairingInfo("111111", Role.SENDER, "ws://a:8000"))),
        )
        SessionStore.clear(prefs)

        val loaded = SessionStore.loadFrom(prefs)
        assertTrue(loaded.pairings.isEmpty())
    }

    @Test
    fun enabledDefaultsToTrueWhenKeyAbsent() {
        val prefs = FakeSharedPreferences()
        prefs.edit()
            .putString(
                "pairings",
                """[{"code":"111111","role":"SENDER","server":"ws://a:8000","deviceToken":"tok"}]""",
            )
            .apply()

        val loaded = SessionStore.loadFrom(prefs)
        assertEquals(1, loaded.pairings.size)
        assertTrue(loaded.pairings[0].enabled)
    }

    @Test
    fun disabledPairingRoundTripsAsDisabled() {
        val prefs = FakeSharedPreferences()
        val state = SessionState(
            role = Role.SENDER,
            code = "111111",
            server = "ws://a:8000",
            pairings = listOf(
                PairingInfo(code = "111111", role = Role.SENDER, server = "ws://a:8000", enabled = false),
                PairingInfo(code = "222222", role = Role.RECEIVER, server = "ws://b:8000"),
            ),
        )
        SessionStore.saveTo(prefs, state)

        val loaded = SessionStore.loadFrom(prefs)
        assertEquals(2, loaded.pairings.size)
        assertEquals(false, loaded.pairings[0].enabled)
        assertEquals(true, loaded.pairings[1].enabled)
    }

    @Test
    fun liveCallOptInRoundTrips() {
        val prefs = FakeSharedPreferences()
        SessionStore.saveTo(
            prefs,
            SessionState(
                pairings = listOf(
                    PairingInfo(
                        code = "111111",
                        role = Role.SENDER,
                        server = "wss://relay.example",
                        liveCallEnabled = true,
                    ),
                ),
            ),
        )

        assertTrue(SessionStore.loadFrom(prefs).pairings.single().liveCallEnabled)
    }

    @Test
    fun liveCallOptInDefaultsOffWhenKeyAbsent() {
        val prefs = FakeSharedPreferences()
        prefs.edit()
            .putString(
                "pairings",
                """[{"code":"111111","role":"SENDER","server":"wss://relay.example"}]""",
            )
            .apply()

        assertEquals(false, SessionStore.loadFrom(prefs).pairings.single().liveCallEnabled)
    }

    @Test
    fun liveCallOptInIsForcedOffForReceiverRole() {
        assertEquals(false, liveCallEnabledFor(Role.RECEIVER, requested = true))
        assertEquals(true, liveCallEnabledFor(Role.SENDER, requested = true))
    }
}
