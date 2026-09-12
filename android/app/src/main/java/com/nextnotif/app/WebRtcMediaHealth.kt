package com.nextnotif.app

/** Monotonic, session-local RTP progress; connectivity alone is not audio health. */
internal class WebRtcMediaHealth(startedMs: Long, private val stallMs: Long = 30_000) {
    init { require(stallMs > 0) }
    private var lastProgressMs = startedMs
    private var lastPackets = 0L
    private var lastSampleMs: Long? = null

    fun observe(nowMs: Long, packetsReceived: Long?) {
        if (packetsReceived == null || packetsReceived < 0) return
        lastSampleMs = nowMs
        if (packetsReceived > lastPackets) lastProgressMs = nowMs
        lastPackets = packetsReceived
    }

    fun stalled(nowMs: Long): Boolean = lastSampleMs?.let {
        nowMs - it in 0..6_000 && nowMs - lastProgressMs >= stallMs
    } ?: false

    fun telemetryUnavailable(nowMs: Long): Boolean =
        nowMs - (lastSampleMs ?: lastProgressMs) >= stallMs
}
