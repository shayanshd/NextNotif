package com.nextnotif.app

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OutboxQueueTest {

    private fun data(i: Int) = JSONObject().put("i", i)

    @Test
    fun fifoOrderPreserved() {
        val prefs = FakeSharedPreferences()
        OutboxQueue.enqueue(prefs, "111111", "sms", data(1))
        OutboxQueue.enqueue(prefs, "111111", "call", data(2))
        OutboxQueue.enqueue(prefs, "111111", "sms", data(3))

        val drained = OutboxQueue.drain(prefs, "111111")
        assertEquals(3, drained.size)
        assertEquals("sms", drained[0].first)
        assertEquals(1, drained[0].second.getInt("i"))
        assertEquals("call", drained[1].first)
        assertEquals(2, drained[1].second.getInt("i"))
        assertEquals("sms", drained[2].first)
        assertEquals(3, drained[2].second.getInt("i"))
    }

    @Test
    fun capacityCapsAt100EvictingOldest() {
        val prefs = FakeSharedPreferences()
        repeat(105) { OutboxQueue.enqueue(prefs, "111111", "sms", data(it)) }

        val drained = OutboxQueue.drain(prefs, "111111")
        assertEquals(100, drained.size)
        // Oldest 5 (i = 0..4) evicted, remaining 100 in FIFO order
        for (k in 0 until 100) {
            assertEquals(5 + k, drained[k].second.getInt("i"))
        }
        assertEquals("sms", drained[0].first)
    }

    @Test
    fun drainReturnsAllAndLeavesQueueEmpty() {
        val prefs = FakeSharedPreferences()
        OutboxQueue.enqueue(prefs, "111111", "sms", data(1))
        OutboxQueue.enqueue(prefs, "111111", "call", data(2))

        assertEquals(2, OutboxQueue.drain(prefs, "111111").size)
        assertTrue(OutboxQueue.drain(prefs, "111111").isEmpty())
    }

    @Test
    fun corruptStoredJsonTreatedAsEmpty() {
        val prefs = FakeSharedPreferences()
        prefs.edit().putString("outbox_111111", "{this is not valid json").apply()

        val drained = OutboxQueue.drain(prefs, "111111")
        assertTrue(drained.isEmpty())
    }

    @Test
    fun missingStoredKeyTreatedAsEmpty() {
        val prefs = FakeSharedPreferences()
        assertTrue(OutboxQueue.drain(prefs, "111111").isEmpty())
    }

    @Test
    fun enqueueAfterDrainWorks() {
        val prefs = FakeSharedPreferences()
        OutboxQueue.enqueue(prefs, "111111", "sms", data(1))
        OutboxQueue.drain(prefs, "111111")
        OutboxQueue.enqueue(prefs, "111111", "call", data(9))

        val drained = OutboxQueue.drain(prefs, "111111")
        assertEquals(1, drained.size)
        assertEquals("call", drained[0].first)
        assertEquals(9, drained[0].second.getInt("i"))
    }

    @Test
    fun peekDoesNotRemoveEventUntilAcknowledged() {
        val prefs = FakeSharedPreferences()
        OutboxQueue.enqueue(prefs, "111111", "sms", data(1))

        val first = OutboxQueue.peek(prefs, "111111")!!
        val retried = OutboxQueue.peek(prefs, "111111")!!

        assertEquals(first.id, retried.id)
        assertEquals(1, retried.data.getInt("i"))
        assertTrue(OutboxQueue.acknowledge(prefs, "111111", first.id))
        assertTrue(OutboxQueue.peek(prefs, "111111") == null)
    }

    @Test
    fun acknowledgementRemovesOnlyMatchingEvent() {
        val prefs = FakeSharedPreferences()
        OutboxQueue.enqueue(prefs, "111111", "sms", data(1))
        OutboxQueue.enqueue(prefs, "111111", "call", data(2))

        val first = OutboxQueue.peek(prefs, "111111")!!
        assertTrue(OutboxQueue.acknowledge(prefs, "111111", first.id))

        val second = OutboxQueue.peek(prefs, "111111")!!
        assertEquals("call", second.type)
        assertEquals(2, second.data.getInt("i"))
        assertTrue(!OutboxQueue.acknowledge(prefs, "111111", first.id))
        assertEquals(second.id, OutboxQueue.peek(prefs, "111111")!!.id)
    }

    @Test
    fun legacyEntryWithoutIdIsMigratedAndCanBeAcknowledged() {
        val prefs = FakeSharedPreferences()
        val legacy = org.json.JSONArray().put(
            JSONObject().put("type", "sms").put("data", data(7)),
        )
        prefs.edit().putString("outbox_111111", legacy.toString()).apply()

        val entry = OutboxQueue.peek(prefs, "111111")!!

        assertEquals(7, entry.data.getInt("i"))
        assertTrue(entry.id.isNotBlank())
        assertTrue(OutboxQueue.acknowledge(prefs, "111111", entry.id))
        assertTrue(OutboxQueue.peek(prefs, "111111") == null)
    }

    @Test
    fun queuesAreIsolatedPerPairing() {
        val prefs = FakeSharedPreferences()
        OutboxQueue.enqueue(prefs, "111111", "sms", data(1))
        OutboxQueue.enqueue(prefs, "222222", "call", data(2))

        val first = OutboxQueue.drain(prefs, "111111")
        val second = OutboxQueue.drain(prefs, "222222")
        assertEquals(1, first.size)
        assertEquals("sms", first[0].first)
        assertEquals(1, second.size)
        assertEquals("call", second[0].first)
    }

    @Test
    fun capacityAppliesPerPairing() {
        val prefs = FakeSharedPreferences()
        repeat(105) { OutboxQueue.enqueue(prefs, "111111", "sms", data(it)) }
        OutboxQueue.enqueue(prefs, "222222", "sms", data(1))

        assertEquals(100, OutboxQueue.drain(prefs, "111111").size)
        assertEquals(1, OutboxQueue.drain(prefs, "222222").size)
    }
}
