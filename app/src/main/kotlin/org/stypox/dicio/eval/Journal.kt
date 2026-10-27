package org.stypox.dicio.eval

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Keeps the last recognised phrases, so that the user can see what the assistant actually heard
 * instead of guessing why it answered "I did not understand".
 */
object Journal {
    private const val PREFS = "una_journal"
    private const val KEY = "entries"
    private const val LIMIT = 50

    data class Entry(val time: Long, val text: String, val result: String) {
        fun timeLabel(): String =
            SimpleDateFormat("dd.MM HH:mm:ss", Locale.getDefault()).format(Date(time))
    }

    @Synchronized
    fun record(context: Context, text: String, result: String) {
        try {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val entries = read(context).toMutableList()
            entries.add(0, Entry(System.currentTimeMillis(), text.trim(), result.trim()))
            val array = JSONArray()
            for (entry in entries.take(LIMIT)) {
                array.put(
                    JSONObject()
                        .put("t", entry.time)
                        .put("text", entry.text)
                        .put("result", entry.result)
                )
            }
            prefs.edit().putString(KEY, array.toString()).apply()
        } catch (t: Throwable) {
            // journaling must never break a command
        }
    }

    fun entries(context: Context): List<Entry> = read(context)

    fun clear(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(KEY).apply()
    }

    private fun read(context: Context): List<Entry> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY, null) ?: return emptyList()
        return try {
            val array = JSONArray(raw)
            (0 until array.length()).mapNotNull { index ->
                val item = array.optJSONObject(index) ?: return@mapNotNull null
                Entry(
                    time = item.optLong("t"),
                    text = item.optString("text"),
                    result = item.optString("result"),
                )
            }
        } catch (t: Throwable) {
            emptyList()
        }
    }
}
