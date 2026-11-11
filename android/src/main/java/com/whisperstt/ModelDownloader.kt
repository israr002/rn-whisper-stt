package com.whisperstt

import android.content.Context
import android.os.SystemClock
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** Downloads ggml models into app storage and finds previously downloaded ones. */
class ModelDownloader(context: Context) {
    private val dir = File(context.filesDir, "whisper-models")

    /** A complete, valid model file, or null. */
    fun find(fileName: String): File? = fileFor(fileName).takeIf { it.isFile && isGgml(it) }

    fun delete(fileName: String) {
        fileFor(fileName).delete()
        File(dir, "$fileName.part").delete()
    }

    /** Blocking; call off the main thread. [onProgress] is throttled to ~10 updates/s. */
    fun download(url: String, fileName: String, onProgress: (written: Long, total: Long) -> Unit): File {
        val target = fileFor(fileName)
        dir.mkdirs()
        // Written to .part and renamed only when complete, so an interrupted download is never used
        val part = File(dir, "$fileName.part")

        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 30_000
            instanceFollowRedirects = true // Hugging Face redirects to its CDN
        }
        try {
            if (conn.responseCode != HttpURLConnection.HTTP_OK) {
                throw IOException("HTTP ${conn.responseCode} downloading $url")
            }
            val total = conn.contentLengthLong
            var written = 0L
            var lastReport = 0L
            conn.inputStream.use { input ->
                part.outputStream().use { output ->
                    val buffer = ByteArray(256 * 1024)
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        output.write(buffer, 0, n)
                        written += n
                        val now = SystemClock.elapsedRealtime()
                        if (now - lastReport >= 100) {
                            lastReport = now
                            onProgress(written, total)
                        }
                    }
                }
            }
            onProgress(written, total)
            if (total > 0 && written != total) throw IOException("Download incomplete: $written of $total bytes")
            if (!isGgml(part)) throw IOException("Downloaded file is not a ggml model")
            if (!part.renameTo(target)) throw IOException("Could not move model into place")
            return target
        } catch (e: Exception) {
            part.delete()
            throw e
        } finally {
            conn.disconnect()
        }
    }

    private fun fileFor(fileName: String): File {
        require(fileName.isNotEmpty() && !fileName.contains('/') && !fileName.startsWith(".")) {
            "Invalid model file name: $fileName"
        }
        return File(dir, fileName)
    }

    // whisper.cpp ggml files start with the magic 0x67676d6c, stored little-endian ("lmgg")
    private fun isGgml(file: File): Boolean = runCatching {
        file.inputStream().use { val b = ByteArray(4); it.read(b) == 4 && String(b, Charsets.US_ASCII) == "lmgg" }
    }.getOrDefault(false)
}
