package com.nextnotif.app

import org.junit.Assert.*
import org.junit.Test
import org.webrtc.RTCStats
import org.webrtc.RTCStatsReport
import java.math.BigInteger

class WebRtcReceivedAudioStatsTest {
    private fun stream(type: String = "inbound-rtp", kind: String = "audio", count: Any? = 0L,
                       kindKey: String = "kind") = RTCStats(0, type, "unused",
        mapOf<String, Any>(kindKey to kind).toMutableMap().apply { if (count != null) put("packetsReceived", count) })
    private fun report(vararg streams: RTCStats) = RTCStatsReport(0,
        streams.mapIndexed { index, stats -> index.toString() to stats }.toMap())

    @Test fun countsOnlyReceivedAudioAcrossNativeFieldVariants() {
        assertEquals(12L, WebRtcReceivedAudioStats.packets(report(
            stream(count = BigInteger.valueOf(5)), stream(count = 7, kindKey = "mediaType"),
            stream(kind = "video", count = 100L), stream(type = "outbound-rtp", count = 100L))))
    }
    @Test fun unsupportedOrIncompleteTelemetryIsNotZeroPackets() {
        assertNull(WebRtcReceivedAudioStats.packets(report()))
        for (count in listOf(null, -1L, "3", 3.5)) {
            assertNull(WebRtcReceivedAudioStats.packets(report(stream(count = 10L), stream(count = count))))
        }
        assertEquals(0L, WebRtcReceivedAudioStats.packets(report(stream())))
    }
    @Test fun unsignedNativeCountersAndTotalsSaturateWithoutWrapping() {
        assertEquals(Long.MAX_VALUE, WebRtcReceivedAudioStats.packets(report(
            stream(count = BigInteger.ONE.shiftLeft(64)), stream(count = 1L))))
        assertNull(WebRtcReceivedAudioStats.packets(report(stream(count = BigInteger.valueOf(-1)))))
    }
}
