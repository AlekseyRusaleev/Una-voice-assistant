package org.stypox.dicio.update

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.FileProvider
import org.json.JSONObject
import org.stypox.dicio.BuildConfig
import org.stypox.dicio.R
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import kotlin.concurrent.thread

/**
 * Self-update: asks the backend for the newest version, downloads the APK and offers to install it.
 * Android always asks the user to confirm the installation — that cannot be skipped.
 *
 * Everything that happens here is reported as an [UpdateStatus] instead of being written to logcat
 * only: when an update silently "does not arrive", the reason must be visible in the app.
 */
object Updater {
    private const val TAG = "Updater"
    private const val BASE_URL = "https://9.247.112.23.sslip.io"
    private const val CHANNEL_ID = "una_updates"
    private const val NOTIFICATION_ID = 424242

    enum class Outcome { UP_TO_DATE, READY_TO_INSTALL, ERROR }

    data class UpdateStatus(
        val installedCode: Int,
        val installedName: String,
        val remoteCode: Int,
        val remoteName: String,
        val outcome: Outcome,
        val message: String,
        val apk: File? = null,
    )

    /** Background check used on start-up and from the wake service. */
    fun checkAndUpdate(context: Context) {
        thread(name = "una-update") {
            val status = runCatching { checkNow(context) }
                .getOrElse {
                    Log.w(TAG, "update check failed", it)
                    UpdateStatus(
                        BuildConfig.VERSION_CODE, BuildConfig.VERSION_NAME, 0, "",
                        Outcome.ERROR, "Ошибка проверки: ${it.message ?: it.javaClass.simpleName}",
                    )
                }
            Log.i(TAG, "update: ${status.outcome} ${status.message}")
        }
    }

    /**
     * Full synchronous check: fetches the version, downloads the APK if it is newer and posts the
     * install notification. Blocking — call it from a background dispatcher.
     *
     * @param onStage reports what is happening right now, so that the UI can show progress instead
     * of a silent pause: a 57 MB download takes a while on mobile data.
     */
    fun checkNow(context: Context, onStage: (String) -> Unit = {}): UpdateStatus {
        val installedCode = BuildConfig.VERSION_CODE
        val installedName = BuildConfig.VERSION_NAME

        fun error(text: String, remoteCode: Int = 0, remoteName: String = "") =
            UpdateStatus(installedCode, installedName, remoteCode, remoteName, Outcome.ERROR, text)

        onStage("Спрашиваю сервер…")
        val info = fetchVersion() ?: return error("Сервер обновлений недоступен: нет ответа на $BASE_URL")
        val remoteCode = info.optInt("versionCode", 0)
        val remoteName = info.optString("versionName", "новая")

        if (remoteCode <= installedCode) {
            return UpdateStatus(
                installedCode, installedName, remoteCode, remoteName, Outcome.UP_TO_DATE,
                "Установлена последняя версия",
            )
        }

        val url = info.optString("url", "$BASE_URL/download/una.apk")
        val expected = info.optString("sha256", "").lowercase()
        // filesDir, not cacheDir: a 57 MB APK is easily evicted from the cache before it is installed
        val file = File(File(context.filesDir, "updates"), "una-update.apk")
        file.parentFile?.mkdirs()
        file.delete()

        onStage("Скачиваю ${remoteName}…")
        download(url, file) { done, total ->
            val doneMb = done / 1024 / 1024
            onStage(
                if (total > 0) {
                    "Скачиваю $doneMb из ${total / 1024 / 1024} МБ…"
                } else {
                    "Скачиваю $doneMb МБ…"
                }
            )
        }?.let { reason ->
            return error("Не удалось скачать: $reason", remoteCode, remoteName)
        }

        onStage("Проверяю контрольную сумму…")
        if (expected.isNotEmpty() && sha256(file) != expected) {
            val size = file.length() / 1024 / 1024
            file.delete()
            return error("Контрольная сумма не совпала (скачано $size МБ)", remoteCode, remoteName)
        }

        notify(
            context,
            "Обновление $remoteName готово",
            "Нажмите, чтобы установить",
            installPendingIntent(context, file),
        )
        return UpdateStatus(
            installedCode, installedName, remoteCode, remoteName, Outcome.READY_TO_INSTALL,
            "Скачано ${file.length() / 1024 / 1024} МБ, можно устанавливать",
            file,
        )
    }

    /** Opens the system installer for an already downloaded APK. Needs a foreground activity. */
    fun installNow(context: Context, file: File): Boolean = try {
        if (!file.exists()) {
            false
        } else {
            context.startActivity(
                Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(
                        FileProvider.getUriForFile(
                            context, "${context.packageName}.files", file,
                        ),
                        "application/vnd.android.package-archive",
                    )
                    addFlags(
                        Intent.FLAG_GRANT_READ_URI_PERMISSION or
                            Intent.FLAG_ACTIVITY_NEW_TASK
                    )
                }
            )
            true
        }
    } catch (t: Throwable) {
        Log.w(TAG, "cannot start the installer", t)
        false
    }

    fun notificationsEnabled(context: Context): Boolean =
        androidx.core.app.NotificationManagerCompat.from(context).areNotificationsEnabled()

    private fun fetchVersion(): JSONObject? = try {
        val connection = (URL("$BASE_URL/version").openConnection() as HttpURLConnection).apply {
            connectTimeout = 10000
            readTimeout = 15000
        }
        val code = connection.responseCode
        if (code !in 200..299) {
            Log.w(TAG, "version endpoint HTTP $code")
            null
        } else {
            val body = connection.inputStream.bufferedReader().use { it.readText() }
            connection.disconnect()
            JSONObject(body)
        }
    } catch (t: Throwable) {
        Log.w(TAG, "cannot fetch version", t)
        null
    }

    /** @return null on success, or a short human-readable reason for the failure. */
    private fun download(
        url: String,
        target: File,
        onProgress: (done: Long, total: Long) -> Unit = { _, _ -> },
    ): String? = try {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15000
            readTimeout = 60000
            instanceFollowRedirects = true
        }
        val code = connection.responseCode
        if (code !in 200..299) {
            Log.w(TAG, "APK download HTTP $code")
            "сервер ответил $code"
        } else {
            val total = connection.contentLengthLong.coerceAtLeast(0L)
            connection.inputStream.use { input ->
                target.outputStream().use { output ->
                    val buffer = ByteArray(256 * 1024)
                    var done = 0L
                    var lastReport = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read <= 0) break
                        output.write(buffer, 0, read)
                        done += read
                        // report at most every 2 MB so the UI is not flooded with updates
                        if (done - lastReport >= 2 * 1024 * 1024) {
                            lastReport = done
                            onProgress(done, total)
                        }
                    }
                    onProgress(done, total)
                }
            }
            connection.disconnect()
            if (target.length() < 1024 * 1024) {
                "получено всего ${target.length()} байт"
            } else {
                null
            }
        }
    } catch (t: Throwable) {
        Log.w(TAG, "APK download failed", t)
        "${t.javaClass.simpleName}: ${t.message ?: "нет подробностей"}"
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(256 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /** Kept for diagnostics: whether the phone is on Wi-Fi right now. */
    fun isOnWifi(context: Context): Boolean {
        val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return false
        val network = manager.activeNetwork ?: return false
        val caps = manager.getNetworkCapabilities(network) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
    }

    private fun installPendingIntent(context: Context, file: File): PendingIntent {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return PendingIntent.getActivity(
            context,
            1,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun notify(context: Context, title: String, text: String, action: PendingIntent?) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID, "Обновления", NotificationManager.IMPORTANCE_HIGH,
                )
            )
        }
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_hearing_white)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
        if (action != null) {
            builder.setContentIntent(action)
        }
        manager.notify(NOTIFICATION_ID, builder.build())
    }
}
