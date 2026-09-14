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
import java.util.concurrent.TimeUnit

/** Durable HTTP command path for both on-demand and WebSocket pairings. FCM carries wake signals only. */
internal object SmsRelay {
    private val client = OkHttpClient.Builder().connectTimeout(8, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS).build()
    private val json = "application/json".toMediaType()
    fun enqueue(context: Context, code: String) {
        val work = OneTimeWorkRequestBuilder<SmsSyncWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.LINEAR, 15, TimeUnit.SECONDS)
            .setInputData(workDataOf("code" to code)).build()
        WorkManager.getInstance(context).enqueueUniqueWork("sms-sync-$code", ExistingWorkPolicy.REPLACE, work)
    }
    fun enqueueAll(context: Context) {
        val session = SessionStore.load(context)
        if (!session.relayEnabled) return
        session.pairings.filter { it.enabled && !it.isFirebase }.forEach { enqueue(context, it.code) }
    }
    fun handlePush(context: Context, data: Map<String, String>) {
        val code = data["code"] ?: return
        val session = SessionStore.load(context)
        if (session.relayEnabled && session.pairings.any { it.code == code && it.enabled && !it.isFirebase }) enqueue(context, code)
    }
    private fun post(pairing: PairingInfo, path: String, data: JSONObject): JSONObject {
        val base = pairing.server.trimEnd('/').replaceFirst("ws://", "http://").replaceFirst("wss://", "https://")
        val request = Request.Builder().url("$base/$path").header("X-NextNotif-Code", pairing.code)
            .header("X-NextNotif-Role", pairing.role.name.lowercase())
            .apply { pairing.deviceToken?.let { header("X-NextNotif-Token", it) } }
            .post(data.toString().toRequestBody(json)).build()
        return client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw SmsHttpError(response.code)
            JSONObject(response.body?.string().orEmpty())
        }
    }
    private fun register(context: Context, pairing: PairingInfo): PairingInfo {
        val fcm = SessionStore.fcmToken(context)
        if (fcm == null && pairing.isFcmOnDemand) throw IllegalStateException("Waiting for notification registration")
        val registration = JSONObject().put("fcm_token", fcm).put("device_token", pairing.deviceToken)
            .put("device_name", android.os.Build.MODEL).put("delivery_mode", if (pairing.isFcmOnDemand) "fcm" else "ws")
        val token = post(pairing, "fcm-register", registration).getString("device_token")
        SessionStore.updateDeviceToken(context, pairing.code, token)
        return pairing.copy(deviceToken = token)
    }
    suspend fun simOptions(context: Context, pairing: PairingInfo, requestRefresh: Boolean): JSONObject? = withContext(Dispatchers.IO) {
        val session = SessionStore.load(context)
        require(session.relayEnabled)
        val saved = session.pairings.first { it.code == pairing.code && it.server == pairing.server && it.enabled && it.role == Role.RECEIVER && !it.isFirebase }
        val current = register(context, saved)
        post(current, "sms-status", JSONObject().put("request_sims", requestRefresh)).optJSONObject("sim_options")
    }
    suspend fun sync(context: Context, code: String): Boolean = withContext(Dispatchers.IO) {
        val session = SessionStore.load(context)
        if (!session.relayEnabled) return@withContext true
        val pairing = session.pairings.firstOrNull { it.code == code && it.enabled && !it.isFirebase } ?: return@withContext true
        val current = register(context, pairing)
        val store = SmsOutbox.store(context)
        fun own(record: JSONObject) = record.optString("code") == code && record.optString("authority") == pairing.server && record.optString("local_role") == pairing.role.name
        if (pairing.role == Role.RECEIVER) {
            for (record in store.all().filter { own(it) && it.optString("status") in setOf("pending", "queued") }) {
                if (!validSmsRequest(record)) {
                    record.put("status", "expired").put("detail", "Request expired before reaching the sender.")
                    SmsOutbox.update(context, record)
                    continue
                }
                try {
                    val accepted = post(current, "sms-submit", record).getJSONObject("command")
                    store.mergeStatus(record.getString("id"), accepted)
                    SmsOutbox.refresh(context)
                    continue
                } catch (error: SmsHttpError) {
                    if (error.status !in setOf(400, 404, 409)) throw error
                    record.put("status", "failed").put("detail", if (error.status == 404)
                        "Update the relay server to enable SMS sending." else "Relay rejected this SMS request.")
                }
                SmsOutbox.update(context, record)
            }
            val statuses = post(current, "sms-status", JSONObject()).getJSONArray("commands")
            for (i in 0 until statuses.length()) {
                val remote = statuses.getJSONObject(i)
                val local = store.get(remote.getString("id")) ?: continue
                if (!own(local) || local.optString("to") != remote.optString("to") || local.optString("body") != remote.optString("body") ||
                    smsSubscription(local) != smsSubscription(remote)) continue
                store.mergeStatus(local.getString("id"), remote)
                SmsOutbox.refresh(context)
            }
        } else {
            // Callbacks may arrive after the command was removed from the fetch set.
            for (record in store.all().filter { own(it) && it.optString("reported_status") != it.optString("status") }) {
                post(current, "sms-result", record)
                store.mutate(record.getString("id")) { it.put("reported_status", record.optString("status")) }
                SmsOutbox.refresh(context)
            }
            val commands = post(current, "sms-fetch", JSONObject().put("sim_options", SmsSender.simOptions(context))).getJSONArray("commands")
            for (i in 0 until commands.length()) {
                val command = commands.getJSONObject(i)
                if (!validSmsRequest(command) && store.get(command.optString("id")) == null) continue
                val result = SmsSender.process(context, pairing, command)
                post(current, "sms-result", result)
                // Do not write a stale snapshot over a concurrently arriving carrier callback.
            }
        }
        val outstanding = store.all().any { own(it) && it.optString("status") in setOf("pending", "queued", "sending") }
        !outstanding
    }
}

internal class SmsHttpError(val status: Int) : Exception("SMS relay HTTP $status")

class SmsSyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val code = inputData.getString("code") ?: return Result.failure()
        return try { if (SmsRelay.sync(applicationContext, code)) Result.success() else Result.retry() }
        catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
        catch (_: Exception) { Result.retry() }
    }
}
