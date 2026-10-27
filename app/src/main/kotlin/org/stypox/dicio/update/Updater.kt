package org.stypox.dicio.update

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.PackageInfo
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
 * Self-update: fetches the newest version, downloads the APK and offers to install it (Android
 * always asks for confirmation). Failures come back as an [UpdateStatus] so the UI can show them.
 */
object Updater {
    private const val TAG = "Updater"
    private const val BASE_URL = "https://9.247.112.23.sslip.io"
    private const val CHANNEL_ID = "una_updates"
    private const val NOTIFICATION_ID = 424242

    private const val PREFS = "una_updater"
    private const val KEY_APK_CODE = "apk_code"       // versionCode of the APK on disk
    private const val KEY_APK_SHA = "apk_sha"
    private const val KEY_NOTIFIED_CODE = "notified_code"
    private const val KEY_NOTIFIED_AT = "notified_at"
    private const val KEY_LAST_CHECK = "last_check"
    private const val KEY_BYTES = "total_bytes"

    /** Background checks are rare: the payload is ~53 MB, so polling often is pure waste. */
    private const val BACKGROUND_INTERVAL_MILLIS = 6L * 60 * 60 * 1000
    private const val NOTIFY_AGAIN_MILLIS = 12L * 60 * 60 * 1000

    // two call sites (app start and the wake service) could download at the same time: only one
    // check may run at once, otherwise the radio stays busy and the traffic doubles
    private val inFlight = java.util.concurrent.atomic.AtomicBoolean(false)

    enum class Outcome { UP_TO_DATE, READY_TO_INSTALL, ERROR }

    data class UpdateStatus(
        val installedCode: Int,
        val installedName: String,
        val remoteCode: Int,
        val remoteName: String,
        val outcome: Outcome,
        val message: String,
        val apk: File? = null,
        val downloadedBytes: Long = 0,
    )

    fun totalBytes(context: Context): Long = prefs(context).getLong(KEY_BYTES, 0L)

    /** Background check used on start-up and from the wake service. */
    fun checkAndUpdate(context: Context) {
        val prefs = prefs(context)
        val now = System.currentTimeMillis()
        if (now - prefs.getLong(KEY_LAST_CHECK, 0L) < BACKGROUND_INTERVAL_MILLIS) {
            return
        }
        prefs.edit().putLong(KEY_LAST_CHECK, now).apply()
        if (!inFlight.compareAndSet(false, true)) {
            return
        }
        thread(name = "una-update") {
            try {
                val status = runCatching { checkNow(context) }
                    .getOrElse {
                        Log.w(TAG, "update check failed", it)
                        UpdateStatus(
                            BuildConfig.VERSION_CODE, BuildConfig.VERSION_NAME, 0, "",
                            Outcome.ERROR, "Ошибка проверки: ${it.message ?: it.javaClass.simpleName}",
                        )
                    }
                Log.i(TAG, "update: ${status.outcome} ${status.message}")
            } finally {
                inFlight.set(false)
            }
        }
    }

    /**
     * Blocking: call from a background dispatcher. `onStage` reports progress for the UI.
     */
    fun checkNow(context: Context, onStage: (String) -> Unit = {}): UpdateStatus {
        val installedCode = BuildConfig.VERSION_CODE
        val installedName = BuildConfig.VERSION_NAME
        val prefs = prefs(context)
        var downloaded = 0L

        fun error(text: String, remoteCode: Int = 0, remoteName: String = "") =
            UpdateStatus(installedCode, installedName, remoteCode, remoteName, Outcome.ERROR, text)

        onStage("Спрашиваю сервер…")
        val info = fetchVersion() ?: return error("Сервер обновлений недоступен: нет ответа на $BASE_URL")
        val remoteCode = info.optInt("versionCode", 0)
        val remoteName = info.optString("versionName", "новая")

        if (remoteCode <= installedCode) {
            // the APK we downloaded earlier is of no use any more: free the 53 MB
            dropCachedApk(context)
            return UpdateStatus(
                installedCode, installedName, remoteCode, remoteName, Outcome.UP_TO_DATE,
                "Установлена последняя версия",
            )
        }

        val url = info.optString("url", "$BASE_URL/download/una.apk")
        val expected = info.optString("sha256", "").lowercase()
        // filesDir, not cacheDir: a 57 MB APK may be evicted from the cache
        val file = File(File(context.filesDir, "updates"), "una-update.apk")
        file.parentFile?.mkdirs()

        // an APK of exactly this version that was downloaded before is reused instead of being
        // fetched again: this is what used to eat hundreds of megabytes of mobile data
        val cached = file.exists() &&
            prefs.getInt(KEY_APK_CODE, 0) == remoteCode &&
            expected.isNotEmpty() &&
            prefs.getString(KEY_APK_SHA, "").orEmpty().equals(expected, ignoreCase = true) &&
            file.length() > MIN_APK_BYTES

        if (cached) {
            onStage("Уже скачано ранее")
        } else {
            file.delete()
            onStage("Скачиваю ${remoteName}…")
            download(url, file) { done, total ->
                downloaded = done
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
            prefs.edit()
                .putInt(KEY_APK_CODE, remoteCode)
                .putString(KEY_APK_SHA, expected)
                .putLong(KEY_BYTES, prefs.getLong(KEY_BYTES, 0L) + file.length())
                .apply()
        }

        if (!signedByUs(context, file)) {
            file.delete()
            prefs.edit().remove(KEY_APK_CODE).remove(KEY_APK_SHA).apply()
            return error(
                "APK подписан другим ключом: сервер отдал чужую сборку или ключ сменился. " +
                    "Удалите приложение и установите APK вручную.",
                remoteCode,
                remoteName,
            )
        }

        val notifiedAt = prefs.getLong(KEY_NOTIFIED_AT, 0L)
        val repeat = prefs.getInt(KEY_NOTIFIED_CODE, 0) != remoteCode ||
            System.currentTimeMillis() - notifiedAt > NOTIFY_AGAIN_MILLIS
        if (repeat) {
            prefs.edit()
                .putInt(KEY_NOTIFIED_CODE, remoteCode)
                .putLong(KEY_NOTIFIED_AT, System.currentTimeMillis())
                .apply()
            notify(
                context,
                "Обновление $remoteName готово",
                "Нажмите, чтобы установить",
                installPendingIntent(context, file),
            )
        }

        return UpdateStatus(
            installedCode, installedName, remoteCode, remoteName, Outcome.READY_TO_INSTALL,
            if (cached) {
                "Скачано ранее, можно устанавливать"
            } else {
                "Скачано ${file.length() / 1024 / 1024} МБ, можно устанавливать"
            },
            file,
            downloaded,
        )
    }

    /** Removes a downloaded APK that is not newer than the installed version. */
    fun dropCachedApk(context: Context) {
        val prefs = prefs(context)
        val file = File(File(context.filesDir, "updates"), "una-update.apk")
        if (file.exists() && prefs.getInt(KEY_APK_CODE, 0) <= BuildConfig.VERSION_CODE) {
            file.delete()
            prefs.edit()
                .remove(KEY_APK_CODE)
                .remove(KEY_APK_SHA)
                .remove(KEY_NOTIFIED_CODE)
                .apply()
        }
    }

    /**
     * Opens the system installer for an already downloaded APK. Needs a foreground activity.
     * @return null on success, otherwise a message for the user: a silent failure here looks
     * exactly like a dead button.
     */
    fun installNow(context: Context, file: File): String? = try {
        when {
            !file.exists() -> "файл обновления не найден, скачайте заново"
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
                !context.packageManager.canRequestPackageInstalls() ->
                "разрешите установку: Настройки → Приложения → Una → Установка неизвестных приложений"
            else -> {
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
                null
            }
        }
    } catch (t: Throwable) {
        Log.w(TAG, "cannot start the installer", t)
        "не удалось открыть установщик: ${t.message ?: t.javaClass.simpleName}"
    }

    fun notificationsEnabled(context: Context): Boolean =
        androidx.core.app.NotificationManagerCompat.from(context).areNotificationsEnabled()

    /**
     * The APK must carry the same signing certificate as the installed app. The checksum only
     * protects against corruption, not against a malicious build served by a compromised server.
     */
    private fun signedByUs(context: Context, apk: File): Boolean = try {
        val pm = context.packageManager
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            PackageManager.GET_SIGNING_CERTIFICATES
        } else {
            @Suppress("DEPRECATION")
            PackageManager.GET_SIGNATURES
        }
        val installed = pm.getPackageInfo(context.packageName, flags)
        val archive = pm.getPackageArchiveInfo(apk.absolutePath, flags)
        if (archive == null) {
            false
        } else {
            fun certs(info: PackageInfo): Set<String> {
                val signatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    info.signingInfo?.apkContentsSigners.orEmpty()
                } else {
                    @Suppress("DEPRECATION")
                    info.signatures.orEmpty()
                }
                return signatures.map { it.toCharsString() }.toSet()
            }
            val ours = certs(installed)
            val theirs = certs(archive)
            ours.isNotEmpty() && ours == theirs
        }
    } catch (t: Throwable) {
        Log.w(TAG, "cannot compare signatures", t)
        false
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** An APK smaller than this is a truncated download and must not be reused. */
    private const val MIN_APK_BYTES = 10L * 1024 * 1024

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
