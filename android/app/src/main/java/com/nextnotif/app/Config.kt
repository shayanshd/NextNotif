package com.nextnotif.app

object Config {
    /** Production-safe default. Debug builds may still accept a ws:// LAN relay. */
    const val DEFAULT_SERVER = "wss://relay.amberdogeorgia.com"
    const val HTTP_DEFAULT_SERVER = "https://relay.amberdogeorgia.com"
}
