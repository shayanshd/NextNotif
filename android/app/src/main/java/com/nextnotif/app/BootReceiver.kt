package com.nextnotif.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action != Intent.ACTION_BOOT_COMPLETED && action != Intent.ACTION_LOCKED_BOOT_COMPLETED) return
        val session = SessionStore.load(context)
        if (!RelayWake.requested(context) || session.pairings.none { it.enabled }) return
        Log.i("BootReceiver", "Restarting relay after boot")
        RelayForegroundService.Controller.start(context)
    }
}
