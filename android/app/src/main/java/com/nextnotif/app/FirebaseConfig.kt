package com.nextnotif.app

data class FirebaseCfg(
    val apiKey: String,
    val databaseUrl: String,
    val appId: String,
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
            apiKey = "AIzaSyDgxLTBpcI8-6QSYff9qFr5Z7ZYdFL4hxw",
            databaseUrl = "https://nextnotif-5bcf9-default-rtdb.europe-west1.firebasedatabase.app",
            appId = "1:223835571995:web:8b18ffe55c1b4e80fff5be",
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
        return FirebaseCfg(apiKey, databaseUrl, appId)
    }

    // Matches `key: "value"` (console snippet) and `"key": "value"` (JSON).
    private fun quotedValue(text: String, key: String): String? =
        Regex("(?:\"$key\"|$key)\\s*:\\s*\"([^\"]+)\"")
            .find(text)?.groupValues?.get(1)
            ?.takeIf { it.isNotBlank() }
}

/** Stable SDK namespace for one pairing and configuration, separate from FCM. */
internal fun firebaseRelayAppName(code: String, cfg: FirebaseCfg): String {
    val identity = listOf(code, cfg.apiKey, cfg.databaseUrl, cfg.appId).joinToString("\u0000")
    val hash = java.security.MessageDigest.getInstance("SHA-256")
        .digest(identity.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
    return "relay-$hash"
}
