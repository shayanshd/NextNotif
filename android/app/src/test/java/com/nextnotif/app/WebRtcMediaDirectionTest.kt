package com.nextnotif.app

import org.junit.Assert.*
import org.junit.Test

class WebRtcMediaDirectionTest {
    @Test fun bothWaysAndDefaultDirectionAreAccepted() {
        assertTrue(WebRtcMediaDirection.bidirectional("v=0\r\nm=audio 9 UDP/TLS/RTP/SAVPF 111\r\na=sendrecv\r\n"))
        assertTrue(WebRtcMediaDirection.bidirectional("v=0\nm=audio 9 UDP/TLS/RTP/SAVPF 111\n"))
    }
    @Test fun oneWayInactiveMissingOrMultipleMediaAreRejected() {
        for (direction in listOf("sendonly", "recvonly", "inactive")) {
            assertFalse(WebRtcMediaDirection.bidirectional("v=0\na=$direction\nm=audio 9 UDP/TLS/RTP/SAVPF 111\n"))
        }
        assertFalse(WebRtcMediaDirection.bidirectional("v=0\n"))
        for (port in listOf("0", "0/2", "-1", "65536", "invalid")) {
            assertFalse(WebRtcMediaDirection.bidirectional("m=audio $port RTP/SAVPF 111\na=sendrecv\n"))
        }
        assertFalse(WebRtcMediaDirection.bidirectional("m=video 9 RTP/SAVPF 96\na=sendrecv\n"))
        assertFalse(WebRtcMediaDirection.bidirectional("m=audio 9 RTP/SAVPF 111\nm=audio 9 RTP/SAVPF 111\n"))
    }
}
