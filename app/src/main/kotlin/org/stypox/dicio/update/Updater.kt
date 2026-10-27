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
 * Self-update: asks the backend for the newest version, downloads the APK on Wi-Fi and offers to
 * install it. Android always asks the user to confirm the installation — that cannot be skipped.
 */
object Updater {
    private const val TAG = "Updater"
    private const val BASE_URL = "https://9.247.112.23.sslip.io"
    private const val CHANNEL_ID = "una_updates"
    private const val NOTIFICATION_ID = 424242

    fun checkAndUpdate(context: Context) {
        thread(name = "una-update") {
            try {
                val info = fetchVersion() ?: return@thread
                val remoteCode = info.optInt("versionCode", 0)
                if (remoteCode <= BuildConfig.VERSION_CODE) {
                    return@thread
                }
                val versionName = info.optString("versionName", "новая")
                Log.i(TAG, "new version available: $versionName (wifi=${isOnWifi(context)})")
                val url = info.optString("url", "$BASE_URL/download/una.apk")
                val expected = info.optString("sha256", "").lowercase()
                val file = File(context.cacheDir, "una-update.apk")
                if (!download(url, file)) {
                    return@thread
                }
                if (expected.isNotEmpty() && sha256(file) != expected) {
                    Log.w(TAG, "checksum mismatch, update discarded")
                    file.delete()
                    return@thread
                }
                notify(
                    context,
                    "Обновление $versionName готово",
                    "Нажмите, чтобы установить",
                    installIntent(context, file),
                )
            } catch (t: Throwable) {
                Log.w(TAG, "update check failed", t)
            }
        }
    }

    private fun fetchVersion(): JSONObject? = try {
        val connection = (URL("$BASE_URL/version").openConnection() as HttpURLConnection).apply {
            connectTimeout = 10000
            readTimeout = 15000
        }
        if (connection.responseCode !in 200..299) {
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

    private fun download(url: String, target: File): Boolean = try {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15000
            readTimeout = 60000
            instanceFollowRedirects = true
        }
        if (connection.responseCode !in 200..299) {
            Log.w(TAG, "APK download HTTP ${connection.responseCode}")
            false
        } else {
            connection.inputStream.use { input ->
                target.outputStream().use { output -> input.copyTo(output, 256 * 1024) }
            }
            connection.disconnect()
            true
        }
    } catch (t: Throwable) {
        Log.w(TAG, "APK download failed", t)
        false
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

    private fun isOnWifi(context: Context): Boolean {
        val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return false
        val network = manager.activeNetwork ?: return false
        val caps = manager.getNetworkCapabilities(network) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
    }

    private fun installIntent(context: Context, file: File): PendingIntent {
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
                NotificationChannel(CHANNEL_ID, "Обновления", NotificationManager.IMPORTANCE_DEFAULT)
            )
        }
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_hearing_white)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
        if (action != null) {
            builder.setContentIntent(action)
        }
        manager.notify(NOTIFICATION_ID, builder.build())
    }
}
