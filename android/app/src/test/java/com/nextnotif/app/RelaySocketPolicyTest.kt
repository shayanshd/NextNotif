package com.nextnotif.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RelaySocketPolicyTest {
    @Test
    fun onlyTemporaryFcmReceiverAdvertisesFcmDelivery() {
        assertEquals("fcm", receiverDeliveryMode(Role.RECEIVER, true))
        assertNull(receiverDeliveryMode(Role.RECEIVER, false))
        assertNull(receiverDeliveryMode(Role.SENDER, true))
        assertNull(receiverDeliveryMode(Role.SENDER, false))
    }
}
