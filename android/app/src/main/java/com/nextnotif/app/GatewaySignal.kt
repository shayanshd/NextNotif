package com.nextnotif.app

import kotlin.math.log10
import kotlin.math.sqrt

enum class ProbeStatus {
    SIGNAL,
    SILENT,
    BLOCKED,
    FAILED,
}

data class SignalSummary(
    val status: ProbeStatus,
    val dbfs: Double,
    val nonZeroPercent: Double,
    val sampleCount: Int,
)

data class TimelineComparison(
    val speechDbfs: Double,
    val quietDbfs: Double,
    val differenceDb: Double,
)

internal fun compareTimeline(slices: List<SignalSlice>): TimelineComparison? {
    val speech = slices.filter { it.expectedSpeech }
    val quiet = slices.filterNot { it.expectedSpeech }
    if (speech.isEmpty() || quiet.isEmpty()) return null
    val speechAverage = speech.map { it.dbfs }.average()
    val quietAverage = quiet.map { it.dbfs }.average()
    return TimelineComparison(speechAverage, quietAverage, speechAverage - quietAverage)
}

/** Converts PCM into coarse measurements without retaining or serializing audio. */
internal fun analyzeSignal(samples: ShortArray, count: Int = samples.size): SignalSummary {
    val safeCount = count.coerceIn(0, samples.size)
    if (safeCount == 0) {
        return SignalSummary(ProbeStatus.SILENT, -120.0, 0.0, 0)
    }

    var sumSquares = 0.0
    var nonZero = 0
    for (index in 0 until safeCount) {
        val value = samples[index].toDouble()
        sumSquares += value * value
        if (samples[index].toInt() != 0) nonZero += 1
    }
    val rms = sqrt(sumSquares / safeCount)
    val dbfs = if (rms == 0.0) -120.0 else 20.0 * log10(rms / Short.MAX_VALUE)
    val nonZeroPercent = nonZero.toDouble() * 100.0 / safeCount
    val status = if (dbfs > -60.0 && nonZeroPercent >= 1.0) ProbeStatus.SIGNAL else ProbeStatus.SILENT
    return SignalSummary(status, dbfs.coerceAtLeast(-120.0), nonZeroPercent, safeCount)
}
