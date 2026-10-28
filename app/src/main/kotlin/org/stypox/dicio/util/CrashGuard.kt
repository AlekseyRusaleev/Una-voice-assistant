package org.stypox.dicio.util

import android.content.Context
import android.util.Log
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Keeps the app usable when a component crashes it on start-up.
 *
 * A native crash in a model cannot be caught in Java: the process dies, Android restarts the
 * service, it dies again, and the user cannot even open the app. Two crashes in a short window
 * therefore switch the wake detector back to the known-good one and store the first stack trace so
 * it can be reported later.
 */
object CrashGuard {
    private const val TAG = "CrashGuard"
    private const val PREFS = "una_crash"
    private const val KEY_TIMES = "times"
    private const val KEY_LOG = "log"
    private const val KEY_SAFE = "safe"
    private const val WINDOW_MILLIS = 10L * 60 * 1000
    private const val CRASHES_BEFORE_SAFE = 2

    private const val KEY_LAST_START = "last_start"
    private const val KEY_FAST_STARTS = "fast_starts"
    private const val FAST_START_WINDOW = 40_000L

    fun install(context: Context) {
        runCatching { detectCrashLoop(context) }
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching { record(context, throwable) }
            previous?.uncaughtException(thread, throwable)
        }
    }

    /**
     * A native crash never reaches the Java handler, so the only sign of a loop is the app starting
     * again seconds after the previous start. That is enough to switch to the safe detector.
     */
    private fun detectCrashLoop(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        val last = prefs.getLong(KEY_LAST_START, 0L)
        val quick = last > 0 && now - last < FAST_START_WINDOW
        val fastStarts = if (quick) prefs.getInt(KEY_FAST_STARTS, 0) + 1 else 0
        prefs.edit()
            .putLong(KEY_LAST_START, now)
            .putInt(KEY_FAST_STARTS, fastStarts)
            .apply()

        if (quick && fastStarts >= CRASHES_BEFORE_SAFE && !safeMode(context)) {
            Log.w(TAG, "the app restarted $fastStarts times in a row: enabling safe mode")
            prefs.edit().putBoolean(KEY_SAFE, true).apply()
        }
    }

    /** Called once the app has been running for a while, to reset the loop counter. */
    fun markHealthy(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putInt(KEY_FAST_STARTS, 0).apply()
    }

    private fun record(context: Context, throwable: Throwable) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        val times = (prefs.getString(KEY_TIMES, "").orEmpty().split(',')
            .mapNotNull { it.trim().toLongOrNull() } +
            now).filter { now - it < WINDOW_MILLIS }

        val stamp = SimpleDateFormat("dd.MM HH:mm:ss", Locale.getDefault()).format(Date(now))
        val stack = throwable.stackTraceToString().lineSequence().take(12).joinToString("\n")
        val entry = "[$stamp] ${throwable.javaClass.simpleName}: ${throwable.message}\n$stack\n\n"
        val log = (prefs.getString(KEY_LOG, "").orEmpty() + entry).takeLast(6000)

        prefs.edit()
            .putString(KEY_TIMES, times.joinToString(","))
            .putString(KEY_LOG, log)
            .apply()

        if (times.size >= CRASHES_BEFORE_SAFE) {
            Log.w(TAG, "repeated crashes, enabling safe mode")
            prefs.edit().putBoolean(KEY_SAFE, true).apply()
            Notification_placeholder.notifySafeMode(context)
        }
    }

    fun safeMode(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_SAFE, false)

    fun leaveSafeMode(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_SAFE, false).apply()
    }

    /** Returns the crash report once, then forgets it. */
    fun takeReport(context: Context): String? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val log = prefs.getString(KEY_LOG, "").orEmpty()
        if (log.isBlank()) {
            return null
        }
        prefs.edit().remove(KEY_LOG).apply()
        return log.take(1500)
    }
}

/** Small indirection so this file does not depend on the UI layer. */
internal object Notification_placeholder {
    fun notifySafeMode(context: Context) {
        Log.w("CrashGuard", "safe mode enabled after repeated crashes")
    }
}
