package com.nextnotif.app

import org.junit.Assert.*
import org.junit.Test

class WebRtcMicrophoneStateTest {
    @Test fun muteSelectedBeforeTrackCreationRemainsApplied() {
        val microphone = WebRtcMicrophoneState(audioEnabled = true)
        microphone.muted = true
        assertFalse(microphone.trackEnabled)
        microphone.muted = false
        assertTrue(microphone.trackEnabled)
    }
    @Test fun initiallyMutedStartsWithTransmissionDisabled() {
        assertFalse(WebRtcMicrophoneState(audioEnabled = true, muted = true).trackEnabled)
    }
    @Test fun noAudioModeCannotBeEnabledByUnmuting() {
        val microphone = WebRtcMicrophoneState(audioEnabled = false, muted = true)
        microphone.muted = false
        assertFalse(microphone.trackEnabled)
    }
}
