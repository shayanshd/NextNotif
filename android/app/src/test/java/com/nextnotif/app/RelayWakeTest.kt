package com.nextnotif.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RelayWakeTest {
    private val pairing = PairingInfo("123456", Role.RECEIVER, "wss://relay.example")
    private val wake = mapOf("nn" to "1", "action" to "wake", "code" to pairing.code)
    private fun receive(p: PairingInfo = pairing, data: Map<String, String> = wake, requested: Boolean = true) =
        RelayWake.receiver(data, SessionState(pairings = listOf(p)), requested)

    @Test fun enabledReceiverCanRecover() {
        assertEquals(pairing, receive())
    }

    @Test fun explicitStopAndPausedPairingRejectStalePush() {
        assertNull(receive(requested = false))
        assertNull(receive(pairing.copy(enabled = false)))
    }

    @Test fun senderAndFirebasePairingsCannotBeWokenByRelayPush() {
        assertNull(receive(pairing.copy(role = Role.SENDER)))
        assertNull(receive(pairing.copy(transport = FirebaseRelay.TRANSPORT)))
    }

    @Test fun removedOrDifferentPairingIsIgnored() {
        assertNull(RelayWake.receiver(wake, SessionState(), true))
        assertNull(receive(data = wake + ("code" to "654321")))
    }

    @Test fun unknownOrIncompletePayloadIsIgnored() {
        assertNull(receive(data = wake - "nn"))
        assertNull(receive(data = wake - "code"))
        assertNull(receive(data = wake + ("action" to "forward")))
        assertNull(receive(data = emptyMap()))
    }

    @Test fun manyPairingsChooseOnlyMatchingReceiver() {
        val session = SessionState(pairings = listOf(pairing.copy(code = "654321"), pairing))
        assertEquals(pairing, RelayWake.receiver(wake, session, true))
    }
}
