package com.nextnotif.app

import android.content.Context
import android.content.SharedPreferences
import android.app.Activity
import android.telephony.SmsManager
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
internal fun smsCarrierFailure(resultCode: Int, radioErrorCode: Int): String = when (resultCode) {
    SmsManager.RESULT_ERROR_NO_SERVICE -> "No cellular service on the selected SIM."
    SmsManager.RESULT_ERROR_RADIO_OFF -> "The sender's cellular radio is off."
    SmsManager.RESULT_ERROR_NULL_PDU -> "The carrier rejected the message format."
    SmsManager.RESULT_ERROR_LIMIT_EXCEEDED -> "The sender's SMS sending limit was reached."
    SmsManager.RESULT_ERROR_FDN_CHECK_FAILURE -> "The selected SIM's fixed dialing restriction blocked this number."
    SmsManager.RESULT_ERROR_SHORT_CODE_NOT_ALLOWED,
    SmsManager.RESULT_ERROR_SHORT_CODE_NEVER_ALLOWED -> "The sender blocked this short code."
    SmsManager.RESULT_ERROR_GENERIC_FAILURE -> if (radioErrorCode > 0)
        "The cellular modem reported error $radioErrorCode. Check the sender's carrier/SIM."
        else "The cellular modem could not send the message."
    else -> "Android reported SMS send error $resultCode."
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
        val records = (0 until array.length()).map { array.getJSONObject(it) }
        val cutoff = System.currentTimeMillis() - CONTENT_RETENTION_MS
        val changed = records.map { record ->
            if (canScrub(record) && (record.optBoolean("hidden") || record.optLong("created_at") < cutoff))
                scrubContent(record) else false
        }.any { it }
        if (changed) persist(records)
        return records
    }
    @Synchronized fun mutate(id: String, change: (JSONObject) -> Unit): JSONObject? {
        val record = get(id) ?: return null
        change(record)
        put(record)
        return record
    }
    @Synchronized fun get(id: String): JSONObject? = all().firstOrNull { it.optString("id") == id }
    @Synchronized fun put(record: JSONObject) {
        if (canScrub(record) && (record.optBoolean("hidden") ||
                record.optLong("created_at") < System.currentTimeMillis() - CONTENT_RETENTION_MS)) {
            scrubContent(record)
        }
        val records = all().filterNot { it.optString("id") == record.optString("id") }.toMutableList()
        records.add(JSONObject(record.toString()))
        // Keep recent deduplication claims beyond the server's one-hour request lifetime.
        // Older history is bounded, while active/uncertain attempts are never discarded.
        val cutoff = System.currentTimeMillis() - 172_800_000L
        val removable = records.filter { it.optLong("created_at") < cutoff && it.optString("status") in SMS_FINAL_STATES }
            .sortedBy { it.optLong("created_at") }.take((records.size - 200).coerceAtLeast(0)).toSet()
        records.removeAll(removable)
        persist(records)
    }
    /** Persist the claim BEFORE touching the modem. A crash leaves an uncertain result, never a retry. */
    @Synchronized fun claim(record: JSONObject): Boolean {
        if (get(record.getString("id")) != null) return false
        put(JSONObject(record.toString()).put("status", "sending").put("attempted_at", System.currentTimeMillis()))
        return true
    }
    @Synchronized fun hideHistory() {
        val records = all()
        records.forEach {
            it.put("hidden", true)
            if (canScrub(it)) scrubContent(it)
        }
        persist(records)
    }
    private fun canScrub(record: JSONObject): Boolean =
        record.optString("status") in SMS_FINAL_STATES || record.optString("status") == "unknown"

    private fun scrubContent(record: JSONObject): Boolean {
        val changed = record.has("to") || record.has("body")
        record.remove("to")
        record.remove("body")
        return changed
    }

    private fun persist(records: List<JSONObject>) {
        check(prefs.edit().putString("records", JSONArray(records).toString()).commit()) { "Could not save SMS request" }
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
    @Synchronized fun partResult(id: String, part: Int, count: Int, resultCode: Int, radioErrorCode: Int = 0): JSONObject? {
        val record = get(id) ?: return null
        if (part !in 0 until count || count != record.optInt("parts")) return null
        val results = record.optJSONObject("part_results") ?: JSONObject()
        if (results.has(part.toString())) return record
        val success = resultCode == Activity.RESULT_OK
        results.put(part.toString(), success)
        record.put("part_results", results)
        if (!success) {
            val errors = record.optJSONObject("part_errors") ?: JSONObject()
            errors.put(part.toString(), JSONObject().put("result_code", resultCode)
                .put("radio_error_code", radioErrorCode.coerceAtLeast(0)))
            record.put("part_errors", errors)
        }
        if (results.length() == count) {
            val accepted = (0 until count).count { results.optBoolean(it.toString()) }
            val firstFailure = (0 until count).firstOrNull { !results.optBoolean(it.toString()) }
            val reason = firstFailure?.let { index ->
                val error = record.getJSONObject("part_errors").getJSONObject(index.toString())
                smsCarrierFailure(error.getInt("result_code"), error.optInt("radio_error_code"))
            }
            record.put("status", if (accepted == count) "sent" else "failed")
                .put("detail", when {
                    accepted == count -> ""
                    accepted > 0 -> "$accepted of $count parts reached the carrier. The message may arrive incomplete. $reason"
                    else -> "No parts reached the carrier. $reason"
                })
        }
        put(record)
        return record
    }
}

private const val CONTENT_RETENTION_MS = 30L * 24 * 60 * 60 * 1000

internal object SmsOutbox {
    private val mutable = MutableStateFlow<List<JSONObject>>(emptyList())
    val records = mutable.asStateFlow()
    private var instance: SmsSendStore? = null
    @Synchronized fun store(context: Context): SmsSendStore = instance ?: SmsSendStore(
        ProtectedPreferences.from(context, "nextnotif_sms_send")
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
