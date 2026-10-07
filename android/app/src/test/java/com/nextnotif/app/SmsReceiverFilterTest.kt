package com.nextnotif.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SmsReceiverFilterTest {
    private val valid = STAGING_MULTIPART_PREFIX + "A".repeat(STAGING_MULTIPART_LENGTH - STAGING_MULTIPART_PREFIX.length)

    @Test fun acceptsOnlyTheKnownMarkerOrThisExactMultipartProbeShape() {
        assertTrue(allowedStagingInboundBody("NN-STAGING-INBOUND-20261003"))
        assertTrue(allowedStagingInboundBody(valid))
        assertFalse(allowedStagingInboundBody(valid.dropLast(1)))
        assertFalse(allowedStagingInboundBody(valid + "A"))
        assertFalse(allowedStagingInboundBody(valid.replaceRange(0, 1, "X")))
        assertFalse(allowedStagingInboundBody(valid.dropLast(1) + "G"))
        assertFalse(allowedStagingInboundBody("Personal message"))
    }
}
