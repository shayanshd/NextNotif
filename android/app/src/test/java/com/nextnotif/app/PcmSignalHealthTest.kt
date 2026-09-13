package com.nextnotif.app

import org.junit.Assert.*
import org.junit.Test

class PcmSignalHealthTest {
    @Test fun zeroPcmReportsSilenceAndResetsWindow() {
        val health = PcmSignalHealth()
        assertNull(health.observe(ByteArray(160), 0))
        assertEquals("frames=2 samples=160 signal=silent", health.observe(ByteArray(160), 5_000))
        assertEquals("frames=1 samples=80 signal=silent", health.observe(ByteArray(160), 10_000))
    }
    @Test fun signedLittleEndianSpeechReportsSignal() {
        val health = PcmSignalHealth()
        assertNull(health.observe(byteArrayOf(0, -128, -1, 127), 0))
        assertTrue(health.observe(byteArrayOf(0, -128), 5_000)!!.endsWith("signal=signal"))
    }
    @Test fun quietNonzeroPcmIsNotReportedAsAllZero() {
        val health = PcmSignalHealth()
        health.observe(byteArrayOf(1, 0), 0)
        assertTrue(health.observe(byteArrayOf(1, 0), 5_000)!!.endsWith("signal=quiet"))
    }
    @Test fun malformedBuffersAndBackwardClockDoNotProduceFalseSummary() {
        val health = PcmSignalHealth()
        assertNull(health.observe(byteArrayOf(1), 0))
        assertNull(health.observe(ByteArray(40_000), 0))
        assertNull(health.observe(ByteArray(2), 100))
        assertNull(health.observe(ByteArray(2), 99))
    }
}
