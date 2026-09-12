package com.nextnotif.app

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class IncomingEventHandlerTest {
    @Test
    fun `legacy call payload does not offer live relay`() {
        assertFalse(IncomingEventHandler.isLiveCallAvailable(JSONObject()))
    }

    @Test
    fun `explicit true enables live relay`() {
        val data = JSONObject().put("live_call_available", true)

        assertTrue(IncomingEventHandler.isLiveCallAvailable(data))
    }

    @Test
    fun `false and malformed values remain informational only`() {
        assertFalse(
            IncomingEventHandler.isLiveCallAvailable(
                JSONObject().put("live_call_available", false),
            ),
        )
        assertFalse(
            IncomingEventHandler.isLiveCallAvailable(
                JSONObject().put("live_call_available", "not-a-boolean"),
            ),
        )
    }

    @Test
    fun `answer action requires explicit availability and pairing code`() {
        assertNull(callNotificationAction("RINGING", "123456", liveCallAvailable = false))
        assertNull(callNotificationAction("RINGING", null, liveCallAvailable = true))
        assertEquals(
            CallNotificationAction.ANSWER,
            callNotificationAction("RINGING", "123456", liveCallAvailable = true),
        )
    }

    @Test
    fun `hang up action is gated and informational states have no action`() {
        assertNull(callNotificationAction("OFFHOOK", "123456", liveCallAvailable = false))
        assertEquals(
            CallNotificationAction.HANG_UP,
            callNotificationAction("OFFHOOK", "123456", liveCallAvailable = true),
        )
        assertNull(callNotificationAction("IDLE", "123456", liveCallAvailable = true))
    }
}
