package com.nextnotif.app

import org.junit.Assert.*
import org.junit.Test

class WebRtcMediaHealthTest {
    @Test fun connectedWithoutAudioEventuallyStalls() {
        val health = WebRtcMediaHealth(100, 1000)
        health.observe(500, 0)
        health.observe(900, null)
        assertFalse(health.stalled(1099))
        assertTrue(health.stalled(1100))
    }
    @Test fun onlyPacketProgressRefreshesDeadline() {
        val health = WebRtcMediaHealth(0, 1000)
        health.observe(500, 10)
        health.observe(1000, 10)
        assertTrue(health.stalled(1500))
        health.observe(1501, 11)
        assertFalse(health.stalled(2000))
    }
    @Test fun counterResetAndMalformedSampleDoNotPretendProgress() {
        val health = WebRtcMediaHealth(0, 1000)
        health.observe(100, 20)
        health.observe(500, 0)
        health.observe(900, -1)
        assertTrue(health.stalled(1100))
        health.observe(1101, 1)
        assertFalse(health.stalled(1102))
    }
    @Test fun missingOrStaleTelemetryIsNotProofOfAudioFailure() {
        val missing = WebRtcMediaHealth(0, 1000)
        missing.observe(1000, null)
        assertFalse(missing.stalled(1000))
        assertTrue(missing.telemetryUnavailable(1000))
        val stale = WebRtcMediaHealth(0, 1000)
        stale.observe(100, 0)
        assertFalse(stale.stalled(7000))
        assertTrue(stale.telemetryUnavailable(7000))
    }
}
