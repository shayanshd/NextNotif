package com.nextnotif.app

/** Owned by the peer's serial executor; survives track creation/replacement. */
internal class WebRtcMicrophoneState(private val audioEnabled: Boolean, var muted: Boolean = false) {
    val trackEnabled: Boolean get() = audioEnabled && !muted
}
