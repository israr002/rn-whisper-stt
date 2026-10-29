package com.whisperstt.whisperengine

import android.content.Context
import android.os.SystemClock
import android.util.Log
import java.io.File

/**
 * whisper.cpp backend. Unlike the TFLite graph (fixed 30s input), the encoder can run on
 * just the audio we have via audio_ctx, which is where most of the speedup comes from.
 */
class WhisperCppEngine(
    private val context: Context,
    // Extra encoder positions beyond the audio; too tight a window makes whisper drop the last words
    private val audioCtxPadding: Int = 64,
    // false = always use the full 30s window (same encoder work as TFLite)
    private val useAudioCtx: Boolean = true,
) : IWhisperEngine {
    private var ctx = 0L
    override val isInitialized: Boolean get() = ctx != 0L

    override fun initialize(modelPath: String?, vocabPath: String?, multilingual: Boolean): Boolean {
        val file = copyAsset(modelPath!!)
        ctx = nativeInit(file.absolutePath)
        if (ctx == 0L) return false

        // Warm-up: first run allocates compute buffers
        val t = SystemClock.elapsedRealtime()
        transcribeSamples(FloatArray(SAMPLE_RATE))
        Log.i(TIMING_TAG, "warmup=${SystemClock.elapsedRealtime() - t}ms threads=${WhisperEngine.numThreads} engine=whisper.cpp")
        return true
    }

    override fun transcribeFile(wavePath: String?): String =
        throw UnsupportedOperationException("WhisperCppEngine only transcribes samples")

    override fun transcribeSamples(samples: FloatArray): String {
        if (!useAudioCtx) return run(samples, 0)
        // A reduced audio_ctx occasionally sends the decoder into a repetition loop;
        // retry with progressively wider windows (the full window never looped in testing)
        // Encoder positions: one per 2 mel frames = one per 320 samples; 1500 = 30s
        for (padding in listOf(audioCtxPadding, audioCtxPadding * 4)) {
            val text = run(samples, minOf(MAX_AUDIO_CTX, samples.size / 320 + padding))
            if (!looksLikeLoop(text, samples.size)) return text
            Log.w(TIMING_TAG, "repetition with padding=$padding, retrying: $text")
        }
        return run(samples, 0)
    }

    private fun run(samples: FloatArray, audioCtx: Int): String {
        val t0 = SystemClock.elapsedRealtime()
        // ~3 words/s of speech is ~4 tokens/s; 6/s plus slack leaves room for fast talkers
        val maxTokens = 16 + samples.size * 6 / SAMPLE_RATE
        val text = String(nativeTranscribe(ctx, samples, WhisperEngine.numThreads, audioCtx, maxTokens), Charsets.UTF_8)
        Log.i(TIMING_TAG, "audio=${samples.size / 16}ms total=${SystemClock.elapsedRealtime() - t0}ms audio_ctx=$audioCtx threads=${WhisperEngine.numThreads} engine=whisper.cpp")
        return text
    }

    // Too many words for the audio length, or a 3+ word phrase repeated 3+ times
    private fun looksLikeLoop(text: String, nSamples: Int): Boolean {
        val words = text.lowercase().split(Regex("[^a-z0-9']+")).filter { it.isNotEmpty() }
        if (words.size > 6 + nSamples * 5 / SAMPLE_RATE) return true
        for (n in 3..6) {
            val counts = HashMap<List<String>, Int>()
            for (i in 0..words.size - n) {
                val gram = words.subList(i, i + n)
                val c = (counts[gram] ?: 0) + 1
                if (c >= 3) return true
                counts[gram] = c
            }
        }
        return false
    }

    private fun copyAsset(assetPath: String): File {
        val out = File(context.filesDir, File(assetPath).name)
        if (!out.exists()) {
            val tmp = File(out.path + ".tmp")
            context.assets.open(assetPath).use { input -> tmp.outputStream().use { input.copyTo(it) } }
            tmp.renameTo(out)
        }
        return out
    }

    private external fun nativeInit(modelPath: String): Long
    private external fun nativeFree(ptr: Long)
    private external fun nativeTranscribe(ptr: Long, samples: FloatArray, nThreads: Int, audioCtx: Int, maxTokens: Int): ByteArray

    companion object {
        private const val TIMING_TAG = "WhisperTiming"
        private const val SAMPLE_RATE = 16000
        private const val MAX_AUDIO_CTX = 1500

        init {
            System.loadLibrary("whisperstt")
        }
    }
}
