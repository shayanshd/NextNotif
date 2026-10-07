package com.nextnotif.app

import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PairingHttpAuthTest {
    @Test
    fun securePairingSendsBoundDeviceAndToken() {
        val pairing = PairingInfo("123456", Role.RECEIVER, "wss://relay.example",
            deviceId = "device-id", deviceToken = "device-token")
        val request = Request.Builder().url("https://relay.example/fetch")
            .pairingAuth(pairing).build()
        assertEquals("device-id", request.header("X-NextNotif-Device-Id"))
        assertEquals("device-token", request.header("X-NextNotif-Token"))
    }

    @Test
    fun legacyPairingDoesNotInventDeviceIdentity() {
        val pairing = PairingInfo("123456", Role.RECEIVER, "wss://relay.example",
            deviceToken = "legacy-token")
        val request = Request.Builder().url("https://relay.example/fetch")
            .pairingAuth(pairing).build()
        assertNull(request.header("X-NextNotif-Device-Id"))
        assertEquals("legacy-token", request.header("X-NextNotif-Token"))
    }
}
