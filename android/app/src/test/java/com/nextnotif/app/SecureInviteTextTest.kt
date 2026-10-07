package com.nextnotif.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SecureInviteTextTest {
    private val secret = "s".repeat(43)

    @Test
    fun inviteRoundTripCarriesServerRoleAndSecret() {
        val text = SecureInviteText.encode("wss://relay.example/", "123456", Role.RECEIVER, secret)
        assertEquals(SecureInvite("wss://relay.example", "123456", Role.RECEIVER, secret),
            SecureInviteText.parse(text))
    }

    @Test
    fun malformedOrAmbiguousInvitesAreRejected() {
        assertNull(SecureInviteText.parse("NN1|wss://relay.example|123456|receiver|short"))
        assertNull(SecureInviteText.parse("NN1|wss://evil@relay.example|123456|receiver|$secret"))
        assertNull(SecureInviteText.parse("NN1|wss://relay.example/path|123456|receiver|$secret"))
        assertNull(SecureInviteText.parse("NN1|wss://relay.example|123456|receiver|$secret|extra"))
    }
}
