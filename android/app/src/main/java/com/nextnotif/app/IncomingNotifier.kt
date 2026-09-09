package com.nextnotif.app

import android.Manifest
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat

object IncomingNotifier {

    private fun notify(ctx: Context, title: String, body: String, id: Int) {
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                ctx, Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) return
        }
        val pi = PendingIntent.getActivity(
            ctx, id,
            Intent(ctx, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val n = NotificationCompat.Builder(ctx, Notifications.CHANNEL_INCOMING)
            .setSmallIcon(R.drawable.ic_stat_relay)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(pi)
            .build()
        nm.notify(id, n)
    }

    fun notifySms(ctx: Context, from: String, body: String, name: String? = null) {
        val title = if (name != null) "SMS from $name ($from)" else "SMS from $from"
        notify(ctx, title, body, 1000 + (from.hashCode() and 0xFF))
    }

    fun notifyCall(ctx: Context, number: String, state: String, name: String? = null) {
        val label = when {
            name != null && number != "unknown" -> "$name ($number)"
            name != null -> name
            number != "unknown" -> number
            else -> null
        }
        val title = when (state) {
            "RINGING" -> label?.let { "Incoming call from $it" } ?: "Incoming call"
            "OFFHOOK" -> label?.let { "Call from $it connected" } ?: "Call connected"
            "IDLE" -> label?.let { "Call from $it ended" } ?: "Call ended"
            else -> label?.let { "Call from $it ($state)" } ?: "Call $state"
        }
        val detail = if (number != "unknown") number else (label ?: "")
        notify(ctx, title, detail, 1100 + (number.hashCode() and 0xFF))
    }
}
