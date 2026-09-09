package com.nextnotif.app

import android.content.Context
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OutboxQueueInstrumentedTest {

    private lateinit var ctx: Context
    private lateinit var prefs: SharedPreferences

    @Before
    fun setUp() {
        ctx = ApplicationProvider.getApplicationContext()
        prefs = ctx.getSharedPreferences("nextnotif_outbox", Context.MODE_PRIVATE)
        prefs.edit().clear().apply()
    }

    @Test
    fun enqueueThenDrainPreservesOrderAndEmptiesQueue() {
        val a = JSONObject().put("from", "a")
        val b = JSONObject().put("from", "b")
        OutboxQueue.enqueue(prefs, "111111", "sms", a)
        OutboxQueue.enqueue(prefs, "111111", "call", b)

        val drained = OutboxQueue.drain(prefs, "111111")
        assertEquals(2, drained.size)
        assertEquals("sms", drained[0].first)
        assertEquals("a", drained[0].second.getString("from"))
        assertEquals("call", drained[1].first)
        assertEquals("b", drained[1].second.getString("from"))
        assertTrue(OutboxQueue.drain(prefs, "111111").isEmpty())
    }

    @Test
    fun drainOnEmptyQueueReturnsEmptyList() {
        assertTrue(OutboxQueue.drain(prefs, "111111").isEmpty())
    }

    @Test
    fun capacityDropsOldestEntries() {
        repeat(105) { i ->
            OutboxQueue.enqueue(prefs, "111111", "sms", JSONObject().put("i", i))
        }
        val drained = OutboxQueue.drain(prefs, "111111")
        assertEquals(100, drained.size)
        assertEquals(5, drained.first().second.getInt("i"))
        assertEquals(104, drained.last().second.getInt("i"))
    }

    @Test
    fun corruptRawDataYieldsEmptyQueue() {
        prefs.edit().putString("outbox_111111", "not-json{").apply()
        assertEquals(0, OutboxQueue.load(prefs, "111111").length())
    }

    @Test
    fun drainToleratesMalformedEntries() {
        val arr = JSONArray()
        arr.put(JSONObject().put("type", "sms").put("data", JSONObject().put("x", 1)))
        arr.put("garbage")
        arr.put(JSONObject().put("type", "call"))
        prefs.edit().putString("outbox_111111", arr.toString()).apply()

        val drained = OutboxQueue.drain(prefs, "111111")
        assertEquals(2, drained.size)
        assertEquals("sms", drained[0].first)
        assertEquals("call", drained[1].first)
        assertTrue(drained[1].second.length() == 0)
    }

    @Test
    fun queuesAreIsolatedPerPairingOnDevice() {
        OutboxQueue.enqueue(prefs, "111111", "sms", JSONObject().put("from", "a"))
        OutboxQueue.enqueue(prefs, "222222", "call", JSONObject().put("from", "b"))

        val first = OutboxQueue.drain(prefs, "111111")
        val second = OutboxQueue.drain(prefs, "222222")
        assertEquals(1, first.size)
        assertEquals("sms", first[0].first)
        assertEquals(1, second.size)
        assertEquals("call", second[0].first)
        assertTrue(OutboxQueue.drain(prefs, "111111").isEmpty())
        assertTrue(OutboxQueue.drain(prefs, "222222").isEmpty())
    }
}
