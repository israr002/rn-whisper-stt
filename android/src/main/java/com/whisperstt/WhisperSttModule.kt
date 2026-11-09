package com.whisperstt

import android.Manifest
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.content.ContextCompat
import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.ReadableMap
import com.facebook.react.modules.core.DeviceEventManagerModule
import com.whisperstt.recorder.RecordingService
import com.whisperstt.recorder.StreamingTranscriber
import com.whisperstt.whisperengine.IWhisperEngine
import com.whisperstt.whisperengine.WhisperCppEngine
import java.util.concurrent.Executors

private const val LOG_TAG = "WhisperStt"

class WhisperSttModule(reactContext: ReactApplicationContext) :
  NativeWhisperSttSpec(reactContext) {

  private val engine: IWhisperEngine = WhisperCppEngine()
  private val models = ModelDownloader(reactContext)

  // Single thread: model init, then every transcription job, strictly in order
  private val worker = Executors.newSingleThreadExecutor { Thread(it, "WhisperWorker") }
  // Separate so a download never delays transcription
  private val downloads = Executors.newSingleThreadExecutor { Thread(it, "WhisperDownload") }

  @Volatile private var transcriber: StreamingTranscriber? = null
  private var usingService = false
  private val finalSegments = sortedMapOf<Int, String>()

  override fun getName(): String = NAME

  // --- Models ---------------------------------------------------------------------------------

  override fun downloadModel(url: String, fileName: String, promise: Promise) {
    downloads.execute {
      try {
        val file = models.find(fileName) ?: models.download(url, fileName) { written, total ->
          emit(EVENT_DOWNLOAD_PROGRESS, Arguments.createMap().apply {
            putString("fileName", fileName)
            putDouble("bytesWritten", written.toDouble())
            putDouble("totalBytes", total.toDouble())
            putDouble("progress", if (total > 0) written.toDouble() / total else -1.0)
          })
        }
        promise.resolve(file.absolutePath)
      } catch (e: Exception) {
        Log.e(LOG_TAG, "Download failed", e)
        promise.reject(E_DOWNLOAD, e.message ?: "Download failed", e)
      }
    }
  }

  override fun getModelPath(fileName: String, promise: Promise) {
    downloads.execute { promise.resolve(models.find(fileName)?.absolutePath) }
  }

  override fun deleteModel(fileName: String, promise: Promise) {
    downloads.execute {
      models.delete(fileName)
      promise.resolve(null)
    }
  }

  // --- Engine ---------------------------------------------------------------------------------

  override fun loadModel(modelPath: String, promise: Promise) {
    if (transcriber != null) return promise.reject(E_ALREADY_RECORDING, "Stop recording before loading a model")
    worker.execute {
      if (engine.initialize(modelPath)) {
        promise.resolve(null)
      } else {
        promise.reject(E_MODEL, "Failed to load model at $modelPath")
      }
    }
  }

  override fun isInitialized(): Boolean = engine.isInitialized

  override fun releaseModel(promise: Promise) {
    if (transcriber != null) return promise.reject(E_ALREADY_RECORDING, "Stop recording before releasing the model")
    worker.execute {
      engine.release()
      promise.resolve(null)
    }
  }

  // --- Recording ------------------------------------------------------------------------------

  override fun startRecording(options: ReadableMap, promise: Promise) {
    if (!engine.isInitialized) return promise.reject(E_NOT_INITIALIZED, "Call init() before startRecording()")
    if (transcriber != null) return promise.reject(E_ALREADY_RECORDING, "Already recording")
    if (ContextCompat.checkSelfPermission(reactApplicationContext, Manifest.permission.RECORD_AUDIO)
      != PackageManager.PERMISSION_GRANTED
    ) {
      return promise.reject(E_PERMISSION, "RECORD_AUDIO permission not granted")
    }

    usingService = options.bool("foregroundService") ?: true
    if (usingService) {
      try {
        RecordingService.start(
          reactApplicationContext,
          options.string("notificationTitle"),
          options.string("notificationText"),
        )
      } catch (e: Exception) {
        // e.g. ForegroundServiceStartNotAllowedException when called from the background
        return promise.reject(E_RECORDING, "Could not start the recording service: ${e.message}", e)
      }
    }

    synchronized(finalSegments) { finalSegments.clear() }
    transcriber = StreamingTranscriber(reactApplicationContext, engine, worker, listener, configFrom(options))
      .also { it.start() }
    Log.d(LOG_TAG, "Recording started")
    promise.resolve(null)
  }

  override fun stopRecording(promise: Promise) {
    val t = transcriber ?: return promise.resolve("")
    transcriber = null
    Thread {
      try {
        t.stop()
        val text = synchronized(finalSegments) { finalSegments.values.filter { it.isNotEmpty() }.joinToString(" ") }
        promise.resolve(text)
      } catch (e: Exception) {
        promise.reject(E_RECORDING, e.message ?: "Failed to stop recording", e)
      } finally {
        if (usingService) RecordingService.stop(reactApplicationContext)
      }
    }.start()
  }

  override fun isRecording(): Boolean = transcriber != null

  // Required by NativeEventEmitter
  override fun addListener(eventName: String) {}
  override fun removeListeners(count: Double) {}

  private val listener = object : StreamingTranscriber.Listener {
    override fun onTranscript(segmentId: Int, text: String, isFinal: Boolean, audioMs: Int, latencyMs: Long) {
      if (isFinal) synchronized(finalSegments) { finalSegments[segmentId] = text }
      emit(EVENT_TRANSCRIPT, Arguments.createMap().apply {
        putInt("id", segmentId)
        putString("text", text)
        putBoolean("isFinal", isFinal)
        putInt("audioMs", audioMs)
        putDouble("latencyMs", latencyMs.toDouble())
      })
    }

    override fun onError(message: String) {
      emit(EVENT_ERROR, Arguments.createMap().apply {
        putString("code", E_RECORDING)
        putString("message", message)
      })
    }
  }

  private fun configFrom(o: ReadableMap): StreamingTranscriber.Config {
    val d = StreamingTranscriber.Config()
    return d.copy(
      speechThreshold = o.number("speechThreshold")?.toFloat() ?: d.speechThreshold,
      silenceThreshold = o.number("silenceThreshold")?.toFloat() ?: d.silenceThreshold,
      endSilenceMs = o.number("endSilenceMs")?.toInt() ?: d.endSilenceMs,
      minUtteranceMs = o.number("minUtteranceMs")?.toInt() ?: d.minUtteranceMs,
      maxUtteranceMs = o.number("maxUtteranceMs")?.toInt()?.coerceAtMost(29_000) ?: d.maxUtteranceMs,
      preRollMs = o.number("preRollMs")?.toInt() ?: d.preRollMs,
      livePartials = o.bool("livePartials") ?: d.livePartials,
      partialIntervalMs = o.number("partialIntervalMs")?.toInt() ?: d.partialIntervalMs,
    )
  }

  private fun ReadableMap.number(key: String): Double? = if (hasKey(key) && !isNull(key)) getDouble(key) else null
  private fun ReadableMap.bool(key: String): Boolean? = if (hasKey(key) && !isNull(key)) getBoolean(key) else null
  private fun ReadableMap.string(key: String): String? = if (hasKey(key) && !isNull(key)) getString(key) else null

  private fun emit(event: String, payload: Any) {
    if (!reactApplicationContext.hasActiveReactInstance()) return
    reactApplicationContext
      .getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter::class.java)
      .emit(event, payload)
  }

  override fun invalidate() {
    transcriber?.let { runCatching { it.stop() } }
    transcriber = null
    if (usingService) RecordingService.stop(reactApplicationContext)
    worker.execute { engine.release() }
    worker.shutdown()
    downloads.shutdown()
    super.invalidate()
  }

  companion object {
    const val NAME = NativeWhisperSttSpec.NAME

    const val EVENT_TRANSCRIPT = "WhisperSttTranscript"
    const val EVENT_ERROR = "WhisperSttError"
    const val EVENT_DOWNLOAD_PROGRESS = "WhisperSttDownloadProgress"

    const val E_NOT_INITIALIZED = "E_NOT_INITIALIZED"
    const val E_MODEL = "E_MODEL"
    const val E_DOWNLOAD = "E_DOWNLOAD"
    const val E_PERMISSION = "E_PERMISSION"
    const val E_ALREADY_RECORDING = "E_ALREADY_RECORDING"
    const val E_RECORDING = "E_RECORDING"
  }
}
