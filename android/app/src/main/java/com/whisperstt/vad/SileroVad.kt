package com.whisperstt.vad

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import java.nio.FloatBuffer
import java.nio.LongBuffer

/**
 * Silero VAD v5 (MIT, https://github.com/snakers4/silero-vad) for 16kHz audio.
 * Feed consecutive [CHUNK_SIZE]-sample chunks; returns speech probability per chunk.
 * Not thread-safe: call from the audio thread only.
 */
class SileroVad(context: Context, assetPath: String = "silero_vad.onnx") : AutoCloseable {
    private val env = OrtEnvironment.getEnvironment()
    private val session: OrtSession

    // v5 expects the last CONTEXT_SIZE samples of the previous chunk prepended to each chunk
    private val input = FloatArray(CONTEXT_SIZE + CHUNK_SIZE)
    private var state = FloatArray(2 * 1 * 128)
    private val srTensor: OnnxTensor

    init {
        val bytes = context.assets.open(assetPath).use { it.readBytes() }
        val opts = OrtSession.SessionOptions().apply {
            setIntraOpNumThreads(1)
            setInterOpNumThreads(1)
        }
        session = env.createSession(bytes, opts)
        srTensor = OnnxTensor.createTensor(env, LongBuffer.wrap(longArrayOf(SAMPLE_RATE.toLong())), longArrayOf())
    }

    fun reset() {
        state.fill(0f)
        input.fill(0f)
    }

    fun process(chunk: FloatArray): Float {
        require(chunk.size == CHUNK_SIZE) { "Silero VAD needs $CHUNK_SIZE samples, got ${chunk.size}" }
        System.arraycopy(chunk, 0, input, CONTEXT_SIZE, CHUNK_SIZE)

        OnnxTensor.createTensor(env, FloatBuffer.wrap(input), longArrayOf(1, input.size.toLong())).use { x ->
            OnnxTensor.createTensor(env, FloatBuffer.wrap(state), longArrayOf(2, 1, 128)).use { s ->
                session.run(mapOf("input" to x, "state" to s, "sr" to srTensor)).use { out ->
                    @Suppress("UNCHECKED_CAST")
                    val prob = (out[0].value as Array<FloatArray>)[0][0]
                    (out[1] as OnnxTensor).floatBuffer.get(state)
                    System.arraycopy(input, CHUNK_SIZE, input, 0, CONTEXT_SIZE)
                    return prob
                }
            }
        }
    }

    override fun close() {
        srTensor.close()
        session.close()
    }

    companion object {
        const val SAMPLE_RATE = 16000
        const val CHUNK_SIZE = 512 // 32ms
        private const val CONTEXT_SIZE = 64
    }
}
