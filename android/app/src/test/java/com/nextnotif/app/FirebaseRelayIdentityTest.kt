package com.nextnotif.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class FirebaseRelayIdentityTest {
    private val cfg = FirebaseCfg("key", "https://example.firebaseio.com", "app")

    @Test fun reconnectReusesSameNamespace() {
        assertEquals(firebaseRelayAppName("123456", cfg), firebaseRelayAppName("123456", cfg.copy()))
    }

    @Test fun pairingsAndConfigurationsHaveSeparateAuthAndPersistence() {
        val name = firebaseRelayAppName("123456", cfg)
        assertNotEquals("[DEFAULT]", name)
        assertNotEquals(name, firebaseRelayAppName("654321", cfg))
        assertNotEquals(name, firebaseRelayAppName("123456", cfg.copy(apiKey = "other")))
        assertNotEquals(name, firebaseRelayAppName("123456", cfg.copy(appId = "other")))
        assertNotEquals(name, firebaseRelayAppName("123456", cfg.copy(databaseUrl = "https://other.firebaseio.com")))
    }
}
