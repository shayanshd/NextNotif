package com.nextnotif.app

import android.content.Context
import android.content.Intent

object PendingForwards {
    fun addSms(ctx: Context, sender: String, body: String, ts: Long) {
        RelayForegroundService.Controller.start(
            ctx,
            RelayForegroundService.ACTION_FORWARD_SMS,
            arrayOf("sender" to sender, "body" to body, "ts" to ts),
        )
    }

    fun addCall(ctx: Context, number: String, state: String, ts: Long) {
        RelayForegroundService.Controller.start(
            ctx,
            RelayForegroundService.ACTION_FORWARD_CALL,
            arrayOf("number" to number, "state" to state, "ts" to ts),
        )
    }
}
