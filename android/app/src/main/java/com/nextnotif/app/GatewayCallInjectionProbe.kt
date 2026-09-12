package com.nextnotif.app

import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.PI
import kotlin.math.sin

data class CallInjectionResult(
    val success: Boolean,
    val parameterResult: Int? = null,
    val framesWritten: Int = 0,
    val detail: String? = null,
)

object GatewayCallInjectionProbe {
    private const val SAMPLE_RATE = 8_000
    private const val TONE_HZ = 1_000.0
    private const val TONE_MS = 350
    private const val SILENCE_MS = 350
    private const val BEEP_COUNT = 4
    private const val AMPLITUDE = 2_000

    suspend fun run(manageForwarding: Boolean = true): CallInjectionResult = withContext(Dispatchers.IO) {
        var track: AudioTrack? = null
        var forwardingEnabled = false
        var parameterResult: Int? = null
        var framesWritten = 0
        try {
            if (manageForwarding) {
                parameterResult = setAudioParameters("call_forwarding=true")
                forwardingEnabled = parameterResult == 0
                if (!forwardingEnabled) {
                    return@withContext CallInjectionResult(
                        success = false,
                        parameterResult = parameterResult,
                        detail = "Samsung HAL rejected call_forwarding=true",
                    )
                }
            }

            val minBytes = AudioTrack.getMinBufferSize(
                SAMPLE_RATE,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
            )
            if (minBytes <= 0) {
                return@withContext CallInjectionResult(false, parameterResult, detail = "minBuffer=$minBytes")
            }
            track = AudioTrack(
                AudioManager.STREAM_VOICE_CALL,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                maxOf(minBytes, SAMPLE_RATE),
                AudioTrack.MODE_STREAM,
            )
            if (track.state != AudioTrack.STATE_INITIALIZED) {
                return@withContext CallInjectionResult(false, parameterResult, detail = "AudioTrack not initialized")
            }

            val tone = ShortArray(SAMPLE_RATE * TONE_MS / 1_000) { index ->
                (AMPLITUDE * sin(2.0 * PI * TONE_HZ * index / SAMPLE_RATE)).toInt().toShort()
            }
            val silence = ShortArray(SAMPLE_RATE * SILENCE_MS / 1_000)
            track.play()
            repeat(BEEP_COUNT) {
                framesWritten += writeFully(track, tone)
                framesWritten += writeFully(track, silence)
            }
            track.stop()
            CallInjectionResult(true, parameterResult, framesWritten)
        } catch (error: Throwable) {
            CallInjectionResult(
                success = false,
                parameterResult = parameterResult,
                framesWritten = framesWritten,
                detail = error.message?.take(180) ?: error.javaClass.simpleName,
            )
        } finally {
            runCatching { track?.release() }
            if (forwardingEnabled) runCatching { setAudioParameters("call_forwarding=false") }
        }
    }

    private fun writeFully(track: AudioTrack, samples: ShortArray): Int {
        var written = 0
        while (written < samples.size) {
            val count = track.write(samples, written, samples.size - written)
            if (count <= 0) throw IllegalStateException("AudioTrack.write=$count")
            written += count
        }
        return written
    }

    private fun setAudioParameters(value: String): Int {
        val audioSystem = Class.forName("android.media.AudioSystem")
        val method = audioSystem.getDeclaredMethod("setParameters", String::class.java)
        return method.invoke(null, value) as Int
    }
}
