package com.nextnotif.app

import org.junit.Assert.*
import org.junit.Test

class WebRtcAudioDevicePolicyTest {
    @Test fun senderUsesDownlinkOnlyWhenGatewayCapabilityIsAvailable() {
        assertTrue(WebRtcAudioDevicePolicy.forRole(Role.SENDER, GatewayCapability.AVAILABLE).gatewayDownlink)
        GatewayCapability.entries.filter { it != GatewayCapability.AVAILABLE }.forEach { capability ->
            assertThrows(IllegalStateException::class.java) {
                WebRtcAudioDevicePolicy.forRole(Role.SENDER, capability)
            }
        }
    }

    @Test fun receiverUsesNormalCommunicationAudioWithoutRoot() {
        GatewayCapability.entries.forEach { capability ->
            assertFalse(WebRtcAudioDevicePolicy.forRole(Role.RECEIVER, capability).gatewayDownlink)
        }
    }
}
