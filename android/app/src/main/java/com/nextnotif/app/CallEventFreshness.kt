package com.nextnotif.app

/** History is durable; an interactive live-call offer is short lived. */
internal object CallEventFreshness {
    private const val MAX_AGE_MS = 90_000L
    private const val MAX_FUTURE_SKEW_MS = 30_000L

    fun permitsInteraction(sourceTime: Long?, now: Long): Boolean {
        if (sourceTime == null || sourceTime <= 0L || now < 0L) return false
        return if (sourceTime > now) sourceTime - now <= MAX_FUTURE_SKEW_MS
            else now - sourceTime <= MAX_AGE_MS
    }
}
