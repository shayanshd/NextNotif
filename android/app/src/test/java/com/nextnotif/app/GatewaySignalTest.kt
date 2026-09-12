package com.nextnotif.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GatewaySignalTest {
    @Test
    fun zeroSamplesAreSilent() {
        val result = analyzeSignal(ShortArray(800))

        assertEquals(ProbeStatus.SILENT, result.status)
        assertEquals(-120.0, result.dbfs, 0.01)
        assertEquals(0.0, result.nonZeroPercent, 0.01)
        assertEquals(800, result.sampleCount)
    }

    @Test
    fun ordinaryPcmIsReportedAsSignal() {
        val samples = ShortArray(1_000) { index -> if (index % 2 == 0) 4_000 else -4_000 }

        val result = analyzeSignal(samples)

        assertEquals(ProbeStatus.SIGNAL, result.status)
        assertTrue(result.dbfs > -20.0)
        assertEquals(100.0, result.nonZeroPercent, 0.01)
    }

    @Test
    fun countLimitsAnalysisToValidReadRegion() {
        val samples = shortArrayOf(6_000, -6_000, 0, 0, 0, 0)

        val result = analyzeSignal(samples, count = 2)

        assertEquals(ProbeStatus.SIGNAL, result.status)
        assertEquals(2, result.sampleCount)
        assertEquals(100.0, result.nonZeroPercent, 0.01)
    }

    @Test
    fun controlledTimelineComparesSpeechAgainstQuietPhases() {
        val slices = listOf(
            SignalSlice(0, -60.0, expectedSpeech = false),
            SignalSlice(250, -56.0, expectedSpeech = false),
            SignalSlice(3_000, -30.0, expectedSpeech = true),
            SignalSlice(3_250, -26.0, expectedSpeech = true),
        )

        val comparison = compareTimeline(slices)!!

        assertEquals(-28.0, comparison.speechDbfs, 0.01)
        assertEquals(-58.0, comparison.quietDbfs, 0.01)
        assertEquals(30.0, comparison.differenceDb, 0.01)
    }
}
