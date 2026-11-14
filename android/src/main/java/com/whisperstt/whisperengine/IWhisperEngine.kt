package com.whisperstt.whisperengine

interface IWhisperEngine {
    val isInitialized: Boolean

    // Absolute path to a ggml model file
    fun initialize(modelPath: String): Boolean

    // 16kHz mono PCM in [-1, 1]; at most 30s is used. `prompt`: vocabulary hint, treated as preceding text
    fun transcribeSamples(samples: FloatArray, prompt: String? = null): String

    fun release()
}
