package org.stypox.dicio.notes

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicLong

/**
 * Simple on-device notes. Stored in a single JSON list, which is enough for a personal assistant and
 * keeps the data available offline; the backend has matching endpoints for syncing later.
 */
object Notes {
    private const val PREFS = "una_notes"
    private const val KEY = "notes"
    private val counter = AtomicLong(System.currentTimeMillis())

    data class Note(val id: Long, val text: String, val created: Long)

    fun all(context: Context): List<Note> {
        val raw = prefs(context).getString(KEY, null) ?: return emptyList()
        return try {
            val array = JSONArray(raw)
            (0 until array.length()).mapNotNull { index ->
                val item = array.optJSONObject(index) ?: return@mapNotNull null
                Note(
                    id = item.optLong("id"),
                    text = item.optString("text"),
                    created = item.optLong("created"),
                )
            }
        } catch (t: Throwable) {
            emptyList()
        }
    }

    fun add(context: Context, text: String): Note {
        val note = Note(counter.incrementAndGet(), text.trim(), System.currentTimeMillis())
        val list = (all(context) + note).takeLast(500)
        save(context, list)
        return note
    }

    fun remove(context: Context, id: Long) {
        save(context, all(context).filterNot { it.id == id })
    }

    fun removeContaining(context: Context, query: String): Boolean {
        val needle = query.trim().lowercase()
        if (needle.length < 2) return false
        val match = all(context).firstOrNull { it.text.lowercase().contains(needle) } ?: return false
        remove(context, match.id)
        return true
    }

    private fun save(context: Context, notes: List<Note>) {
        val array = JSONArray()
        for (note in notes) {
            array.put(JSONObject().put("id", note.id).put("text", note.text).put("created", note.created))
        }
        prefs(context).edit().putString(KEY, array.toString()).apply()
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
