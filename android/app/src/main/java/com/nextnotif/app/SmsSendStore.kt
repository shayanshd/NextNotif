package com.nextnotif.app

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

internal const val SMS_REQUEST_TTL = 3_600_000L
internal val SMS_FINAL_STATES = setOf("sent", "failed", "expired")
internal fun smsDestination(raw: String?): String? = raw?.trim()?.let { value ->
    if (value.any { !it.isDigit() && it !in "+-(). " }) null
    else value.filter { it in '0'..'9' || it == '+' }.takeIf { Regex("\\+?[0-9]{3,20}").matches(it) }
}
internal fun smsSubscription(data: JSONObject): Int? = if (data.isNull("subscription_id")) null else data.getInt("subscription_id")
internal fun validSmsSubscription(data: JSONObject): Boolean = data.isNull("subscription_id") ||
    (data.opt("subscription_id") is Int && data.getInt("subscription_id") >= 0)
internal fun selectSmsSubscription(requested: Int?, active: List<Int>, preferred: Int): Int? = when {
    requested != null -> requested.takeIf { it in active }
    preferred in active -> preferred
    active.size == 1 -> active.single()
    else -> null
}
internal fun validSmsRequest(data: JSONObject, now: Long = System.currentTimeMillis()): Boolean =
    validSmsSubscription(data) && runCatching { UUID.fromString(data.getString("id")) }.isSuccess &&
        smsDestination(data.optString("to")) == data.optString("to") &&
        data.optString("body").isNotBlank() && data.optString("body").length <= 1600 &&
        data.optLong("created_at") > now - SMS_REQUEST_TTL && data.optLong("created_at") <= now + 300_000

/** Separate durable outbox: transport acceptance is never shown as carrier success. */
internal class SmsSendStore(private val prefs: SharedPreferences) {
    @Synchronized fun all(): List<JSONObject> {
        val array = JSONArray(prefs.getString("records", "[]"))
        return (0 until array.length()).map { array.getJSONObject(it) }
    }
    @Synchronized fun mutate(id: String, change: (JSONObject) -> Unit): JSONObject? {
        val record = get(id) ?: return null
        change(record)
        put(record)
        return record
    }
    @Synchronized fun get(id: String): JSONObject? = all().firstOrNull { it.optString("id") == id }
    @Synchronized fun put(record: JSONObject) {
        val records = all().filterNot { it.optString("id") == record.optString("id") }.toMutableList()
        records.add(JSONObject(record.toString()))
        // Keep recent deduplication claims beyond the server's one-hour request lifetime.
        // Older history is bounded, while active/uncertain attempts are never discarded.
        val cutoff = System.currentTimeMillis() - 172_800_000L
        val removable = records.filter { it.optLong("created_at") < cutoff && it.optString("status") in SMS_FINAL_STATES }
            .sortedBy { it.optLong("created_at") }.take((records.size - 200).coerceAtLeast(0)).toSet()
        records.removeAll(removable)
        check(prefs.edit().putString("records", JSONArray(records).toString()).commit()) { "Could not save SMS request" }
    }
    /** Persist the claim BEFORE touching the modem. A crash leaves an uncertain result, never a retry. */
    @Synchronized fun claim(record: JSONObject): Boolean {
        if (get(record.getString("id")) != null) return false
        put(JSONObject(record.toString()).put("status", "sending").put("attempted_at", System.currentTimeMillis()))
        return true
    }
    @Synchronized fun hideHistory() {
        all().forEach { put(it.put("hidden", true)) }
    }
    @Synchronized fun mergeStatus(id: String, remote: JSONObject) {
        mutate(id) { local ->
            val old = local.optString("status")
            val next = remote.optString("status")
            if (old !in setOf("sent", "failed") &&
                !(old in setOf("unknown", "expired") && next !in setOf("sent", "failed"))) {
                local.put("status", next).put("detail", remote.optString("detail"))
            }
        }
    }
    @Synchronized fun partResult(id: String, part: Int, count: Int, success: Boolean): JSONObject? {
        val record = get(id) ?: return null
        if (part !in 0 until count || count != record.optInt("parts")) return null
        val results = record.optJSONObject("part_results") ?: JSONObject()
        if (results.has(part.toString())) return record
        results.put(part.toString(), success)
        record.put("part_results", results)
        if (results.length() == count) {
            val ok = (0 until count).all { results.optBoolean(it.toString()) }
            record.put("status", if (ok) "sent" else "failed")
                .put("detail", if (ok) "" else "SMS could not be sent completely. Some parts may have been sent.")
        }
        put(record)
        return record
    }
}

internal object SmsOutbox {
    private val mutable = MutableStateFlow<List<JSONObject>>(emptyList())
    val records = mutable.asStateFlow()
    private var instance: SmsSendStore? = null
    @Synchronized fun store(context: Context): SmsSendStore = instance ?: SmsSendStore(
        context.applicationContext.getSharedPreferences("nextnotif_sms_send", Context.MODE_PRIVATE)
    ).also { instance = it }
    @Synchronized fun refresh(context: Context) { mutable.value = store(context).all() }
    @Synchronized fun update(context: Context, record: JSONObject) { store(context).put(record); refresh(context) }
    fun submit(context: Context, pairing: PairingInfo, number: String, body: String, subscriptionId: Int? = null): String {
        val data = JSONObject().put("id", UUID.randomUUID().toString()).put("to", number)
            .put("body", body).put("subscription_id", subscriptionId ?: JSONObject.NULL).put("created_at", System.currentTimeMillis()).put("code", pairing.code)
            .put("authority", pairing.server).put("local_role", pairing.role.name)
            .put("status", "pending").put("detail", "Waiting for relay")
        require(pairing.role == Role.RECEIVER && pairing.enabled && !pairing.isFirebase && validSmsRequest(data))
        update(context, data)
        SmsRelay.enqueue(context, pairing.code)
        return data.getString("id")
    }
    fun asEntry(record: JSONObject) = AppState.Entry(
        ts = record.optLong("created_at"), tag = "OUT", message = "SMS → ${record.optString("to")}: ${record.optString("body")}",
        code = record.optString("code"), eventId = "sms-send:${record.optString("id")}",
        communication = AppState.CommunicationDetails(AppState.CommunicationKind.SMS,
            AppState.CommunicationDirection.OUTGOING, address = record.optString("to"), body = record.optString("body"),
            smsStatus = record.optString("status"), smsDetail = record.optString("detail")),
    )
}
