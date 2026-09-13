package com.nextnotif.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Explicit maintenance opt-in only. No audio, permission grants, or remote writes. */
@RunWith(AndroidJUnit4::class)
class SecureRelayUpgradeInstrumentedTest {
    @Test fun upgradeOnlyApprovedPairingToTls() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue("Explicit secure relay upgrade opt-in required", args.getString("secureRelayUpgrade") == "true")
        val code = requireNotNull(args.getString("pairingCode"))
        require(code == "454512") { "This maintenance action targets only the approved test pairing" }
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val before = SessionStore.load(context)
        val pairing = before.pairings.single { it.code == code }
        val from = "ws://relay.amberdogeorgia.com"
        val to = "wss://relay.amberdogeorgia.com"
        require(pairing.server == from || pairing.server == to) { "Unexpected relay; no settings changed" }
        val history = MessageStore.from(context).load()
        val expected = before.copy(pairings = before.pairings.map {
            if (it.code == code) it.copy(server = to) else it
        })
        SessionStore.save(context, expected)
        // SessionStore.save uses apply(); maintenance must flush to disk before
        // instrumentation exits and Android replaces its process.
        assertTrue("Unable to persist secure relay settings", context.getSharedPreferences(
            "nextnotif_prefs", android.content.Context.MODE_PRIVATE).edit()
            .putLong("secure_relay_upgrade_flush", System.currentTimeMillis()).commit())
        // Boolean assertions avoid exposing credentials/history in test failures.
        assertTrue("Pairing settings were not preserved", expected == SessionStore.load(context))
        assertTrue("History changed during relay upgrade", history == MessageStore.from(context).load())
        assertEquals(to, SessionStore.load(context).pairings.single { it.code == code }.server)
    }
}
