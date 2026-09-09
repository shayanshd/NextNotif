package com.nextnotif.app

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SessionStoreInstrumentedTest {

    private val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()

    @After
    fun tearDown() = SessionStore.clear(ctx)

    @Test
    fun saveLoadRoundTrip() {
        SessionStore.save(
            ctx,
            SessionState(role = Role.SENDER, code = "123456", server = "ws://example.com:8000"),
        )
        val loaded = SessionStore.load(ctx)
        assertEquals(Role.SENDER, loaded.role)
        assertEquals("123456", loaded.code)
        assertEquals("ws://example.com:8000", loaded.server)
    }

    @Test
    fun clearRemovesRoleAndCodeKeepsDefaultServer() {
        SessionStore.save(
            ctx,
            SessionState(role = Role.RECEIVER, code = "654321", server = "ws://example.com:8000"),
        )
        SessionStore.clear(ctx)
        val loaded = SessionStore.load(ctx)
        assertNull(loaded.role)
        assertNull(loaded.code)
        assertEquals(Config.DEFAULT_SERVER, loaded.server)
    }

    @Test
    fun savingNullRoleOverwrites() {
        SessionStore.save(ctx, SessionState(role = Role.SENDER, code = "123456"))
        SessionStore.save(ctx, SessionStore.load(ctx).copy(role = null))
        val loaded = SessionStore.load(ctx)
        assertNull(loaded.role)
        assertEquals("123456", loaded.code)
    }

    @Test
    fun loadOnFreshPrefsGivesDefaults() {
        val prefs = ctx.getSharedPreferences(SessionStoreTestFresh, 0)
        assertTrue(prefs.all.isEmpty())
        val state = SessionStore.loadFrom(prefs)
        assertNull(state.role)
        assertNull(state.code)
        assertEquals(Config.DEFAULT_SERVER, state.server)
    }

    @Test
    fun pairingsListSurvivesRealSharedPreferencesRoundTrip() {
        SessionStore.save(
            ctx,
            SessionState(
                role = Role.SENDER,
                code = "111111",
                pairings = listOf(
                    PairingInfo(code = "111111", role = Role.SENDER, server = "ws://a:8000", deviceToken = "tok-a"),
                    PairingInfo(code = "222222", role = Role.RECEIVER, server = "wss://b:8000"),
                ),
            ),
        )
        val loaded = SessionStore.load(ctx)
        assertEquals(2, loaded.pairings.size)
        assertEquals("111111", loaded.pairings[0].code)
        assertEquals(Role.SENDER, loaded.pairings[0].role)
        assertEquals("tok-a", loaded.pairings[0].deviceToken)
        assertEquals("222222", loaded.pairings[1].code)
        assertEquals(Role.RECEIVER, loaded.pairings[1].role)
        assertEquals("wss://b:8000", loaded.pairings[1].server)
    }

    @Test
    fun removingAllPairingsClearsLegacyFields() {
        SessionStore.save(
            ctx,
            SessionState(
                role = Role.SENDER,
                code = "111111",
                pairings = listOf(PairingInfo(code = "111111", role = Role.SENDER, server = "ws://a:8000")),
            ),
        )
        val cur = SessionStore.load(ctx)
        SessionStore.save(
            ctx,
            cur.copy(
                pairings = emptyList(),
                code = null,
                deviceToken = null,
                server = Config.DEFAULT_SERVER,
            ),
        )
        val loaded = SessionStore.load(ctx)
        assertTrue(loaded.pairings.isEmpty())
        assertNull(loaded.code)
    }

    @Test
    fun legacyOnlyPrefsMigrateToPairingsList() {
        val prefs = ctx.getSharedPreferences(SessionStoreTestFresh, 0)
        prefs.edit()
            .putString("role", "RECEIVER")
            .putString("code", "424645")
            .putString("device_token", "tok")
            .apply()
        val state = SessionStore.loadFrom(prefs)
        assertEquals(1, state.pairings.size)
        assertEquals("424645", state.pairings[0].code)
        assertEquals(Role.RECEIVER, state.pairings[0].role)
        assertEquals("tok", state.pairings[0].deviceToken)
    }

    companion object {
        const val SessionStoreTestFresh = "nextnotif_test_fresh"
    }
}
