package org.stypox.dicio.io.wake.vad

import android.content.Context
import android.util.Log
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.stypox.dicio.eval.RemoteConfig
import org.stypox.dicio.io.input.gigaam.GigaAmEngine
import org.stypox.dicio.io.input.gigaam.GigaAmModel
import org.stypox.dicio.io.wake.WakeDevice
import org.stypox.dicio.io.wake.WakeState
import org.stypox.dicio.util.FileToDownload
import org.stypox.dicio.util.downloadBinaryFilesWithPartial
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

/**
 * Wake word detection without Vosk: a tiny voice activity detector watches for speech, and only
 * then the accurate model (the same one that transcribes commands) listens to that segment.
 *
 * Why: an always-on recognition of the whole room is what reacted to background talk, while a model
 * that only wakes up on real speech hears the whole phrase accurately — the command included.
 * The cost is a longer reaction: the segment has to end first.
 */
class VadGigaAmWakeDevice(
    @param:ApplicationContext private val appContext: Context,
    private val okHttpClient: okhttp3.OkHttpClient,
) : WakeDevice {

    private val dir = File(appContext.filesDir, "vad")
    private val modelFile = File(dir, MODEL_NAME)

    private val _state = MutableStateFlow<WakeState>(
        if (modelFile.isFile && modelFile.length() > 100_000) {
            WakeState.NotLoaded
        } else {
            WakeState.NotDownloaded
        }
    )
    override val state: StateFlow<WakeState> = _state

    private val scope = CoroutineScope(Dispatchers.IO)
    private val busy = AtomicBoolean(false)
    private val pendingCommand = AtomicReference<String?>(null)
    private val detected = AtomicBoolean(false)

    private var vad: Vad? = null
    private var framesSinceLoad = 0

    override fun download() {
        if (modelFile.isFile && modelFile.length() > 100_000) {
            _state.value = WakeState.NotLoaded
            return
        }
        _state.value = WakeState.Downloading(org.stypox.dicio.ui.util.Progress.UNKNOWN)
        scope.launch {
            try {
                dir.mkdirs()
                downloadBinaryFilesWithPartial(
                    urlsFiles = listOf(FileToDownload("$BASE_URL/$MODEL_NAME", modelFile)),
                    httpClient = okHttpClient,
                    cacheDir = appContext.cacheDir,
                ) { progress ->
                    _state.value = WakeState.Downloading(progress)
                }
                _state.value = if (modelFile.isFile) WakeState.NotLoaded else WakeState.ErrorLoading(
                    IllegalStateException("модель не скачалась")
                )
            } catch (t: Throwable) {
                Log.w(TAG, "cannot download the VAD model", t)
                _state.value = WakeState.ErrorDownloading(t)
            }
        }
    }

    override fun processFrame(audio16bitPcm: ShortArray): Boolean {
        val detector = ensureLoaded() ?: return false

        val floats = FloatArray(audio16bitPcm.size) { audio16bitPcm[it] / 32768.0f }
        detector.acceptWaveform(floats)
        while (!detector.empty()) {
            val segment = detector.front()
            detector.pop()
            if (segment.samples.size < MIN_SEGMENT_SAMPLES) {
                continue
            }
            analyse(segment.samples)
        }

        // the recognition runs on another thread, so the flag is what a later frame sees
        return if (detected.compareAndSet(true, false)) true else false
    }

    /** Recognises a finished speech segment and remembers the command that came after the name. */
    private fun analyse(samples: FloatArray) {
        if (!busy.compareAndSet(false, true)) {
            return
        }
        scope.launch {
            try {
                if (!GigaAmModel.isReady(appContext)) {
                    return@launch
                }
                val pcm = ShortArray(samples.size) {
                    (samples[it] * 32767).toInt().coerceIn(-32768, 32767).toShort()
                }
                val text = GigaAmEngine.recognize(appContext, pcm)
                if (text.isBlank()) {
                    return@launch
                }
                val normalized = text.lowercase().replace('ё', 'е')
                val wake = WakeWords.find(normalized)
                if (wake == null) {
                    Log.d(TAG, "speech without the name: $text")
                    return@launch
                }
                val command = normalized.substring(wake.second).trim()
                    .trimStart(',', '.', '!', '?', ' ', '-', '—', ':')
                pendingCommand.set(command.ifBlank { null })
                detected.set(true)
                Log.i(TAG, "wake word in: $text")
            } catch (t: Throwable) {
                Log.w(TAG, "cannot analyse the segment", t)
            } finally {
                busy.set(false)
            }
        }
    }

    private fun ensureLoaded(): Vad? {
        vad?.let { return it }
        if (!(modelFile.isFile && modelFile.length() > 100_000)) {
            _state.value = WakeState.NotDownloaded
            return null
        }
        return try {
            _state.value = WakeState.Loading
            val config = VadModelConfig(
                sileroVadModelConfig = SileroVadModelConfig(
                    model = modelFile.absolutePath,
                    threshold = 0.5f,
                    minSilenceDuration = 0.45f,
                    minSpeechDuration = 0.25f,
                    windowSize = 512,
                    maxSpeechDuration = 6.0f,
                ),
                sampleRate = SAMPLE_RATE,
                numThreads = 1,
                provider = "cpu",
                debug = false,
            )
            val created = Vad(assetManager = appContext.assets, config = config)
            vad = created
            _state.value = WakeState.Loaded
            framesSinceLoad = 0
            created
        } catch (t: Throwable) {
            Log.w(TAG, "cannot load the VAD model", t)
            _state.value = WakeState.ErrorLoading(t)
            null
        }
    }

    override fun takeDetectedCommand(): String? = pendingCommand.getAndSet(null)

    /** This detector always reports 1.0: it confirms the name by recognising the whole phrase. */
    fun takeDetectedConfidence(): Double = 1.0

    override fun frameSize(): Int = FRAME_SIZE

    override fun isOccupyingResources(): Boolean = vad != null

    override fun destroy() {
        runCatching { vad?.release() }
        vad = null
        scope.cancel()
    }

    companion object {
        private const val TAG = "VadGigaAmWakeDevice"
        private const val BASE_URL = "https://9.247.112.23.sslip.io/models/gigaam"
        private const val MODEL_NAME = "silero_vad.onnx"
        private const val SAMPLE_RATE = 16000
        private const val FRAME_SIZE = 1280
        private const val MIN_SEGMENT_SAMPLES = 6400 // 0.4 s
    }
}

/** The spellings of the name we accept, with the position where the name ends. */
private object WakeWords {
    private val words = listOf("юна", "юнна", "уна", "юнона")

    fun find(text: String): Pair<String, Int>? {
        for (word in words) {
            val index = text.indexOf(word)
            if (index >= 0) {
                return word to index + word.length
            }
        }
        // the name is often recognised at the very start with a clipped first letter
        val trimmed = text.trimStart()
        if (trimmed.startsWith("на") && trimmed.length > 3) {
            return "на" to 2
        }
        return null
    }
}
