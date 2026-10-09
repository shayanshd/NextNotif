package com.nextnotif.app

import android.content.Context
import android.telephony.TelephonyManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.resume

/** Checks the production call socket without asking the gateway to dial. */
internal object CallSignalingSelfTest {
    sealed interface Result {
        data object Success : Result
        data class Failure(val message: String) : Result
    }

    fun canRun(pairing: PairingInfo): Boolean =
        pairing.enabled && pairing.role == Role.SENDER && pairing.isFcmOnDemand && pairing.liveCallEnabled

    suspend fun run(context: Context, pairing: PairingInfo): Result = withContext(Dispatchers.IO) {
        if (!canRun(pairing)) return@withContext Result.Failure("Enable live calls on this sender pairing first.")
        val phone = context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
        if (phone.callState != TelephonyManager.CALL_STATE_IDLE) {
            return@withContext Result.Failure("Wait until the phone call ends before testing the connection.")
        }
        if (AppState.callRelay.value.phase !in setOf(
                AppState.CallPhase.IDLE, AppState.CallPhase.ENDED, AppState.CallPhase.FAILED)) {
            return@withContext Result.Failure("Wait until the relayed call ends before testing the connection.")
        }
        var socket: RelaySocket? = null
        try {
            withTimeout(10_000L) {
                suspendCancellableCoroutine<Result> { continuation ->
                    val probe = RelaySocket(
                        context.applicationContext,
                        pairing.server,
                        pairing.role,
                        pairing.code,
                        pairing.deviceToken,
                        SessionStore.fcmToken(context),
                        fcmOnDemand = true,
                        deviceId = pairing.deviceId,
                    ) { event ->
                        val result = when (event) {
                            is RelaySocket.Event.AuthOk -> Result.Success
                            is RelaySocket.Event.Failure -> Result.Failure("Call signaling transport failed.")
                            is RelaySocket.Event.Closed -> Result.Failure("Call signaling connection closed.")
                            else -> null
                        }
                        if (result != null && continuation.isActive) continuation.resume(result)
                    }
                    socket = probe
                    continuation.invokeOnCancellation { probe.close() }
                    probe.connect()
                }
            }
        } catch (_: TimeoutCancellationException) {
            Result.Failure("Call signaling did not authenticate within 10 seconds.")
        } catch (_: Exception) {
            Result.Failure("Call signaling could not start.")
        } finally {
            socket?.close()
        }
    }
}
