package org.stypox.dicio.reminders

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

/**
 * Local reminders: scheduled with [AlarmManager] and delivered by [ReminderReceiver], so they fire
 * even when the app is closed.
 */
object Reminders {
    private const val TAG = "Reminders"
    private const val PREFS = "una_reminders"
    private const val KEY = "items"

    data class Reminder(val id: Int, val at: Long, val text: String)

    fun all(context: Context): List<Reminder> {
        val raw = prefs(context).getString(KEY, null) ?: return emptyList()
        return try {
            val array = JSONArray(raw)
            (0 until array.length()).mapNotNull { index ->
                val item = array.optJSONObject(index) ?: return@mapNotNull null
                Reminder(item.optInt("id"), item.optLong("at"), item.optString("text"))
            }.sortedBy { it.at }
        } catch (t: Throwable) {
            emptyList()
        }
    }

    /** Schedules a reminder; returns false when the system refuses the alarm. */
    fun schedule(context: Context, at: Long, text: String): Boolean {
        val id = (at % Int.MAX_VALUE).toInt()
        val intent = Intent(context, ReminderReceiver::class.java).apply {
            putExtra("text", text)
            putExtra("id", id)
        }
        val pending = PendingIntent.getBroadcast(
            context, id, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val manager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return false
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !manager.canScheduleExactAlarms()) {
                manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending)
            } else {
                manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending)
            }
            save(context, all(context) + Reminder(id, at, text))
            Log.d(TAG, "reminder at $at: $text")
            true
        } catch (t: Throwable) {
            Log.e(TAG, "cannot schedule reminder", t)
            false
        }
    }

    fun remove(context: Context, id: Int) {
        val manager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager
        val intent = Intent(context, ReminderReceiver::class.java)
        val pending = PendingIntent.getBroadcast(
            context, id, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        manager?.cancel(pending)
        save(context, all(context).filterNot { it.id == id })
    }

    private fun save(context: Context, items: List<Reminder>) {
        val array = JSONArray()
        for (item in items) {
            array.put(JSONObject().put("id", item.id).put("at", item.at).put("text", item.text))
        }
        prefs(context).edit().putString(KEY, array.toString()).apply()
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
