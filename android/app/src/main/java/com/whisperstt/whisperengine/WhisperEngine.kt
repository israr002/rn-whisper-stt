package com.whisperstt.whisperengine

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.whisperstt.utils.WhisperUtil
import org.tensorflow.lite.Interpreter
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel

class WhisperEngine(private val context: Context) : IWhisperEngine {
    private val TAG = "WhisperEngineJava"
    private val mWhisperUtil = WhisperUtil()
    override var isInitialized = false
        private set
    private var mInterpreter: Interpreter? = null
    private val nativePtr // Native pointer to the TFLiteEngine instance
            : Long

    // Reused across calls to avoid reallocating ~1MB per inference
    private var inputBuf: ByteBuffer? = null
    private var outputBuf: ByteBuffer? = null

    @Throws(IOException::class)
    override fun initialize(
        modelPath: String?,
        vocabPath: String?,
        multilingual: Boolean
    ): Boolean {
        // Load model
        loadModel(modelPath)
        Log.d(TAG, "Model is loaded...$modelPath")

        // Load filters and vocab
        val ret = mWhisperUtil.loadFiltersAndVocab(multilingual, vocabPath!!)
        if (ret) {
            this.isInitialized = true
            Log.d(TAG, "Filters and Vocab are loaded...$vocabPath")
        } else {
            this.isInitialized = false
            Log.d(TAG, "Failed to load Filters and Vocab...")
        }

        // Warm-up: the first Invoke() pays for XNNPACK weight packing and memory planning
        val t = SystemClock.elapsedRealtime()
        runInference(FloatArray(WhisperUtil.WHISPER_N_MEL * WhisperUtil.WHISPER_MEL_LEN))
        Log.i(TIMING_TAG, "warmup=${SystemClock.elapsedRealtime() - t}ms threads=$numThreads")
        return this.isInitialized
    }

    override fun transcribeFile(wavePath: String?): String {
        val t0 = SystemClock.elapsedRealtime()
        val melSpectrogram = transcribeFileWithMel(nativePtr, wavePath, mWhisperUtil.getFilters())
        val t1 = SystemClock.elapsedRealtime()
        val result = runInference(melSpectrogram)
        val t2 = SystemClock.elapsedRealtime()
        Log.i(TIMING_TAG, "mel=${t1 - t0}ms inference=${t2 - t1}ms total=${t2 - t0}ms threads=$numThreads")
        return result
    }

    // 16kHz mono PCM in [-1, 1]; at most 30s is used
    override fun transcribeSamples(samples: FloatArray): String {
        val t0 = SystemClock.elapsedRealtime()
        val melSpectrogram = melFromSamples(nativePtr, samples, mWhisperUtil.getFilters())
        val t1 = SystemClock.elapsedRealtime()
        val result = runInference(melSpectrogram)
        val t2 = SystemClock.elapsedRealtime()
        Log.i(TIMING_TAG, "audio=${samples.size / 16}ms mel=${t1 - t0}ms inference=${t2 - t1}ms total=${t2 - t0}ms threads=$numThreads")
        return result
    }

    @Throws(IOException::class)
    private fun loadModel(modelPath: String?) {
        val fileDescriptor = context.assets.openFd(modelPath!!)
        val inputStream = FileInputStream(fileDescriptor.fileDescriptor)
        val fileChannel = inputStream.channel
        val startOffset = fileDescriptor.startOffset
        val declaredLength = fileDescriptor.declaredLength
        val retFile = fileChannel.map(FileChannel.MapMode.READ_ONLY, startOffset, declaredLength)
        fileDescriptor.close()

        val tfliteOptions = Interpreter.Options()
        tfliteOptions.setNumThreads(numThreads)
        tfliteOptions.setUseXNNPACK(true)

        mInterpreter = Interpreter(retFile, tfliteOptions)
    }

    private fun runInference(inputData: FloatArray): String {
        val interpreter = mInterpreter!!
        val input = inputBuf ?: ByteBuffer.allocateDirect(interpreter.getInputTensor(0).numBytes())
            .order(ByteOrder.nativeOrder()).also { inputBuf = it }
        val output = outputBuf ?: ByteBuffer.allocateDirect(interpreter.getOutputTensor(0).numBytes())
            .order(ByteOrder.nativeOrder()).also { outputBuf = it }

        input.rewind()
        input.asFloatBuffer().put(inputData)
        output.rewind()

        interpreter.run(input, output)

        // Decode tokens until EOT, skipping special tokens
        output.rewind()
        val tokens = output.asIntBuffer()
        val result = StringBuilder()
        while (tokens.hasRemaining()) {
            val token = tokens.get()
            if (token == mWhisperUtil.tokenEOT) break
            if (token < mWhisperUtil.tokenEOT) result.append(mWhisperUtil.getWordFromToken(token))
        }
        return result.toString()
    }

    init {
        nativePtr = createEngine()
    }

    // Native methods
    private external fun createEngine(): Long
    private external fun transcribeFileWithMel(
        nativePtr: Long,
        waveFile: String?,
        filters: FloatArray
    ): FloatArray
    private external fun melFromSamples(
        nativePtr: Long,
        samples: FloatArray,
        filters: FloatArray
    ): FloatArray

    companion object {
        private const val TIMING_TAG = "WhisperTiming"

        // Using every core includes the LITTLE cores, which stall XNNPACK's thread pool.
        // Default to the number of performance cores (capped at 4).
        val numThreads: Int by lazy { performanceCoreCount().coerceIn(1, 4) }

        private fun performanceCoreCount(): Int {
            val maxFreqs = (0 until Runtime.getRuntime().availableProcessors()).mapNotNull {
                File("/sys/devices/system/cpu/cpu$it/cpufreq/cpuinfo_max_freq")
                    .takeIf { f -> f.canRead() }?.readText()?.trim()?.toLongOrNull()
            }
            if (maxFreqs.isEmpty()) return 4
            val littleFreq = maxFreqs.min()
            val big = maxFreqs.count { it > littleFreq }
            return if (big == 0) 4 else big
        }

        init {
            System.loadLibrary("whisper")
        }
    }
}
