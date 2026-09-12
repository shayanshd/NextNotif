package com.nextnotif.app

import org.junit.Assert.*
import org.junit.Test
import org.webrtc.MediaStreamTrack
import org.webrtc.RtpCapabilities

class WebRtcOpusPolicyTest {
    @Test fun preservesNativeCapabilitiesWithoutMutatingParameters() {
        val original = RtpCapabilities.CodecCapability().apply {
            name = "opus"; kind = MediaStreamTrack.MediaType.MEDIA_TYPE_AUDIO
            preferredPayloadType = 111; clockRate = 48000; numChannels = 2
            mimeType = "audio/opus"; parameters = mapOf("useinbandfec" to "1", "usedtx" to "1")
        }
        val other = RtpCapabilities.CodecCapability().apply { name = "PCMU" }
        val result = WebRtcOpusPolicy.preferences(listOf(original, other)).single()
        assertSame(original, result)
        assertEquals("1", original.parameters["usedtx"])
        assertEquals("1", result.parameters["usedtx"])
        assertEquals("1", result.parameters["useinbandfec"])
        assertEquals(original.preferredPayloadType, result.preferredPayloadType)
        assertEquals(original.clockRate, result.clockRate)
        assertEquals(original.numChannels, result.numChannels)
        assertEquals(original.kind, result.kind)
        assertEquals(original.mimeType, result.mimeType)
    }
    @Test fun validatesDtxDefaultAndExplicitZeroWithoutRewritingSdp() {
        val sdp = "v=0\r\na=rtpmap:111 opus/48000/2\r\n"
        assertTrue(WebRtcOpusPolicy.continuous(sdp))
        assertTrue(WebRtcOpusPolicy.continuous(sdp + "a=fmtp:111 useinbandfec=1;usedtx=0\r\n"))
        assertFalse(WebRtcOpusPolicy.continuous(sdp + "a=fmtp:111 usedtx=1\r\n"))
        assertFalse(WebRtcOpusPolicy.continuous(sdp + "a=fmtp:111 usedtx=0;usedtx=1\r\n"))
        assertFalse(WebRtcOpusPolicy.continuous("v=0\r\n"))
    }
}
