package com.nextnotif.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build

object Notifications {
    const val CHANNEL_RELAY = "relay"
    const val CHANNEL_INCOMING = "incoming"
    const val CHANNEL_CALLS = "live_calls"
    const val NOTIF_FOREGROUND = 1

    fun ensureChannels(ctx: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(NotificationChannel(
            CHANNEL_CALLS, ctx.getString(R.string.notif_channel_calls), NotificationManager.IMPORTANCE_HIGH,
        ))
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
