package org.stypox.dicio.eval

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread

/**
 * Uploads the recognition journal and short wake word clips. Enabled by default, can be turned off
 * in the journal screen; clips are limited per day to keep traffic and battery use low.
 */
object Uplink {
    private const val TAG = "Uplink"
    private const val BASE_URL = "https://9.247.112.23.sslip.io"

    private const val PREFS = "una_uplink"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_CONSENT = "consent"
    private const val KEY_LAST_SENT = "last_sent"
    private const val KEY_AUDIO_DAY = "audio_day"
    private const val KEY_AUDIO_COUNT = "audio_count"

    private const val MAX_AUDIO_PER_DAY = 20

    /** Confirmed wake-ups are capped separately from rejected triggers. */
    private const val MAX_POSITIVE_PER_DAY = 8
    private const val SAMPLE_RATE = 16000

    fun isEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_ENABLED, true) && isConsented(context)

    /** Kept as a separate flag so the journal screen can still ask before re-enabling. */
    fun isConsented(context: Context): Boolean =
        prefs(context).getBoolean(KEY_CONSENT, true)

    fun setConsented(context: Context, consented: Boolean) {
        prefs(context).edit().putBoolean(KEY_CONSENT, consented).apply()
    }

    fun setEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    /** Uploads journal entries that have not been sent yet. Never throws. */
    fun sendJournal(context: Context) {
        if (!isEnabled(context)) {
            return
        }
        thread(name = "una-journal") {
            try {
                val token = Device.token(context)
                if (token == null) {
                    Log.w(TAG, "no device token yet, journal not sent")
                    return@thread
                }
                val lastSent = prefs(context).getLong(KEY_LAST_SENT, 0L)
                val entries = Journal.entries(context).filter { it.time > lastSent }
                if (entries.isEmpty()) {
                    return@thread
                }
                val array = JSONArray()
                for (entry in entries) {
                    array.put(
                        JSONObject()
                            .put("ts", entry.time / 1000)
                            .put("text", entry.text)
                            .put("result", entry.result)
                            .put("meta", JSONObject().put("v", versionName(context))),
                    )
                }
                val body = JSONObject().put("entries", array).toString()
                if (post("/journal", body.toByteArray(), "application/json", token)) {
                    val newest = entries.maxOf { it.time }
                    prefs(context).edit().putLong(KEY_LAST_SENT, newest).apply()
                    Log.d(TAG, "journal sent: ${entries.size}")
                }
            } catch (t: Throwable) {
                Log.w(TAG, "journal upload failed", t)
            }
        }
    }

    /**
     * Uploads a short WAV clip. `reason` is `rejected` (false trigger) or `positive` (confirmed
     * wake-up); a wake word model needs both.
     */
    fun sendAudio(context: Context, pcm: ShortArray, reason: String) {
        if (!isEnabled(context) || pcm.isEmpty()) {
            return
        }
        if (!allowedAudioToday(context, reason)) {
            return
        }
        thread(name = "una-audio") {
            try {
                val token = Device.token(context)
                if (token == null) {
                    Log.w(TAG, "no device token yet, audio not sent")
                    return@thread
                }
                val wav = toWav(pcm)
                if (post("/audio?reason=$reason", wav, "audio/wav", token)) {
                    bumpAudioCounter(context, reason)
                    Log.d(TAG, "$reason clip sent: ${wav.size} bytes")
                }
            } catch (t: Throwable) {
                Log.w(TAG, "audio upload failed", t)
            }
        }
    }

    private fun dailyCap(reason: String): Int =
        if (reason == "positive") MAX_POSITIVE_PER_DAY else MAX_AUDIO_PER_DAY

    /** Each reason has its own daily counter. */
    private fun allowedAudioToday(context: Context, reason: String): Boolean {
        val today = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        val prefs = prefs(context)
        val dayKey = "$KEY_AUDIO_DAY-$reason"
        val countKey = "$KEY_AUDIO_COUNT-$reason"
        if (prefs.getString(dayKey, "") != today) {
            prefs.edit().putString(dayKey, today).putInt(countKey, 0).apply()
            return true
        }
        return prefs.getInt(countKey, 0) < dailyCap(reason)
    }

    private fun bumpAudioCounter(context: Context, reason: String) {
        val prefs = prefs(context)
        val countKey = "$KEY_AUDIO_COUNT-$reason"
        prefs.edit().putInt(countKey, prefs.getInt(countKey, 0) + 1).apply()
    }

    /** Minimal 16 kHz mono PCM → WAV writer. */
    private fun toWav(pcm: ShortArray): ByteArray {
        val dataSize = pcm.size * 2
        val out = ByteArrayOutputStream(44 + dataSize)
        fun ascii(s: String) = out.write(s.toByteArray(Charsets.US_ASCII))
        fun le32(v: Int) {
            out.write(v and 0xFF)
            out.write((v shr 8) and 0xFF)
            out.write((v shr 16) and 0xFF)
            out.write((v shr 24) and 0xFF)
        }
        fun le16(v: Int) {
            out.write(v and 0xFF)
            out.write((v shr 8) and 0xFF)
        }
        ascii("RIFF")
        le32(36 + dataSize)
        ascii("WAVEfmt ")
        le32(16)
        le16(1)
        le16(1)
        le32(SAMPLE_RATE)
        le32(SAMPLE_RATE * 2)
        le16(2)
        le16(16)
        ascii("data")
        le32(dataSize)
        for (sample in pcm) {
            out.write(sample.toInt() and 0xFF)
            out.write((sample.toInt() shr 8) and 0xFF)
        }
        return out.toByteArray()
    }

    private fun post(path: String, body: ByteArray, contentType: String, token: String): Boolean {
        val connection = (URL("$BASE_URL$path").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15000
            readTimeout = 30000
            doOutput = true
            setRequestProperty("X-Una-Token", token)
            setRequestProperty("Content-Type", contentType)
        }
        return try {
            connection.outputStream.use { it.write(body) }
            val code = connection.responseCode
            if (code !in 200..299) {
                Log.w(TAG, "HTTP $code for $path")
            }
            code in 200..299
        } catch (t: Throwable) {
            Log.w(TAG, "POST $path failed", t)
            false
        } finally {
            connection.disconnect()
        }
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    @Suppress("DEPRECATION")
    private fun versionName(context: Context): String = try {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "?"
    } catch (t: Throwable) {
        "?"
    }
}
