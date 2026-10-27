package org.stypox.dicio.eval

import android.content.Context
import android.os.Build
import android.util.Log
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Per-device registration: the backend gives every phone its own token, so a leaked token does not
 * expose someone else's data and rows in the database can be attributed to a specific device.
 */
object Device {
    private const val TAG = "Device"
    private const val BASE_URL = "https://9.247.112.23.sslip.io"
    private const val PREFS = "una_device"
    private const val KEY_TOKEN = "token"
    private const val KEY_ID = "id"

    fun token(context: Context): String? =
        prefs(context).getString(KEY_TOKEN, null)

    fun id(context: Context): Int = prefs(context).getInt(KEY_ID, 0)

    /** Registers on first use; returns the token or `null` when the server is unreachable. */
    fun ensureRegistered(context: Context): String? {
        token(context)?.let { return it }
        val latch = CountDownLatch(1)
        var token: String? = null
        Thread {
            try {
                val name = JSONObject()
                    .put("name", "${Build.MANUFACTURER} ${Build.MODEL}".take(60))
                    .toString()
                val connection = (URL("$BASE_URL/register").openConnection() as HttpURLConnection)
                    .apply {
                        requestMethod = "POST"
                        connectTimeout = 10000
                        readTimeout = 15000
                        doOutput = true
                        setRequestProperty("Content-Type", "application/json")
                    }
                connection.outputStream.use { it.write(name.toByteArray()) }
                if (connection.responseCode in 200..299) {
                    val body = connection.inputStream.bufferedReader().use { it.readText() }
                    val json = JSONObject(body)
                    val newToken = json.optString("token")
                    if (newToken.isNotBlank()) {
                        prefs(context).edit()
                            .putString(KEY_TOKEN, newToken)
                            .putInt(KEY_ID, json.optInt("deviceId", 0))
                            .apply()
                        token = newToken
                        Log.i(TAG, "registered as device ${json.optInt("deviceId", 0)}")
                    }
                } else {
                    Log.w(TAG, "register HTTP ${connection.responseCode}")
                }
                connection.disconnect()
            } catch (t: Throwable) {
                Log.w(TAG, "registration failed", t)
            } finally {
                latch.countDown()
            }
        }.start()
        latch.await(20, TimeUnit.SECONDS)
        return token
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
