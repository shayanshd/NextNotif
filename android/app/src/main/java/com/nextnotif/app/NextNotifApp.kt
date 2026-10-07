package com.nextnotif.app

import android.app.Application
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

/** Migrate every sensitive legacy preference file before any component reads it. */
class NextNotifApp : Application() {
    override fun onCreate() {
        super.onCreate()
        ProtectedPreferences.migrateAll(this)
        MessageStore.from(this).load()
        SmsSendStore(ProtectedPreferences.from(this, "nextnotif_sms_send")).all()
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "local-content-retention",
            ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<LocalRetentionWorker>(1, TimeUnit.DAYS).build(),
        )
    }
}
