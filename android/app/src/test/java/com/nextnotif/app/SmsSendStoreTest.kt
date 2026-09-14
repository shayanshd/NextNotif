package com.nextnotif.app

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class SmsSendStoreTest {
    private fun command() = JSONObject().put("id", "00000000-0000-4000-8000-000000000001")
        .put("to", "+15551234567").put("body", "Hello").put("created_at", 1_000_000L).put("parts", 2)

    @Test fun refusesServiceNamesDialCodesAndEmptyDestinations() {
        assertNull(smsDestination("Bank"))
        assertNull(smsDestination("*123#"))
        assertNull(smsDestination(""))
        assertEquals("+15551234567", smsDestination("+1 (555) 123-4567"))
    }
    @Test fun validatesAgeBodyAndIdentityBeforeModemAccess() {
        assertTrue(validSmsRequest(command(), 1_000_001))
        assertFalse(validSmsRequest(command(), 1_000_000 + SMS_REQUEST_TTL))
        assertFalse(validSmsRequest(command().put("body", " "), 1_000_001))
        assertFalse(validSmsRequest(command().put("body", "x".repeat(1601)), 1_000_001))
        assertFalse(validSmsRequest(command().put("id", "bad"), 1_000_001))
        assertFalse(validSmsRequest(command().put("created_at", 2_000_000), 1_000_001))
    }
    @Test fun explicitSimNeverFallsBackToAnotherSubscription() {
        assertEquals(12, selectSmsSubscription(12, listOf(7, 12), 7))
        assertNull(selectSmsSubscription(12, listOf(7), 7))
        assertNull(selectSmsSubscription(12, emptyList(), 7))
        assertEquals(7, selectSmsSubscription(null, listOf(7, 12), 7))
        assertEquals(12, selectSmsSubscription(null, listOf(12), -1))
        assertNull(selectSmsSubscription(null, listOf(7, 12), -1))
    }
    @Test fun simSelectionIsValidatedAndRetainedAcrossRestart() {
        assertTrue(validSmsRequest(command().put("subscription_id", 12), 1_000_001))
        for (bad in listOf(-1, "12", true, 1.5))
            assertFalse(validSmsRequest(command().put("subscription_id", bad), 1_000_001))
        val prefs = FakeSharedPreferences()
        SmsSendStore(prefs).claim(command().put("subscription_id", 12))
        assertEquals(12, smsSubscription(SmsSendStore(prefs).get(command().getString("id"))!!))
    }
    @Test fun claimSurvivesRestartAndPreventsSecondModemSubmission() {
        val prefs = FakeSharedPreferences()
        assertTrue(SmsSendStore(prefs).claim(command()))
        val restarted = SmsSendStore(prefs)
        assertFalse(restarted.claim(command()))
        assertEquals("sending", restarted.get(command().getString("id"))!!.getString("status"))
    }
    @Test fun multipartWaitsForAllPartsAndDuplicateCallbacksDoNotCountTwice() {
        val store = SmsSendStore(FakeSharedPreferences())
        val data = command()
        val id = data.getString("id")
        store.claim(data)
        assertEquals("sending", store.partResult(id, 0, 2, true)!!.getString("status"))
        assertEquals("sending", store.partResult(id, 0, 2, true)!!.getString("status"))
        assertEquals("sent", store.partResult(id, 1, 2, true)!!.getString("status"))
    }
    @Test fun failedDurableClaimNeverReturnsPermissionToSend() {
        val prefs = FakeSharedPreferences().apply { failCommits = true }
        try {
            SmsSendStore(prefs).claim(command())
            fail("Claim must fail before modem submission")
        } catch (_: IllegalStateException) { }
    }
    @Test fun clearingHistoryRetainsDeduplicationAndLateResults() {
        val store = SmsSendStore(FakeSharedPreferences())
        val id = command().getString("id")
        store.claim(command())
        store.hideHistory()
        assertFalse(store.claim(command()))
        store.partResult(id, 0, 2, true)
        val result = store.partResult(id, 1, 2, true)!!
        assertTrue(result.getBoolean("hidden"))
        assertEquals("sent", result.getString("status"))
    }
    @Test fun lateQueueSnapshotsCannotRegressCarrierSuccess() {
        val store = SmsSendStore(FakeSharedPreferences())
        val id = command().getString("id")
        store.put(command().put("status", "sent"))
        store.mergeStatus(id, JSONObject().put("status", "queued"))
        assertEquals("sent", store.get(id)!!.getString("status"))
    }
    @Test fun partialFailureIsNeverShownAsSent() {
        val store = SmsSendStore(FakeSharedPreferences())
        val id = command().getString("id")
        store.claim(command())
        store.partResult(id, 1, 2, false)
        assertEquals("failed", store.partResult(id, 0, 2, true)!!.getString("status"))
        assertNull(store.partResult(id, 4, 2, true))
    }
}
