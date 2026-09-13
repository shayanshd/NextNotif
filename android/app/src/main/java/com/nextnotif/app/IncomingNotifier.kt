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

internal enum class CallNotificationAction { ANSWER, HANG_UP }

internal fun callNotificationAction(
    state: String,
    code: String?,
    liveCallAvailable: Boolean,
): CallNotificationAction? = when {
    !liveCallAvailable || code == null -> null
    state == "RINGING" -> CallNotificationAction.ANSWER
    state == "OFFHOOK" -> CallNotificationAction.HANG_UP
    else -> null
}

object IncomingNotifier {

    private fun notify(
        ctx: Context,
        title: String,
        body: String,
        id: Int,
        actionLabel: String? = null,
        actionIntent: Intent? = null,
        actionOpensActivity: Boolean = false,
        callScreenIntent: Intent? = null,
        ringing: Boolean = false,
    ) {
        Notifications.ensureChannels(ctx)
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                ctx, Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) return
        }
        val pi = PendingIntent.getActivity(
            ctx, id,
            callScreenIntent ?: Intent(ctx, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = NotificationCompat.Builder(ctx,
            if (ringing) Notifications.CHANNEL_CALLS else Notifications.CHANNEL_INCOMING)
            .setSmallIcon(R.drawable.ic_stat_relay)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setAutoCancel(!ringing)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(pi)
        if (ringing) {
            builder.setCategory(NotificationCompat.CATEGORY_CALL)
                .setOngoing(true)
                .setTimeoutAfter(90_000L)
            if (Build.VERSION.SDK_INT < 34 || nm.canUseFullScreenIntent()) {
                builder.setFullScreenIntent(pi, true)
            }
        }
        if (actionLabel != null && actionIntent != null) {
            val actionPi = if (actionOpensActivity) {
                PendingIntent.getActivity(
                    ctx,
                    id + 10_000,
                    actionIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )
            } else {
                PendingIntent.getService(
                    ctx,
                    id + 10_000,
                    actionIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )
            }
            builder.addAction(0, actionLabel, actionPi)
        }
        nm.notify(id, builder.build())
    }

    fun notifySms(ctx: Context, from: String, body: String, name: String? = null) {
        val title = if (name != null) "SMS from $name ($from)" else "SMS from $from"
        notify(ctx, title, body, 1000 + (from.hashCode() and 0xFF))
    }

    fun notifyRelayTest(ctx: Context, code: String?) {
        notify(
            ctx,
            "NextNotif relay test",
            RelaySelfTest.DEFAULT_MESSAGE,
            1200 + ((code ?: "relay-test").hashCode() and 0xFF),
        )
    }

    fun notifyCall(
        ctx: Context,
        number: String,
        state: String,
        name: String? = null,
        code: String? = null,
        liveCallAvailable: Boolean = false,
        offeredAt: Long? = null,
    ) {
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
        val actionKind = callNotificationAction(state, code, liveCallAvailable)
        val action = when (actionKind) {
            CallNotificationAction.ANSWER -> RelayCallActivity.createIntent(
                ctx,
                requireNotNull(code),
                number,
                name,
                answer = true,
                offeredAt = offeredAt,
            )
            CallNotificationAction.HANG_UP -> Intent(ctx, RelayForegroundService::class.java)
                .setAction(RelayForegroundService.ACTION_END_RELAY_CALL)
                .putExtra("code", requireNotNull(code))
            null -> null
        }
        val actionLabel = when (actionKind) {
            CallNotificationAction.ANSWER -> "Answer here"
            CallNotificationAction.HANG_UP -> "Hang up"
            null -> null
        }
        notify(
            ctx,
            title,
            detail,
            // Caller metadata can change or disappear on OFFHOOK/IDLE. Replace
            // the same pairing's ringing notification rather than leaving it behind.
            1100 + ((code ?: number).hashCode() and 0xFFFF),
            actionLabel,
            action,
            actionOpensActivity = actionKind == CallNotificationAction.ANSWER,
            callScreenIntent = if (actionKind != null) RelayCallActivity.createIntent(
                ctx, requireNotNull(code), number, name, answer = false, offeredAt = offeredAt,
            ) else null,
            ringing = actionKind == CallNotificationAction.ANSWER,
        )
    }
}
