package com.nextnotif.app

import org.junit.Assert.assertEquals
import org.junit.Test

class ConversationsTest {
    private fun sms(address: String?, ts: Long, code: String = "a", name: String? = null) = AppState.Entry(
        ts, "IN", "SMS from $address: hello", code,
        AppState.CommunicationDetails(AppState.CommunicationKind.SMS,
            AppState.CommunicationDirection.INCOMING, address, name, "hello"))

    @Test fun formattingAndRenamesStayInOneThread() {
        val threads = textConversations(listOf(sms("+1 (222) 333-4444", 1, name = "Old name"), sms("+12223334444", 2, name = "New name")))
        assertEquals(1, threads.size)
        assertEquals("New name", threads.single().identity)
        assertEquals(listOf(2L, 1L), threads.single().entries.map { it.ts })
    }
    @Test fun pairingsAndNumbersRemainSeparateDespiteSameName() {
        assertEquals(3, textConversations(listOf(sms("123", 1, name = "Sam"), sms("456", 2, name = "Sam"), sms("123", 3, "b"))).size)
    }
    @Test fun unknownSendersAreNotMerged() {
        assertEquals(2, textConversations(listOf(sms(null, 1), sms(null, 2))).size)
    }
    @Test fun legacyTextsGroupAndCallsAndTestsAreExcluded() {
        val entries = listOf(AppState.Entry(1, "IN", "SMS from 123: one"), AppState.Entry(3, "OUT", "SMS → 123: two"),
            AppState.Entry(4, "IN", "Call RINGING from 123"), AppState.Entry(5, "TEST", "test"))
        assertEquals(listOf(3L, 1L), textConversations(entries).single().entries.map { it.ts })
    }
}
