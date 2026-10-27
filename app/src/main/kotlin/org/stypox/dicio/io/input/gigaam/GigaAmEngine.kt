package org.stypox.dicio.io.input.gigaam

import android.content.Context
import android.util.Log

/**
 * One shared GigaAM recognizer for the whole app: the model takes hundreds of megabytes in RAM, so a
 * second instance would risk an out-of-memory kill. Calls are serialised.
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
