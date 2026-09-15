package com.nextnotif.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneAccountMappingTest {
    @Test fun acceptsAndroidAndSamsungSubscriptionAccountIds() {
        assertTrue(phoneAccountMatchesSubscription("2", 2, "8998432001234567890"))
        assertTrue(phoneAccountMatchesSubscription("08998432001234567890", 2, "8998432001234567890"))
        assertFalse(phoneAccountMatchesSubscription("18998113901234567890", 2, "8998432001234567890"))
        assertFalse(phoneAccountMatchesSubscription("unrelated", 2, null))
    }
}
