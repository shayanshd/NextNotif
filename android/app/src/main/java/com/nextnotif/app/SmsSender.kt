package com.nextnotif.app

import android.Manifest
import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.telephony.SmsManager
import android.telephony.SubscriptionManager
import androidx.core.content.ContextCompat
import org.json.JSONObject
import org.json.JSONArray

internal object SmsSender {
    fun enabled(context: Context): Boolean = context.getSharedPreferences("nextnotif_sms_preferences", Context.MODE_PRIVATE).getBoolean("enabled", false)
    fun setEnabled(context: Context, enabled: Boolean) {
        check(context.getSharedPreferences("nextnotif_sms_preferences", Context.MODE_PRIVATE).edit().putBoolean("enabled", enabled).commit())
    }
    fun permitted(context: Context): Boolean = ContextCompat.checkSelfPermission(context, Manifest.permission.SEND_SMS) == PackageManager.PERMISSION_GRANTED

    fun simOptions(context: Context): JSONObject {
        val result = JSONObject().put("sims", JSONArray()).put("default_id", JSONObject.NULL)
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED)
            return result.put("state", "permission_required")
        return try {
            val active = context.getSystemService(SubscriptionManager::class.java).activeSubscriptionInfoList.orEmpty()
                .filter { it.simSlotIndex >= 0 }.sortedBy { it.simSlotIndex }
            val sims = JSONArray()
            active.forEach { sim -> sims.put(JSONObject().put("id", sim.subscriptionId).put("slot", sim.simSlotIndex)
                .put("name", sim.displayName?.toString().orEmpty().take(80))
                .put("carrier", sim.carrierName?.toString().orEmpty().take(80))) }
            val preferred = SubscriptionManager.getDefaultSmsSubscriptionId()
            result.put("state", "ready").put("sims", sims)
                .put("default_id", preferred.takeIf { id -> active.any { it.subscriptionId == id } } ?: JSONObject.NULL)
        } catch (_: SecurityException) { result.put("state", "permission_required") }
        catch (_: Exception) { result.put("state", "unavailable") }
    }

    @Synchronized fun process(context: Context, pairing: PairingInfo, command: JSONObject): JSONObject {
        val store = SmsOutbox.store(context)
        val id = command.optString("id")
        store.get(id)?.let { old ->
            check(old.optString("code") == pairing.code && old.optString("authority") == pairing.server &&
                old.optString("to") == command.optString("to") && old.optString("body") == command.optString("body") &&
                smsSubscription(old) == smsSubscription(command))
            if (old.optString("status") == "sending" && System.currentTimeMillis() - old.optLong("attempted_at") > 120_000) {
                val latest = store.mutate(id) {
                    if (it.optString("status") == "sending") it.put("status", "unknown")
                        .put("detail", "Carrier result unavailable. Check the sender before sending again.")
                }
                SmsOutbox.refresh(context)
                return requireNotNull(latest)
            }
            return old
        }
        require(pairing.role == Role.SENDER && pairing.enabled)
        require(validSmsRequest(command)) { "Invalid or expired SMS request" }
        val record = JSONObject(command.toString()).put("code", pairing.code).put("authority", pairing.server).put("local_role", Role.SENDER.name)
        fun fail(detail: String): JSONObject {
            record.put("status", "failed").put("detail", detail)
            SmsOutbox.update(context, record)
            return record
        }
        if (!enabled(context) || !permitted(context)) return fail("Enable SMS replies in NextNotif on the sender phone and allow SMS permission.")
        if (!context.packageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY)) return fail("Sender has no cellular SMS capability.")
        val active = try {
            context.getSystemService(SubscriptionManager::class.java).activeSubscriptionInfoList.orEmpty().map { it.subscriptionId }
        } catch (_: SecurityException) { return fail("Allow phone permission on the sender to select its SMS SIM.") }
        val preferred = SubscriptionManager.getDefaultSmsSubscriptionId()
        val requested = smsSubscription(command)
        val subscription = selectSmsSubscription(requested, active, preferred)
            ?: return fail(if (requested != null) "Selected SIM is no longer available on the sender. Refresh SIM options and choose again."
                else "Choose a SIM on the receiver or a default SMS SIM in the sender phone settings.")
        val manager = SmsManager.getSmsManagerForSubscriptionId(subscription)
        val parts = try { manager.divideMessage(record.getString("body")) }
            catch (_: Exception) { return fail("Sender could not prepare this SMS message.") }
        record.put("parts", parts.size)
        if (!store.claim(record)) return requireNotNull(store.get(id))
        SmsOutbox.refresh(context)
        val sentIntents = ArrayList(parts.indices.map { index ->
            val intent = Intent(context, SmsSentReceiver::class.java).apply {
                data = Uri.parse("nextnotif://sms-result/$id/$index")
                putExtra("id", id); putExtra("part", index); putExtra("count", parts.size)
            }
            PendingIntent.getBroadcast(context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        })
        try {
            if (parts.size == 1) manager.sendTextMessage(record.getString("to"), null, parts.single(), sentIntents.single(), null)
            else manager.sendMultipartTextMessage(record.getString("to"), null, parts, sentIntents, null)
        } catch (_: SecurityException) {
            return fail("SMS permission was denied on the sender phone.")
        } catch (_: Exception) {
            // A binder/service error may occur after modem submission. Never retry it automatically.
            record.put("status", "unknown").put("detail", "Send result unavailable. Check the sender before sending again.")
            SmsOutbox.update(context, record)
        }
        return requireNotNull(store.get(id))
    }
}

class SmsSentReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getStringExtra("id") ?: return
        val result = SmsOutbox.store(context).partResult(id, intent.getIntExtra("part", -1),
            intent.getIntExtra("count", 0), resultCode == Activity.RESULT_OK) ?: return
        SmsOutbox.refresh(context)
        SmsRelay.enqueue(context, result.optString("code"))
    }
}
