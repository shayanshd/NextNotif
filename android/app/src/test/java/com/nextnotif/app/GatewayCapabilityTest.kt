package com.nextnotif.app

import org.junit.Assert.assertEquals
import org.junit.Test

class GatewayCapabilityTest {
    @Test
    fun `successful probe is cached until its ttl expires`() {
        var now = 1_000L
        var probes = 0
        val cache = CachedGatewayCapability(
            probe = GatewayCapabilityProbe {
                probes++
                GatewayCapability.AVAILABLE
            },
            nowMs = { now },
            successTtlMs = 1_000L,
            failureTtlMs = 100L,
        )

        assertEquals(GatewayCapability.AVAILABLE, cache.get())
        now += 999L
        assertEquals(GatewayCapability.AVAILABLE, cache.get())
        assertEquals(1, probes)

        now += 1L
        assertEquals(GatewayCapability.AVAILABLE, cache.get())
        assertEquals(2, probes)
    }

    @Test
    fun `failed probe uses shorter ttl so repaired root can recover`() {
        var now = 10_000L
        var probes = 0
        val cache = CachedGatewayCapability(
            probe = GatewayCapabilityProbe {
                probes++
                if (probes == 1) GatewayCapability.ROOT_UNAVAILABLE else GatewayCapability.AVAILABLE
            },
            nowMs = { now },
            successTtlMs = 1_000L,
            failureTtlMs = 100L,
        )

        assertEquals(GatewayCapability.ROOT_UNAVAILABLE, cache.get())
        now += 99L
        assertEquals(GatewayCapability.ROOT_UNAVAILABLE, cache.get())
        assertEquals(1, probes)

        now += 1L
        assertEquals(GatewayCapability.AVAILABLE, cache.get())
        assertEquals(2, probes)
    }
}
