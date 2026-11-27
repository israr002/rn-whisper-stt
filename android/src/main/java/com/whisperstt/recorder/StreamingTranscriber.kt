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
        val prompt: String? = null,           // vocabulary hint passed to every whisper run
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
                if (seg.consumeVadReset()) vad.reset()
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
        // Recent chunks from before speech onset
        private inner class Chunk(val frame: FloatArray, val prob: Float, val rms: Float)
        private val preRoll = ArrayDeque<Chunk>()
        private val preRollChunks = config.preRollMs / chunkMs
        private val lookBackChunks = maxOf(preRollChunks, LOOK_BACK_MS / chunkMs)

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
        private var vadResetPending = false

        // A too-short burst (e.g. "ask not" under noise) waiting to be prepended to the next utterance
        private var carry: FloatArray? = null
        private var carrySpeechMs = 0
        private var chunksSinceCarry = 0

        /**
         * Where in [preRoll] the new utterance starts. Normally the last preRollMs.
         *
         * Silero can score real speech near zero: "ask not" in the JFK clip is as loud as its
         * neighbours but scored 0.0-0.4, so it never started an utterance and was lost. Whisper is
         * the better judge, so when the audio before the onset holds evidence of speech (a weak
         * VAD score, or audio well above the noise floor) the utterance starts there instead. The
         * search walks back from the onset and stops at the first quiet gap of LOOK_BACK_GAP_MS.
         */
        private fun preRollStart(): Int {
            val default = maxOf(0, preRoll.size - preRollChunks)
            val loud = maxOf(preRoll.minOf { it.rms } * LOUD_OVER_FLOOR, MIN_LOUD_RMS)
            val evidence = preRoll.map { it.prob >= WEAK_SPEECH_PROB || it.rms >= loud }
            var start = default
            for (i in default - 1 downTo 0) {
                // Two chunks in a row: a single click isn't evidence
                if (evidence[i] && evidence[i + 1]) start = i
                else if ((start - i) * chunkMs > LOOK_BACK_GAP_MS) break
            }
            return if (start == default) default else maxOf(0, start - LOOK_BACK_MARGIN_CHUNKS)
        }

        /**
         * True once after an utterance ended on a pause. Silero's recurrent state drifts during a
         * recording: on a vivo V2231, speech after the first utterance scored 0.1-0.5 instead of
         * 0.6-0.95 and whole phrases fell under the threshold. A fresh state per utterance fixed it
         * (windows detected: 22/64 -> 57/68). Not after a max-length cut: speech is still going on.
         */
        fun consumeVadReset(): Boolean = vadResetPending.also { vadResetPending = false }

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
                    preRoll.drop(preRollStart()).forEach { append(it.frame) }
                    preRoll.clear()
                } else {
                    preRoll.addLast(Chunk(frame.copyOf(), prob, rms(frame)))
                    if (preRoll.size > lookBackChunks) preRoll.removeFirst()
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
                vadResetPending = trailingSilenceMs >= config.endSilenceMs
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
                    // All of it, not just up to the last detected speech: the VAD may have
                    // missed the rest of the phrase (see preRollStart)
                    carry = audio.copyOf(length.coerceAtLeast(1))
                    carrySpeechMs = speechMs
                    chunksSinceCarry = 0
                }
                if (config.livePartials) listener.onTranscript(segmentId, "", true, 0, 0)
                return
            }
            val end = trimmedEnd()
            submitted(end, isFinal = true)
            if (force) {
                Log.d(TAG, "Flushed trailing utterance #$segmentId on stop")
            } else {
                // The pause after the last detected speech can hold speech the VAD scored low
                // (e.g. the next phrase starting while a short utterance waited out
                // maxHoldSilenceMs). Don't discard it: hand it to the next utterance's look-back
                val chunk = SileroVad.CHUNK_SIZE
                var i = end
                while (i + chunk <= length) {
                    val frame = audio.copyOfRange(i, i + chunk)
                    preRoll.addLast(Chunk(frame, 0f, rms(frame)))
                    i += chunk
                }
                while (preRoll.size > lookBackChunks) preRoll.removeFirst()
            }
        }

        private fun rms(frame: FloatArray): Float {
            var sum = 0.0
            for (x in frame) sum += x * x
            return Math.sqrt(sum / frame.size).toFloat()
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
                val text = if (reuse) cachedText else engine.transcribeSamples(samples, config.prompt).trim()
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
        // How far before speech onset to look for weak speech, and what counts as weak
        private const val LOOK_BACK_MS = 5000
        private const val LOOK_BACK_GAP_MS = 1500
        private const val WEAK_SPEECH_PROB = 0.15f
        // Loud = this many times the quietest recent chunk, and at least MIN_LOUD_RMS
        private const val LOUD_OVER_FLOOR = 3f
        private const val MIN_LOUD_RMS = 0.01f
        private const val LOOK_BACK_MARGIN_CHUNKS = 6 // ~190 ms before the first evidence
        const val SAMPLE_RATE = 16000
    }
}
