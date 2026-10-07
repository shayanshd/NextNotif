package com.nextnotif.app

internal data class SecureInvite(
    val server: String,
    val code: String,
    val role: Role,
    val secret: String,
)

/** One copyable invite containing the relay, display code, expected role, and secret. */
internal object SecureInviteText {
    private const val PREFIX = "NN1"
    private val codePattern = Regex("[0-9]{6}")
    private val secretPattern = Regex("[A-Za-z0-9_-]{43}")

    fun encode(server: String, code: String, role: Role, secret: String): String {
        val cleanServer = server.trim().trimEnd('/')
        require(validServer(cleanServer) && codePattern.matches(code) && secretPattern.matches(secret))
        return "$PREFIX|$cleanServer|$code|${role.name.lowercase()}|$secret"
    }

    fun parse(raw: String): SecureInvite? {
        if (raw.length > 512) return null
        val fields = raw.trim().split('|')
        if (fields.size != 5 || fields[0] != PREFIX || !validServer(fields[1]) ||
            !codePattern.matches(fields[2]) || !secretPattern.matches(fields[4])) return null
        val role = runCatching { Role.valueOf(fields[3].uppercase()) }.getOrNull() ?: return null
        return SecureInvite(fields[1], fields[2], role, fields[4])
    }

    fun validServer(server: String): Boolean {
        if (server.contains('|')) return false
        val uri = runCatching { java.net.URI(server) }.getOrNull() ?: return false
        return uri.scheme in setOf("wss", "ws") && !uri.host.isNullOrBlank() &&
            uri.userInfo == null && uri.rawQuery == null && uri.rawFragment == null &&
            (uri.rawPath.isNullOrEmpty() || uri.rawPath == "/")
    }
}
