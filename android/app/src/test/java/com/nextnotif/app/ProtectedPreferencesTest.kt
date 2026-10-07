package com.nextnotif.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProtectedPreferencesTest {
    private val codec = object : ProtectedPreferences.Codec {
        override fun seal(plain: String): String = plain.reversed()
        override fun open(sealed: String): String = sealed.reversed()
    }

    @Test fun migratesExistingValuesBeforeDeletingPlaintext() {
        val source = FakeSharedPreferences()
        source.edit().putString("pairings", "private-token").putBoolean("relay_enabled", true).commit()
        val protected = ProtectedPreferences.forTest(source, codec)
        assertEquals("private-token", protected.getString("pairings", null))
        assertTrue(protected.getBoolean("relay_enabled", false))
        assertEquals(setOf("protected_document_v1"), source.all.keys)
        assertFalse(source.getString("protected_document_v1", "")!!.contains("private-token"))
        assertEquals("private-token", ProtectedPreferences.forTest(source, codec).getString("pairings", null))
    }

    @Test fun failedMigrationRetainsLegacyValues() {
        val source = FakeSharedPreferences()
        source.edit().putString("pairings", "private-token").commit()
        val failingCodec = object : ProtectedPreferences.Codec {
            override fun seal(plain: String): String = error("Key unavailable")
            override fun open(sealed: String): String = error("Key unavailable")
        }
        val failure = runCatching { ProtectedPreferences.forTest(source, failingCodec) }
        assertTrue(failure.isFailure)
        assertEquals("private-token", source.getString("pairings", null))
    }

    @Test fun editingAndClearingNeverRestoresPlaintext() {
        val source = FakeSharedPreferences()
        val protected = ProtectedPreferences.forTest(source, codec)
        protected.edit().putString("message", "private-body").putLong("count", 7).commit()
        assertEquals("private-body", protected.getString("message", null))
        assertEquals(7, protected.getLong("count", 0))
        protected.edit().clear().commit()
        assertFalse(protected.contains("message"))
        assertEquals(setOf("protected_document_v1"), source.all.keys)
    }
}
