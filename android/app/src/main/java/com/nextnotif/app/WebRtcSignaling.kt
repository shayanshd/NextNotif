package com.nextnotif.app

import org.json.JSONObject
import java.util.UUID

/** Signaling only. Audio uses native WebRTC tracks, never this envelope. */
internal sealed class WebRtcSignal(val sessionId: String) {
    class Description(sessionId: String, val offer: Boolean, val sdp: String) : WebRtcSignal(sessionId)
    class Candidate(sessionId: String, val mid: String?, val line: Int, val candidate: String) : WebRtcSignal(sessionId)

    fun toJson(): JSONObject = JSONObject().put("session_id", sessionId).apply {
        when (val signal = this@WebRtcSignal) {
            is Description -> put("kind", if (signal.offer) "offer" else "answer").put("sdp", signal.sdp)
            is Candidate -> put("kind", "ice").put("sdp_mid", signal.mid ?: JSONObject.NULL)
                .put("sdp_mline_index", signal.line).put("candidate", signal.candidate)
        }
    }

    companion object {
        const val MAX_SDP_BYTES = 64 * 1024
        const val MAX_CANDIDATE_BYTES = 2048

        fun newSessionId(): String = UUID.randomUUID().toString()

        fun parse(data: JSONObject, expectedSessionId: String, remoteRole: Role): WebRtcSignal? {
            val sessionId = data.opt("session_id") as? String ?: return null
            if (sessionId != expectedSessionId || !canonicalSessionId(sessionId)) return null
            return when (data.opt("kind") as? String) {
                // The receiver initiates negotiation after its explicit Answer;
                // the rooted sender responds. Avoid simultaneous-offer glare.
                "offer" -> if (remoteRole == Role.RECEIVER) description(data, sessionId, true) else null
                "answer" -> if (remoteRole == Role.SENDER) description(data, sessionId, false) else null
                "ice" -> {
                    val rawMid = data.opt("sdp_mid")
                    val mid = if (rawMid == null || rawMid == JSONObject.NULL) null else rawMid as? String ?: return null
                    if (mid != null && (mid.length > 128 || mid.any(Char::isISOControl))) return null
                    val rawLine = data.opt("sdp_mline_index")
                    val line = when (rawLine) {
                        is Int -> rawLine
                        is Long -> rawLine.takeIf { it in 0..16 }?.toInt() ?: return null
                        else -> return null
                    }
                    if (line !in 0..16) return null
                    val candidate = data.opt("candidate") as? String ?: return null
                    if (!boundedText(candidate, MAX_CANDIDATE_BYTES) || !candidate.startsWith("candidate:")) return null
                    Candidate(sessionId, mid, line, candidate)
                }
                else -> null
            }
        }

        private fun description(data: JSONObject, sessionId: String, offer: Boolean): Description? {
            val sdp = data.opt("sdp") as? String ?: return null
            if (!boundedText(sdp, MAX_SDP_BYTES) || !sdp.startsWith("v=0\r\n")) return null
            return Description(sessionId, offer, sdp)
        }

        private fun boundedText(text: String, bytes: Int): Boolean = text.isNotBlank() &&
            text.length <= bytes && text.toByteArray(Charsets.UTF_8).size <= bytes && '\u0000' !in text

        private fun canonicalSessionId(value: String): Boolean = value.length == 36 &&
            runCatching { UUID.fromString(value).toString() == value }.getOrDefault(false)
    }
}

/** Early ICE is bounded and session-scoped; overflow is a negotiation failure. */
internal class EarlyIceCandidates(private val sessionId: String, private val capacity: Int = 64) {
    init { require(capacity in 1..64) }
    private val pending = LinkedHashMap<Triple<String?, Int, String>, WebRtcSignal.Candidate>()

    @Synchronized fun offer(candidate: WebRtcSignal.Candidate): Boolean {
        if (candidate.sessionId != sessionId) return false
        val key = Triple(candidate.mid, candidate.line, candidate.candidate)
        if (pending.containsKey(key)) return true
        if (pending.size == capacity) return false
        pending[key] = candidate
        return true
    }

    @Synchronized fun drain(): List<WebRtcSignal.Candidate> = pending.values.toList().also { pending.clear() }
    @Synchronized fun clear() = pending.clear()
}

/** Bounds native ICE work for the entire negotiation, including after SDP is set. */
internal class IceCandidateBudget(private val capacity: Int = 128) {
    init { require(capacity in 1..128) }
    enum class Admission { NEW, DUPLICATE, OVERFLOW }
    private val seen = HashSet<Triple<String?, Int, String>>()

    fun admit(candidate: WebRtcSignal.Candidate): Admission {
        val key = Triple(candidate.mid, candidate.line, candidate.candidate)
        if (key in seen) return Admission.DUPLICATE
        if (seen.size == capacity) return Admission.OVERFLOW
        seen.add(key)
        return Admission.NEW
    }
}
