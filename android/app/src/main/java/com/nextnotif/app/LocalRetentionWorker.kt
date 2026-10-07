package com.nextnotif.app

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

/** Best-effort background sweep; startup also prunes after Android force-stop. */
class LocalRetentionWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = runCatching {
        MessageStore.from(applicationContext).load()
        SmsSendStore(ProtectedPreferences.from(applicationContext, "nextnotif_sms_send")).all()
        Result.success()
    }.getOrElse { Result.retry() }
}
