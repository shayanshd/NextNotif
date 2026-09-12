package com.nextnotif.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BootReceiverPolicyTest {
    private val pairing = PairingInfo(
        code = "123456",
        role = Role.SENDER,
        server = "wss://relay.example",
    )

    @Test
    fun restoresWhenUserLeftRelayEnabled() {
        assertTrue(shouldRestoreRelay(SessionState(pairings = listOf(pairing))))
    }

    @Test
    fun respectsExplicitStopAcrossRestart() {
        assertFalse(
            shouldRestoreRelay(
                SessionState(pairings = listOf(pairing), relayEnabled = false),
            ),
        )
    }

    @Test
    fun ignoresEmptyOrFullyDisabledConfiguration() {
        assertFalse(shouldRestoreRelay(SessionState()))
        assertFalse(
            shouldRestoreRelay(
                SessionState(pairings = listOf(pairing.copy(enabled = false))),
            ),
        )
    }
}
