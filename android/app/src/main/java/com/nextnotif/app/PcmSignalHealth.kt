package com.nextnotif.app

import kotlin.math.sqrt

/** Debug-only five-second aggregate. Never holds a PCM buffer or individual sample. */
internal class PcmSignalHealth {
    private var startedAt: Long? = null
    private var squares = 0.0
    private var samples = 0L
    private var frames = 0L

    fun observe(pcm: ByteArray, nowMs: Long): String? {
        if (pcm.isEmpty() || pcm.size % 2 != 0 || pcm.size > 32_768) return null
        val start = startedAt ?: nowMs.also { startedAt = it }
        if (nowMs < start) { reset(nowMs); return null }
        for (offset in pcm.indices step 2) {
            val value = ((pcm[offset].toInt() and 255) or
                ((pcm[offset + 1].toInt() and 255) shl 8)).toShort().toDouble()
            squares += value * value
        }
        samples += pcm.size / 2
        frames++
        if (nowMs - start < 5_000L) return null
        val rms = sqrt(squares / samples)
        val status = when {
            squares == 0.0 -> "silent"
            rms >= 32.0 -> "signal"
            else -> "quiet"
        }
        val summary = "frames=$frames samples=$samples signal=$status"
        reset(nowMs)
        return summary
    }

    private fun reset(nowMs: Long) {
        startedAt = nowMs
        squares = 0.0
        samples = 0L
        frames = 0L
    }
}
