package com.nextnotif.app

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.os.Build
import android.provider.Settings
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

internal fun shouldRingCallNotification(state: String): Boolean = state == "RINGING"

internal enum class ForwardedNotificationKind(val prefix: Int) {
    SMS(0x10000000), CALL(0x20000000), TEST(0x30000000), DELIVERY_GAP(0x40000000),
}

internal fun forwardedNotificationId(kind: ForwardedNotificationKind, identity: String): Int =
    kind.prefix or (identity.hashCode() and 0x0FFFFFFF)

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
        val channel = if (ringing) Notifications.CHANNEL_CALLS else Notifications.CHANNEL_INCOMING
        val lockScreenVersion = NotificationCompat.Builder(ctx, channel)
            .setSmallIcon(R.drawable.ic_stat_relay)
            .setContentTitle(if (ringing) "Incoming forwarded call" else "NextNotif activity")
            .setContentText("Unlock to view details")
            .setContentIntent(pi)
            .build()
        val builder = NotificationCompat.Builder(ctx, channel)
            .setSmallIcon(R.drawable.ic_stat_relay)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setAutoCancel(!ringing)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(pi)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(lockScreenVersion)
        if (ringing) {
            builder.setCategory(NotificationCompat.CATEGORY_CALL)
                .setOngoing(true)
                .setTimeoutAfter(90_000L)
                .setVibrate(longArrayOf(0L, 700L, 500L, 700L))
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
                builder.setSound(Settings.System.DEFAULT_RINGTONE_URI, AudioManager.STREAM_RING)
            }
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
        if (!ringing) {
            // Replacing an insistent call notification stops its ringtone as
            // soon as the sender reports OFFHOOK or IDLE.
            nm.cancel(id)
        }
        val notification = builder.build().apply {
            if (ringing) flags = flags or Notification.FLAG_INSISTENT
        }
        nm.notify(id, notification)
    }

    fun notifySms(ctx: Context, from: String, body: String, name: String? = null,
        code: String? = null, eventId: String? = null) {
        val title = if (name != null) "SMS from $name ($from)" else "SMS from $from"
        notify(ctx, title, body, forwardedNotificationId(ForwardedNotificationKind.SMS,
            "${code.orEmpty()}:${eventId ?: from}"))
    }

    fun notifyRelayTest(ctx: Context, code: String?) {
        notify(
            ctx,
            "NextNotif relay test",
            RelaySelfTest.DEFAULT_MESSAGE,
            forwardedNotificationId(ForwardedNotificationKind.TEST, code ?: "relay-test"),
        )
    }

    fun notifyQueueOverflow(ctx: Context, code: String, count: Int) {
        if (count <= 0) return
        notify(ctx, "NextNotif delivery gap",
            "$count older forwarded event(s) were lost because the relay queue filled. Check the other phone's connection.",
            forwardedNotificationId(ForwardedNotificationKind.DELIVERY_GAP, code))
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
            forwardedNotificationId(ForwardedNotificationKind.CALL, code ?: number),
            actionLabel,
            action,
            actionOpensActivity = actionKind == CallNotificationAction.ANSWER,
            callScreenIntent = if (actionKind != null) RelayCallActivity.createIntent(
                ctx, requireNotNull(code), number, name, answer = false, offeredAt = offeredAt,
            ) else null,
            // Ring for every forwarded incoming call. Whether this device can
            // answer the call is a separate capability represented by actionKind.
            ringing = shouldRingCallNotification(state),
        )
    }
}
