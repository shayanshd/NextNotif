package com.nextnotif.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (
            action != Intent.ACTION_BOOT_COMPLETED &&
            action != Intent.ACTION_LOCKED_BOOT_COMPLETED &&
            action != Intent.ACTION_MY_PACKAGE_REPLACED
        ) return
        val session = SessionStore.load(context)
        if (!shouldRestoreRelay(session)) return
        Log.i("BootReceiver", "Restoring enabled relay after $action")
        RelayForegroundService.Controller.start(context)
    }
}

internal fun shouldRestoreRelay(session: SessionState): Boolean =
    session.relayEnabled && session.pairings.any { it.enabled }
