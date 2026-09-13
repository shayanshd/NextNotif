package com.nextnotif.app

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaRecorder
import android.media.AudioFormat
import android.os.SystemClock
import android.util.Log
import org.webrtc.audio.JavaAudioDeviceModule

/**
 * Audio-device configuration selected by the live-call service.
 * PeerConnectionFactory.initialize must precede creation.
 * The call owner still manages foreground permissions, audio mode, root mixer
 * enable/restore, and module release; this factory never changes those states.
 */
internal object WebRtcAudioDeviceFactory {
    fun create(
        context: Context,
        role: Role,
        capability: GatewayCapability,
        onError: (String) -> Unit,
    ): JavaAudioDeviceModule {
        val policy = WebRtcAudioDevicePolicy.forRole(role, capability)
        val builder = JavaAudioDeviceModule.builder(context.applicationContext)
            .setAudioSource(if (policy.gatewayDownlink) MediaRecorder.AudioSource.VOICE_DOWNLINK
                else MediaRecorder.AudioSource.VOICE_COMMUNICATION)
            .setUseStereoInput(false)
            .setUseStereoOutput(false)
            .setAudioAttributes(AudioAttributes.Builder()
                .setLegacyStreamType(AudioManager.STREAM_VOICE_CALL).build())
            .setAudioRecordErrorCallback(object : JavaAudioDeviceModule.AudioRecordErrorCallback {
                override fun onWebRtcAudioRecordInitError(errorMessage: String) = onError("WebRTC capture initialization failed")
                override fun onWebRtcAudioRecordStartError(errorCode: JavaAudioDeviceModule.AudioRecordStartErrorCode, errorMessage: String) = onError("WebRTC capture could not start")
                override fun onWebRtcAudioRecordError(errorMessage: String) = onError("WebRTC capture stopped")
            })
            .setAudioTrackErrorCallback(object : JavaAudioDeviceModule.AudioTrackErrorCallback {
                override fun onWebRtcAudioTrackInitError(errorMessage: String) = onError("WebRTC playback initialization failed")
                override fun onWebRtcAudioTrackStartError(errorCode: JavaAudioDeviceModule.AudioTrackStartErrorCode, errorMessage: String) = onError("WebRTC playback could not start")
                override fun onWebRtcAudioTrackError(errorMessage: String) = onError("WebRTC playback stopped")
            })
        if (policy.gatewayDownlink) {
            // Preserve the sample rates proven by the A520F hardware probes.
            // WebRTC handles resampling for Opus independently of device I/O.
            builder.setSampleRate(8_000)
                .setUseHardwareAcousticEchoCanceler(false)
                .setUseHardwareNoiseSuppressor(false)
        }
        if (BuildConfig.DEBUG) {
            val capture = PcmSignalHealth()
            val playback = PcmSignalHealth()
            fun observe(path: String, health: PcmSignalHealth,
                        samples: JavaAudioDeviceModule.AudioSamples) {
                if (samples.audioFormat != AudioFormat.ENCODING_PCM_16BIT) return
                health.observe(samples.data, SystemClock.elapsedRealtime())?.let { summary ->
                    Log.i("WebRtcMediaHealth", "role=$role path=$path rate=${samples.sampleRate} channels=${samples.channelCount} $summary")
                }
            }
            builder.setSamplesReadyCallback { samples -> observe("capture", capture, samples) }
                .setPlaybackSamplesReadyCallback { samples -> observe("playback", playback, samples) }
        }
        return builder.createAudioDeviceModule()
    }
}

internal data class WebRtcAudioDevicePolicy(val gatewayDownlink: Boolean) {
    companion object {
        fun forRole(role: Role, capability: GatewayCapability): WebRtcAudioDevicePolicy {
            check(role != Role.SENDER || capability == GatewayCapability.AVAILABLE) {
                "Live call relay requires root and a supported gateway audio route"
            }
            return WebRtcAudioDevicePolicy(gatewayDownlink = role == Role.SENDER)
        }
    }
}
