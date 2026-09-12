package com.nextnotif.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveCallLifecycleTest {
    @Test
    fun `deadline expires exactly at each configured bound`() {
        val policy = LiveCallTimeoutPolicy(10, 20, 30, 40)
        LiveCallWaitPhase.entries.forEach { phase ->
            val timeout = policy.timeoutMs(phase)
            assertFalse(policy.expired(phase, 100, 100 + timeout - 1))
            assertTrue(policy.expired(phase, 100, 100 + timeout))
        }
    }

    @Test
    fun `metrics aggregate reconnects queue pressure underruns and drops`() {
        var now = 1_000L
        val tracker = LiveCallMetricsTracker(startedAtMs = now, nowMs = { now })
        now = 1_275L
        tracker.connected()
        tracker.reconnected()
        tracker.reconnected()
        tracker.socketDrop()
        tracker.merge(CallAudioMetrics(7, 2, 3))
        tracker.merge(CallAudioMetrics(4, 1, 2))

        assertEquals(
            LiveCallMetrics(
                setupMs = 275L,
                reconnectCount = 2,
                playbackHighWaterFrames = 7,
                underrunCount = 3,
                dropCount = 6,
                endReason = "remote_ended",
            ),
            tracker.finish("remote_ended"),
        )
    }

    @Test
    fun `audio counter reports high water without retaining frames`() {
        val counter = CallAudioMetricsCounter()
        counter.queued(2, false)
        counter.queued(8, true)
        counter.queued(3, false)
        counter.underrun()
        assertEquals(CallAudioMetrics(8, 1, 1), counter.snapshot())
    }
}
