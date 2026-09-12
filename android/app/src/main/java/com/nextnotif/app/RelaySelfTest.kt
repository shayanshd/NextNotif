package com.nextnotif.app

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** User-initiated, non-call event that verifies the real HTTPS -> queue -> FCM path. */
object RelaySelfTest {
    const val EVENT_TYPE = "relay_test"
    const val DEFAULT_MESSAGE = "Test received — this pairing can reach your phone."
    private const val MAX_MESSAGE_LENGTH = 160

    sealed interface Result {
        data class Success(val queuedForReceiver: Boolean) : Result
        data class Failure(val message: String) : Result
    }

    fun canSend(pairing: PairingInfo): Boolean =
        pairing.enabled && pairing.role == Role.SENDER && pairing.isFcmOnDemand

    fun payload(timestamp: Long): JSONObject = JSONObject().apply {
        put("message", DEFAULT_MESSAGE)
        put("ts", timestamp)
    }

    internal fun receivedMessage(data: JSONObject): String =
        data.optString("message")
            .trim()
            .take(MAX_MESSAGE_LENGTH)
            .ifBlank { DEFAULT_MESSAGE }

    suspend fun send(context: Context, pairing: PairingInfo): Result {
        if (!canSend(pairing)) return Result.Failure("This test requires an enabled FCM sender pairing.")
        return withContext(Dispatchers.IO) {
            val response = SenderUplink(
                context.applicationContext,
                pairing.server,
                pairing.code,
                pairing.deviceToken,
            ).sendDetailed(EVENT_TYPE, payload(System.currentTimeMillis()))
            if (response.accepted) {
                Result.Success(queuedForReceiver = response.deliveredDirectly == false)
            } else {
                Result.Failure(response.failure ?: "The relay did not accept the test.")
            }
        }
    }
}
