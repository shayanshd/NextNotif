package com.nextnotif.app

data class FirebaseCfg(
    val apiKey: String,
    val databaseUrl: String,
    val appId: String,
    val messagingSenderId: String? = null,
) {
    val projectId: String
        get() = databaseUrl.substringAfter("https://").substringBefore(".").substringBefore("/")
}

/**
 * Parses the pasted Firebase web-app config. Accepts the whole snippet from the
 * console (`const firebaseConfig = { apiKey: "...", databaseURL: "...", ... };`,
 * with comments and unquoted JS keys) as well as strict JSON — everything outside
 * the config object is ignored. Only `apiKey` + `databaseURL` (or `url`) are needed.
 */
object FirebaseConfig {
    /**
     * Developer-operated relay project — the default Firebase transport, so a
     * fresh install needs nothing but a code + secret (the "ships its own
     * backend" model). The pasted-config override in setup exists for
     * bring-your-own-project use.
     */
    val DEFAULT: FirebaseCfg =
        FirebaseCfg(
            // Android-specific key generated for com.nextnotif.app. The old
            // Web key works for Auth/RTDB but Firebase Installations rejects
            // it when the Android SDK includes package/certificate headers.
            apiKey = "AIzaSyC15xJn01Yn8h6F7UcsYnJ54Qe4J0pStLY",
            databaseUrl = "https://nextnotif-5bcf9-default-rtdb.europe-west1.firebasedatabase.app",
            appId = "1:223835571995:android:e18d5607a74d20ddfff5be",
            messagingSenderId = "223835571995",
        )

    fun parse(raw: String): FirebaseCfg? {
        val text = raw.trim()
        if (text.isEmpty()) return null
        // Drop whole-line // comments so commented-out fields can't shadow real ones.
        val cleaned = text.lineSequence()
            .filterNot { it.trimStart().startsWith("//") }
            .joinToString("\n")
        val apiKey = quotedValue(cleaned, "apiKey") ?: return null
        val databaseUrl =
            quotedValue(cleaned, "databaseURL") ?: quotedValue(cleaned, "url") ?: return null
        if (!databaseUrl.startsWith("https://")) return null
        // firebase-common 21+ hard-requires an applicationId at build time.
        val appId = quotedValue(cleaned, "appId") ?: return null
        val messagingSenderId = quotedValue(cleaned, "messagingSenderId")
        return FirebaseCfg(apiKey, databaseUrl, appId, messagingSenderId)
    }

    // Matches `key: "value"` (console snippet) and `"key": "value"` (JSON).
    private fun quotedValue(text: String, key: String): String? =
        Regex("(?:\"$key\"|$key)\\s*:\\s*\"([^\"]+)\"")
            .find(text)?.groupValues?.get(1)
            ?.takeIf { it.isNotBlank() }
}
