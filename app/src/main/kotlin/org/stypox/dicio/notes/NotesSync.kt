package org.stypox.dicio.notes

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

/**
 * Uploads notes to the backend so that they survive a reinstall. Only new notes are sent: the
 * timestamp of the last uploaded note is stored locally.
 */
object NotesSync {
    private const val TAG = "NotesSync"
    private const val BASE_URL = "https://9.247.112.23.sslip.io"
    private const val PREFS = "una_notes_sync"
    private const val KEY_LAST = "last_pushed"
    private const val KEY_ALLOWED = "server_allowed"

    /** Upload is opt-in: by default notes stay on the phone. */
    fun isServerAllowed(context: Context): Boolean =
        prefs(context).getBoolean(KEY_ALLOWED, false)

    fun setServerAllowed(context: Context, allowed: Boolean) {
        prefs(context).edit().putBoolean(KEY_ALLOWED, allowed).apply()
    }

    /** @return number of notes uploaded, or -1 when nothing could be sent. */
    fun push(context: Context, onDone: ((Int) -> Unit)? = null) {
        thread(name = "una-notes-sync") {
            var sent = 0
            try {
                if (!isServerAllowed(context)) {
                    onDone?.invoke(-2)
                    return@thread
                }
                val token = org.stypox.dicio.eval.Device.token(context)
                if (token == null) {
                    onDone?.invoke(-1)
                    return@thread
                }
                val last = prefs(context).getLong(KEY_LAST, 0L)
                val fresh = Notes.all(context).filter { it.created > last }
                if (fresh.isEmpty()) {
                    onDone?.invoke(0)
                    return@thread
                }
                for (note in fresh) {
                    if (post(token, note.text)) {
                        sent++
                        prefs(context).edit().putLong(KEY_LAST, note.created).apply()
                    }
                }
            } catch (t: Throwable) {
                Log.w(TAG, "notes upload failed", t)
                onDone?.invoke(-1)
                return@thread
            }
            onDone?.invoke(sent)
        }
    }

    private fun post(token: String, text: String): Boolean {
        val body = JSONObject()
            .put("title", text.take(40))
            .put("body", text)
            .toString()
            .toByteArray()
        val connection = (URL("$BASE_URL/memory/notes").openConnection() as HttpURLConnection)
            .apply {
                requestMethod = "POST"
                connectTimeout = 15000
                readTimeout = 20000
                doOutput = true
                setRequestProperty("X-Una-Token", token)
                setRequestProperty("Content-Type", "application/json")
            }
        return try {
            connection.outputStream.use { it.write(body) }
            val code = connection.responseCode
            code in 200..299
        } catch (t: Throwable) {
            Log.w(TAG, "POST note failed", t)
            false
        } finally {
            connection.disconnect()
        }
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
