package com.nextnotif.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AdaptivePlayoutPolicyTest {
    private val policy = AdaptivePlayoutPolicy()

    @Test
    fun `startup waits for three complete frames`() {
        assertFalse(policy.isPrimed(0))
        assertFalse(policy.isPrimed(2))
        assertTrue(policy.isPrimed(3))
        assertTrue(policy.isPrimed(9))
    }

    @Test
    fun `low water slows playout to resist underrun`() {
        assertEquals(7_920, policy.rateFor(0))
        assertEquals(7_920, policy.rateFor(2))
    }

    @Test
    fun `normal water uses native sample rate`() {
        assertEquals(8_000, policy.rateFor(3))
        assertEquals(8_000, policy.rateFor(7))
    }

    @Test
    fun `high water speeds playout to bound latency`() {
        assertEquals(8_160, policy.rateFor(8))
        assertEquals(8_160, policy.rateFor(18))
    }

    @Test
    fun `bounded queue drops exactly the oldest frame on overflow`() {
        val queue = BoundedPlaybackQueue<Int>(3)

        assertFalse(queue.offerDroppingOldest(1))
        assertFalse(queue.offerDroppingOldest(2))
        assertFalse(queue.offerDroppingOldest(3))
        assertTrue(queue.offerDroppingOldest(4))

        assertEquals(3, queue.size)
        assertEquals(2, queue.poll())
        assertEquals(3, queue.poll())
        assertEquals(4, queue.poll())
        assertNull(queue.poll())
    }
}
