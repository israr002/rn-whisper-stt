package com.whisperstt.whisperengine

import java.io.IOException

interface IWhisperEngine {
    val isInitialized: Boolean

    @Throws(IOException::class)
    fun initialize(modelPath: String?, vocabPath: String?, multilingual: Boolean): Boolean
    fun transcribeFile(wavePath: String?): String
}
