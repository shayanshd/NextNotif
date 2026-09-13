package com.nextnotif.app

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import org.webrtc.PeerConnection
import java.util.concurrent.TimeUnit

/** Blocking, short-lived HTTPS request. Call only on an IO worker, never UI. */
internal object WebRtcIceClient {
    private val client = OkHttpClient.Builder().callTimeout(9, TimeUnit.SECONDS)
        .followRedirects(false).followSslRedirects(false).build()

    fun fetch(pairing: PairingInfo): List<PeerConnection.IceServer> {
        val base = pairing.server.trim().replaceFirst("wss://", "https://").trimEnd('/')
        check(base.startsWith("https://")) { "TURN requires an HTTPS relay" }
        val token = checkNotNull(pairing.deviceToken) { "Pairing authentication is missing" }
        val request = Request.Builder().url("$base/ice")
            .header("X-NextNotif-Code", pairing.code).header("Authorization", "Bearer $token")
            .post("".toRequestBody("application/json".toMediaType())).build()
        return client.newCall(request).execute().use { response ->
            check(response.isSuccessful) { "TURN credential request failed (HTTP ${response.code})" }
            val body = checkNotNull(response.body)
            val source = body.source()
            check(!source.request(65537)) { "TURN response is too large" }
            val data = JSONObject(source.readUtf8())
            check(data.getLong("expiresAtMs") > System.currentTimeMillis() + 60000) { "TURN credentials expired" }
            parse(data.getJSONArray("iceServers"))
        }
    }

    fun parse(array: JSONArray): List<PeerConnection.IceServer> {
        check(array.length() in 1..8) { "Invalid TURN configuration" }
        var hasRelay = false
        val servers = (0 until array.length()).map { index ->
            val server = array.getJSONObject(index)
            val raw = server.get("urls")
            val urls = if (raw is JSONArray) (0 until raw.length()).map { raw.getString(it) } else listOf(raw as String)
            check(urls.size in 1..12 && urls.all {
                it.length <= 512 && Regex("^(stun|turn|turns):[a-zA-Z0-9.-]+:[0-9]+(\\?transport=(udp|tcp))?$").matches(it)
            }) { "Invalid TURN URLs" }
            val builder = PeerConnection.IceServer.builder(urls)
            if (urls.any { it.startsWith("turn:") || it.startsWith("turns:") }) {
                val username = server.getString("username")
                val credential = server.getString("credential")
                check(username.length in 1..512 && credential.length in 1..1024) { "Invalid TURN authentication" }
                builder.setUsername(username).setPassword(credential)
                hasRelay = true
            }
            builder.createIceServer()
        }
        check(hasRelay) { "TURN relay is missing" }
        return servers
    }
}
