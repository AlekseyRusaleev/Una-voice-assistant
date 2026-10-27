package org.stypox.dicio.io.input.gigaam

import android.content.Context
import android.util.Log
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

/**
 * Downloads and stores the GigaAM model (~216 MB) used to transcribe a command after the wake
 * word. The files are served by our own backend, so phones do not depend on HuggingFace.
 */
object GigaAmModel {
    private const val TAG = "GigaAmModel"
    private const val BASE_URL = "https://9.247.112.23.sslip.io/models/gigaam"

    private val FILES = listOf(
        "encoder.int8.onnx" to 224_571_327L,
        "decoder.onnx" to 1_159_351L,
        "joiner.onnx" to 687_791L,
        "tokens.txt" to 13_354L,
    )

    /** Everything is either idle, downloading, or already on disk. */
    data class Status(
        val ready: Boolean,
        val downloading: Boolean,
        val downloadedBytes: Long,
        val totalBytes: Long,
    ) {
        val percent: Int
            get() = if (totalBytes <= 0L) 0 else ((downloadedBytes * 100) / totalBytes).toInt()
    }

    @Volatile
    private var downloading = false

    fun dir(context: Context): File = File(context.filesDir, "gigaam")

    fun isReady(context: Context): Boolean {
        val folder = dir(context)
        return FILES.all { (name, _) -> File(folder, name).let { it.isFile && it.length() > 0 } }
    }

    fun status(context: Context): Status {
        val folder = dir(context)
        val ready = isReady(context)
        val downloaded = FILES.sumOf { (name, _) ->
            File(folder, name).let { if (it.isFile) it.length() else 0L }
        }
        return Status(
            ready = ready,
            downloading = downloading,
            downloadedBytes = downloaded,
            totalBytes = FILES.sumOf { it.second },
        )
    }

    /** Starts the download in the background; does nothing when the model is already there. */
    fun downloadIfNeeded(context: Context) {
        if (downloading || isReady(context)) {
            return
        }
        downloading = true
        thread(name = "gigaam-download") {
            try {
                val folder = dir(context)
                folder.mkdirs()
                for ((name, _) in FILES) {
                    val target = File(folder, name)
                    if (target.isFile && target.length() > 0) {
                        continue
                    }
                    if (!download("$BASE_URL/$name", target)) {
                        Log.w(TAG, "could not download $name")
                        return@thread
                    }
                }
                Log.i(TAG, "GigaAM model is ready")
            } finally {
                downloading = false
            }
        }
    }

    private fun download(url: String, target: File): Boolean {
        val partial = File(target.parentFile, target.name + ".part")
        return try {
            val connection = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 20000
                readTimeout = 60000
                instanceFollowRedirects = true
            }
            if (connection.responseCode !in 200..299) {
                Log.w(TAG, "HTTP ${connection.responseCode} for $url")
                return false
            }
            connection.inputStream.use { input ->
                partial.outputStream().use { output -> input.copyTo(output, 256 * 1024) }
            }
            partial.renameTo(target)
        } catch (t: Throwable) {
            Log.w(TAG, "download failed: $url", t)
            partial.delete()
            false
        }
    }
}
