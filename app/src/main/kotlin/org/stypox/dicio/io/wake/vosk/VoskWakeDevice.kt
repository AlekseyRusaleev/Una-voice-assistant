package org.stypox.dicio.io.wake.vosk

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import org.json.JSONObject
import org.stypox.dicio.eval.RemoteConfig
import org.stypox.dicio.io.wake.WakeDevice
import org.stypox.dicio.io.wake.WakeState
import org.stypox.dicio.io.wake.WakeStats
import org.stypox.dicio.ui.util.Progress
import org.stypox.dicio.util.FileToDownload
import org.stypox.dicio.util.downloadBinaryFilesWithPartial
import org.stypox.dicio.util.extractZip
import org.vosk.LibVosk
import org.vosk.LogLevel
import org.vosk.Model
import org.vosk.Recognizer
import java.io.File

/**
 * Wake word detection based on Vosk with a restricted decoding grammar: the decoder only knows the
 * name plus the words our commands are made of, so a one-shot phrase like "Юна, следующий трек" is
 * decoded whole, the name is stripped and the rest becomes the command.
 */
class VoskWakeDevice(
    @param:ApplicationContext private val appContext: Context,
    private val okHttpClient: OkHttpClient,
) : WakeDevice {

    private val wakeDir = File(appContext.filesDir, "vosk-wake")
    private val modelDir = File(wakeDir, "model")
    private val zipFile = File(wakeDir, "model.zip")

    private val _state = MutableStateFlow<WakeState>(
        if (isModelPresent()) WakeState.NotLoaded else WakeState.NotDownloaded
    )
    override val state: StateFlow<WakeState> = _state
    private val scope = CoroutineScope(Dispatchers.IO)

    private var model: Model? = null
    private var recognizer: Recognizer? = null
    private var logLevelSet = false
    private var detectedCommand: String? = null
    private var pcmBuffer = ByteArray(0)

    /**
     * A truncated unpack (interrupted download) leaves a directory that looks fine but fails to load
     * later, and the service then stops without explanation. The Russian model is ~45 MB, so a much
     * smaller folder is treated as missing and downloaded again.
     */
    private fun isModelPresent(): Boolean {
        if (!File(modelDir, "ivector").isDirectory) {
            return false
        }
        var total = 0L
        var files = 0
        modelDir.walkTopDown().forEach { file ->
            if (file.isFile) {
                total += file.length()
                files++
            }
        }
        return files >= 5 && total > MIN_MODEL_BYTES
    }

    override fun download() {
        if (isModelPresent()) {
            _state.value = WakeState.NotLoaded
            return
        }
        // drop whatever is left of a previous attempt
        modelDir.deleteRecursively()

        _state.value = WakeState.Downloading(Progress.UNKNOWN)
        scope.launch {
            try {
                wakeDir.mkdirs()
                downloadBinaryFilesWithPartial(
                    urlsFiles = listOf(FileToDownload(MODEL_URL, zipFile)),
                    httpClient = okHttpClient,
                    cacheDir = appContext.cacheDir,
                ) { progress ->
                    _state.value = WakeState.Downloading(progress)
                }
                extractZip(zipFile, modelDir) { progress ->
                    _state.value = WakeState.Downloading(progress)
                }
                zipFile.delete()
                _state.value = if (isModelPresent()) {
                    WakeState.NotLoaded
                } else {
                    WakeState.ErrorLoading(IllegalStateException("unpacked model is missing"))
                }
            } catch (t: Throwable) {
                Log.e(TAG, "Can't download the Vosk wake word model", t)
                _state.value = WakeState.ErrorDownloading(t)
            }
        }
    }

    override fun processFrame(audio16bitPcm: ShortArray): Boolean {
        if (recognizer == null) {
            try {
                if (!isModelPresent()) {
                    _state.value = WakeState.NotDownloaded
                    return false
                }
                _state.value = WakeState.Loading
                if (!logLevelSet) {
                    LibVosk.setLogLevel(LogLevel.WARNINGS)
                    logLevelSet = true
                }
                model = Model(modelDir.absolutePath)
                recognizer = newRecognizer()
                _state.value = WakeState.Loaded
            } catch (t: Throwable) {
                Log.e(TAG, "Failed to load the Vosk wake word model", t)
                org.stypox.dicio.eval.Journal.record(
                    appContext,
                    "diag: vosk load failed ${t.javaClass.simpleName}: ${t.message}",
                    "diag",
                )
                _state.value = WakeState.ErrorLoading(t)
                return false
            }
        }

        val rec = recognizer ?: return false
        // the buffer is reused: a fresh array every 80 ms is pure garbage collector pressure
        if (pcmBuffer.size != audio16bitPcm.size * 2) {
            pcmBuffer = ByteArray(audio16bitPcm.size * 2)
        }
        val bytes = pcmBuffer
        var i = 0
        for (sample in audio16bitPcm) {
            val v = sample.toInt()
            bytes[i++] = (v and 0xFF).toByte()
            bytes[i++] = ((v shr 8) and 0xFF).toByte()
        }

        // the recognizer returns true when it has decoded a full utterance (i.e. the user stopped
        // talking), which is exactly the moment we can look at the whole phrase
        val utteranceDone = rec.acceptWaveForm(bytes, bytes.size)
        if (!utteranceDone) {
            return false
        }

        val (command, matched) = parseUtterance(rec.result)
        if (!matched) {
            return false
        }

        detectedCommand = command.ifEmpty { null }

        Log.d(TAG, "Wake word detected, command: ${detectedCommand ?: "<none>"}")
        // start from a clean state so that the same phrase cannot trigger twice
        runCatching { rec.close() }
        recognizer = runCatching { newRecognizer() }.getOrNull()
        return true
    }

    /**
     * Parses a Vosk result and checks that the utterance really starts with the wake word.
     *
     * Requiring the wake word to be the first meaningful token, and to be recognised with a high
     * enough confidence, is what keeps ordinary speech (and background noise) from waking the
     * assistant up: a grammar-only decoder would otherwise happily "find" the wake word in any
     * phrase, because it has to map every sound onto one of the allowed words.
     */
    private fun parseUtterance(raw: String): Pair<String, Boolean> {
        val json = try {
            JSONObject(raw)
        } catch (e: Exception) {
            return "" to false
        }
        val words = json.optJSONArray("result") ?: return "" to false

        val command = mutableListOf<String>()
        var seenWakeWord = false
        for (i in 0 until words.length()) {
            val item = words.optJSONObject(i) ?: continue
            val word = item.optString("word", "")

            if (word.isEmpty() || word == "[unk]") {
                continue
            }
            if (!seenWakeWord) {
                // "слушай, юна" is as good as "юна"
                if (word in PREFIX_WORDS) {
                    continue
                }
                val confidence = item.optDouble("conf", 0.0)
                if (word !in WAKE_WORDS) {
                    return "" to false
                }
                if (confidence < MIN_WAKE_CONFIDENCE) {
                    // the name was heard but not confidently: count it for tuning
                    WakeStats.onNearMiss(appContext)
                    Log.d(TAG, "near miss: $word at $confidence")
                    return "" to false
                }
                seenWakeWord = true
            } else {
                command.add(word)
            }
        }
        return command.joinToString(" ") to seenWakeWord
    }

    override fun takeDetectedCommand(): String? {
        val command = detectedCommand
        detectedCommand = null
        return command
    }

    private fun newRecognizer(): Recognizer =
        Recognizer(model ?: throw IllegalStateException("model is not loaded"), SAMPLE_RATE, grammar())
            .apply { setWords(true) }

    override fun frameSize(): Int = FRAME_SIZE

    override fun isOccupyingResources(): Boolean = model != null

    override fun destroy() {
        runCatching { recognizer?.close() }
        recognizer = null
        runCatching { model?.close() }
        model = null
        scope.cancel()
    }

    companion object {
        private const val TAG = "VoskWakeDevice"
        private const val SAMPLE_RATE = 16000.0f

        /** 80 ms of audio at 16 kHz, the same frame size the OpenWakeWord device uses. */
        private const val FRAME_SIZE = 1280

        /** Accepted spellings of the name: short words get clipped, and false positives are
         *  filtered later by the strict check. */
        private const val MIN_MODEL_BYTES = 30L * 1024 * 1024

        private val WAKE_WORDS = setOf("юна", "юнна", "уна", "юнона")

        /**
         * Words people naturally say before the name ("слушай, юна"). They are skipped while
         * looking for the wake word, so both "юна, ..." and "слушай, юна, ..." work.
         */
        private val PREFIX_WORDS = setOf("слушай", "слушайте", "эй", "привет", "окей", "ок", "алло")

        /** Confidence Vosk needs for the name itself; the GigaAM re-check is the real filter. */
        private const val MIN_WAKE_CONFIDENCE = 0.55

        /**
         * The decoder is limited to the wake word plus the words our commands are made of, so that
         * a phrase like "юна следующий трек" is decoded in full. Everything else becomes [unk].
         */
        private fun grammar(): String {
            val words = linkedSetOf(
                "юна", "юнна", "уна", "юнона",
                "слушай", "слушайте", "эй", "привет", "окей", "ок", "алло",
            )
            words.addAll(RemoteConfig.current.grammar)
            words.addAll(RemoteConfig.current.wakeWord.split(' '))
            return words.filter { it.isNotBlank() }
                .joinToString(prefix = "[", postfix = "]", separator = ",") { "\"$it\"" }
        }

        private const val MODEL_URL =
            "https://alphacephei.com/vosk/models/vosk-model-small-ru-0.22.zip"
    }
}
