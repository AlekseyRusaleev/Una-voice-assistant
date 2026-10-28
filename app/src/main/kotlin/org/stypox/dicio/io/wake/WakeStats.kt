package org.stypox.dicio.io.wake

import android.content.Context
import java.util.concurrent.atomic.AtomicLong

/**
 * Wake word counters, so that tuning is driven by numbers.
 *
 * Per-frame numbers (frames processed, frames skipped as silence) are kept in memory and flushed to
 * prefs rarely: writing to SharedPreferences twelve times per second would cost more battery than
 * the detection itself.
 */
object WakeStats {
    private const val PREFS = "una_wake_stats"
    private const val KEY_TRIGGERS = "triggers"
    private const val KEY_REJECTED = "rejected"
    private const val KEY_COMMANDS = "commands"
    private const val KEY_NEAR = "near"
    private const val KEY_STRICT = "strict"
    private const val KEY_SKIP_SILENCE = "skip_silence"
    private const val KEY_FRAMES = "frames"
    private const val KEY_SKIPPED = "skipped"
    private const val KEY_CPU_MILLIS = "cpu_millis"
    private const val KEY_WALL_MILLIS = "wall_millis"
    private const val KEY_CPU_BASE = "cpu_base"
    private const val KEY_WALL_BASE = "wall_base"

    private val frames = AtomicLong()
    private val skipped = AtomicLong()

    fun triggers(context: Context): Int = read(context, KEY_TRIGGERS)
    fun rejected(context: Context): Int = read(context, KEY_REJECTED)
    fun commands(context: Context): Int = read(context, KEY_COMMANDS)

    /** Triggers the detector recognised but dropped because it was not confident enough. */
    fun nearMisses(context: Context): Int = read(context, KEY_NEAR)

    fun onTrigger(context: Context) = bump(context, KEY_TRIGGERS)
    fun onRejected(context: Context) = bump(context, KEY_REJECTED)
    fun onCommand(context: Context) = bump(context, KEY_COMMANDS)
    fun onNearMiss(context: Context) = bump(context, KEY_NEAR)

    fun onFrame() = frames.incrementAndGet()
    fun onSkippedFrame() = skipped.incrementAndGet()

    fun framesTotal(context: Context): Long = readLong(context, KEY_FRAMES) + frames.get()

    fun skippedTotal(context: Context): Long = readLong(context, KEY_SKIPPED) + skipped.get()

    /** Share of audio frames that were silence and never reached the decoder, 0..100. */
    fun skippedPercent(context: Context): Int {
        val total = framesTotal(context)
        if (total <= 0) return 0
        return (skippedTotal(context) * 100 / total).toInt()
    }

    /** Accumulated CPU time of the whole process, in milliseconds. */
    fun cpuMillis(context: Context): Long = readLong(context, KEY_CPU_MILLIS)

    fun cpuWallMillis(context: Context): Long = readLong(context, KEY_WALL_MILLIS)

    /** CPU used by the process between the last two maintenance ticks, in percent of one core. */
    fun cpuPercent(context: Context): Int {
        val wall = cpuWallMillis(context)
        if (wall <= 0) return 0
        return (cpuMillis(context) * 100 / wall).toInt()
    }

    fun cpuBaseline(context: Context): Long =
        prefs(context).getLong(KEY_CPU_BASE, -1L)

    fun wallBaseline(context: Context): Long =
        prefs(context).getLong(KEY_WALL_BASE, 0L)

    fun addCpuDelta(context: Context, cpuDelta: Long, wallDelta: Long) {
        if (cpuDelta <= 0 || wallDelta <= 0) return
        val prefs = prefs(context)
        prefs.edit()
            .putLong(KEY_CPU_MILLIS, prefs.getLong(KEY_CPU_MILLIS, 0L) + cpuDelta)
            .putLong(KEY_WALL_MILLIS, prefs.getLong(KEY_WALL_MILLIS, 0L) + wallDelta)
            .apply()
    }

    fun setBaseline(context: Context, cpuMillis: Long, wallMillis: Long) {
        prefs(context).edit()
            .putLong(KEY_CPU_BASE, cpuMillis)
            .putLong(KEY_WALL_BASE, wallMillis)
            .apply()
    }

    /** Writes the in-memory frame counters to prefs; called from the maintenance tick. */
    fun flushFrames(context: Context) {
        val newFrames = frames.getAndSet(0)
        val newSkipped = skipped.getAndSet(0)
        if (newFrames == 0L && newSkipped == 0L) return
        val prefs = prefs(context)
        prefs.edit()
            .putLong(KEY_FRAMES, prefs.getLong(KEY_FRAMES, 0L) + newFrames)
            .putLong(KEY_SKIPPED, prefs.getLong(KEY_SKIPPED, 0L) + newSkipped)
            .apply()
    }

    /**
     * Skipping silence saves battery, but it also removes the acoustic context the decoder needs:
     * with it on, the name was recognised with lower confidence and real wake-ups were lost. That
     * is why it is off by default and only a deliberate choice.
     */
    fun isSkipSilence(context: Context): Boolean =
        prefs(context).getBoolean(KEY_SKIP_SILENCE, false)

    fun setSkipSilence(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_SKIP_SILENCE, enabled).apply()
    }

    /** Strict mode re-checks every trigger with the accurate model before doing anything. */
    fun isStrict(context: Context): Boolean =
        prefs(context).getBoolean(KEY_STRICT, true)

    fun setStrict(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_STRICT, enabled).apply()
    }

    fun reset(context: Context) {
        frames.set(0)
        skipped.set(0)
        prefs(context).edit().clear().apply()
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun read(context: Context, key: String): Int = prefs(context).getInt(key, 0)

    private fun readLong(context: Context, key: String): Long = prefs(context).getLong(key, 0L)

    private fun bump(context: Context, key: String) {
        val prefs = prefs(context)
        prefs.edit().putInt(key, prefs.getInt(key, 0) + 1).apply()
    }
}
