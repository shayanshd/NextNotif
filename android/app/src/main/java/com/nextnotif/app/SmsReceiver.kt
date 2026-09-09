package com.nextnotif.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import android.util.Log

class SmsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return
        val session = SessionStore.load(context)
        if (session.pairings.none { it.role == Role.SENDER }) return

        val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent) ?: return
        val sender = messages.firstOrNull()?.displayOriginatingAddress ?: "unknown"
        val body = messages.joinToString("") { it.displayMessageBody ?: "" }
        val ts = messages.firstOrNull()?.timestampMillis ?: System.currentTimeMillis()

        Log.i("SmsReceiver", "Forwarding SMS from $sender")
        PendingForwards.addSms(context, sender, body, ts)
    }
}
