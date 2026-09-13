package com.nextnotif.app

import org.webrtc.PeerConnection

/** Public-address discovery only. This is not a TURN relay or a connectivity guarantee. */
internal object WebRtcIceConfiguration {
    const val STUN_URL = "stun:stun.cloudflare.com:3478"

    fun discoveryServers(): List<PeerConnection.IceServer> =
        listOf(PeerConnection.IceServer.builder(STUN_URL).createIceServer())
}
