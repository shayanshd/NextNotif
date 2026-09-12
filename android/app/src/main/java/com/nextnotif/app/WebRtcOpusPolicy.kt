package com.nextnotif.app

import org.webrtc.RtpCapabilities

/** Keep native capabilities intact; omitted usedtx means off under RFC 7587. */
internal object WebRtcOpusPolicy {
    fun preferences(codecs: List<RtpCapabilities.CodecCapability>): List<RtpCapabilities.CodecCapability> =
        codecs.filter { it.name.equals("opus", ignoreCase = true) }

    fun continuous(sdp: String): Boolean {
        val lines = sdp.lineSequence().map(String::trim).toList()
        val payloads = lines.filter { it.startsWith("a=rtpmap:") &&
            it.substringAfter(' ').startsWith("opus/", ignoreCase = true) }
            .map { it.substringAfter("a=rtpmap:").substringBefore(' ') }
        return payloads.isNotEmpty() && payloads.all { payload ->
            lines.filter { it.startsWith("a=fmtp:$payload ") }.all { line ->
                line.substringAfter(' ').split(';').map(String::trim).filter {
                    it.substringBefore('=').equals("usedtx", ignoreCase = true)
                }.all { it.substringAfter('=', "") == "0" }
            }
        }
    }
}
