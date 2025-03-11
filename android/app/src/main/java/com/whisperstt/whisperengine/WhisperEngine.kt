package com.whisperstt.whisperengine

import android.content.Context
import android.util.Log
import com.whisperstt.utils.WaveUtil.getSamples
import com.whisperstt.utils.WhisperUtil
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.Tensor
import org.tensorflow.lite.support.tensorbuffer.TensorBuffer
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
        return this.isInitialized
    }

    override fun transcribeFile(wavePath: String?): String {
        // Calculate Mel spectrogram
        Log.d(TAG, "Calculating Mel spectrogram...")
        val time = System.currentTimeMillis()
        val melSpectrogram = getMelSpectrogram(wavePath)
        Log.d(TAG, "Mel spectrogram is calculated...!")
        Log.v("inference_time_mel", (System.currentTimeMillis() - time).toString())

        // Perform inference
        val time2 = System.currentTimeMillis()
        val result = runInference(melSpectrogram)
        Log.d(TAG, "Inference is executed...!")
        Log.v("inference_time_mel", (System.currentTimeMillis() - time2).toString())
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
        tfliteOptions.setNumThreads(Runtime.getRuntime().availableProcessors())

        mInterpreter = Interpreter(retFile, tfliteOptions)
    }

    private fun getMelSpectrogram(wavePath: String?): FloatArray {
        // Get samples in PCM_FLOAT format
        val time = System.currentTimeMillis()
        val samples = getSamples(wavePath)
        Log.v("inference_get_samples", (System.currentTimeMillis() - time).toString())
        val fixedInputSize = WhisperUtil.WHISPER_SAMPLE_RATE * WhisperUtil.WHISPER_CHUNK_SIZE
        val inputSamples = FloatArray(fixedInputSize)
        val copyLength = Math.min(samples.size, fixedInputSize)
        System.arraycopy(samples, 0, inputSamples, 0, copyLength)
        val time2 = System.currentTimeMillis()
        val value = transcribeFileWithMel(nativePtr, wavePath, mWhisperUtil.getFilters())
        Log.v("inference_get_mel", (System.currentTimeMillis() - time2).toString())
        return value
    }

    private fun runInference(inputData: FloatArray): String {
        // Create input tensor
        val inputTensor = mInterpreter!!.getInputTensor(0)
        val inputBuffer = TensorBuffer.createFixedSize(inputTensor.shape(), inputTensor.dataType())
        Log.d(TAG, "Input Tensor Dump ===>")
        printTensorDump(inputTensor)

        // Create output tensor
        val outputTensor = mInterpreter!!.getOutputTensor(0)
        val outputBuffer = TensorBuffer.createFixedSize(outputTensor.shape(), DataType.FLOAT32)
        Log.d(TAG, "Output Tensor Dump ===>")
        printTensorDump(outputTensor)

        // Load input data
        val inputSize =
            inputTensor.shape()[0] * inputTensor.shape()[1] * inputTensor.shape()[2] * java.lang.Float.BYTES
        val inputBuf = ByteBuffer.allocateDirect(inputSize)
        inputBuf.order(ByteOrder.nativeOrder())
        for (input in inputData) {
            inputBuf.putFloat(input)
        }

        inputBuffer.loadBuffer(inputBuf)

        // Run inference
        mInterpreter!!.run(inputBuffer.buffer, outputBuffer.buffer)

        // Retrieve the results
        val outputLen = outputBuffer.intArray.size
        Log.d(TAG, "output_len: $outputLen")
        val result = StringBuilder()
        val time = System.currentTimeMillis()
        for (i in 0 until outputLen) {
            val token = outputBuffer.buffer.getInt()
            if (token == mWhisperUtil.tokenEOT) break

            // Get word for token and Skip additional token
            if (token < mWhisperUtil.tokenEOT) {
                val word = mWhisperUtil.getWordFromToken(token)
                Log.d(TAG, "Adding token: $token, word: $word")
                result.append(word)
            } else {
                if (token == mWhisperUtil.tokenTranscribe) Log.d(TAG, "It is Transcription...")
                if (token == mWhisperUtil.tokenTranslate) Log.d(TAG, "It is Translation...")
                val word = mWhisperUtil.getWordFromToken(token)
                Log.d(TAG, "Skipping token: $token, word: $word")
            }
        }
        Log.v("inference_time_decode", (System.currentTimeMillis() - time).toString())
        return result.toString()
    }

    private fun printTensorDump(tensor: Tensor) {
        Log.d(TAG, "  shape.length: " + tensor.shape().size)
        for (i in tensor.shape().indices) Log.d(TAG, "    shape[" + i + "]: " + tensor.shape()[i])
        Log.d(TAG, "  dataType: " + tensor.dataType())
        Log.d(TAG, "  name: " + tensor.name())
        Log.d(TAG, "  numBytes: " + tensor.numBytes())
        Log.d(TAG, "  index: " + tensor.index())
        Log.d(TAG, "  numDimensions: " + tensor.numDimensions())
        Log.d(TAG, "  numElements: " + tensor.numElements())
        Log.d(TAG, "  shapeSignature.length: " + tensor.shapeSignature().size)
        Log.d(TAG, "  quantizationParams.getScale: " + tensor.quantizationParams().scale)
        Log.d(TAG, "  quantizationParams.getZeroPoint: " + tensor.quantizationParams().zeroPoint)
        Log.d(TAG, "==================================================================")
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
  companion object {
          
        init {
            System.loadLibrary("whisper")
        }
        }
  
}
