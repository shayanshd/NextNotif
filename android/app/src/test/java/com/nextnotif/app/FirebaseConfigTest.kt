package com.nextnotif.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FirebaseConfigTest {

    @Test
    fun parsesBareObject() {
        val cfg = FirebaseConfig.parse(
            """{"apiKey":"AIzaSyB-key","databaseURL":"https://my-proj-abc.firebaseio.com","appId":"1:111:web:abc"}"""
        )
        assertEquals("AIzaSyB-key", cfg?.apiKey)
        assertEquals("https://my-proj-abc.firebaseio.com", cfg?.databaseUrl)
        assertEquals("1:111:web:abc", cfg?.appId)
        assertEquals("my-proj-abc", cfg?.projectId)
    }

    @Test
    fun parsesConsoleSnippetWithCommentsAndConst() {
        val snippet = """
            // For more info on these configuration options, see:
            // https://firebase.google.com/docs/web/setup
            const firebaseConfig = {
              apiKey: "AIzaSyC-snippet",
              authDomain: "proj.firebaseapp.com",
              databaseURL: "https://proj-xyz.firebaseio.com",
              projectId: "proj-xyz",
              storageBucket: "proj-xyz.appspot.com",
              messagingSenderId: "1234567890",
              appId: "1:1234567890:web:abc",
              measurementId: "G-XXXX"
            };
        """.trimIndent()
        val cfg = FirebaseConfig.parse(snippet)
        assertEquals("AIzaSyC-snippet", cfg?.apiKey)
        assertEquals("https://proj-xyz.firebaseio.com", cfg?.databaseUrl)
        assertEquals("1:1234567890:web:abc", cfg?.appId)
        assertEquals("proj-xyz", cfg?.projectId)
    }

    @Test
    fun urlKeyIsAcceptedAsDatabaseUrl() {
        val cfg = FirebaseConfig.parse(
            """{"apiKey":"k","url":"https://alt.firebaseio.com","appId":"1:1:web:x"}"""
        )
        assertEquals("https://alt.firebaseio.com", cfg?.databaseUrl)
    }

    @Test
    fun missingApiKeyRejected() {
        assertNull(FirebaseConfig.parse("""{"databaseURL":"https://x.firebaseio.com","appId":"1:1:web:x"}"""))
    }

    @Test
    fun blankApiKeyRejected() {
        assertNull(FirebaseConfig.parse("""{"apiKey":"  ","databaseURL":"https://x.firebaseio.com","appId":"1:1:web:x"}"""))
    }

    @Test
    fun missingDatabaseUrlRejected() {
        assertNull(FirebaseConfig.parse("""{"apiKey":"k","appId":"1:1:web:x"}"""))
    }

    @Test
    fun missingAppIdRejected() {
        assertNull(FirebaseConfig.parse("""{"apiKey":"k","databaseURL":"https://x.firebaseio.com"}"""))
    }

    @Test
    fun nonHttpsDatabaseUrlRejected() {
        assertNull(FirebaseConfig.parse("""{"apiKey":"k","databaseURL":"http://x.firebaseio.com"}"""))
    }

    @Test
    fun emptyInputRejected() {
        assertNull(FirebaseConfig.parse(""))
        assertNull(FirebaseConfig.parse("   "))
    }

    @Test
    fun garbageRejected() {
        assertNull(FirebaseConfig.parse("not json at all"))
        assertNull(FirebaseConfig.parse("const firebaseConfig = {"))
        assertNull(FirebaseConfig.parse("[1,2,3]"))
    }

    @Test
    fun extraWhitespaceTolerated() {
        val cfg = FirebaseConfig.parse(
            """
            {
                "apiKey" : "spaced",
                "databaseURL" : "https://spaced.firebaseio.com",
                "appId" : "1:999:web:def",
            }
            """
                .trimIndent()
        )
        assertEquals("spaced", cfg?.apiKey)
    }
}
