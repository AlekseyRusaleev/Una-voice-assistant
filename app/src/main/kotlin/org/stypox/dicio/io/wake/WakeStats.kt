package org.stypox.dicio.io.wake

import android.content.Context

/**
 * Wake word quality counters, so that tuning is driven by facts instead of impressions.
 *
 * "Triggers" is how many times the wake word detector fired, "rejected" is how many of those were
 * thrown away by the strict check ("the phrase did not actually start with the name"), and
 * "commands" is how many led to a recognised command.
 */
object WakeStats {
    private const val PREFS = "una_wake_stats"
    private const val KEY_TRIGGERS = "triggers"
    private const val KEY_REJECTED = "rejected"
    private const val KEY_COMMANDS = "commands"
    private const val KEY_STRICT = "strict"

    fun triggers(context: Context): Int = read(context, KEY_TRIGGERS)
    fun rejected(context: Context): Int = read(context, KEY_REJECTED)
    fun commands(context: Context): Int = read(context, KEY_COMMANDS)

    fun onTrigger(context: Context) = bump(context, KEY_TRIGGERS)
    fun onRejected(context: Context) = bump(context, KEY_REJECTED)
    fun onCommand(context: Context) = bump(context, KEY_COMMANDS)

    /** Strict mode re-checks every trigger with the accurate model before doing anything. */
    fun isStrict(context: Context): Boolean =
        prefs(context).getBoolean(KEY_STRICT, true)

    fun setStrict(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_STRICT, enabled).apply()
    }

    fun reset(context: Context) {
        prefs(context).edit().clear().apply()
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun read(context: Context, key: String): Int = prefs(context).getInt(key, 0)

    private fun bump(context: Context, key: String) {
        val prefs = prefs(context)
        prefs.edit().putInt(key, prefs.getInt(key, 0) + 1).apply()
    }
}
