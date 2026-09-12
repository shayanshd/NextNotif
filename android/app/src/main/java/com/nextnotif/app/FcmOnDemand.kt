package com.nextnotif.app

import android.content.Context
import android.os.Build
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** Battery-saving receiver transport: FCM wake + one short HTTPS queue drain. */
object FcmOnDemand {
    const val TRANSPORT = "fcm"
    private const val TAG = "FcmOnDemand"
    private const val INPUT_CODE = "code"
    private val jsonType = "application/json".toMediaType()
    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .build()

    fun enqueueAll(context: Context) {
        if (!SessionStore.load(context).relayEnabled) return
        SessionStore.load(context).pairings
            .filter { it.enabled && it.role == Role.RECEIVER && it.isFcmOnDemand }
            .forEach { enqueueFresh(context, it.code) }
    }

    fun enqueue(context: Context, code: String) {
        enqueue(context, code, ExistingWorkPolicy.APPEND_OR_REPLACE)
    }

    /** User/app lifecycle actions must not sit behind an obsolete retry chain. */
    fun enqueueFresh(context: Context, code: String) {
        enqueue(context, code, ExistingWorkPolicy.REPLACE)
    }

    private fun enqueue(context: Context, code: String, policy: ExistingWorkPolicy) {
        if (!SessionStore.load(context).relayEnabled) return
        AppState.setConnState(code, AppState.ConnState.ON_DEMAND)
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()
        val request = OneTimeWorkRequestBuilder<FcmSyncWorker>()
            .setInputData(workDataOf(INPUT_CODE to code))
            .setConstraints(constraints)
            .build()
        // A push that lands during an active drain appends one more pass, so an
        // event queued just after the first drain snapshot is not stranded.
        WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
            "nextnotif-fcm-$code",
            policy,
            request,
        )
    }

    fun cancel(context: Context, code: String) {
        WorkManager.getInstance(context.applicationContext).cancelUniqueWork("nextnotif-fcm-$code")
    }

    /** Returns true only when this push belongs to an enabled on-demand receiver. */
    fun handlePush(context: Context, values: Map<String, String>): Boolean {
        if (!SessionStore.load(context).relayEnabled) return false
        val code = values["code"] ?: return false
        val pairing = SessionStore.load(context).pairings.firstOrNull {
            it.code == code && it.enabled && it.role == Role.RECEIVER && it.isFcmOnDemand
        } ?: return false
        // FCM is deliberately wake-only: SMS bodies, caller identity, and test
        // contents are fetched from the authenticated HTTPS queue below. Older
        // relays may still include those fields; ignore them so a spoofed push
        // cannot render a notification or poison event-id de-duplication.
        enqueue(context, pairing.code)
        return true
    }

    internal suspend fun sync(context: Context, code: String): Boolean = withContext(Dispatchers.IO) {
        if (!SessionStore.load(context).relayEnabled) return@withContext true
        val pairing = SessionStore.load(context).pairings.firstOrNull {
            it.code == code && it.enabled && it.role == Role.RECEIVER && it.isFcmOnDemand
        } ?: return@withContext true
        val fcmToken = SessionStore.fcmToken(context) ?: return@withContext false
        val registeredToken = register(pairing, fcmToken) ?: return@withContext false
        SessionStore.updateDeviceToken(context, pairing.code, registeredToken)
        val current = pairing.copy(deviceToken = registeredToken)
        val events = fetch(current) ?: return@withContext false
        val acknowledged = JSONArray()
        for (i in 0 until events.length()) {
            val event = events.optJSONObject(i) ?: return@withContext false
            val eventId = event.optString("event_id").takeIf { it.isNotBlank() }
                ?: return@withContext false
            val data = event.optJSONObject("data") ?: JSONObject()
            IncomingEventHandler.handle(
                context,
                event.optString("type", "unknown"),
                data,
                pairing.code,
                eventId,
            )
            acknowledged.put(eventId)
        }
        if (acknowledged.length() > 0 && !acknowledge(current, acknowledged)) return@withContext false
        AppState.setPairingError(pairing.code, null)
        AppState.setConnState(pairing.code, AppState.ConnState.ON_DEMAND)
        Log.i(TAG, "on-demand sync complete code=${pairing.code} events=${events.length()}")
        true
    }

    private fun register(pairing: PairingInfo, fcmToken: String): String? {
        val body = JSONObject().apply {
            put("fcm_token", fcmToken)
            pairing.deviceToken?.let { put("device_token", it) }
            put("device_name", deviceName())
        }
        val request = Request.Builder()
            .url("${httpBase(pairing.server)}/fcm-register")
            .header("X-NextNotif-Code", pairing.code)
            .post(body.toString().toRequestBody(jsonType))
            .build()
        return runCatching {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) error("register HTTP ${response.code}")
                JSONObject(response.body?.string().orEmpty()).optString("device_token").ifBlank { null }
            }
        }.onFailure {
            Log.w(TAG, "register failed code=${pairing.code}: ${it.message}")
            AppState.setPairingError(pairing.code, "FCM registration failed: ${it.message}")
        }.getOrNull()
    }

    private fun fetch(pairing: PairingInfo): JSONArray? {
        val token = pairing.deviceToken ?: return null
        val request = Request.Builder()
            .url("${httpBase(pairing.server)}/fetch")
            .header("X-NextNotif-Code", pairing.code)
            .header("X-NextNotif-Token", token)
            .post(ByteArray(0).toRequestBody(null))
            .build()
        return runCatching {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) error("fetch HTTP ${response.code}")
                JSONObject(response.body?.string().orEmpty()).optJSONArray("events") ?: JSONArray()
            }
        }.onFailure {
            Log.w(TAG, "fetch failed code=${pairing.code}: ${it.message}")
            AppState.setPairingError(pairing.code, "Queue sync failed: ${it.message}")
        }.getOrNull()
    }

    private fun acknowledge(pairing: PairingInfo, eventIds: JSONArray): Boolean {
        val token = pairing.deviceToken ?: return false
        val body = JSONObject().put("event_ids", eventIds).toString()
        val request = Request.Builder()
            .url("${httpBase(pairing.server)}/ack")
            .header("X-NextNotif-Code", pairing.code)
            .header("X-NextNotif-Token", token)
            .post(body.toRequestBody(jsonType))
            .build()
        return runCatching {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) error("ack HTTP ${response.code}")
                true
            }
        }.onFailure {
            AppState.setPairingError(pairing.code, "Queue acknowledgment failed: ${it.message}")
        }.getOrDefault(false)
    }

    private fun httpBase(server: String): String = server.trimEnd('/')
        .replaceFirst("ws://", "http://")
        .replaceFirst("wss://", "https://")

    private fun deviceName(): String = listOf(Build.MANUFACTURER, Build.MODEL)
        .filter { it.isNotBlank() }
        .joinToString(" ")
        .ifBlank { "Android" }
        .take(64)

    internal fun inputCode(params: WorkerParameters): String? = params.inputData.getString(INPUT_CODE)
}

class FcmSyncWorker(
    context: Context,
    private val params: WorkerParameters,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val code = FcmOnDemand.inputCode(params) ?: return Result.failure()
        return try {
            if (FcmOnDemand.sync(applicationContext, code)) Result.success() else Result.retry()
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            AppState.setPairingError(code, "Message sync interrupted; retrying safely")
            Result.retry()
        }
    }
}
