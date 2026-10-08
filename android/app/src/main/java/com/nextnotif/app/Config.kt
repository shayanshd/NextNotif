package com.nextnotif.app

object Config {
    val IS_STAGING_BUILD: Boolean = BuildConfig.BUILD_TYPE.startsWith("staging")
    val RECEIVER_ONLY_BUILD: Boolean = BuildConfig.BUILD_TYPE == "htcReceiver"
    /** Live call controls are available in the private release; the sender still needs rooted audio access. */
    val LIVE_CALL_BETA_ENABLED: Boolean = true
    /** Production-safe default. Debug builds may still accept a ws:// LAN relay. */
    val DEFAULT_SERVER = if (IS_STAGING_BUILD)
        "wss://nextnotif-relay-staging.shayanshad.workers.dev"
    else "wss://relay.amberdogeorgia.com"
    val HTTP_DEFAULT_SERVER = DEFAULT_SERVER.replaceFirst("wss://", "https://")

    fun allowsServer(server: String): Boolean =
        !IS_STAGING_BUILD ||
            server.trim().trimEnd('/') == DEFAULT_SERVER
}
