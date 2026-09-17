package com.nextnotif.app

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppStateTest {

    @Test
    fun pushPrependsAndCapsLogAt50() {
        repeat(55) { AppState.push(AppState.EventKind.Info("msg-$it")) }
        val log = AppState.log.value
        assertEquals(50, log.size)
        // Newest first: the 5 oldest pushes were dropped
        for (k in 0 until 50) {
            assertEquals(AppState.EventKind.Info("msg-${54 - k}"), log[k].kind)
        }
    }

    @Test
    fun pushIncomingSmsKeepsFullBody() {
        val body = "a".repeat(100)
        AppState.pushIncoming(
            "sms",
            JSONObject().put("from", "+15551234567").put("body", body),
        )
        val entry = AppState.log.value.first()
        assertEquals(
            AppState.EventKind.SmsIn(from = "+15551234567", name = null, body = body),
            entry.kind,
        )
    }

    @Test
    fun pushIncomingCallFormats() {
        AppState.pushIncoming(
            "call",
            JSONObject().put("state", "ringing").put("number", "+15550001111"),
        )
        val entry = AppState.log.value.first()
        assertEquals(
            AppState.EventKind.CallIn(number = "+15550001111", name = null, state = "ringing"),
            entry.kind,
        )
    }

    @Test
    fun pushIncomingUnknownTypeFallsBack() {
        val data = JSONObject().put("x", 1)
        AppState.pushIncoming("mms", data)
        val entry = AppState.log.value.first()
        assertEquals(AppState.EventKind.Info("mms $data"), entry.kind)
    }

    @Test
    fun pushOutgoingSmsFormats() {
        AppState.pushOutgoing("sms", JSONObject().put("from", "+15559998888"))
        val entry = AppState.log.value.first()
        assertEquals(
            AppState.EventKind.SmsOut(from = "+15559998888", name = null),
            entry.kind,
        )
    }

    @Test
    fun setConnConnectedSetsConnSince() {
        AppState.setConn(AppState.ConnState.CONNECTED)
        assertEquals(AppState.ConnState.CONNECTED, AppState.conn.value)
        assertTrue(AppState.connSince.value > 0)
    }

    @Test
    fun setConnDisconnectedResetsConnSince() {
        AppState.setConn(AppState.ConnState.CONNECTED)
        AppState.setConn(AppState.ConnState.DISCONNECTED)
        assertEquals(AppState.ConnState.DISCONNECTED, AppState.conn.value)
        assertEquals(0L, AppState.connSince.value)
    }

    @Test
    fun setErrorUpdatesLastError() {
        AppState.setError("boom")
        assertEquals("boom", AppState.lastError.value)
    }

    @Test
    fun setConnStateTracksPerPairingIndependently() {
        AppState.clearStates()
        AppState.setConnState("111111", AppState.ConnState.CONNECTED)
        AppState.setConnState("222222", AppState.ConnState.CONNECTING)

        val states = AppState.connStates.value
        assertEquals(AppState.ConnState.CONNECTED, states["111111"])
        assertEquals(AppState.ConnState.CONNECTING, states["222222"])
    }

    @Test
    fun aggregatePrefersConnectedOverConnecting() {
        AppState.clearStates()
        AppState.setConnState("111111", AppState.ConnState.CONNECTING)
        assertEquals(AppState.ConnState.CONNECTING, AppState.conn.value)
        AppState.setConnState("222222", AppState.ConnState.CONNECTED)
        assertEquals(AppState.ConnState.CONNECTED, AppState.conn.value)
    }

    @Test
    fun aggregateFallsBackToDisconnectedWhenAllDrop() {
        AppState.clearStates()
        AppState.setConnState("111111", AppState.ConnState.CONNECTED)
        AppState.setConnState("222222", AppState.ConnState.CONNECTED)
        AppState.setConnState("111111", AppState.ConnState.DISCONNECTED)
        assertEquals(AppState.ConnState.CONNECTED, AppState.conn.value)
        AppState.setConnState("222222", AppState.ConnState.DISCONNECTED)
        assertEquals(AppState.ConnState.DISCONNECTED, AppState.conn.value)
    }

    @Test
    fun clearStatesWipesEverything() {
        AppState.setConnState("111111", AppState.ConnState.CONNECTED)
        AppState.setPartnerState("111111", AppState.ConnState.CONNECTED)
        AppState.setPairingError("111111", "boom")

        AppState.clearStates()

        assertTrue(AppState.connStates.value.isEmpty())
        assertTrue(AppState.partnerStates.value.isEmpty())
        assertTrue(AppState.pairingErrors.value.isEmpty())
        assertEquals(AppState.ConnState.IDLE, AppState.conn.value)
        assertEquals(0L, AppState.connSince.value)
    }

    @Test
    fun partnerAndErrorMapsTrackPerPairing() {
        AppState.clearStates()
        AppState.setPartnerState("111111", AppState.ConnState.CONNECTED, "Xiaomi 23049PCD8G")
        AppState.setPartnerState("222222", AppState.ConnState.DISCONNECTED)
        AppState.setPairingError("222222", "no route")

        val partners = AppState.partnerStates.value
        assertEquals(AppState.ConnState.CONNECTED, partners["111111"]!!.state)
        assertEquals("Xiaomi 23049PCD8G", partners["111111"]!!.name)
        assertEquals(AppState.ConnState.DISCONNECTED, partners["222222"]!!.state)
        assertNull(partners["222222"]!!.name)
        assertEquals("no route", AppState.pairingErrors.value["222222"])
    }

    @Test
    fun pushWithCodeTagsEntryForPerPairingFiltering() {
        AppState.push(AppState.EventKind.Connected(firebase = false), "111111")
        AppState.push(AppState.EventKind.Info("global note"))
        val log = AppState.log.value
        assertEquals(AppState.EventKind.Info("global note"), log[0].kind)
        assertNull(log[0].code)
        assertEquals("111111", log[1].code)
        // Simulates the pairing detail screen's filter.
        val forPairing = log.filter { it.code == "111111" }
        assertEquals(1, forPairing.size)
        assertEquals(AppState.EventKind.Connected(firebase = false), forPairing[0].kind)
    }

    @Test
    fun pushIncomingAndOutgoingCarryPairingCode() {
        AppState.pushIncoming("sms", JSONObject().put("from", "+15551234567").put("body", "hi"), "222222")
        assertEquals("222222", AppState.log.value.first().code)
        AppState.pushOutgoing("sms", JSONObject().put("from", "+15551234567"), "333333")
        assertEquals("333333", AppState.log.value.first().code)
        assertEquals("222222", AppState.log.value[1].code)
    }

    @Test
    fun clearPairingStateRemovesOnlyThatCode() {
        AppState.clearStates()
        AppState.setConnState("111111", AppState.ConnState.CONNECTED)
        AppState.setConnState("222222", AppState.ConnState.CONNECTED)
        AppState.setPartnerState("111111", AppState.ConnState.CONNECTED, "Xiaomi 23049PCD8G")
        AppState.setPartnerState("222222", AppState.ConnState.DISCONNECTED)
        AppState.setPairingError("111111", "boom")
        AppState.setPairingError("222222", "no route")

        AppState.clearPairingState("111111")

        val states = AppState.connStates.value
        assertNull(states["111111"])
        assertEquals(AppState.ConnState.CONNECTED, states["222222"])
        val partners = AppState.partnerStates.value
        assertNull(partners["111111"])
        assertEquals(AppState.ConnState.DISCONNECTED, partners["222222"]!!.state)
        assertNull(AppState.pairingErrors.value["111111"])
        assertEquals("no route", AppState.pairingErrors.value["222222"])
        // Aggregate recomputes: the remaining pairing is still connected.
        assertEquals(AppState.ConnState.CONNECTED, AppState.conn.value)

        AppState.clearPairingState("222222")

        assertTrue(AppState.connStates.value.isEmpty())
        assertTrue(AppState.partnerStates.value.isEmpty())
        assertTrue(AppState.pairingErrors.value.isEmpty())
        assertEquals(AppState.ConnState.IDLE, AppState.conn.value)
    }
}
