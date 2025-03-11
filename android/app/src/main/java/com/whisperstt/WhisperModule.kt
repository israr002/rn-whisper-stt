package com.whisperstt
import android.content.res.AssetManager
import android.os.Build
import android.util.Log
import com.whisperstt.whisperengine.IWhisperEngine
import com.whisperstt.whisperengine.WhisperEngine
import com.whisperstt.utils.WaveUtil;
import com.whisperstt.recorder.Recorder;
import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.ReactContextBaseJavaModule
import com.facebook.react.bridge.ReactMethod
import kotlinx.coroutines.*
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Executors


private const val LOG_TAG = "LibWhisper"
class WhisperModule(reactContext: ReactApplicationContext) : ReactContextBaseJavaModule(reactContext) {
   private val recorder: Recorder = Recorder(reactApplicationContext)
       private val whisperEngine: IWhisperEngine = WhisperEngine(reactApplicationContext)
         

    private val outputFileWav = File(reactApplicationContext.filesDir, RECORDING_FILE_WAV)
    

         init {
            copyAssetsWithExtensionsToDataFolder(reactApplicationContext, arrayOf("bin", "tflite"))
            whisperEngine.initialize(MODEL_PATH, getFilePath(VOCAB_PATH), false)
            recorder.setFilePath(getFilePath(RECORDING_FILE_WAV) )
  



        }
            companion object {
          
        // init {
        //     System.loadLibrary("whisper")
        // }
    

        private const val MODEL_PATH = "models/whisper-tiny-en.tflite"
        private const val RECORDING_FILE_WAV = "recording.wav"
        private const val VOCAB_PATH = "filters_vocab_en.bin"
    }
    private val singleThreadDispatcher = Executors.newFixedThreadPool(4).asCoroutineDispatcher()
    override fun getName(): String {
        return "WhisperModule"
    }

  

    @ReactMethod
    fun startRecording() {
         Log.d(LOG_TAG, "Recording Started")
         recorder.start()

      
    }

    @ReactMethod
    fun stopRecording(promise: Promise) {
        Log.d(LOG_TAG, "Recording Stopped")
        recorder?.stop()
 
        CoroutineScope(singleThreadDispatcher).launch {
            val transcribedText = whisperEngine.transcribeFile(outputFileWav.absolutePath)
            promise.resolve(transcribedText)
            Log.d(LOG_TAG, "Transcribed Text: $transcribedText")
        }

}

     
    private fun getFilePath(assetName: String): String {
    val outfile = File(reactApplicationContext.filesDir, assetName)
    if (!outfile.exists()) {
        Log.d(LOG_TAG, "File not found")
    }

    Log.d(LOG_TAG, "Returned asset path")
    return outfile.absolutePath
}



    // Copy assets to data folder
    private fun copyAssetsWithExtensionsToDataFolder(context: ReactApplicationContext, extensions: Array<String>) {
        val assetManager = context.assets
        try {
            // Specify the destination directory in the app's data folder
            val destFolder = context.filesDir.absolutePath
            for (extension in extensions) {
                // List all files in the assets folder with the specified extension
                val assetFiles: Array<String>? = assetManager.list("")
                if (assetFiles != null) {
                    for (assetFileName in assetFiles) {
                        if (assetFileName.endsWith(".$extension")) {
                            val outFile = File(destFolder, assetFileName)
                            if (outFile.exists()) continue
                            val inputStream = assetManager.open(assetFileName)
                            val outputStream: OutputStream = FileOutputStream(outFile)

                            // Copy the file from assets to the data folder
                            val buffer = ByteArray(1024)
                            var read: Int
                            while (inputStream.read(buffer).also { read = it } != -1) {
                                outputStream.write(buffer, 0, read)
                            }
                            inputStream.close()
                            outputStream.flush()
                            outputStream.close()
                        }
                    }
                }
            }
        } catch (e: IOException) {
            e.printStackTrace()
        }
    }


    // Decode WAV file to FloatArray
    private fun decodeWav(file: File): FloatArray {
        val baos = file.inputStream().use { inputStream ->
            ByteArrayOutputStream().apply {
                inputStream.copyTo(this)
            }
        }
        val buffer = ByteBuffer.wrap(baos.toByteArray())
        buffer.order(ByteOrder.LITTLE_ENDIAN)
        val channel = buffer.getShort(22).toInt()
        buffer.position(44)
        val shortBuffer = buffer.asShortBuffer()
        val shortArray = ShortArray(shortBuffer.limit())
        shortBuffer.get(shortArray)
        return FloatArray(shortArray.size / channel) { index ->
            when (channel) {
                1 -> (shortArray[index] / 32767.0f).coerceIn(-1f..1f)
                else -> ((shortArray[2 * index] + shortArray[2 * index + 1]) / 32767.0f / 2.0f).coerceIn(-1f..1f)
            }
        }
    }

  
}

