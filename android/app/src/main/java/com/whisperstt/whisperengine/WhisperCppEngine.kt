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
    // Extra encoder positions beyond the audio; too tight a window makes whisper loop or drop words
    private val audioCtxPadding: Int = 64,
    // Short utterances loop most often with a tight window; never go below this (256 ≈ 5s)
    private val minAudioCtx: Int = 256,
) : IWhisperEngine {
    private var ctx = 0L
    override val isInitialized: Boolean get() = ctx != 0L

    override fun initialize(modelPath: String): Boolean {
        val file = copyAsset(modelPath)
        ctx = nativeInit(file.absolutePath)
        if (ctx == 0L) return false

        // Warm-up: first run allocates compute buffers
        val t = SystemClock.elapsedRealtime()
        transcribeSamples(FloatArray(SAMPLE_RATE))
        Log.i(TIMING_TAG, "warmup=${SystemClock.elapsedRealtime() - t}ms threads=${threadsForNow()}")
        return true
    }

    override fun transcribeSamples(samples: FloatArray): String {
        // A reduced audio_ctx occasionally sends the decoder into a repetition loop;
        // retry with progressively wider windows (the full window never looped in testing)
        // Encoder positions: one per 2 mel frames = one per 320 samples; 1500 = 30s
        for (padding in listOf(audioCtxPadding, audioCtxPadding * 4)) {
            val text = run(samples, (samples.size / 320 + padding).coerceIn(minAudioCtx, MAX_AUDIO_CTX))
            if (!looksLikeLoop(text, samples.size)) return text
            Log.w(TIMING_TAG, "repetition with padding=$padding, retrying: $text")
        }
        return run(samples, 0)
    }

    private fun run(samples: FloatArray, audioCtx: Int): String {
        val t0 = SystemClock.elapsedRealtime()
        // ~3 words/s of speech is ~4 tokens/s; 6/s plus slack leaves room for fast talkers
        val maxTokens = 16 + samples.size * 6 / SAMPLE_RATE
        val threads = threadsForNow()
        val text = String(nativeTranscribe(ctx, samples, threads, audioCtx, maxTokens), Charsets.UTF_8)
        Log.i(TIMING_TAG, "audio=${samples.size / 16}ms total=${SystemClock.elapsedRealtime() - t0}ms audio_ctx=$audioCtx threads=$threads")
        return text
    }

    // Too many words for the audio length, a 3+ word phrase repeated back to back,
    // or a 3+ word phrase repeated 3+ times anywhere
    private fun looksLikeLoop(text: String, nSamples: Int): Boolean {
        val words = text.lowercase().split(Regex("[^a-z0-9']+")).filter { it.isNotEmpty() }
        if (words.size > 6 + nSamples * 5 / SAMPLE_RATE) return true
        for (n in 3..words.size / 2) {
            for (i in 0..words.size - 2 * n) {
                if (words.subList(i, i + n) == words.subList(i + n, i + 2 * n)) return true
            }
        }
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

    override fun release() {
        if (ctx != 0L) nativeFree(ctx)
        ctx = 0L
    }

    private external fun nativeInit(modelPath: String): Long
    private external fun nativeFree(ptr: Long)
    private external fun nativeTranscribe(ptr: Long, samples: FloatArray, nThreads: Int, audioCtx: Int, maxTokens: Int): ByteArray

    companion object {
        private const val TIMING_TAG = "WhisperTiming"
        private const val SAMPLE_RATE = 16000
        private const val MAX_AUDIO_CTX = 1500

        // Max frequency per core; cores above the slowest tier count as "fast"
        private val coreMaxFreq: Map<Int, Long> by lazy {
            (0 until Runtime.getRuntime().availableProcessors()).mapNotNull { cpu ->
                File("/sys/devices/system/cpu/cpu$cpu/cpufreq/cpuinfo_max_freq")
                    .takeIf { it.canRead() }?.readText()?.trim()?.toLongOrNull()?.let { cpu to it }
            }.toMap()
        }

        /**
         * Thread count for the next run, from the cores this process may use *right now*.
         * In the foreground: one thread per fast core (adding LITTLE cores stalls the pool).
         * Some OEMs (e.g. vivo's h-background cpuset) confine background apps to LITTLE cores
         * even with a foreground service; then spread the work over up to 4 of them instead.
         */
        fun threadsForNow(): Int {
            if (coreMaxFreq.isEmpty()) return 4
            val allowed = allowedCores() ?: coreMaxFreq.keys
            val slowest = coreMaxFreq.values.min()
            val fastAllowed = allowed.count { (coreMaxFreq[it] ?: slowest) > slowest }
            return if (fastAllowed > 0) fastAllowed.coerceAtMost(4) else allowed.size.coerceIn(1, 4)
        }

        // Cpus_allowed_list reflects the cpuset Android moves the process into, e.g. "0-5"
        private fun allowedCores(): Set<Int>? = runCatching {
            val line = File("/proc/self/status").readLines().first { it.startsWith("Cpus_allowed_list:") }
            line.substringAfter(':').trim().split(',').flatMap { part ->
                val (lo, hi) = part.split('-').let { it[0].toInt() to (it.getOrNull(1)?.toInt() ?: it[0].toInt()) }
                (lo..hi).toList()
            }.toSet()
        }.getOrNull()

        init {
            System.loadLibrary("whisperstt")
        }
    }
}
