package com.whisperstt.recorder

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Process
import android.os.SystemClock
import android.util.Log
import androidx.core.app.ActivityCompat
import com.whisperstt.vad.SileroVad
import com.whisperstt.whisperengine.IWhisperEngine
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Continuous transcription: mic -> Silero VAD -> utterance segments -> Whisper.
 *
 * Whisper is run on whole utterances (cut at pauses), not fixed 1s chunks, so it keeps
 * sentence context. While someone is speaking, the in-progress utterance is transcribed
 * as a partial result whenever the worker is idle; the final result replaces it.
 */
class StreamingTranscriber(
    private val context: Context,
    private val engine: IWhisperEngine,
    // Must be single-threaded; shared with engine initialization so jobs queue behind it
    private val worker: ExecutorService,
    private val listener: Listener,
    private val config: Config = Config(),
) {
    data class Config(
        // Silero's default is 0.5; quiet words after a pause (far-field, fan noise) scored just under it
        val speechThreshold: Float = 0.45f,
        val silenceThreshold: Float = 0.3f,   // hysteresis: below this counts as silence
        val endSilenceMs: Int = 700,          // pause that ends an utterance
        val minUtteranceMs: Int = 1000,       // shorter utterances wait to merge with the next one...
        val maxHoldSilenceMs: Int = 2000,     // ...unless the pause is this long
        val minSpeechMs: Int = 250,           // below this it's not transcribed on its own...
        val carryWindowMs: Int = 2000,        // ...but joins the next utterance if that starts this soon
        val maxUtteranceMs: Int = 20_000,     // force a cut; Whisper's window is 30s
        val preRollMs: Int = 800,             // audio kept from before speech onset (catches soft starts)
        val livePartials: Boolean = true,
        val partialIntervalMs: Int = 600,     // whisper.cpp runs take ~100-400ms on mid-range phones
        // Start transcribing this soon after speech stops; reused as the final if no more speech comes
        val speculativeSilenceMs: Int = 200,
    )

    interface Listener {
        fun onTranscript(segmentId: Int, text: String, isFinal: Boolean, audioMs: Int, latencyMs: Long)
        fun onError(message: String)
    }

    private val running = AtomicBoolean(false)
    private val workerBusy = AtomicBoolean(false)
    private var audioThread: Thread? = null

    fun start() {
        if (!running.compareAndSet(false, true)) return
        audioThread = Thread({ audioLoop() }, "WhisperAudio").also { it.start() }
    }

    /** Stops capture, transcribes the trailing utterance and waits for all pending results. */
    fun stop() {
        if (!running.compareAndSet(true, false)) return
        audioThread?.join()
        audioThread = null
        // Drain: the worker is single-threaded, so this runs after every queued job
        worker.submit {}.get(60, TimeUnit.SECONDS)
    }

    @SuppressLint("MissingPermission")
    private fun audioLoop() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        if (ActivityCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            listener.onError("RECORD_AUDIO permission not granted")
            running.set(false)
            return
        }

        val chunk = SileroVad.CHUNK_SIZE
        val minBuf = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        val record = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION, SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
            maxOf(minBuf, chunk * 2 * 8)
        )
        val vad = SileroVad(context)
        try {
            record.startRecording()
            val pcm = ShortArray(chunk)
            val frame = FloatArray(chunk)
            val seg = Segmenter()

            while (running.get()) {
                var read = 0
                while (read < chunk && running.get()) {
                    val n = record.read(pcm, read, chunk - read)
                    if (n < 0) throw IllegalStateException("AudioRecord.read error $n")
                    read += n
                }
                if (read < chunk) break
                for (i in 0 until chunk) frame[i] = pcm[i] / 32768f
                seg.push(frame, vad.process(frame))
            }
            seg.flush(force = true)
        } catch (e: Exception) {
            Log.e(TAG, "Audio loop failed", e)
            listener.onError(e.message ?: e.toString())
        } finally {
            runCatching { record.stop() }
            record.release()
            vad.close()
        }
    }

    /** Runs on the audio thread. Turns per-chunk VAD probabilities into utterances. */
    private inner class Segmenter {
        private val chunkMs = SileroVad.CHUNK_SIZE * 1000 / SAMPLE_RATE
        private val preRoll = ArrayDeque<FloatArray>()
        private val preRollChunks = config.preRollMs / chunkMs

        private var audio = FloatArray(SAMPLE_RATE * 32)
        private var length = 0
        private var active = false
        private var inSpeech = false
        private var speechMs = 0
        private var trailingSilenceMs = 0
        private var segmentId = 0
        private var lastPartialAt = 0L
        private var speechEnd = 0          // audio length right after the last speech chunk
        private var submittedSpeechEnd = -1

        // A too-short burst (e.g. "ask not" under noise) waiting to be prepended to the next utterance
        private var carry: FloatArray? = null
        private var carrySpeechMs = 0
        private var chunksSinceCarry = 0

        fun push(frame: FloatArray, prob: Float) {
            if (!active) {
                if (carry != null && ++chunksSinceCarry * chunkMs > config.carryWindowMs) {
                    Log.d(TAG, "Dropped ${carrySpeechMs}ms speech burst (nothing followed)")
                    carry = null
                }
                if (prob >= config.speechThreshold) {
                    active = true
                    inSpeech = true
                    segmentId++
                    length = 0
                    speechMs = 0
                    trailingSilenceMs = 0
                    speechEnd = 0
                    submittedSpeechEnd = -1
                    lastPartialAt = SystemClock.elapsedRealtime()
                    carry?.let {
                        Log.d(TAG, "#$segmentId starts with a carried ${carrySpeechMs}ms burst")
                        append(it)
                        speechMs = carrySpeechMs
                        carry = null
                    }
                    preRoll.forEach { append(it) }
                    preRoll.clear()
                } else {
                    preRoll.addLast(frame.copyOf())
                    if (preRoll.size > preRollChunks) preRoll.removeFirst()
                    return
                }
            }

            append(frame)
            inSpeech = if (inSpeech) prob >= config.silenceThreshold else prob >= config.speechThreshold
            if (inSpeech) {
                speechMs += chunkMs
                trailingSilenceMs = 0
                speechEnd = length
            } else {
                trailingSilenceMs += chunkMs
            }

            val utteranceMs = length * 1000 / SAMPLE_RATE
            val ended = (trailingSilenceMs >= config.endSilenceMs && speechMs >= config.minUtteranceMs) ||
                trailingSilenceMs >= config.maxHoldSilenceMs ||
                utteranceMs >= config.maxUtteranceMs
            if (ended) {
                flush(force = false)
            } else if (workerBusy.get()) {
                return
            } else if (!inSpeech && trailingSilenceMs >= config.speculativeSilenceMs &&
                speechMs >= config.minSpeechMs && submittedSpeechEnd != speechEnd
            ) {
                // Speech probably ended: transcribe now instead of waiting out endSilenceMs
                submitted(trimmedEnd(), isFinal = false)
            } else if (config.livePartials && inSpeech &&
                SystemClock.elapsedRealtime() - lastPartialAt >= config.partialIntervalMs
            ) {
                submitted(length, isFinal = false)
            }
        }

        private fun submitted(end: Int, isFinal: Boolean) {
            lastPartialAt = SystemClock.elapsedRealtime()
            submittedSpeechEnd = speechEnd
            submit(segmentId, audio.copyOf(end.coerceIn(1, length)), isFinal, speechEnd)
        }

        // Keep ~300ms of the trailing pause; Whisper doesn't need the rest
        private fun trimmedEnd(): Int =
            speechEnd + minOf(trailingSilenceMs, 300) * SAMPLE_RATE / 1000

        fun flush(force: Boolean) {
            if (!active) return
            active = false
            if (speechMs < config.minSpeechMs) {
                // Too short to transcribe alone. Keep it in case more speech follows; the UI
                // drops any partial it showed for this id
                if (!force) {
                    carry = audio.copyOf(trimmedEnd().coerceIn(1, length))
                    carrySpeechMs = speechMs
                    chunksSinceCarry = 0
                }
                if (config.livePartials) listener.onTranscript(segmentId, "", true, 0, 0)
                return
            }
            submitted(trimmedEnd(), isFinal = true)
            if (force) Log.d(TAG, "Flushed trailing utterance #$segmentId on stop")
        }

        private fun append(frame: FloatArray) {
            if (length + frame.size > audio.size) audio = audio.copyOf(audio.size * 2)
            System.arraycopy(frame, 0, audio, length, frame.size)
            length += frame.size
        }
    }

    // Last result, touched only on the worker thread
    private var cachedId = -1
    private var cachedSpeechEnd = -1
    private var cachedText = ""

    private fun submit(segmentId: Int, samples: FloatArray, isFinal: Boolean, speechEnd: Int) {
        val queuedAt = SystemClock.elapsedRealtime()
        workerBusy.set(true)
        worker.execute {
            workerBusy.set(true)
            try {
                // A partial that already covered every speech chunk is as good as a fresh run
                val reuse = isFinal && cachedId == segmentId && cachedSpeechEnd >= speechEnd
                val text = if (reuse) cachedText else engine.transcribeSamples(samples).trim()
                cachedId = segmentId
                cachedSpeechEnd = speechEnd
                cachedText = text
                val latency = SystemClock.elapsedRealtime() - queuedAt
                val audioMs = samples.size * 1000 / SAMPLE_RATE
                val kind = if (!isFinal) "partial" else if (reuse) "final(reused)" else "final"
                Log.i(TAG, "#$segmentId $kind audio=${audioMs}ms latency=${latency}ms: $text")
                listener.onTranscript(segmentId, text, isFinal, audioMs, latency)
            } catch (e: Exception) {
                Log.e(TAG, "Transcription failed", e)
                listener.onError(e.message ?: e.toString())
            } finally {
                workerBusy.set(false)
            }
        }
    }

    companion object {
        private const val TAG = "StreamingTranscriber"
        const val SAMPLE_RATE = 16000
    }
}
