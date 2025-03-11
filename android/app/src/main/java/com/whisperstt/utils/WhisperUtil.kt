package com.whisperstt.utils

import android.util.Log
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Paths
import java.util.Arrays
import java.util.concurrent.CountDownLatch

class WhisperUtil {
    private val vocab = WhisperVocab()
    private val filters = WhisperFilter()
    private val mel = WhisperMel()
    val tokenTranslate: Int
        // Helper functions definitions
        get() = vocab.tokenTRANSLATE
    val tokenTranscribe: Int
        get() = vocab.tokenTRANSCRIBE
    val tokenEOT: Int
        get() = vocab.tokenEOT
    val tokenSOT: Int
        get() = vocab.tokenSOT
    val tokenPREV: Int
        get() = vocab.tokenPREV
    val tokenSOLM: Int
        get() = vocab.tokenSOLM
    val tokenNOT: Int
        get() = vocab.tokenNOT
    val tokenBEG: Int
        get() = vocab.tokenBEG

    fun getFilters(): FloatArray {
        return filters.data
    }

    fun getWordFromToken(token: Int): String? {
        return vocab.tokenToWord[token]
    }

    // Load filters and vocab data from pre-generated filters_vocab_en.bin file
    @Throws(IOException::class)
    fun loadFiltersAndVocab(multilingual: Boolean, vocabPath: String): Boolean {

        // Read vocab file
        val bytes = Files.readAllBytes(Paths.get(vocabPath))
        val vocabBuf = ByteBuffer.wrap(bytes)
        vocabBuf.order(ByteOrder.nativeOrder())
        Log.d(TAG, "Vocab file size: " + vocabBuf.limit())

        val magic = vocabBuf.getInt()
        if (magic == 0x5553454e) {
            Log.d(TAG, "Magic number: $magic")
        } else {
            Log.d(TAG, "Invalid vocab file (bad magic: $magic), $vocabPath")
            return false
        }

        // Load mel filters
        filters.nMel = vocabBuf.getInt()
        filters.nFft = vocabBuf.getInt()
        Log.d(TAG, "n_mel:" + filters.nMel + ", n_fft:" + filters.nFft)
        val filterData = ByteArray(filters.nMel * filters.nFft * java.lang.Float.BYTES)
        vocabBuf[filterData, 0, filterData.size]
        val filterBuf = ByteBuffer.wrap(filterData)
        filterBuf.order(ByteOrder.nativeOrder())
        filters.data = FloatArray(filters.nMel * filters.nFft)
        run {
            var i = 0
            while (filterBuf.hasRemaining()) {
                filters.data[i] = filterBuf.getFloat()
                i++
            }
        }

        // Load vocabulary
        val nVocab = vocabBuf.getInt()
        Log.d(TAG, "nVocab: $nVocab")
        for (i in 0 until nVocab) {
            val len = vocabBuf.getInt()
            val wordBytes = ByteArray(len)
            vocabBuf[wordBytes, 0, wordBytes.size]
            val word = String(wordBytes)
            vocab.tokenToWord[i] = word
        }

        // Add additional vocab ids
        val nVocabAdditional: Int
        if (!multilingual) {
            nVocabAdditional = vocab.nVocabEnglish
        } else {
            nVocabAdditional = vocab.nVocabMultilingual
            vocab.tokenEOT++
            vocab.tokenSOT++
            vocab.tokenPREV++
            vocab.tokenSOLM++
            vocab.tokenNOT++
            vocab.tokenBEG++
        }
        for (i in nVocab until nVocabAdditional) {
            var word: String
            word = if (i > vocab.tokenBEG) {
                "[_TT_" + (i - vocab.tokenBEG) + "]"
            } else if (i == vocab.tokenEOT) {
                "[_EOT_]"
            } else if (i == vocab.tokenSOT) {
                "[_SOT_]"
            } else if (i == vocab.tokenPREV) {
                "[_PREV_]"
            } else if (i == vocab.tokenNOT) {
                "[_NOT_]"
            } else if (i == vocab.tokenBEG) {
                "[_BEG_]"
            } else {
                "[_extra_token_$i]"
            }
            vocab.tokenToWord[i] = word
            //Log.d(TAG, "i= " + i + ", word= " + word);
        }
        return true
    }

    // If you want to implement log_mel_spectrogram in kotlin
    fun getMelSpectrogram(samples: FloatArray, nSamples: Int, nThreads: Int): FloatArray {
        val fftSize = WHISPER_N_FFT
        val fftStep = WHISPER_HOP_LENGTH
        mel.nMel = WHISPER_N_MEL
        mel.nLen = nSamples / fftStep
        mel.data = FloatArray(mel.nMel * mel.nLen)
        val hann = FloatArray(fftSize)
        for (i in 0 until fftSize) {
            hann[i] = (0.5 * (1.0 - Math.cos(2.0 * Math.PI * i / fftSize))).toFloat()
        }
        val nFft = 1 + fftSize / 2

/////////////// UNCOMMENT below block to use multithreaded mel calculation /////////////////////////
        // Calculate mel values using multiple threads
        val workers: MutableList<Thread> = ArrayList()
        for (iw in 0 until nThreads) {
            val thread = Thread {

                // Inside the thread, ith will have the same value as iw (first value is 0)
                Log.d(TAG, "Thread $iw started.")
                val fftIn = FloatArray(fftSize)
                Arrays.fill(fftIn, 0.0f)
                val fftOut = FloatArray(fftSize * 2)
                var i = iw
                while (i < mel.nLen) {

/////////////// END of Block ///////////////////////////////////////////////////////////////////////

/////////////// COMMENT below block to use multithreaded mel calculation ///////////////////////////
//        float[] fftIn = new float[fftSize];
//        Arrays.fill(fftIn, 0.0f);
//        float[] fftOut = new float[fftSize * 2];
//
//        for (int i = 0; i < mel.nLen; i++) {
/////////////// END of Block ///////////////////////////////////////////////////////////////////////
                    val offset = i * fftStep

                    // apply Hanning window
                    for (j in 0 until fftSize) {
                        if (offset + j < nSamples) {
                            fftIn[j] = hann[j] * samples[offset + j]
                        } else {
                            fftIn[j] = 0.0f
                        }
                    }

                    // FFT -> mag^2
                    fft(fftIn, fftOut)
                    for (j in 0 until fftSize) {
                        fftOut[j] =
                            fftOut[2 * j] * fftOut[2 * j] + fftOut[2 * j + 1] * fftOut[2 * j + 1]
                    }
                    for (j in 1 until fftSize / 2) {
                        fftOut[j] += fftOut[fftSize - j]
                    }

                    // mel spectrogram
                    for (j in 0 until mel.nMel) {
                        var sum = 0.0
                        for (k in 0 until nFft) {
                            sum += (fftOut[k] * filters.data[j * nFft + k]).toDouble()
                        }
                        if (sum < 1e-10) {
                            sum = 1e-10
                        }
                        sum = Math.log10(sum)
                        mel.data[j * mel.nLen + i] = sum.toFloat()
                    }
                    i += nThreads
                }
            }
            workers.add(thread)
            thread.start()
        }

        // Wait for all threads to finish
        for (worker in workers) {
            try {
                worker.join()
            } catch (e: InterruptedException) {
                e.printStackTrace()
            }
        }
        /////////////// END of Block ///////////////////////////////////////////////////////////////////////

        // clamping and normalization
        var mmax = -1e20
        for (i in 0 until mel.nMel * mel.nLen) {
            if (mel.data[i] > mmax) {
                mmax = mel.data[i].toDouble()
            }
        }
        mmax -= 8.0
        for (i in 0 until mel.nMel * mel.nLen) {
            if (mel.data[i] < mmax) {
                mel.data[i] = mmax.toFloat()
            }
            mel.data[i] = ((mel.data[i] + 4.0) / 4.0).toFloat()
        }
        return mel.data
    }

    fun getMultiMelSpectrogram(samples: FloatArray, nSamples: Int, nThreads: Int): FloatArray {
        val fftSize = WHISPER_N_FFT
        val fftStep = WHISPER_HOP_LENGTH
        mel.nMel = WHISPER_N_MEL
        mel.nLen = nSamples / fftStep
        mel.data = FloatArray(mel.nMel * mel.nLen)
        val hann = FloatArray(fftSize)
        for (i in 0 until fftSize) {
            hann[i] = (0.5 * (1.0 - Math.cos(2.0 * Math.PI * i / fftSize))).toFloat()
        }
        val nFft = 1 + fftSize / 2
        synchronized(mel) {

            // Calculate mel values using multiple threads
            val latch = CountDownLatch(nThreads)
            val workers: MutableList<Thread> = ArrayList()
            for (iw in 0 until nThreads) {
                val thread = Thread {
                    try {
                        // Inside the thread, ith will have the same value as iw (first value is 0)
                        Log.d(TAG, "Thread $iw started.")
                        val fftIn = FloatArray(fftSize)
                        val fftOut = FloatArray(fftSize * 2)
                        var i = iw
                        while (i < mel.nLen) {
                            val offset = i * fftStep

                            // apply Hanning window
                            for (j in 0 until fftSize) {
                                if (offset + j < nSamples) {
                                    fftIn[j] = hann[j] * samples[offset + j]
                                } else {
                                    fftIn[j] = 0.0f
                                }
                            }

                            // FFT -> mag^2
                            fft(fftIn, fftOut)
                            for (j in 0 until fftSize) {
                                fftOut[j] =
                                    fftOut[2 * j] * fftOut[2 * j] + fftOut[2 * j + 1] * fftOut[2 * j + 1]
                            }
                            for (j in 1 until fftSize / 2) {
                                fftOut[j] += fftOut[fftSize - j]
                            }

                            // mel spectrogram
                            for (j in 0 until mel.nMel) {
                                var sum = 0.0
                                for (k in 0 until nFft) {
                                    sum += (fftOut[k] * filters.data[j * nFft + k]).toDouble()
                                }
                                if (sum < 1e-10) {
                                    sum = 1e-10
                                }
                                sum = Math.log10(sum)
                                mel.data[j * mel.nLen + i] = sum.toFloat()
                            }
                            i += nThreads
                        }
                    } catch (e: Exception) {
                        // Log and handle the exception
                        e.printStackTrace()
                    } finally {
                        latch.countDown() // Signal that the thread has finished
                    }
                }
                workers.add(thread)
                thread.start()
            }

            // Wait for all threads to finish concurrently
            try {
                latch.await()
            } catch (e: InterruptedException) {
                e.printStackTrace()
            }

            // clamping and normalization
            var mmax = -1e20
            for (i in 0 until mel.nMel * mel.nLen) {
                if (mel.data[i] > mmax) {
                    mmax = mel.data[i].toDouble()
                }
            }
            mmax -= 8.0
            for (i in 0 until mel.nMel * mel.nLen) {
                if (mel.data[i] < mmax) {
                    mel.data[i] = mmax.toFloat()
                }
                mel.data[i] = ((mel.data[i] + 4.0) / 4.0).toFloat()
            }
        }
        return mel.data
    }

    // Cooley-Tukey FFT
    private fun fft(input: FloatArray, output: FloatArray) {
        val N = input.size
        if (N == 1) {
            output[0] = input[0]
            output[1] = 0f
            return
        }
        if (N % 2 == 1) {
            dft(input, output)
            return
        }
        val even = FloatArray(N / 2)
        val odd = FloatArray(N / 2)
        for (i in 0 until N) {
            if (i % 2 == 0) {
                even[i / 2] = input[i]
            } else {
                odd[i / 2] = input[i]
            }
        }
        val evenFft = FloatArray(N)
        val oddFft = FloatArray(N)
        fft(even, evenFft)
        fft(odd, oddFft)
        for (k in 0 until N / 2) {
            val theta = (2 * Math.PI * k / N).toFloat()
            val re = Math.cos(theta.toDouble()).toFloat()
            val im = -Math.sin(theta.toDouble()).toFloat()
            val reOdd = oddFft[2 * k]
            val imOdd = oddFft[2 * k + 1]
            output[2 * k] = evenFft[2 * k] + re * reOdd - im * imOdd
            output[2 * k + 1] = evenFft[2 * k + 1] + re * imOdd + im * reOdd
            output[2 * (k + N / 2)] = evenFft[2 * k] - re * reOdd + im * imOdd
            output[2 * (k + N / 2) + 1] = evenFft[2 * k + 1] - re * imOdd - im * reOdd
        }
    }

    // Helper class definitions
    private class WhisperVocab {
        var golden_generated_ids = intArrayOf(
            50257, 50362, 1770, 13, 2264, 346, 353, 318,
            262, 46329, 286, 262, 3504, 6097, 11, 290, 356, 389, 9675, 284, 7062
        )

        // Token types
        var tokenEOT = 50256 // end of transcript
        var tokenSOT = 50257 // start of transcript
        var tokenPREV = 50360
        var tokenSOLM = 50361 // ??
        var tokenNOT = 50362 // no timestamps
        var tokenBEG = 50363

        // Available tasks
        val tokenTRANSLATE = 50358
        val tokenTRANSCRIBE = 50359

        // Vocab types
        val nVocabEnglish = 51864 // for english only vocab
        val nVocabMultilingual = 51865 // for multilingual vocab
        var tokenToWord: MutableMap<Int, String> = HashMap()
    }

    private class WhisperFilter {
        var nMel = 0
        var nFft = 0
        lateinit var data: FloatArray
    }

    private class WhisperMel {
        var nLen = 0
        var nMel = 0
        lateinit var data: FloatArray
    }

    companion object {
        private const val TAG = "WhisperUtil"
        const val WHISPER_SAMPLE_RATE = 16000
        const val WHISPER_N_FFT = 400
        const val WHISPER_N_MEL = 80
        const val WHISPER_HOP_LENGTH = 160
        const val WHISPER_CHUNK_SIZE = 30
        const val WHISPER_MEL_LEN = 3000
        private fun dft(`in`: FloatArray, out: FloatArray) {
            val N = `in`.size
            for (k in 0 until N) {
                var re = 0f
                var im = 0f
                for (n in 0 until N) {
                    val angle = (2 * Math.PI * k * n / N).toFloat()
                    re += (`in`[n] * Math.cos(angle.toDouble())).toFloat()
                    im -= (`in`[n] * Math.sin(angle.toDouble())).toFloat()
                }
                out[k * 2] = re
                out[k * 2 + 1] = im
            }
        }
    }
}
