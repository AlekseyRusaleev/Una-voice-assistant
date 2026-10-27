package org.stypox.dicio.io.input.gigaam

import android.content.Context
import android.util.Log

/**
 * A single shared GigaAM recognizer for the whole app.
 *
 * The model takes a few hundred megabytes of RAM once loaded, so two instances (the wake service and
 * the button inside the app) would double that and risk an out-of-memory kill. Every call is
 * serialised: recognition is short (a second or two), so waiting is cheaper than duplicating the
 * model.
 */
object GigaAmEngine {
    private const val TAG = "GigaAmEngine"

    private var instance: GigaAmRecognizer? = null

    /** @return the loaded recognizer, or null when the model is not on the device yet. */
    @Synchronized
    private fun recognizer(context: Context): GigaAmRecognizer? {
        instance?.let { return it }
        if (!GigaAmModel.isReady(context)) {
            return null
        }
        val recognizer = GigaAmRecognizer(GigaAmModel.dir(context))
        return try {
            if (recognizer.load()) {
                instance = recognizer
                recognizer
            } else {
                Log.w(TAG, "the model did not load")
                null
            }
        } catch (t: Throwable) {
            Log.w(TAG, "cannot load the model", t)
            null
        }
    }

    fun isReady(context: Context): Boolean = recognizer(context) != null

    /** Recognises [samples] (16 kHz mono PCM16) and returns the text, or an empty string. */
    @Synchronized
    fun recognize(context: Context, samples: ShortArray): String {
        val recognizer = recognizer(context) ?: return ""
        return try {
            recognizer.recognize(samples).trim()
        } catch (t: Throwable) {
            Log.w(TAG, "recognition failed", t)
            ""
        }
    }

    @Synchronized
    fun release() {
        runCatching { instance?.release() }
        instance = null
    }
}
