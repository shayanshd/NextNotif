package com.nextnotif.app

import android.content.Context
import android.util.Log
import com.google.firebase.FirebaseApp
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kotlinx.coroutines.flow.MutableStateFlow

/** The push only requests catch-up. Event content stays in the relay queue. */
internal object RelayWake {
    val token = MutableStateFlow<String?>(null)

    fun requested(ctx: Context): Boolean =
        ctx.getSharedPreferences("relay_wake", Context.MODE_PRIVATE)
            .getBoolean("requested", true) // Preserve pre-upgrade auto-restart.

    fun setRequested(ctx: Context, requested: Boolean) {
        ctx.getSharedPreferences("relay_wake", Context.MODE_PRIVATE)
            .edit().putBoolean("requested", requested).apply()
    }

    fun receiver(data: Map<String, String>, session: SessionState, requested: Boolean): PairingInfo? {
        if (!requested || data["nn"] != "1" || data["action"] != "wake") return null
        return session.pairings.firstOrNull {
            it.code == data["code"] && it.enabled && it.role == Role.RECEIVER && it.isWs
        }
    }

    fun refreshToken(ctx: Context) {
        // FCM supports the default Android app, not the named per-pairing web
        // apps used by FirebaseRelay. Unconfigured builds remain usable.
        if (FirebaseApp.getApps(ctx).none { it.name == FirebaseApp.DEFAULT_APP_NAME }) return
        runCatching {
            FirebaseMessaging.getInstance().token
                .addOnSuccessListener {
                    token.value = it
                    Log.i("RelayWake", "Push token available")
                }
                .addOnFailureListener { Log.w("RelayWake", "Push registration unavailable", it) }
        }.onFailure { Log.w("RelayWake", "Push registration unavailable", it) }
    }
}

class RelayMessagingService : FirebaseMessagingService() {
    override fun onNewToken(token: String) {
        RelayWake.token.value = token
    }

    override fun onMessageReceived(message: RemoteMessage) {
        val pairing = RelayWake.receiver(message.data, SessionStore.load(this), RelayWake.requested(this))
            ?: return
        Log.i("RelayWake", "Wake received for an active receiver pairing")
        Notifications.ensureChannels(this)
        IncomingNotifier.notifyWake(this, pairing)
        // FCM can downgrade messages. Keep the notification as the tap-to-open
        // recovery path when background foreground-service starts aren't allowed.
        if (message.priority != RemoteMessage.PRIORITY_HIGH) return
        runCatching { RelayForegroundService.Controller.start(this) }
            .onFailure { Log.w("RelayWake", "Open NextNotif to retrieve queued activity", it) }
    }
}
