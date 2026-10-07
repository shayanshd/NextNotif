package com.nextnotif.app

data class FirebaseCfg(
    val apiKey: String,
    val projectId: String,
    val appId: String,
    val messagingSenderId: String,
)

/**
 * Firebase identifiers used only for FCM delivery. The custom Realtime
 * Database transport has been retired.
 */
object FirebaseConfig {
    val DEFAULT: FirebaseCfg =
        FirebaseCfg(
            apiKey = "AIzaSyC15xJn01Yn8h6F7UcsYnJ54Qe4J0pStLY",
            projectId = "nextnotif-5bcf9",
            appId = "1:223835571995:android:e18d5607a74d20ddfff5be",
            messagingSenderId = "223835571995",
        )
}
