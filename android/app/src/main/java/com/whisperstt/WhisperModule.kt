package com.whisperstt

import android.util.Log
import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.ReactContextBaseJavaModule
import com.facebook.react.bridge.ReactMethod
import com.facebook.react.modules.core.DeviceEventManagerModule
import com.whisperstt.recorder.RecordingService
import com.whisperstt.recorder.StreamingTranscriber
import com.whisperstt.whisperengine.IWhisperEngine
import com.whisperstt.whisperengine.WhisperCppEngine
import java.util.concurrent.Executors

private const val LOG_TAG = "LibWhisper"

class WhisperModule(reactContext: ReactApplicationContext) : ReactContextBaseJavaModule(reactContext) {
    private val whisperEngine: IWhisperEngine = WhisperCppEngine(reactApplicationContext)

    // Single thread: model init, then every transcription job, strictly in order
    private val worker = Executors.newSingleThreadExecutor { Thread(it, "WhisperWorker") }

    private var transcriber: StreamingTranscriber? = null
    private val finalSegments = sortedMapOf<Int, String>()

    init {
        // Load + warm up off the JS thread; recordings queue behind it on the same worker
        worker.execute {
            if (!whisperEngine.initialize(MODEL_PATH)) Log.e(LOG_TAG, "Failed to load $MODEL_PATH")
        }
    }

    override fun getName(): String = "WhisperModule"

    @ReactMethod
    fun startRecording() {
        if (transcriber != null) return
        Log.d(LOG_TAG, "Recording Started")
        synchronized(finalSegments) { finalSegments.clear() }
        RecordingService.start(reactApplicationContext)
        transcriber = StreamingTranscriber(reactApplicationContext, whisperEngine, worker, listener).also { it.start() }
    }

    /** Stops recording; resolves with the full transcript once the last utterance is transcribed. */
    @ReactMethod
    fun stopRecording(promise: Promise) {
        val t = transcriber ?: return promise.resolve("")
        transcriber = null
        Log.d(LOG_TAG, "Recording Stopped")
        Thread {
            try {
                t.stop()
                val text = synchronized(finalSegments) { finalSegments.values.filter { it.isNotEmpty() }.joinToString(" ") }
                Log.d(LOG_TAG, "Transcribed Text: $text")
                promise.resolve(text)
            } catch (e: Exception) {
                promise.reject("E_TRANSCRIBE", e)
            } finally {
                RecordingService.stop(reactApplicationContext)
            }
        }.start()
    }

    // Required by NativeEventEmitter
    @ReactMethod
    fun addListener(eventName: String) {}

    @ReactMethod
    fun removeListeners(count: Int) {}

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
            emit(EVENT_ERROR, Arguments.createMap().apply { putString("message", message) })
        }
    }

    private fun emit(event: String, payload: Any) {
        if (!reactApplicationContext.hasActiveReactInstance()) return
        reactApplicationContext
            .getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter::class.java)
            .emit(event, payload)
    }

    override fun invalidate() {
        transcriber?.let { runCatching { it.stop() } }
        transcriber = null
        RecordingService.stop(reactApplicationContext)
        worker.execute { whisperEngine.release() }
        worker.shutdown()
        super.invalidate()
    }

    companion object {
        private const val MODEL_PATH = "models/ggml-tiny.en-q8_0.bin"
        const val EVENT_TRANSCRIPT = "WhisperTranscript"
        const val EVENT_ERROR = "WhisperError"
    }
}
