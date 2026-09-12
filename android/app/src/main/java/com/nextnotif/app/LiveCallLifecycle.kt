package com.nextnotif.app

internal enum class LiveCallWaitPhase { UNANSWERED, RENDEZVOUS, RECONNECT, SESSION }

/** Central, testable bounds for every temporary live-call resource. */
internal data class LiveCallTimeoutPolicy(
    val unansweredMs: Long = 60_000L,
    val rendezvousMs: Long = 20_000L,
    val reconnectMs: Long = 20_000L,
    val sessionMs: Long = 4 * 60 * 60_000L,
) {
    init {
        require(unansweredMs > 0 && rendezvousMs > 0 && reconnectMs > 0 && sessionMs > 0)
    }

    fun timeoutMs(phase: LiveCallWaitPhase): Long = when (phase) {
        LiveCallWaitPhase.UNANSWERED -> unansweredMs
        LiveCallWaitPhase.RENDEZVOUS -> rendezvousMs
        LiveCallWaitPhase.RECONNECT -> reconnectMs
        LiveCallWaitPhase.SESSION -> sessionMs
    }

    fun expired(phase: LiveCallWaitPhase, startedAtMs: Long, nowMs: Long): Boolean =
        nowMs >= startedAtMs && nowMs - startedAtMs >= timeoutMs(phase)
}

internal data class CallAudioMetrics(
    val playbackHighWaterFrames: Int = 0,
    val underrunCount: Int = 0,
    val playbackDropCount: Int = 0,
)

internal data class LiveCallMetrics(
    val setupMs: Long?,
    val reconnectCount: Int,
    val playbackHighWaterFrames: Int,
    val underrunCount: Int,
    val dropCount: Int,
    val endReason: String,
) {
    fun logLine(): String =
        "setup_ms=${setupMs ?: -1} reconnects=$reconnectCount " +
            "playback_high_water=$playbackHighWaterFrames underruns=$underrunCount " +
            "drops=$dropCount end_reason=$endReason"
}

/** Aggregates counters only. No phone number, audio frame, or audio sample is retained. */
internal class LiveCallMetricsTracker(
    private val startedAtMs: Long,
    private val nowMs: () -> Long,
) {
    private var connectedAtMs: Long? = null
    private var reconnects = 0
    private var highWater = 0
    private var underruns = 0
    private var drops = 0

    @Synchronized
    fun connected() {
        if (connectedAtMs == null) connectedAtMs = nowMs()
    }

    @Synchronized
    fun reconnected() {
        reconnects++
    }

    @Synchronized
    fun socketDrop() {
        drops++
    }

    @Synchronized
    fun merge(audio: CallAudioMetrics) {
        highWater = maxOf(highWater, audio.playbackHighWaterFrames)
        underruns += audio.underrunCount
        drops += audio.playbackDropCount
    }

    @Synchronized
    fun finish(reason: String): LiveCallMetrics = LiveCallMetrics(
        setupMs = connectedAtMs?.let { (it - startedAtMs).coerceAtLeast(0L) },
        reconnectCount = reconnects,
        playbackHighWaterFrames = highWater,
        underrunCount = underruns,
        dropCount = drops,
        endReason = reason,
    )
}

/** Thread-safe audio counter used by the capture/playback workers. */
internal class CallAudioMetricsCounter {
    private var highWater = 0
    private var underruns = 0
    private var drops = 0

    @Synchronized
    fun queued(depth: Int, droppedOldest: Boolean) {
        highWater = maxOf(highWater, depth)
        if (droppedOldest) drops++
    }

    @Synchronized
    fun underrun() {
        underruns++
    }

    @Synchronized
    fun snapshot(): CallAudioMetrics = CallAudioMetrics(highWater, underruns, drops)
}
