package com.nextnotif.app

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MessageStoreTest {
    @Test
    fun failedDiskWriteCannotBeMistakenForDurableReplay() {
        val prefs = FakeSharedPreferences().apply { failCommits = true }
        val store = MessageStore(prefs)
        val entry = AppState.Entry(1, "IN", "SMS from Alice: retained", "123456", eventId = "disk-failure")
        assertTrue(runCatching { store.append(entry) }.isFailure)
        assertTrue(runCatching { store.hasEvent("123456", "disk-failure") }.isFailure)
        prefs.failCommits = false
        assertTrue(store.hasEvent("123456", "disk-failure"))
        assertEquals(entry, store.load().single())
    }

    @Test
    fun eventIdentityAndHistoryArePersistedTogetherAndReplayIsIgnored() {
        val prefs = FakeSharedPreferences()
        val entry = AppState.Entry(42, "IN", "SMS from Alice: hello", "123456", eventId = "event-a")
        MessageStore(prefs).append(entry)
        val reopened = MessageStore(prefs)
        assertTrue(reopened.hasEvent("123456", "event-a"))
        assertEquals(entry, reopened.load().single())
        reopened.append(entry.copy(ts = 99))
        assertEquals(listOf(entry), reopened.load())
        reopened.clear()
        assertTrue(reopened.hasEvent("123456", "event-a"))
        reopened.append(entry)
        assertTrue(reopened.load().isEmpty())
    }

    @Test
    fun replayIdentityIsScopedToPairing() {
        val store = MessageStore(FakeSharedPreferences())
        store.append(AppState.Entry(1, "IN", "Call RINGING from Alice", "111111", eventId = "shared"))
        store.append(AppState.Entry(2, "IN", "Call RINGING from Bob", "222222", eventId = "shared"))
        assertEquals(2, store.load().size)
    }

    @Test
    fun filteringKeepsOnlySelectedPairingAndAllPreservesOrder() {
        val first = AppState.Entry(1, "IN", "SMS from a: one", "111111")
        val second = AppState.Entry(2, "IN", "SMS from b: two", "222222")
        val entries = listOf(second, first)
        assertEquals(listOf(first), MessageStore.filterByPairing(entries, "111111"))
        assertEquals(entries, MessageStore.filterByPairing(entries, null))
        assertEquals(emptyList<AppState.Entry>(), MessageStore.filterByPairing(entries, "missing"))
    }

    @Test
    fun structuredFullContentAndIdentitySurviveReload() {
        val prefs = FakeSharedPreferences()
        val entry = AppState.Entry(
            ts = 42,
            tag = "OUT",
            message = "SMS → Alice: complete text",
            code = "123456",
            communication = AppState.CommunicationDetails(
                AppState.CommunicationKind.SMS,
                AppState.CommunicationDirection.OUTGOING,
                address = "+15551234567",
                name = "Alice",
                body = "complete text\nsecond line",
            ),
        )
        MessageStore(prefs).append(entry)
        assertEquals(entry, MessageStore(prefs).load().single())
    }

    @Test
    fun appendPersistsNewestFirstAndSurvivesNewInstance() {
        val prefs = FakeSharedPreferences()
        val store = MessageStore(prefs, maxEntries = 5)

        store.append(entry(1, "IN", "SMS from Alice: hello", "111111"))
        store.append(entry(2, "OUT", "Call ACTIVE → Bob", "222222"))

        assertEquals(
            listOf("Call ACTIVE → Bob", "SMS from Alice: hello"),
            MessageStore(prefs, maxEntries = 5).load().map { it.message },
        )
        assertEquals("222222", MessageStore(prefs, maxEntries = 5).load().first().code)
    }

    @Test
    fun historyIsCapped() {
        val store = MessageStore(FakeSharedPreferences(), maxEntries = 3)
        repeat(5) { index -> store.append(entry(index.toLong(), "IN", "Call RINGING from $index")) }

        assertEquals(listOf(4L, 3L, 2L), store.load().map { it.ts })
    }

    @Test
    fun technicalAndQueuedEventsAreNotPersisted() {
        val store = MessageStore(FakeSharedPreferences())

        store.append(entry(1, "WS", "connected"))
        store.append(entry(2, "OUT", "queued sms (no route)"))
        store.append(entry(3, "IN", "diagnostic payload"))

        assertTrue(store.load().isEmpty())
    }

    @Test
    fun relayTestIsPersistedAsClearlyLabelledHistory() {
        val store = MessageStore(FakeSharedPreferences())

        store.append(entry(4, "TEST", RelaySelfTest.DEFAULT_MESSAGE, "123456"))

        assertEquals(RelaySelfTest.DEFAULT_MESSAGE, store.load().single().message)
        assertEquals("123456", store.load().single().code)
    }

    @Test
    fun corruptRootReturnsEmptyHistory() {
        val prefs = FakeSharedPreferences().apply { data["entries"] = "not-json" }

        assertTrue(MessageStore(prefs).load().isEmpty())
    }

    @Test
    fun corruptRowsAreSkippedWithoutLosingValidRows() {
        val prefs = FakeSharedPreferences()
        val valid = JSONObject()
            .put("ts", 7)
            .put("tag", "IN")
            .put("message", "SMS from Alice: intact")
        prefs.data["entries"] = JSONArray()
            .put(JSONObject().put("tag", "IN"))
            .put("wrong type")
            .put(valid)
            .toString()

        val loaded = MessageStore(prefs).load()

        assertEquals(1, loaded.size)
        assertEquals("SMS from Alice: intact", loaded.single().message)
    }

    @Test
    fun clearRemovesPersistedHistory() {
        val prefs = FakeSharedPreferences()
        val store = MessageStore(prefs)
        store.append(entry(1, "IN", "Call RINGING from Alice"))

        store.clear()

        assertTrue(MessageStore(prefs).load().isEmpty())
    }

    private fun entry(ts: Long, tag: String, message: String, code: String? = null) =
        AppState.Entry(ts = ts, tag = tag, message = message, code = code)
}
