package com.nextnotif.app

object Config {
    val IS_STAGING_BUILD: Boolean = BuildConfig.BUILD_TYPE.startsWith("staging")
    /** The private release ships baseline call alerts; rooted live audio is a separate beta. */
    val LIVE_CALL_BETA_ENABLED: Boolean = BuildConfig.BUILD_TYPE != "release"
    /** Production-safe default. Debug builds may still accept a ws:// LAN relay. */
    val DEFAULT_SERVER = if (IS_STAGING_BUILD)
        "wss://nextnotif-relay-staging.shayanshad.workers.dev"
    else "wss://relay.amberdogeorgia.com"
    val HTTP_DEFAULT_SERVER = DEFAULT_SERVER.replaceFirst("wss://", "https://")

    fun allowsServer(server: String): Boolean =
        !IS_STAGING_BUILD ||
            server.trim().trimEnd('/') == DEFAULT_SERVER
}
