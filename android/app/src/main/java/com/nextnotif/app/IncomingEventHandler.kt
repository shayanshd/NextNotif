package com.nextnotif.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Shared notification path for WebSocket, Firebase RTDB, and FCM delivery. */
object IncomingEventHandler {
    private const val PREFS = "nextnotif_seen_events"
    private const val KEY = "ids"
    private const val MAX_SEEN = 128

    /** Returns false when an FCM event was already rendered from queue replay. */
    @Synchronized
    fun handle(
        context: Context,
        type: String,
        data: JSONObject,
        code: String?,
        eventId: String? = null,
    ): Boolean {
        AppState.initializeMessages(context)
        if (AppState.hasStoredEvent(code, eventId)) return false
        val durableCommunication = type == "sms" || type == "call" || type == RelaySelfTest.EVENT_TYPE
        if (!durableCommunication && !rememberIfNew(context, eventId)) return false
        val number = when (type) {
            "sms" -> data.optString("from")
            "call" -> data.optString("number")
            else -> ""
        }
        val localName = if (number.isNotBlank() && number != "unknown") {
            runCatching { Contacts.lookupName(context, number) }.getOrNull()
        } else null
        val remoteName = if (data.has("name")) {
            data.optString("name").takeIf { it.isNotBlank() }
        } else null
        val name = localName ?: remoteName
        if (name != null) data.put("name", name)
        if (type == "call" && code != null) {
            IncomingCallOfferStore.observe(context, code, data.optString("state"), interactiveLiveCallAvailable(data),
                callOfferTimestamp(data), number.takeIf { it.isNotBlank() }, name)
            AppState.observeIncomingCall(code, data.optString("state"), interactiveLiveCallAvailable(data),
                number.takeIf { it.isNotBlank() }, name, callOfferTimestamp(data))
        }
        if (type == RelaySelfTest.EVENT_TYPE) {
            AppState.push("TEST", RelaySelfTest.receivedMessage(data), code, eventId)
        } else {
            AppState.pushIncoming(type, data, code, eventId)
        }
        when (type) {
            "sms" -> IncomingNotifier.notifySms(context, number, data.optString("body"), name)
            "call" -> IncomingNotifier.notifyCall(
                context,
                number,
                data.optString("state"),
                name,
                code,
                liveCallAvailable = interactiveLiveCallAvailable(data),
                offeredAt = callOfferTimestamp(data),
            )
            RelaySelfTest.EVENT_TYPE -> IncomingNotifier.notifyRelayTest(context, code)
        }
        return true
    }

    /**
     * Live audio relay is an optional, privileged capability. Treat legacy or
     * malformed payloads as informational call events only.
     */
    internal fun isLiveCallAvailable(data: JSONObject): Boolean =
        data.opt("live_call_available") == true

    internal fun interactiveLiveCallAvailable(data: JSONObject, now: Long = System.currentTimeMillis()): Boolean {
        return isLiveCallAvailable(data) && CallEventFreshness.permitsInteraction(callOfferTimestamp(data), now)
    }

    internal fun callOfferTimestamp(data: JSONObject): Long? = when (val raw = data.opt("ts")) {
            is Long -> raw
            is Int -> raw.toLong()
            else -> null
        }

    private fun rememberIfNew(context: Context, eventId: String?): Boolean {
        val id = eventId?.takeIf { it.isNotBlank() } ?: return true
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val old = runCatching { JSONArray(prefs.getString(KEY, "[]")) }.getOrElse { JSONArray() }
        val ids = (0 until old.length()).mapNotNull { old.optString(it).takeIf(String::isNotBlank) }
        if (id in ids) return false
        val updated = (ids + id).takeLast(MAX_SEEN)
        prefs.edit().putString(KEY, JSONArray(updated).toString()).apply()
        return true
    }
}
