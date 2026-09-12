package com.nextnotif.app

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RelaySelfTestTest {
    private val sender = PairingInfo(
        code = "123456",
        role = Role.SENDER,
        server = "https://relay.example",
        transport = FcmOnDemand.TRANSPORT,
    )

    @Test
    fun `only enabled FCM sender can start test`() {
        assertTrue(RelaySelfTest.canSend(sender))
        assertFalse(RelaySelfTest.canSend(sender.copy(enabled = false)))
        assertFalse(RelaySelfTest.canSend(sender.copy(role = Role.RECEIVER)))
        assertFalse(RelaySelfTest.canSend(sender.copy(transport = null)))
    }

    @Test
    fun `test payload is dedicated and contains no call controls`() {
        val payload = RelaySelfTest.payload(42L)

        assertEquals(RelaySelfTest.DEFAULT_MESSAGE, payload.getString("message"))
        assertEquals(42L, payload.getLong("ts"))
        assertFalse(payload.has("state"))
        assertFalse(payload.has("live_call_available"))
    }

    @Test
    fun `receiver message is bounded and safely defaults`() {
        assertEquals(
            RelaySelfTest.DEFAULT_MESSAGE,
            RelaySelfTest.receivedMessage(JSONObject()),
        )
        assertEquals(
            "x".repeat(160),
            RelaySelfTest.receivedMessage(JSONObject().put("message", "x".repeat(500))),
        )
    }

    @Test
    fun `uplink response exposes queued delivery`() {
        val result = SenderUplink.parseAcceptedResponse("""{"delivered":false,"queued":2}""")

        assertTrue(result.accepted)
        assertFalse(result.deliveredDirectly ?: true)
        assertEquals(2, result.queuedCount)
    }
}
