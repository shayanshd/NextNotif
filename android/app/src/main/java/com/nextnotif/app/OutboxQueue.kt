package com.nextnotif.app

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

object OutboxQueue {
    private const val PREFS_NAME = "nextnotif_outbox"
    private const val CAPACITY = 100
    private const val ID = "id"

    internal data class Entry(
        val id: String,
        val type: String,
        val data: JSONObject,
    )

    // One queue per pairing: an offline event must be replayed to EVERY
    // sender pairing, and a queue shared between pairings would deliver one
    // drained event to only the first pairing that answers.
    private fun key(code: String) = "outbox_$code"

    @Synchronized
    internal fun load(prefs: SharedPreferences, code: String): JSONArray {
        val raw = prefs.getString(key(code), null)
        return runCatching { raw?.let { JSONArray(it) } }.getOrNull() ?: JSONArray()
    }

    @Synchronized
    internal fun save(prefs: SharedPreferences, code: String, entries: JSONArray) {
        prefs.edit()
            .putString(key(code), entries.toString())
            .commit()
    }

    @Synchronized
    internal fun enqueue(prefs: SharedPreferences, code: String, type: String, data: JSONObject) {
        val entries = load(prefs, code)
        entries.put(JSONObject().apply {
            put(ID, UUID.randomUUID().toString())
            put("type", type)
            put("data", data)
        })
        while (entries.length() > CAPACITY) {
            entries.remove(0)
        }
        save(prefs, code, entries)
    }

    /**
     * Returns the oldest valid event without removing it. Legacy entries are
     * assigned an id here so a later acknowledgement can remove only the
     * event that was actually sent. Malformed entries keep the historical
     * behavior of being discarded rather than blocking the queue forever.
     */
    @Synchronized
    internal fun peek(prefs: SharedPreferences, code: String): Entry? {
        val stored = load(prefs, code)
        val valid = JSONArray()
        var changed = false
        var first: Entry? = null

        for (i in 0 until stored.length()) {
            val obj = stored.optJSONObject(i)
            val type = obj?.optString("type")?.takeIf { it.isNotBlank() }
            if (obj == null || type == null) {
                changed = true
                continue
            }
            val id = obj.optString(ID).takeIf { it.isNotBlank() }
                ?: UUID.randomUUID().toString().also {
                    obj.put(ID, it)
                    changed = true
                }
            val data = obj.optJSONObject("data") ?: JSONObject()
            valid.put(obj)
            if (first == null) first = Entry(id, type, data)
        }

        if (changed) save(prefs, code, valid)
        return first
    }

    /** Remove an event only after its send operation reports success. */
    @Synchronized
    internal fun acknowledge(prefs: SharedPreferences, code: String, id: String): Boolean {
        val entries = load(prefs, code)
        for (i in 0 until entries.length()) {
            if (entries.optJSONObject(i)?.optString(ID) == id) {
                entries.remove(i)
                save(prefs, code, entries)
                return true
            }
        }
        return false
    }

    /** Compatibility helper for callers that explicitly want destructive draining. */
    @Synchronized
    internal fun drain(prefs: SharedPreferences, code: String): List<Pair<String, JSONObject>> {
        val entries = load(prefs, code)
        val queued = (0 until entries.length()).mapNotNull { i ->
            runCatching {
                val entry = entries.getJSONObject(i)
                entry.getString("type") to (entry.optJSONObject("data") ?: JSONObject())
            }.getOrNull()
        }
        save(prefs, code, JSONArray())
        return queued
    }

    fun enqueue(ctx: Context, code: String, type: String, data: JSONObject) {
        enqueue(ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE), code, type, data)
    }

    internal fun peek(ctx: Context, code: String): Entry? {
        return peek(ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE), code)
    }

    internal fun acknowledge(ctx: Context, code: String, id: String): Boolean {
        return acknowledge(ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE), code, id)
    }

    fun drain(ctx: Context, code: String): List<Pair<String, JSONObject>> {
        return drain(ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE), code)
    }
}
