package org.stypox.dicio.io.input.gigaam

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

/**
 * Downloads and stores the GigaAM model (~216 MB) used to transcribe a command after the wake
 * word. Files are served by our own backend, so phones do not depend on HuggingFace.
 *
 * The big encoder is downloaded to a `.part` file and resumed with a Range request: on mobile a
 * 224 MB transfer is easily interrupted, and starting over from zero made the whole thing look
 * stuck.
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

    private val totalBytes: Long = FILES.sumOf { it.second }

    /** Everything is either idle, downloading, or already on disk. */
    data class Status(
        val ready: Boolean,
        val downloading: Boolean,
        val downloadedBytes: Long,
        val totalBytes: Long,
        val error: String? = null,
    ) {
        val percent: Int
            get() = if (totalBytes <= 0L) 0 else ((downloadedBytes * 100) / totalBytes).toInt()
    }

    @Volatile
    private var downloading = false

    @Volatile
    private var lastError: String? = null

    fun dir(context: Context): File = File(context.filesDir, "gigaam")

    fun isReady(context: Context): Boolean {
        val folder = dir(context)
        return FILES.all { (name, size) ->
            File(folder, name).let { it.isFile && it.length() == size }
        }
    }

    fun status(context: Context): Status {
        val folder = dir(context)
        // count the partial files too, otherwise the UI shows 0% while hundreds of megabytes are
        // already on disk
        val downloaded = FILES.sumOf { (name, _) ->
            val done = File(folder, name).let { if (it.isFile) it.length() else 0L }
            val partial = File(folder, "$name.part").let { if (it.isFile) it.length() else 0L }
            done + partial
        }.coerceAtMost(totalBytes)
        return Status(
            ready = isReady(context),
            downloading = downloading,
            downloadedBytes = downloaded,
            totalBytes = totalBytes,
            error = lastError,
        )
    }

    /** Starts the download in the background; does nothing when the model is already there. */
    fun downloadIfNeeded(context: Context) {
        if (downloading || isReady(context)) {
            return
        }
        downloading = true
        lastError = null
        thread(name = "gigaam-download") {
            try {
                val folder = dir(context)
                folder.mkdirs()
                for ((name, size) in FILES) {
                    val target = File(folder, name)
                    if (target.isFile && target.length() == size) {
                        continue
                    }
                    val error = download("$BASE_URL/$name", target, size)
                    if (error != null) {
                        lastError = "$name: $error"
                        Log.w(TAG, "could not download $name: $error")
                        return@thread
                    }
                }
                Log.i(TAG, "GigaAM model is ready")
            } catch (t: Throwable) {
                lastError = t.message ?: t.javaClass.simpleName
                Log.w(TAG, "download crashed", t)
            } finally {
                downloading = false
            }
        }
    }

    /** @return null on success, otherwise a short reason for the UI. */
    private fun download(url: String, target: File, size: Long): String? {
        val partial = File(target.parentFile, target.name + ".part")
        var have = if (partial.isFile) partial.length() else 0L
        if (have > size) {
            // a leftover from a different build of the model: start clean
            partial.delete()
            have = 0
        }
        if (have == size) {
            return if (partial.renameTo(target)) null else "не удалось переименовать файл"
        }

        return try {
            val connection = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 20000
                readTimeout = 60000
                instanceFollowRedirects = true
                if (have > 0) {
                    setRequestProperty("Range", "bytes=$have-")
                }
            }
            val code = connection.responseCode
            if (code !in 200..299) {
                connection.disconnect()
                return "сервер ответил $code"
            }
            // 200 means the server ignored the Range header and sends the whole file again
            val append = have > 0 && code == 206
            if (!append) {
                have = 0
            }
            connection.inputStream.use { input ->
                FileOutputStream(partial, append).use { output ->
                    val buffer = ByteArray(256 * 1024)
                    while (true) {
                        val read = input.read(buffer)
                        if (read <= 0) break
                        output.write(buffer, 0, read)
                    }
                }
            }
            connection.disconnect()

            if (partial.length() != size) {
                // keep the partial file: the next attempt continues from here
                return "скачано ${partial.length() / 1024 / 1024} из ${size / 1024 / 1024} МБ"
            }
            if (partial.renameTo(target)) null else "не удалось переименовать файл"
        } catch (t: Throwable) {
            Log.w(TAG, "download failed: $url", t)
            "обрыв связи (${t.javaClass.simpleName}), продолжаем с ${partial.length() / 1024 / 1024} МБ"
        }
    }
}
