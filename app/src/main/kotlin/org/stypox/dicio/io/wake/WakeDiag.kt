package org.stypox.dicio.io.wake

/**
 * Why the wake service is not running, in words the user can act on.
 *
 * The service can refuse to start or stop right away for several unrelated reasons (no model,
 * microphone busy, Android restricting foreground services). Without this the only symptom was a
 * button that "does nothing".
 */
object WakeDiag {
    private const val FRESH_MILLIS = 30L * 60 * 1000

    @Volatile
    private var lastError: String? = null

    @Volatile
    private var lastErrorAt: Long = 0L

    fun record(message: String) {
        lastError = message
        lastErrorAt = System.currentTimeMillis()
    }

    fun record(what: String, error: Throwable?) {
        val detail = error?.let { "${it.javaClass.simpleName}: ${it.message ?: "нет подробностей"}" }
        record(if (detail == null) what else "$what — $detail")
    }

    fun clear() {
        lastError = null
        lastErrorAt = 0L
    }

    /** The reason, if it happened recently enough to be about the current attempt. */
    fun recent(): String? =
        lastError?.takeIf { System.currentTimeMillis() - lastErrorAt <= FRESH_MILLIS }
}
