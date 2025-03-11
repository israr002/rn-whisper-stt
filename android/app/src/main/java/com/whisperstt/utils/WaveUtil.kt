package com.whisperstt.utils

import android.util.Log
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets

object WaveUtil {
    const val TAG = "WaveUtil"
    fun createWaveFile(
        filePath: String?,
        samples: ByteArray,
        sampleRate: Int,
        numChannels: Int,
        bytesPerSample: Int
    ) {
        try {
            val dataSize = samples.size // actual data size in bytes
            val audioFormat =
                if (bytesPerSample == 2) 1 else if (bytesPerSample == 4) 3 else 0 // PCM_16 = 1, PCM_FLOAT = 3
            val fileOutputStream = FileOutputStream(filePath)
            fileOutputStream.write("RIFF".toByteArray(StandardCharsets.UTF_8)) // Write the "RIFF" chunk descriptor
            fileOutputStream.write(intToByteArray(36 + dataSize), 0, 4) // Total file size - 8 bytes
            fileOutputStream.write("WAVE".toByteArray(StandardCharsets.UTF_8)) // Write the "WAVE" format
            fileOutputStream.write("fmt ".toByteArray(StandardCharsets.UTF_8)) // Write the "fmt " sub-chunk
            fileOutputStream.write(intToByteArray(16), 0, 4) // Sub-chunk size (16 for PCM)
            fileOutputStream.write(
                shortToByteArray(audioFormat.toShort().toInt()),
                0,
                2
            ) // Audio format (1 for PCM)
            fileOutputStream.write(
                shortToByteArray(numChannels.toShort().toInt()),
                0,
                2
            ) // Number of channels
            fileOutputStream.write(intToByteArray(sampleRate), 0, 4) // Sample rate
            fileOutputStream.write(
                intToByteArray(sampleRate * numChannels * bytesPerSample),
                0,
                4
            ) // Byte rate
            fileOutputStream.write(
                shortToByteArray(
                    (numChannels * bytesPerSample).toShort().toInt()
                ), 0, 2
            ) // Block align
            fileOutputStream.write(
                shortToByteArray((bytesPerSample * 8).toShort().toInt()),
                0,
                2
            ) // Bits per sample
            fileOutputStream.write("data".toByteArray(StandardCharsets.UTF_8)) // Write the "data" sub-chunk
            fileOutputStream.write(intToByteArray(dataSize), 0, 4) // Data size

            // Write audio samples
            fileOutputStream.write(samples)

            // Close the file output stream
            fileOutputStream.close()
        } catch (e: IOException) {
            e.printStackTrace()
            Log.e(TAG, "Error...", e)
        }
    }

    @JvmStatic
    fun getSamples(filePath: String?): FloatArray {
        try {
            val fileInputStream = FileInputStream(filePath)

            // Read the WAV file header
            val header = ByteArray(44)
            fileInputStream.read(header)

            // Check if it's a valid WAV file (contains "RIFF" and "WAVE" markers)
            val headerStr = String(header, 0, 4)
            if (headerStr != "RIFF") {
                System.err.println("Not a valid WAV file")
                return FloatArray(0)
            }

            // Get the audio format details from the header
            val sampleRate = byteArrayToNumber(header, 24, 4)
            val bitsPerSample = byteArrayToNumber(header, 34, 2)
            if (bitsPerSample != 16 && bitsPerSample != 32) {
                System.err.println("Unsupported bits per sample: $bitsPerSample")
                return FloatArray(0)
            }

            // Get the size of the data section (all PCM data)
            val dataLength = fileInputStream.available() // byteArrayToInt(header, 40, 4);

            // Calculate the number of samples
            val bytesPerSample = bitsPerSample / 8
            val numSamples = dataLength / bytesPerSample

            // Read the audio data
            val audioData = ByteArray(dataLength)
            fileInputStream.read(audioData)
            val byteBuffer = ByteBuffer.wrap(audioData)
            byteBuffer.order(ByteOrder.nativeOrder())

            // Convert audio data to PCM_FLOAT format
            val samples = FloatArray(numSamples)
            if (bitsPerSample == 16) {
                for (i in 0 until numSamples) {
                    samples[i] = (byteBuffer.getShort() / 32768.0).toFloat()
                }
            } else if (bitsPerSample == 32) {
                for (i in 0 until numSamples) {
                    samples[i] = byteBuffer.getFloat()
                }
            }
            return samples
        } catch (e: IOException) {
            e.printStackTrace()
            Log.e(TAG, "Error...", e)
        }
        return FloatArray(0)
    }

    // Convert a portion of a byte array into an integer or a short
    private fun byteArrayToNumber(bytes: ByteArray, offset: Int, length: Int): Int {
        var value = 0 // Start with an initial value of 0

        // Loop through the specified portion of the byte array
        for (i in 0 until length) {
            // Extract a byte, ensure it's positive, and shift it to its position in the integer
            value = value or (bytes[offset + i].toInt() and 0xFF shl 8 * i)
        }
        return value // Return the resulting integer value
    }

    private fun intToByteArray(value: Int): ByteArray {
        val byteArray = ByteArray(4) // Create a 4-byte array

        // Convert and store the bytes in little-endian order
        byteArray[0] = (value and 0xFF).toByte() // Least significant byte (LSB)
        byteArray[1] = (value shr 8 and 0xFF).toByte() // Second least significant byte
        byteArray[2] = (value shr 16 and 0xFF).toByte() // Second most significant byte
        byteArray[3] = (value shr 24 and 0xFF).toByte() // Most significant byte (MSB)
        return byteArray
    }

    private fun shortToByteArray(value: Int): ByteArray {
        val byteArray = ByteArray(2) // Create a 2-byte array

        // Convert and store the bytes in little-endian order
        byteArray[0] = (value and 0xFF).toByte() // Least significant byte (LSB)
        byteArray[1] = (value shr 8 and 0xFF).toByte() // Most significant byte (MSB)
        return byteArray
    }
}
