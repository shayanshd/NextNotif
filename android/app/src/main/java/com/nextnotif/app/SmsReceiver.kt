package com.nextnotif.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import android.util.Log

internal const val STAGING_MULTIPART_PREFIX = "NN-MULTIPART-20261004-1046B18B93932FDE-"
internal const val STAGING_MULTIPART_LENGTH = 700

internal fun allowedStagingInboundBody(body: String): Boolean =
    body == "NN-STAGING-INBOUND-20261003" ||
        (body.length == STAGING_MULTIPART_LENGTH && body.startsWith(STAGING_MULTIPART_PREFIX) &&
            body.drop(STAGING_MULTIPART_PREFIX.length).all { it in '0'..'9' || it in 'A'..'F' })

class SmsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return
        val session = SessionStore.load(context)
        if (!session.relayEnabled || session.pairings.none { it.enabled && it.role == Role.SENDER }) return

        val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent) ?: return
        val sender = messages.firstOrNull()?.displayOriginatingAddress ?: "unknown"
        val body = messages.joinToString("") { it.displayMessageBody ?: "" }
        if (BuildConfig.BUILD_TYPE == "stagingInbound" && !allowedStagingInboundBody(body)) return
        val ts = messages.firstOrNull()?.timestampMillis ?: System.currentTimeMillis()

        Log.i("SmsReceiver", "Forwarding SMS event")
        PendingForwards.addSms(context, sender, body, ts)
    }
}
