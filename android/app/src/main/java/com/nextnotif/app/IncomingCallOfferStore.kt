package com.nextnotif.app

import android.content.Context
import org.json.JSONObject

internal data class IncomingCallOffer(val code: String, val number: String?, val name: String?, val offeredAt: Long)

/** Notification dismissal must not discard the still-ringing call's navigation. */
internal object IncomingCallOfferStore {
    private const val PREFS = "nextnotif_incoming_call_offer"
    private const val KEY = "offer"

    internal fun next(current: IncomingCallOffer?, code: String, state: String,
                      available: Boolean, timestamp: Long?, number: String?, name: String?, now: Long): IncomingCallOffer? {
        if (state == "RINGING" && available && CallEventFreshness.permitsInteraction(timestamp, now)) {
            if (current != null && timestamp!! < current.offeredAt) return current
            return IncomingCallOffer(code, number, name, requireNotNull(timestamp))
        }
        if (state in setOf("OFFHOOK", "IDLE") && current?.code == code &&
            timestamp != null && timestamp >= current.offeredAt) return null
        return current
    }

    private fun read(context: Context): IncomingCallOffer? = runCatching {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null) ?: return null
        val json = JSONObject(raw)
        IncomingCallOffer(json.getString("code"), json.optString("number").ifBlank { null },
            json.optString("name").ifBlank { null }, json.getLong("ts"))
    }.getOrNull()

    @Synchronized
    fun observe(context: Context, code: String, state: String, available: Boolean,
                timestamp: Long?, number: String?, name: String?) {
        val offer = next(read(context), code, state, available, timestamp, number, name, System.currentTimeMillis())
        val editor = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
        if (offer == null) editor.remove(KEY) else editor.putString(KEY, JSONObject()
            .put("code", offer.code).put("number", offer.number ?: "").put("name", offer.name ?: "")
            .put("ts", offer.offeredAt).toString())
        editor.commit()
    }

    @Synchronized
    fun clear(context: Context, code: String) {
        if (read(context)?.code == code) context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().remove(KEY).commit()
    }

    @Synchronized
    fun restore(context: Context) {
        val offer = read(context) ?: return
        val session = SessionStore.load(context)
        val eligible = session.relayEnabled && session.pairings.any {
            it.code == offer.code && it.enabled && it.role == Role.RECEIVER && (it.isWs || it.isFcmOnDemand)
        }
        if (!eligible || !CallEventFreshness.permitsInteraction(offer.offeredAt, System.currentTimeMillis())) {
            clear(context, offer.code)
            return
        }
        val call = AppState.callRelay.value
        if (call.phase == AppState.CallPhase.IDLE && call.code == null) {
            AppState.setIncomingCall(offer.code, offer.number, offer.name, offer.offeredAt)
        }
    }
}
