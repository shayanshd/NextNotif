package com.nextnotif.app

/** Require exactly one audio section and no one-way/inactive direction. */
internal object WebRtcMediaDirection {
    fun bidirectional(sdp: String): Boolean {
        val lines = sdp.lineSequence().map(String::trim).toList()
        if (lines.count { it.startsWith("m=") } != 1 ||
            lines.none { it.startsWith("m=audio ") }) return false
        val media = lines.single { it.startsWith("m=audio ") }.split(Regex("\\s+"))
        val port = media.getOrNull(1)?.substringBefore('/')?.toIntOrNull() ?: return false
        if (port !in 1..65_535) return false // Port zero rejects the media section.
        return lines.filter { it in setOf("a=sendrecv", "a=sendonly", "a=recvonly", "a=inactive") }
            .all { it == "a=sendrecv" } // Absent direction defaults to sendrecv.
    }
}
