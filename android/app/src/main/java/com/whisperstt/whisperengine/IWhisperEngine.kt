package com.whisperstt.whisperengine

interface IWhisperEngine {
    val isInitialized: Boolean

    fun initialize(modelPath: String): Boolean

    // 16kHz mono PCM in [-1, 1]; at most 30s is used
    fun transcribeSamples(samples: FloatArray): String

    fun release()
}
