package com.nextnotif.app

import java.io.IOException
import java.util.concurrent.TimeUnit
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

internal data class SecurePairingGrant(
    val code: String,
    val deviceId: String,
    val deviceToken: String,
    val inviteRole: Role?,
    val inviteSecret: String?,
    val inviteExpiresAt: Long?,
)

/** Creates or accepts a single-use pairing invite over the relay's HTTPS API. */
internal object SecurePairingClient {
    private val jsonType = "application/json".toMediaType()
    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .build()

    private fun base(server: String): String {
        val normalized = server.trim().trimEnd('/')
            .replaceFirst("wss://", "https://")
            .replaceFirst("ws://", "http://")
        if (!normalized.startsWith("https://") &&
            !(BuildConfig.DEBUG && normalized.startsWith("http://"))) {
            throw IOException("Secure pairing requires an HTTPS relay")
        }
        return normalized
    }

    private fun execute(request: Request): JSONObject = client.newCall(request).execute().use { response ->
        if (response.code == 429) {
            val waitSeconds = response.header("Retry-After")?.toIntOrNull()?.coerceIn(1, 3600) ?: 60
            throw IOException("Too many pairing attempts. Wait $waitSeconds seconds and try again.")
        }
        if (!response.isSuccessful) throw IOException("Pairing request failed (HTTP ${response.code})")
        val body = response.body ?: throw IOException("Empty pairing response")
        val source = body.source()
        if (source.request(65_537)) throw IOException("Pairing response too large")
        JSONObject(source.readUtf8())
    }

    internal fun parseGrant(body: JSONObject, invite: Boolean): SecurePairingGrant {
        val code = body.getString("code")
        val deviceId = body.getString("device_id")
        val token = body.getString("device_token")
        require(code.matches(Regex("[0-9]{6}")))
        require(deviceId.matches(Regex("[A-Za-z0-9_-]{22}")))
        require(token.matches(Regex("[A-Za-z0-9_-]{43}")))
        if (!invite) return SecurePairingGrant(code, deviceId, token, null, null, null)
        val details = body.getJSONObject("invite")
        val role = Role.valueOf(details.getString("role").uppercase())
        val secret = details.getString("secret")
        val expiry = details.getLong("expires_at")
        require(secret.matches(Regex("[A-Za-z0-9_-]{43}")) && expiry > 0)
        return SecurePairingGrant(code, deviceId, token, role, secret, expiry)
    }

    fun create(server: String, role: Role): SecurePairingGrant {
        val request = Request.Builder().url("${base(server)}/pair/secure-create")
            .post(JSONObject().put("role", role.name.lowercase()).toString().toRequestBody(jsonType))
            .build()
        return parseGrant(execute(request), invite = true)
    }

    fun join(server: String, code: String, role: Role, secret: String): SecurePairingGrant {
        require(code.matches(Regex("[0-9]{6}")))
        require(secret.matches(Regex("[A-Za-z0-9_-]{43}")))
        val request = Request.Builder().url("${base(server)}/pair/secure-join")
            .header("X-NextNotif-Code", code)
            .post(JSONObject().put("role", role.name.lowercase()).put("secret", secret)
                .toString().toRequestBody(jsonType))
            .build()
        return parseGrant(execute(request).put("code", code), invite = false)
    }

    fun delete(pairing: PairingInfo) {
        require(pairing.ownsPairing && pairing.deviceId != null && pairing.deviceToken != null)
        val request = Request.Builder().url("${base(pairing.server)}/pair/secure-delete")
            .header("X-NextNotif-Code", pairing.code)
            .pairingAuth(pairing)
            .post("{}".toRequestBody(jsonType))
            .build()
        if (execute(request).optBoolean("deleted") != true) {
            throw IOException("Pairing deletion was not confirmed by the relay")
        }
    }
}
