package org.stypox.dicio.io.input.gigaam

import android.util.Log
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineTransducerModelConfig
import java.io.File

/**
 * GigaAM v3 (end-to-end RNN-T) running on-device through sherpa-onnx.
 *
 * Unlike the small Vosk model used for wake word spotting, this model is accurate enough to
 * recognise arbitrary Russian speech — names, numbers and free-form phrases — and it returns
 * punctuation and capitalisation without any post-processing.
 *
 * The model needs ~216 MB on disk and ~450 MB of RAM while decoding. It is meant for transcribing
 * one command after the wake word, not for continuous listening; the wake word stays on Vosk.
 */
class GigaAmRecognizer(private val modelDir: File) {
    private var recognizer: OfflineRecognizer? = null

    private val encoder = File(modelDir, "encoder.int8.onnx")
    private val decoder = File(modelDir, "decoder.onnx")
    private val joiner = File(modelDir, "joiner.onnx")
    private val tokens = File(modelDir, "tokens.txt")

    fun isModelReady(): Boolean =
        encoder.isFile && decoder.isFile && joiner.isFile && tokens.isFile

    /** Loads the model; returns `false` when the files are missing or the engine refuses to start. */
    @Synchronized
    fun load(): Boolean {
        if (recognizer != null) {
            return true
        }
        if (!isModelReady()) {
            Log.w(TAG, "model files are missing in ${modelDir.absolutePath}")
            return false
        }
        return try {
            val started = System.currentTimeMillis()
            val config = OfflineRecognizerConfig(
                featConfig = FeatureConfig(sampleRate = SAMPLE_RATE, featureDim = FEATURE_DIM),
                modelConfig = OfflineModelConfig(
                    transducer = OfflineTransducerModelConfig(
                        encoder = encoder.absolutePath,
                        decoder = decoder.absolutePath,
                        joiner = joiner.absolutePath,
                    ),
                    tokens = tokens.absolutePath,
                    numThreads = THREADS,
                    // GigaAM is a NeMo transducer; sherpa-onnx cannot detect it automatically
                    modelType = "nemo_transducer",
                ),
                decodingMethod = "greedy_search",
            )
            recognizer = OfflineRecognizer(config = config)
            Log.i(TAG, "GigaAM loaded in ${System.currentTimeMillis() - started} ms")
            true
        } catch (t: Throwable) {
            Log.e(TAG, "cannot load GigaAM", t)
            recognizer = null
            false
        }
    }

    /**
     * Recognises 16 kHz mono PCM. Returns the text, or an empty string on failure.
     * Audio longer than ~25 seconds must be split beforehand: the model degrades badly on
     * longer input.
     */
    @Synchronized
    fun recognize(samples: ShortArray): String {
        // sherpa-onnx divides by the number of feature frames: an empty or tiny buffer used to blow
        // up with ArithmeticException instead of simply returning nothing
        if (samples.size < MIN_SAMPLES) {
            return ""
        }
        val engine = recognizer ?: if (load()) recognizer else null
        if (engine == null) {
            return ""
        }
        return try {
            val floats = FloatArray(samples.size) { samples[it] / 32768.0f }
            val stream = engine.createStream()
            try {
                stream.acceptWaveform(floats, SAMPLE_RATE)
                engine.decode(stream)
                engine.getResult(stream).text.trim()
            } finally {
                stream.release()
            }
        } catch (t: Throwable) {
            Log.e(TAG, "recognition failed", t)
            ""
        }
    }

    @Synchronized
    fun release() {
        runCatching { recognizer?.release() }
        recognizer = null
    }

    companion object {
        private const val TAG = "GigaAmRecognizer"
        private const val SAMPLE_RATE = 16000

    /** Anything shorter than 0.1 s cannot carry a word and breaks feature extraction. */
    private const val MIN_SAMPLES = SAMPLE_RATE / 10
        private const val FEATURE_DIM = 64 // GigaAM uses 64 mel bins, not the usual 80
        private const val THREADS = 2
    }
}
