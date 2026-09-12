package com.nextnotif.app

import android.content.Context
import android.util.Log
import java.util.concurrent.TimeUnit
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

internal data class SenderUplinkResult(
    val accepted: Boolean,
    val deliveredDirectly: Boolean? = null,
    val queuedCount: Int? = null,
    val failure: String? = null,
)

/**
 * Short-lived uplink for the sender role. Instead of holding a websocket, the
 * sender POSTs each event to the relay the moment it happens and lets the
 * connection die. The relay holds events for the receiver until it next
 * connects (catch-up), so the sender needs no persistent channel at all:
 * no socket to be zombied by a middlebox, no ping traffic, and the radio
 * sleeps between events.
 *
 * Reuses [DohFirstDns]: a one-shot POST must survive poisoned resolvers too.
 * The connection pool keeps TCP+TLS warm across an outbox-flush burst, so
 * only the first POST pays the handshake cost.
 */
class SenderUplink(
    context: Context,
    server: String,
    private val code: String,
    private val deviceToken: String? = null,
) {
    private val base: String = when {
        server.startsWith("wss://") -> "https://" + server.substring("wss://".length)
        server.startsWith("ws://") -> "http://" + server.substring("ws://".length)
        server.startsWith("http://") || server.startsWith("https://") -> server
        else -> "https://$server"
    }.trimEnd('/')

    private val client: OkHttpClient = OkHttpClient.Builder()
        // A sinkhole stalls TLS; a real edge answers in well under 5 s, so a
        // short connect budget makes bad addresses fail over fast.
        .connectTimeout(5, TimeUnit.SECONDS)
        .writeTimeout(5, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .dns(DohFirstDns(context.applicationContext))
        .build()

    /** POSTs one event. Returns true when the relay accepted it (delivered or queued). */
    fun send(type: String, data: JSONObject): Boolean = sendDetailed(type, data).accepted

    /** Same uplink with delivery detail for explicit, user-initiated diagnostics. */
    internal fun sendDetailed(type: String, data: JSONObject): SenderUplinkResult {
        val payload = JSONObject().apply {
            put("type", type)
            put("data", data)
            deviceToken?.let { put("device_token", it) }
        }
        val req = Request.Builder()
            .url("$base/send")
            .header("X-NextNotif-Code", code)
            .post(payload.toString().toRequestBody(JSON_MEDIA))
            .build()
        return try {
            client.newCall(req).execute().use { resp ->
                Log.i(TAG, "send $type http=${resp.code}")
                val body = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    SenderUplinkResult(false, failure = "Relay returned HTTP ${resp.code}")
                } else {
                    parseAcceptedResponse(body)
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "send $type failed: ${t::class.simpleName}: ${t.message}", t)
            SenderUplinkResult(false, failure = t.message ?: t::class.simpleName ?: "Network error")
        }
    }

    companion object {
        private const val TAG = "RelayUplink"
        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()

        internal fun parseAcceptedResponse(body: String): SenderUplinkResult {
            val json = runCatching { JSONObject(body) }.getOrNull()
            return SenderUplinkResult(
                accepted = true,
                deliveredDirectly = json?.takeIf { it.has("delivered") }?.optBoolean("delivered"),
                queuedCount = json?.takeIf { it.has("queued") }?.optInt("queued")?.coerceAtLeast(0),
            )
        }
    }
}
