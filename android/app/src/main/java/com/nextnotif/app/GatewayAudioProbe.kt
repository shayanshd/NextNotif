package com.nextnotif.app

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class AudioProbeResult(
    val source: String,
    val status: ProbeStatus,
    val summary: SignalSummary? = null,
    val detail: String? = null,
    val timeline: List<SignalSlice> = emptyList(),
)

data class SignalSlice(
    val startMs: Int,
    val dbfs: Double,
    val expectedSpeech: Boolean,
)

data class ControlledProbeProgress(
    val expectedSpeech: Boolean,
    val phase: Int,
    val secondsRemaining: Int,
)

private data class AudioProbeSpec(val label: String, val audioSource: Int)

object GatewayAudioProbe {
    private const val SAMPLE_RATE = 16_000
    private const val SAMPLE_DURATION_MS = 800
    private const val CONTROLLED_SLICE_MS = 250
    private const val CONTROLLED_PHASE_MS = 3_000
    private const val CONTROLLED_DURATION_MS = CONTROLLED_PHASE_MS * 4

    private val specs = listOf(
        AudioProbeSpec("MIC", MediaRecorder.AudioSource.MIC),
        AudioProbeSpec("VOICE_COMMUNICATION", MediaRecorder.AudioSource.VOICE_COMMUNICATION),
        AudioProbeSpec("VOICE_CALL", MediaRecorder.AudioSource.VOICE_CALL),
        AudioProbeSpec("VOICE_UPLINK", MediaRecorder.AudioSource.VOICE_UPLINK),
        AudioProbeSpec("VOICE_DOWNLINK", MediaRecorder.AudioSource.VOICE_DOWNLINK),
    )

    suspend fun run(onProgress: (String) -> Unit): List<AudioProbeResult> {
        val results = mutableListOf<AudioProbeResult>()
        for (spec in specs) {
            onProgress(spec.label)
            results += withContext(Dispatchers.IO) { probe(spec) }
        }
        return results
    }

    suspend fun runControlledMic(
        onProgress: (ControlledProbeProgress) -> Unit,
    ): AudioProbeResult = withContext(Dispatchers.IO) {
        controlledProbe(AudioProbeSpec("MIC CONTROLLED", MediaRecorder.AudioSource.MIC), onProgress)
    }

    suspend fun runControlledCall(
        onProgress: (ControlledProbeProgress) -> Unit,
    ): AudioProbeResult = withContext(Dispatchers.IO) {
        controlledProbe(AudioProbeSpec("VOICE_CALL CONTROLLED", MediaRecorder.AudioSource.VOICE_CALL), onProgress)
    }

    @SuppressLint("MissingPermission")
    private fun controlledProbe(
        spec: AudioProbeSpec,
        onProgress: (ControlledProbeProgress) -> Unit,
    ): AudioProbeResult {
        var recorder: AudioRecord? = null
        return try {
            val minimum = AudioRecord.getMinBufferSize(
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
            )
            if (minimum <= 0) {
                return AudioProbeResult(spec.label, ProbeStatus.FAILED, detail = "No compatible input buffer")
            }
            val sliceSamples = SAMPLE_RATE * CONTROLLED_SLICE_MS / 1_000
            val bufferBytes = maxOf(minimum, sliceSamples * 2)
            recorder = buildRecorder(spec.audioSource, bufferBytes)
            if (recorder.state != AudioRecord.STATE_INITIALIZED) {
                return AudioProbeResult(spec.label, ProbeStatus.FAILED, detail = "Audio source did not initialize")
            }

            val chunk = ShortArray(sliceSamples)
            val timeline = mutableListOf<SignalSlice>()
            var totalSumSquares = 0.0
            var totalNonZero = 0
            var totalSamples = 0
            var previousPhase = -1
            recorder.startRecording()

            val sliceCount = CONTROLLED_DURATION_MS / CONTROLLED_SLICE_MS
            repeat(sliceCount) { sliceIndex ->
                val startMs = sliceIndex * CONTROLLED_SLICE_MS
                val phase = startMs / CONTROLLED_PHASE_MS
                val expectedSpeech = phase % 2 == 1
                val elapsedInPhase = startMs % CONTROLLED_PHASE_MS
                val secondsRemaining = (CONTROLLED_PHASE_MS - elapsedInPhase + 999) / 1_000
                if (phase != previousPhase || elapsedInPhase % 1_000 == 0) {
                    onProgress(ControlledProbeProgress(expectedSpeech, phase + 1, secondsRemaining))
                    previousPhase = phase
                }

                var collected = 0
                while (collected < sliceSamples) {
                    val read = recorder.read(
                        chunk,
                        collected,
                        sliceSamples - collected,
                        AudioRecord.READ_BLOCKING,
                    )
                    if (read <= 0) {
                        return AudioProbeResult(spec.label, ProbeStatus.FAILED, detail = "Audio read failed ($read)")
                    }
                    collected += read
                }
                val summary = analyzeSignal(chunk, collected)
                timeline += SignalSlice(startMs, summary.dbfs, expectedSpeech)
                val rms = dbfsToRms(summary.dbfs)
                totalSumSquares += rms * rms * collected
                totalNonZero += (summary.nonZeroPercent * collected / 100.0).toInt()
                totalSamples += collected
            }

            val aggregate = aggregateSummary(totalSumSquares, totalNonZero, totalSamples)
            AudioProbeResult(
                source = spec.label,
                status = aggregate.status,
                summary = aggregate,
                timeline = timeline,
            )
        } catch (error: SecurityException) {
            AudioProbeResult(spec.label, ProbeStatus.BLOCKED, detail = error.message?.take(120))
        } catch (error: Exception) {
            AudioProbeResult(
                spec.label,
                ProbeStatus.FAILED,
                detail = error.message?.take(120) ?: error.javaClass.simpleName,
            )
        } finally {
            recorder?.let {
                runCatching {
                    if (it.recordingState == AudioRecord.RECORDSTATE_RECORDING) it.stop()
                }
                it.release()
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun probe(spec: AudioProbeSpec): AudioProbeResult {
        var recorder: AudioRecord? = null
        return try {
            val minimum = AudioRecord.getMinBufferSize(
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
            )
            if (minimum <= 0) {
                return AudioProbeResult(spec.label, ProbeStatus.FAILED, detail = "No compatible input buffer")
            }
            val bufferBytes = maxOf(minimum, SAMPLE_RATE / 2)
            recorder = buildRecorder(spec.audioSource, bufferBytes)
            if (recorder.state != AudioRecord.STATE_INITIALIZED) {
                return AudioProbeResult(spec.label, ProbeStatus.FAILED, detail = "Audio source did not initialize")
            }

            val targetSamples = SAMPLE_RATE * SAMPLE_DURATION_MS / 1_000
            val chunk = ShortArray(maxOf(256, bufferBytes / 2))
            var collected = 0
            var sumSquares = 0.0
            var nonZero = 0
            var emptyReads = 0
            recorder.startRecording()
            while (collected < targetSamples) {
                val requested = minOf(chunk.size, targetSamples - collected)
                val read = recorder.read(chunk, 0, requested, AudioRecord.READ_BLOCKING)
                if (read < 0) {
                    return AudioProbeResult(spec.label, ProbeStatus.FAILED, detail = "Audio read failed ($read)")
                }
                if (read == 0) {
                    emptyReads += 1
                    if (emptyReads >= 10) {
                        return AudioProbeResult(spec.label, ProbeStatus.FAILED, detail = "Audio source returned no samples")
                    }
                    continue
                }
                emptyReads = 0
                val summary = analyzeSignal(chunk, read)
                val rms = dbfsToRms(summary.dbfs)
                sumSquares += rms * rms * read
                nonZero += (summary.nonZeroPercent * read / 100.0).toInt()
                collected += read
            }

            val aggregate = aggregateSummary(sumSquares, nonZero, collected)
            AudioProbeResult(
                source = spec.label,
                status = aggregate.status,
                summary = aggregate,
            )
        } catch (error: SecurityException) {
            AudioProbeResult(spec.label, ProbeStatus.BLOCKED, detail = error.message?.take(120))
        } catch (error: Exception) {
            AudioProbeResult(
                spec.label,
                ProbeStatus.FAILED,
                detail = error.message?.take(120) ?: error.javaClass.simpleName,
            )
        } finally {
            recorder?.let {
                runCatching {
                    if (it.recordingState == AudioRecord.RECORDSTATE_RECORDING) it.stop()
                }
                it.release()
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun buildRecorder(audioSource: Int, bufferBytes: Int): AudioRecord =
        AudioRecord.Builder()
            .setAudioSource(audioSource)
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                    .build(),
            )
            .setBufferSizeInBytes(bufferBytes)
            .build()

    private fun dbfsToRms(dbfs: Double): Double =
        if (dbfs <= -120.0) 0.0 else Short.MAX_VALUE * Math.pow(10.0, dbfs / 20.0)

    private fun aggregateSummary(sumSquares: Double, nonZero: Int, sampleCount: Int): SignalSummary {
        val rms = if (sampleCount == 0) 0.0 else kotlin.math.sqrt(sumSquares / sampleCount)
        val dbfs = if (rms == 0.0) -120.0 else 20.0 * kotlin.math.log10(rms / Short.MAX_VALUE)
        val nonZeroPercent = if (sampleCount == 0) 0.0 else nonZero * 100.0 / sampleCount
        val status = if (dbfs > -60.0 && nonZeroPercent >= 1.0) ProbeStatus.SIGNAL else ProbeStatus.SILENT
        return SignalSummary(status, dbfs.coerceAtLeast(-120.0), nonZeroPercent, sampleCount)
    }
}
