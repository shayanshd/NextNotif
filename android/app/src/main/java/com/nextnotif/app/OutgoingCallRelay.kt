package com.nextnotif.app

import android.content.Context
import androidx.work.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.TimeUnit

internal object OutgoingCallRelay {
    private val client = OkHttpClient.Builder().connectTimeout(8, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS).build()
    private val json = "application/json".toMediaType()

    private fun post(pairing: PairingInfo, path: String, body: JSONObject): JSONObject {
        val base = pairing.server.trimEnd('/').replaceFirst("ws://", "http://").replaceFirst("wss://", "https://")
        val request = Request.Builder().url("$base/$path").header("X-NextNotif-Code", pairing.code)
            .header("X-NextNotif-Role", pairing.role.name.lowercase())
            .header("X-NextNotif-Token", requireNotNull(pairing.deviceToken))
            .post(body.toString().toRequestBody(json)).build()
        return client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("Call relay HTTP ${response.code}")
            JSONObject(response.body?.string().orEmpty())
        }
    }

    fun enqueue(context: Context, code: String) {
        val work = OneTimeWorkRequestBuilder<OutgoingCallSyncWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.LINEAR, 15, TimeUnit.SECONDS)
            .setInputData(workDataOf("code" to code)).build()
        WorkManager.getInstance(context).enqueueUniqueWork("call-sync-$code", ExistingWorkPolicy.REPLACE, work)
    }

    fun handlePush(context: Context, data: Map<String, String>) {
        data["code"]?.let { enqueue(context, it) }
    }

    suspend fun submit(context: Context, pairing: PairingInfo, number: String, subscriptionId: Int?): String = withContext(Dispatchers.IO) {
        require(pairing.role == Role.RECEIVER && pairing.enabled && !pairing.isFirebase && pairing.deviceToken != null)
        val id = UUID.randomUUID().toString()
        val command = JSONObject().put("id", id).put("to", number)
            .put("subscription_id", subscriptionId ?: JSONObject.NULL).put("created_at", System.currentTimeMillis())
        post(pairing, "call-submit", command)
        AppState.updateCall(pairing.code, AppState.CallPhase.CONNECTING, number = number)
        RelayForegroundService.Controller.beginOutgoingCall(context, pairing.code, id, number)
        id
    }

    suspend fun sync(context: Context, code: String): Boolean = withContext(Dispatchers.IO) {
        val pairing = SessionStore.load(context).pairings.firstOrNull { it.code == code && it.enabled && !it.isFirebase }
            ?: return@withContext true
        if (pairing.deviceToken == null) return@withContext false
        if (pairing.role == Role.SENDER) {
            val commands = post(pairing, "call-fetch", JSONObject()).getJSONArray("commands")
            for (i in 0 until commands.length()) {
                val c = commands.getJSONObject(i)
                RelayForegroundService.Controller.placeOutgoingCall(context, pairing.code, c.getString("id"),
                    c.getString("to"), smsSubscription(c))
            }
        } else {
            val commands = post(pairing, "call-status", JSONObject()).getJSONArray("commands")
            for (i in 0 until commands.length()) {
                val c = commands.getJSONObject(i)
                when (c.optString("status")) {
                    "failed", "expired" -> AppState.finishCall(code, c.optString("detail", "Sender could not place the call"))
                    "ended" -> AppState.finishCall(code)
                }
            }
        }
        true
    }

    suspend fun report(context: Context, pairing: PairingInfo, id: String, status: String, detail: String = "") = withContext(Dispatchers.IO) {
        post(pairing, "call-result", JSONObject().put("id", id).put("status", status).put("detail", detail))
    }
}

class OutgoingCallSyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val code = inputData.getString("code") ?: return Result.failure()
        return try {
            if (OutgoingCallRelay.sync(applicationContext, code)) Result.success() else Result.retry()
        } catch (_: Exception) { Result.retry() }
    }
}
