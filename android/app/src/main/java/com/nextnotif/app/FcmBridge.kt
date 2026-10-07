package com.nextnotif.app

import android.content.Context
import android.util.Log
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

/** FCM token lifecycle for on-demand delivery and WebSocket recovery. */
object FcmBridge {
    private const val TAG = "NextNotifFCM"

    private fun initialize(context: Context): FirebaseApp {
        if (BuildConfig.BUILD_TYPE == "stagingFcm") {
            val options = requireNotNull(FirebaseOptions.fromResource(context.applicationContext)) {
                "Staging FCM requires its own google-services.json"
            }
            require(options.applicationId != FirebaseConfig.DEFAULT.appId) {
                "Staging FCM must use a separate Firebase Android app"
            }
            val existing = runCatching { FirebaseApp.getInstance() }.getOrNull()
            if (existing != null) {
                require(existing.options.applicationId == options.applicationId) {
                    "Staging FCM app configuration mismatch"
                }
                return existing
            }
            return requireNotNull(FirebaseApp.initializeApp(context.applicationContext, options))
        }
        val cfg = FirebaseConfig.DEFAULT
        val existing = runCatching { FirebaseApp.getInstance() }.getOrNull()
        if (
            existing != null &&
            existing.options.applicationId == cfg.appId &&
            existing.options.apiKey == cfg.apiKey &&
            existing.options.gcmSenderId == cfg.messagingSenderId
        ) {
            Log.i(TAG, "using existing Android Firebase app")
            return existing
        }
        if (existing != null) {
            Log.w(TAG, "replacing mismatched default Firebase app")
            existing.delete()
        }
        val builder = FirebaseOptions.Builder()
            .setApiKey(cfg.apiKey)
            .setApplicationId(cfg.appId)
            .setProjectId(cfg.projectId)
        builder.setGcmSenderId(cfg.messagingSenderId)
        return requireNotNull(FirebaseApp.initializeApp(context.applicationContext, builder.build())).also {
            Log.i(TAG, "initialized Android Firebase app")
        }
    }

    fun ensureToken(context: Context, onToken: (String) -> Unit = {}) {
        // Only the dedicated stagingFcm package has a real staging Firebase app.
        if (Config.IS_STAGING_BUILD && BuildConfig.BUILD_TYPE != "stagingFcm") return
        initialize(context)
        FirebaseMessaging.getInstance().token
            .addOnSuccessListener { token ->
                SessionStore.saveFcmToken(context, token)
                Log.i(TAG, "FCM token acquired (${token.length} chars)")
                FcmOnDemand.enqueueAll(context)
                SmsRelay.enqueueAll(context)
                onToken(token)
            }
            .addOnFailureListener { error ->
                Log.w(TAG, "FCM token failed: ${error::class.simpleName}")
            }
    }
}

class NextNotifMessagingService : FirebaseMessagingService() {
    override fun onNewToken(token: String) {
        SessionStore.saveFcmToken(this, token)
        Log.i("NextNotifFCM", "FCM token refreshed (${token.length} chars)")
        FcmOnDemand.enqueueAll(this)
        SmsRelay.enqueueAll(this)
        // Persistent WebSocket pairings publish the refreshed token when the
        // service starts; an FCM-only receiver never starts that service.
        RelayForegroundService.Controller.start(this)
    }

    override fun onMessageReceived(message: RemoteMessage) {
        Log.i("NextNotifFCM", "FCM message received")
        if (message.data["nn"] == "1") {
            SmsRelay.handlePush(this, message.data)
            OutgoingCallRelay.handlePush(this, message.data)
            if (!FcmOnDemand.handlePush(this, message.data)) {
                RelayForegroundService.Controller.start(this)
            }
        }
    }

    override fun onDeletedMessages() {
        FcmOnDemand.enqueueAll(this)
        SmsRelay.enqueueAll(this)
    }
}
