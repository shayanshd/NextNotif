package com.nextnotif.app

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.util.Log
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * A deliberately small, 8 kHz G.711 telephony bridge.
 *
 * WebSocket binary frames contain G.711 mu-law mono audio. They are never queued by the
 * relay server. On the gateway, VOICE_DOWNLINK isolates the remote caller and
 * the received stream is injected into the modem uplink through the A520F
 * mixer route proven by the hardware probe. On the receiver, the normal mic
 * and voice-call output are used.
 */
class CallAudioBridge(
    context: Context,
    private val role: Role,
    private val send: (ByteArray) -> Boolean,
    private val onError: (String) -> Unit,
) {
    companion object {
        private const val TAG = "CallAudioBridge"
        private const val SAMPLE_RATE = 8_000
        private const val FRAME_SAMPLES = 160 // 20 ms
        private const val FRAME_BYTES = FRAME_SAMPLES * 2
        private const val WIRE_FRAME_BYTES = FRAME_SAMPLES
        // Prime a small jitter buffer before starting AudioTrack. Mobile TCP
        // commonly delivers several 20 ms frames together; starting on the
        // first frame made those scheduling gaps sound like packet loss.
        private const val MAX_QUEUED_FRAMES = 18
    }

    private val appContext = context.applicationContext
    private val running = AtomicBoolean(false)
    private val playback = BoundedPlaybackQueue<ByteArray>(MAX_QUEUED_FRAMES)
    private val playoutPolicy = AdaptivePlayoutPolicy(normalRate = SAMPLE_RATE)
    private val metrics = CallAudioMetricsCounter()
    private var recorder: AudioRecord? = null
    private var track: AudioTrack? = null
    private var captureThread: Thread? = null
    private var playbackThread: Thread? = null
    private var oldMode = AudioManager.MODE_NORMAL
    private var oldSpeaker = false

    @SuppressLint("MissingPermission")
    fun start(): Boolean {
        if (!running.compareAndSet(false, true)) return true
        try {
            val audio = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            oldMode = audio.mode
            @Suppress("DEPRECATION")
            oldSpeaker = audio.isSpeakerphoneOn
            audio.mode = AudioManager.MODE_IN_COMMUNICATION

            if (role == Role.SENDER && !GatewayUplinkRoute.enable()) {
                throw IllegalStateException("root mixer route unavailable")
            }

            val recordSource = if (role == Role.SENDER) {
                MediaRecorder.AudioSource.VOICE_DOWNLINK
            } else {
                MediaRecorder.AudioSource.VOICE_COMMUNICATION
            }
            val inputMin = AudioRecord.getMinBufferSize(
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
            )
            val outputMin = AudioTrack.getMinBufferSize(
                SAMPLE_RATE,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
            )
            check(inputMin > 0 && outputMin > 0) { "unsupported 8 kHz audio" }
            recorder = AudioRecord(
                recordSource,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                maxOf(inputMin, FRAME_BYTES * 4),
            ).also { check(it.state == AudioRecord.STATE_INITIALIZED) { "AudioRecord initialization failed" } }
            track = AudioTrack(
                AudioManager.STREAM_VOICE_CALL,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                maxOf(outputMin, FRAME_BYTES * 4),
                AudioTrack.MODE_STREAM,
            ).also { check(it.state == AudioTrack.STATE_INITIALIZED) { "AudioTrack initialization failed" } }

            recorder!!.startRecording()
            captureThread = thread(name = "nextnotif-call-capture", isDaemon = true) { captureLoop() }
            playbackThread = thread(name = "nextnotif-call-playback", isDaemon = true) { playbackLoop() }
            Log.i(TAG, "started as $role")
            return true
        } catch (t: Throwable) {
            fail(t)
            return false
        }
    }

    fun offer(bytes: ByteArray) {
        if (!running.get() || bytes.isEmpty()) return
        val pcm = when (bytes.size) {
            WIRE_FRAME_BYTES -> G711MuLaw.decode(bytes)
            // Lets two phones on adjacent development builds still complete a
            // call while both installations are being updated.
            FRAME_BYTES -> bytes.copyOf()
            else -> return
        }
        // Keep a bounded playout queue, but discard only one oldest frame at a
        // time. Dropping several consecutive speech frames created audible
        // holes whenever TCP delivered a burst.
        val dropped = playback.offerDroppingOldest(pcm)
        metrics.queued(playback.size, dropped)
        if (dropped) {
            Log.w(TAG, "dropped one stale playback frame")
        }
    }

    private fun captureLoop() {
        val frame = ByteArray(FRAME_BYTES)
        try {
            while (running.get()) {
                var offset = 0
                while (offset < frame.size && running.get()) {
                    val read = recorder?.read(frame, offset, frame.size - offset, AudioRecord.READ_BLOCKING) ?: -1
                    if (read <= 0) throw IllegalStateException("AudioRecord.read=$read")
                    offset += read
                }
                if (offset == frame.size && !send(G711MuLaw.encode(frame))) {
                    throw IllegalStateException("audio socket closed")
                }
            }
        } catch (t: Throwable) {
            if (running.get()) fail(t)
        }
    }

    private fun playbackLoop() {
        try {
            // A 60 ms startup cushion prevents the first network scheduling
            // gap from immediately underrunning AudioTrack.
            val primed = ArrayList<ByteArray>(playoutPolicy.startupFrames)
            repeat(playoutPolicy.startupFrames) { primed += playback.take() }
            track?.play()
            primed.forEach(::writePlaybackFrame)

            var currentRate = playoutPolicy.normalRate
            while (running.get()) {
                val bytes = playback.poll() ?: run {
                    metrics.underrun()
                    playback.take()
                }
                // Gently vary playout by at most 2%. This absorbs clock drift
                // and short TCP bursts without deleting whole speech frames or
                // allowing latency to grow indefinitely.
                val queued = playback.size
                val wantedRate = playoutPolicy.rateFor(queued)
                if (wantedRate != currentRate) {
                    track?.playbackRate = wantedRate
                    currentRate = wantedRate
                }
                writePlaybackFrame(bytes)
            }
        } catch (t: InterruptedException) {
            // Normal stop.
        } catch (t: Throwable) {
            if (running.get()) fail(t)
        }
    }

    private fun writePlaybackFrame(bytes: ByteArray) {
        var offset = 0
        while (offset < bytes.size && running.get()) {
            val written = track?.write(bytes, offset, bytes.size - offset) ?: -1
            if (written <= 0) throw IllegalStateException("AudioTrack.write=$written")
            offset += written
        }
    }

    @Synchronized
    fun stop() {
        if (!running.getAndSet(false)) return
        runCatching { recorder?.stop() }
        captureThread?.interrupt()
        playbackThread?.interrupt()
        runCatching { track?.stop() }
        runCatching { recorder?.release() }
        runCatching { track?.release() }
        recorder = null
        track = null
        playback.clear()
        if (role == Role.SENDER && !GatewayUplinkRoute.disable()) {
            Log.e(TAG, "failed to restore gateway uplink to AIF4IN")
        }
        val audio = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        audio.mode = oldMode
        @Suppress("DEPRECATION")
        runCatching { audio.isSpeakerphoneOn = oldSpeaker }
        Log.i(TAG, "stopped as $role")
    }

    internal fun metricsSnapshot(): CallAudioMetrics = metrics.snapshot()

    private fun fail(t: Throwable) {
        val detail = t.message ?: t.javaClass.simpleName
        Log.e(TAG, detail, t)
        onError(detail)
        stop()
    }
}

/**
 * Pure jitter-buffer policy. Queue depth is measured after taking the frame that is about to play.
 * Keeping this independent of [AudioTrack] makes boundary decisions deterministic in JVM tests.
 */
internal class AdaptivePlayoutPolicy(
    val startupFrames: Int = 3,
    private val lowWaterFrames: Int = 2,
    private val highWaterFrames: Int = 8,
    val normalRate: Int = 8_000,
    private val slowRate: Int = 7_920,
    private val fastRate: Int = 8_160,
) {
    init {
        require(startupFrames > 0)
        require(lowWaterFrames >= 0)
        require(highWaterFrames > lowWaterFrames)
        require(slowRate in 1 until normalRate)
        require(fastRate > normalRate)
    }

    fun isPrimed(queuedFrames: Int): Boolean = queuedFrames >= startupFrames

    fun rateFor(queuedFrames: Int): Int = when {
        queuedFrames <= lowWaterFrames -> slowRate
        queuedFrames >= highWaterFrames -> fastRate
        else -> normalRate
    }
}

/** Fixed-size FIFO that preserves fresh audio by dropping exactly one oldest frame on overflow. */
internal class BoundedPlaybackQueue<T>(capacity: Int) {
    private val queue = ArrayBlockingQueue<T>(capacity)

    val size: Int
        get() = queue.size

    /** Returns true when accepting [item] required dropping the oldest queued item. */
    @Synchronized
    fun offerDroppingOldest(item: T): Boolean {
        if (queue.offer(item)) return false
        queue.poll()
        check(queue.offer(item)) { "playback queue did not accept a frame after dropping one" }
        return true
    }

    fun take(): T = queue.take()

    fun poll(): T? = queue.poll()

    fun clear() = queue.clear()
}

/** ITU-T G.711 mu-law. It halves PCM16 bandwidth while retaining telephone speech quality. */
internal object G711MuLaw {
    private const val BIAS = 0x84
    private const val CLIP = 32635

    fun encode(pcm16le: ByteArray): ByteArray {
        val result = ByteArray(pcm16le.size / 2)
        var source = 0
        var target = 0
        while (source + 1 < pcm16le.size) {
            val sample = ((pcm16le[source].toInt() and 0xff) or
                (pcm16le[source + 1].toInt() shl 8)).toShort().toInt()
            result[target] = encodeSample(sample)
            source += 2
            target++
        }
        return result
    }

    fun decode(muLaw: ByteArray): ByteArray {
        val result = ByteArray(muLaw.size * 2)
        for (i in muLaw.indices) {
            val sample = decodeSample(muLaw[i])
            result[i * 2] = sample.toByte()
            result[i * 2 + 1] = (sample.toInt() shr 8).toByte()
        }
        return result
    }

    private fun encodeSample(input: Int): Byte {
        val mask: Int
        var sample: Int
        if (input < 0) {
            sample = BIAS - input
            mask = 0x7f
        } else {
            sample = BIAS + input
            mask = 0xff
        }
        sample = sample.coerceAtMost(CLIP)
        var segment = 0
        var boundary = 0x100
        while (segment < 8 && sample >= boundary) {
            segment++
            boundary = boundary shl 1
        }
        val value = if (segment >= 8) {
            0x7f
        } else {
            (segment shl 4) or ((sample shr (segment + 3)) and 0x0f)
        }
        return (value xor mask).toByte()
    }

    private fun decodeSample(input: Byte): Short {
        val value = (input.toInt() and 0xff) xor 0xff
        var magnitude = ((value and 0x0f) shl 3) + BIAS
        magnitude = magnitude shl ((value and 0x70) shr 4)
        val sample = if (value and 0x80 != 0) BIAS - magnitude else magnitude - BIAS
        return sample.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
    }
}

/** Root-only A520F uplink selector. The fallback path is the experiment tool. */
internal object GatewayUplinkRoute {
    private const val TAG = "GatewayUplinkRoute"
    private const val CONTROL = "AudioMixer CH2 DOUT Select"
    private const val SCRIPT =
        "for p in /system/bin/nextnotif-tinymix /data/local/tmp/a520f-audio-tools/tinymix; do " +
            "[ -x \"\$p\" ] && exec \"\$p\" -D 0 set '$CONTROL'"

    fun enable(): Boolean = run("DMIX_OUT")
    fun disable(): Boolean {
        repeat(3) { attempt ->
            if (run("AIF4IN")) return true
            Log.w(TAG, "AIF4IN restore attempt ${attempt + 1} failed")
            Thread.sleep(100)
        }
        return false
    }

    private fun run(value: String): Boolean = runCatching {
        val process = ProcessBuilder("su", "-c", "$SCRIPT $value; done; exit 127")
            .redirectErrorStream(true)
            .start()
        if (!process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)) {
            process.destroyForcibly()
            Log.e(TAG, "route=$value timed out")
            return@runCatching false
        }
        val output = process.inputStream.bufferedReader().use { it.readText() }.trim()
        val exit = process.exitValue()
        Log.i(TAG, "route=$value exit=$exit output=${output.take(120)}")
        exit == 0
    }.onFailure { Log.e(TAG, "route=$value failed", it) }.getOrDefault(false)
}
