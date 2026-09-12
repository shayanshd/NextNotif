package com.nextnotif.app

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class WebRtcSignalingTest {
    private val session = WebRtcSignal.newSessionId()
    private fun offer() = WebRtcSignal.Description(session, true, "v=0\r\ns=-\r\n").toJson()
    private fun ice() = WebRtcSignal.Candidate(session, "audio", 0, "candidate:1 1 UDP 1 192.0.2.1 12345 typ host").toJson()

    @Test fun descriptionsAreBoundToSessionAndRole() {
        assertNotNull(WebRtcSignal.parse(offer(), session, Role.RECEIVER))
        assertNull(WebRtcSignal.parse(offer(), session, Role.SENDER))
        assertNull(WebRtcSignal.parse(offer(), WebRtcSignal.newSessionId(), Role.RECEIVER))
        val answer = offer().put("kind", "answer")
        assertNotNull(WebRtcSignal.parse(answer, session, Role.SENDER))
        assertNull(WebRtcSignal.parse(answer, session, Role.RECEIVER))
    }

    @Test fun rejectsMalformedOrOversizedDescriptions() {
        for (bad in listOf(JSONObject.NULL, 42, "", "not SDP", "v=0\r\n\u0000", "v=0\r\n" + "x".repeat(WebRtcSignal.MAX_SDP_BYTES))) {
            assertNull(WebRtcSignal.parse(offer().put("sdp", bad), session, Role.RECEIVER))
        }
        assertNull(WebRtcSignal.parse(offer().put("session_id", "invalid"), "invalid", Role.RECEIVER))
        assertNull(WebRtcSignal.parse(offer().put("kind", "unknown"), session, Role.RECEIVER))
    }

    @Test fun iceRoundTripsAndRejectsInvalidFields() {
        assertNotNull(WebRtcSignal.parse(ice(), session, Role.SENDER))
        assertNotNull(WebRtcSignal.parse(ice().put("sdp_mid", JSONObject.NULL), session, Role.RECEIVER))
        for (line in listOf(-1, 17, "0", 0.5, JSONObject.NULL)) {
            assertNull(WebRtcSignal.parse(ice().put("sdp_mline_index", line), session, Role.SENDER))
        }
        assertNull(WebRtcSignal.parse(ice().put("candidate", "x".repeat(2049)), session, Role.SENDER))
        assertNull(WebRtcSignal.parse(ice().put("sdp_mid", "audio\n"), session, Role.SENDER))
    }

    @Test fun earlyIceIsBoundedDeduplicatedAndClearedAfterDrain() {
        val queue = EarlyIceCandidates(session, 2)
        val one = WebRtcSignal.Candidate(session, "audio", 0, "candidate:1")
        assertTrue(queue.offer(one))
        assertTrue(queue.offer(one))
        assertFalse(queue.offer(WebRtcSignal.Candidate(WebRtcSignal.newSessionId(), "audio", 0, "candidate:other")))
        assertTrue(queue.offer(WebRtcSignal.Candidate(session, "audio", 0, "candidate:2")))
        assertFalse(queue.offer(WebRtcSignal.Candidate(session, "audio", 0, "candidate:3")))
        assertEquals(listOf("candidate:1", "candidate:2"), queue.drain().map { it.candidate })
        assertTrue(queue.drain().isEmpty())
        assertTrue(queue.offer(one))
        queue.clear()
        assertTrue(queue.drain().isEmpty())
    }

    @Test fun lifetimeIceBudgetDeduplicatesAndDoesNotResetAfterCapacity() {
        val budget = IceCandidateBudget(2)
        val one = WebRtcSignal.Candidate(session, "audio", 0, "candidate:1")
        val two = WebRtcSignal.Candidate(session, "audio", 0, "candidate:2")
        val three = WebRtcSignal.Candidate(session, "audio", 0, "candidate:3")
        assertEquals(IceCandidateBudget.Admission.NEW, budget.admit(one))
        assertEquals(IceCandidateBudget.Admission.DUPLICATE, budget.admit(one))
        assertEquals(IceCandidateBudget.Admission.NEW, budget.admit(two))
        repeat(10) { assertEquals(IceCandidateBudget.Admission.OVERFLOW, budget.admit(three)) }
        assertEquals(IceCandidateBudget.Admission.DUPLICATE, budget.admit(two))
    }
}
