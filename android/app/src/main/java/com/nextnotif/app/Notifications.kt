package com.nextnotif.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.media.AudioAttributes
import android.os.Build
import android.provider.Settings

object Notifications {
    const val CHANNEL_RELAY = "relay"
    const val CHANNEL_INCOMING = "incoming"
    // A new channel ID is intentional: Android freezes a channel's sound after
    // it is first created, so existing installs need a fresh channel to gain a
    // real ringtone instead of retaining the old one-shot notification sound.
    const val CHANNEL_CALLS = "forwarded_calls_v2"
    const val NOTIF_FOREGROUND = 1

    fun ensureChannels(ctx: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val ringtoneAttributes = AudioAttributes.Builder()
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
            .build()
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_CALLS,
                ctx.getString(R.string.notif_channel_calls),
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = ctx.getString(R.string.notif_channel_calls_description)
                setSound(Settings.System.DEFAULT_RINGTONE_URI, ringtoneAttributes)
                enableVibration(true)
                vibrationPattern = longArrayOf(0L, 700L, 500L, 700L)
            },
        )
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_RELAY,
                ctx.getString(R.string.notif_channel_relay),
                NotificationManager.IMPORTANCE_LOW,
            )
        )
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_INCOMING,
                ctx.getString(R.string.notif_channel_incoming),
                NotificationManager.IMPORTANCE_HIGH,
            )
        )
    }
}
