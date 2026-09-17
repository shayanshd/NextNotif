package com.nextnotif.app

import org.junit.Assert.assertEquals
import org.junit.Test

class ComponentsTest {

    private fun pairing(transport: String? = null) = PairingInfo(
        code = "123456",
        role = Role.RECEIVER,
        server = "ws://relay.example.com",
        transport = transport,
    )

    @Test
    fun noEditingDefaultsToWs() {
        assertEquals(TRANSPORT_WS, initialTransportFor(null))
        assertEquals("ws", initialTransportFor(null))
    }

    @Test
    fun editingWsPairingStaysWs() {
        assertEquals(TRANSPORT_WS, initialTransportFor(pairing(null)))
        assertEquals(TRANSPORT_WS, initialTransportFor(pairing(TRANSPORT_WS)))
    }

    @Test
    fun editingFirebasePairingStaysFirebase() {
        val fb = pairing(FirebaseRelay.TRANSPORT)
        assertEquals(FirebaseRelay.TRANSPORT, initialTransportFor(fb))
        assertEquals("firebase", initialTransportFor(fb))
    }

    @Test
    fun unrecognizedTransportFallsBackToWs() {
        assertEquals(TRANSPORT_WS, initialTransportFor(pairing("carrier-pigeon")))
    }

    @Test
    fun partnerStatusSenderSeesReceiverSlotAndName() {
        val json = """{"exists":true,"sender_connected":true,"receiver_connected":true,
            "sender_name":"Samsung SM-A520F","receiver_name":"Xiaomi 23049PCD8G"}"""
        val (online, name) = partnerStatusFrom(json, Role.SENDER)
        assertEquals(true, online)
        assertEquals("Xiaomi 23049PCD8G", name)
        val (revOnline, revName) = partnerStatusFrom(json, Role.RECEIVER)
        assertEquals(true, revOnline)
        assertEquals("Samsung SM-A520F", revName)
    }

    @Test
    fun partnerStatusJsonNullNameIsUnknown() {
        val json = """{"exists":true,"sender_connected":false,"receiver_connected":true,"sender_name":null}"""
        val (senderOnline, senderName) = partnerStatusFrom(json, Role.SENDER)
        assertEquals(true, senderOnline) // partner = receiver slot, connected
        assertEquals(null, senderName) // no receiver_name key at all
        val (receiverOnline, receiverName) = partnerStatusFrom(json, Role.RECEIVER)
        assertEquals(false, receiverOnline) // partner = sender slot, offline
        assertEquals(null, receiverName) // sender_name is JSON null
    }

    @Test
    fun partnerStatusLiteralNullStringIsUnknown() {
        val json = """{"exists":true,"sender_connected":false,"receiver_connected":true,"sender_name":"null"}"""
        assertEquals(null, partnerStatusFrom(json, Role.SENDER).second)
        assertEquals(null, partnerStatusFrom(json, Role.RECEIVER).second)
    }

    @Test
    fun partnerStatusMissingNameKeyIsUnknown() {
        val json = """{"exists":true,"sender_connected":false,"receiver_connected":false}"""
        assertEquals(false, partnerStatusFrom(json, Role.SENDER).first)
        assertEquals(null, partnerStatusFrom(json, Role.SENDER).second)
        assertEquals(null, partnerStatusFrom(json, Role.RECEIVER).second)
    }

    @Test
    fun uptimeFormatsSecondsUnderAMinute() {
        assertEquals("0s", uptimeLabel(0L, 5_000L)) // 0 = never connected
        assertEquals("45s", uptimeLabel(1_000L, 46_000L))
    }

    @Test
    fun uptimeFormatsMinutesUnderAnHour() {
        assertEquals("1m", uptimeLabel(1_000L, 61_000L))
        assertEquals("59m", uptimeLabel(1_000L, 1_000L + 59 * 60_000L + 59_000L))
    }

    @Test
    fun uptimeFormatsHoursWithZeroPaddedMinutes() {
        assertEquals("1h 05m", uptimeLabel(1_000L, 1_000L + 65 * 60_000L))
        assertEquals("3h 00m", uptimeLabel(1_000L, 1_000L + 3 * 3_600_000L))
        assertEquals("23h 59m", uptimeLabel(1_000L, 1_000L + 23 * 3_600_000L + 59 * 60_000L))
    }

    @Test
    fun uptimeFormatsDaysWithHours() {
        assertEquals("1d 2h", uptimeLabel(1_000L, 1_000L + 26 * 3_600_000L))
        assertEquals("2d 5h", uptimeLabel(1_000L, 1_000L + 53 * 3_600_000L))
    }

    @Test
    fun uptimeClampsNegativeAndFutureTimestamps() {
        assertEquals("0s", uptimeLabel(-5L, 0L))
        assertEquals("0s", uptimeLabel(10_000L, 5_000L))
    }

    @Test
    fun participantPrefersNameThenNumberThenUnknown() {
        assertEquals("Samin (+1234567890)", eventParticipant("Samin", "+1234567890"))
        assertEquals("Samin", eventParticipant("Samin", "unknown"))
        assertEquals("+1234567890", eventParticipant(null, "+1234567890"))
        assertEquals("unknown", eventParticipant(null, "unknown"))
    }
}
