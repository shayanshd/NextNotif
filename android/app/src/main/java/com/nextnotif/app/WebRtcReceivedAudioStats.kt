package com.nextnotif.app

import org.webrtc.RTCStatsReport
import java.math.BigInteger

/** Extract only RTP counters; never retain addresses, stream IDs or audio levels. */
internal object WebRtcReceivedAudioStats {
    fun packets(report: RTCStatsReport): Long? {
        val streams = report.statsMap.values.filter {
            it.type == "inbound-rtp" &&
                (it.members["kind"] == "audio" || it.members["mediaType"] == "audio")
        }
        if (streams.isEmpty()) return null
        var total = 0L
        for (stream in streams) {
            val count = counter(stream.members["packetsReceived"]) ?: return null
            total = if (Long.MAX_VALUE - total < count) Long.MAX_VALUE else total + count
        }
        return total
    }

    private fun counter(value: Any?): Long? = when (value) {
        is BigInteger -> if (value.signum() < 0) null else
            if (value > BigInteger.valueOf(Long.MAX_VALUE)) Long.MAX_VALUE else value.toLong()
        is Long -> value.takeIf { it >= 0 }
        is Int -> value.toLong().takeIf { it >= 0 }
        else -> null // Reject fractional, string and unsupported telemetry.
    }
}
