package com.nextnotif.app

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

object OutboxQueue {
    private const val PREFS_NAME = "nextnotif_outbox"
    private const val CAPACITY = 100

    // One queue per pairing: an offline event must be replayed to EVERY
    // sender pairing, and a queue shared between pairings would deliver one
    // drained event to only the first pairing that answers.
    private fun key(code: String) = "outbox_$code"

    internal fun load(prefs: SharedPreferences, code: String): JSONArray {
        val raw = prefs.getString(key(code), null)
        return runCatching { raw?.let { JSONArray(it) } }.getOrNull() ?: JSONArray()
    }

    internal fun save(prefs: SharedPreferences, code: String, entries: JSONArray) {
        prefs.edit()
            .putString(key(code), entries.toString())
            .apply()
    }

    internal fun enqueue(prefs: SharedPreferences, code: String, type: String, data: JSONObject) {
        val entries = load(prefs, code)
        entries.put(JSONObject().apply {
            put("type", type)
            put("data", data)
        })
        while (entries.length() > CAPACITY) {
            entries.remove(0)
        }
        save(prefs, code, entries)
    }

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

    fun drain(ctx: Context, code: String): List<Pair<String, JSONObject>> {
        return drain(ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE), code)
    }
}
