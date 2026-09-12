package com.nextnotif.app

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/** Durable, bounded history of communication a person would expect to revisit. */
class MessageStore(
    private val prefs: SharedPreferences,
    private val maxEntries: Int = DEFAULT_MAX_ENTRIES,
) {
    init {
        require(maxEntries > 0) { "maxEntries must be positive" }
    }

    @Synchronized
    fun load(): List<AppState.Entry> {
        val raw = prefs.getString(KEY_ENTRIES, null) ?: return emptyList()
        val array = runCatching { JSONArray(raw) }.getOrNull() ?: return emptyList()
        return buildList {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                decode(item)?.let(::add)
                if (size == maxEntries) break
            }
        }
    }

    /** Returns the current persisted history, newest first. */
    @Synchronized
    fun append(entry: AppState.Entry): List<AppState.Entry> {
        if (!isHumanCommunication(entry)) return load()
        if (entry.eventId != null && hasEvent(entry.code, entry.eventId)) return load()
        val updated = (listOf(entry) + load()).take(maxEntries)
        persist(updated)
        return updated
    }

    @Synchronized
    fun clear() {
        check(prefs.edit().remove(KEY_ENTRIES).commit()) { "Unable to clear message history" }
    }

    @Synchronized
    fun hasEvent(code: String?, eventId: String): Boolean {
        if (eventKey(code, eventId) !in seenEvents()) return false
        // A failed commit can still update SharedPreferences' memory cache.
        // Reflush before treating a replay as safely stored and acknowledging it.
        check(prefs.edit().putLong("durability_flush", System.nanoTime()).commit()) {
            "Unable to verify persisted message history"
        }
        return true
    }

    private fun eventKey(code: String?, eventId: String): String = "${code.orEmpty()}:$eventId"

    private fun seenEvents(): List<String> {
        val array = runCatching { JSONArray(prefs.getString(KEY_SEEN, "[]")) }.getOrElse { JSONArray() }
        return (0 until array.length()).mapNotNull { array.optString(it).takeIf(String::isNotBlank) }
    }

    private fun persist(entries: List<AppState.Entry>) {
        val array = JSONArray()
        entries.forEach { entry ->
            array.put(
                JSONObject().apply {
                    put("ts", entry.ts)
                    put("tag", entry.tag)
                    put("message", entry.message)
                    entry.code?.let { put("code", it) }
                    entry.eventId?.let { put("event_id", it) }
                    entry.communication?.let { details ->
                        put(
                            "communication",
                            JSONObject().apply {
                                put("kind", details.kind.name)
                                put("direction", details.direction.name)
                                details.address?.let { put("address", it) }
                                details.name?.let { put("name", it) }
                                details.body?.let { put("body", it) }
                                details.callState?.let { put("call_state", it) }
                            },
                        )
                    }
                }
            )
        }
        val seen = (seenEvents() + entries.mapNotNull { entry ->
            entry.eventId?.let { eventKey(entry.code, it) }
        }).distinct().takeLast(1000)
        // History and replay protection must succeed in the same durable write
        // before the receiver may acknowledge this event to the relay.
        check(prefs.edit().putString(KEY_ENTRIES, array.toString())
            .putString(KEY_SEEN, JSONArray(seen).toString()).commit()) {
            "Unable to persist message history"
        }
    }

    private fun decode(item: JSONObject): AppState.Entry? {
        val ts = item.optLong("ts", -1L)
        val tag = item.optString("tag")
        val message = item.optString("message")
        if (ts < 0L || tag.isBlank() || message.isBlank()) return null
        return AppState.Entry(
            ts = ts,
            tag = tag,
            message = message,
            code = item.optString("code").ifBlank { null },
            communication = decodeCommunication(item.optJSONObject("communication")),
            eventId = item.optString("event_id").ifBlank { null },
        ).takeIf(::isHumanCommunication)
    }

    private fun decodeCommunication(item: JSONObject?): AppState.CommunicationDetails? {
        item ?: return null
        val kind = runCatching {
            AppState.CommunicationKind.valueOf(item.optString("kind"))
        }.getOrNull() ?: return null
        val direction = runCatching {
            AppState.CommunicationDirection.valueOf(item.optString("direction"))
        }.getOrNull() ?: return null
        return AppState.CommunicationDetails(
            kind = kind,
            direction = direction,
            address = item.optString("address").ifBlank { null },
            name = item.optString("name").ifBlank { null },
            body = item.optString("body").takeIf { item.has("body") },
            callState = item.optString("call_state").ifBlank { null },
        )
    }

    companion object {
        private const val PREFS = "nextnotif_message_history"
        private const val KEY_ENTRIES = "entries"
        private const val KEY_SEEN = "seen_events"
        const val DEFAULT_MAX_ENTRIES = 200

        fun from(context: Context): MessageStore = MessageStore(
            context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        )

        fun isHumanCommunication(entry: AppState.Entry): Boolean = when (entry.tag) {
            "IN" -> entry.message.startsWith("SMS from ") || entry.message.startsWith("Call ")
            "OUT" -> entry.message.startsWith("SMS → ") || entry.message.startsWith("Call ")
            "TEST" -> true
            else -> false
        }

        fun filterByPairing(entries: List<AppState.Entry>, code: String?): List<AppState.Entry> =
            if (code == null) entries else entries.filter { it.code == code }
    }
}
