package org.stypox.dicio.io.input.gigaam

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.stypox.dicio.io.input.InputEvent
import org.stypox.dicio.io.input.SttInputDevice
import org.stypox.dicio.io.input.SttState
import kotlin.math.sqrt

/**
 * Microphone button input, backed by the same [GigaAmEngine] the wake service uses.
 */
class GigaAmInputDevice(
    @ApplicationContext private val appContext: Context,
    private val silencesBeforeStop: StateFlow<Int>,
) : SttInputDevice {

    private val scope = CoroutineScope(Dispatchers.Default)
    private val _uiState = MutableStateFlow<SttState>(
        if (GigaAmModel.isReady(appContext)) SttState.NotLoaded else SttState.NotDownloaded
    )
    override val uiState: StateFlow<SttState> = _uiState

    private var listenJob: Job? = null
    private var pendingListener: ((InputEvent) -> Unit)? = null

    @Synchronized
    override fun tryLoad(thenStartListeningEventListener: ((InputEvent) -> Unit)?): Boolean {
        if (!GigaAmModel.isReady(appContext)) {
            _uiState.value = SttState.NotDownloaded
            return false
        }
        if (!GigaAmEngine.isReady(appContext)) {
            _uiState.value = SttState.Loading(thenStartListeningEventListener != null)
            pendingListener = thenStartListeningEventListener
            scope.launch {
                val ready = withContext(Dispatchers.IO) { GigaAmEngine.isReady(appContext) }
                if (!ready) {
                    _uiState.value = SttState.ErrorLoading(
                        IllegalStateException("модель GigaAM не загрузилась")
                    )
                    pendingListener = null
                    return@launch
                }
                _uiState.value = SttState.Loaded
                val listener = pendingListener
                pendingListener = null
                if (listener != null) {
                    startListening(listener)
                }
            }
            return true
        }
        _uiState.value = SttState.Loaded
        if (thenStartListeningEventListener != null) {
            startListening(thenStartListeningEventListener)
        }
        return true
    }

    override fun onClick(eventListener: (InputEvent) -> Unit) {
        synchronized(this) {
            when (_uiState.value) {
                is SttState.Listening -> {
                    stopListeningInternal(sendNone = true, listener = eventListener)
                    return
                }
                is SttState.Loading -> {
                    // the user tapped while the model was still loading: remember the intent
                    pendingListener = eventListener
                    return
                }
                else -> Unit
            }
        }
        if (!tryLoad(eventListener)) {
            eventListener(
                InputEvent.Error(IllegalStateException("Сначала скачайте модель в журнале Una"))
            )
        }
    }

    override fun stopListening() {
        stopListeningInternal(sendNone = false, listener = null)
    }

    private fun stopListeningInternal(sendNone: Boolean, listener: ((InputEvent) -> Unit)?) {
        listenJob?.cancel()
        listenJob = null
        if (_uiState.value is SttState.Listening) {
            _uiState.value = SttState.Loaded
        }
        if (sendNone) {
            (listener ?: pendingListener)?.invoke(InputEvent.None)
        }
    }

    private fun startListening(listener: (InputEvent) -> Unit) {
        if (ContextCompat.checkSelfPermission(appContext, Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            _uiState.value = SttState.NoMicrophonePermission
            listener(InputEvent.Error(SecurityException("нет разрешения на микрофон")))
            return
        }

        _uiState.value = SttState.Listening
        listenJob = scope.launch {
            val chunks = ArrayList<ShortArray>()
            var total = 0
            var speech = false
            var silentMillis = 0
            val silenceLimit = silencesBeforeStop.value.coerceAtLeast(1) * SILENCE_STEP_MILLIS
            var recorder: AudioRecord? = null
            var failure: Throwable? = null

            try {
                val minBuffer = AudioRecord.getMinBufferSize(
                    SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                ).coerceAtLeast(FRAME_SAMPLES * 2)
                recorder = AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    minBuffer,
                )
                if (recorder.state != AudioRecord.STATE_INITIALIZED) {
                    throw IllegalStateException("микрофон занят")
                }
                recorder.startRecording()
                val frame = ShortArray(FRAME_SAMPLES)
                val deadline = System.currentTimeMillis() + MAX_LISTEN_MILLIS
                while (isActive && System.currentTimeMillis() < deadline) {
                    val read = recorder.read(frame, 0, frame.size)
                    if (read <= 0) continue
                    chunks.add(frame.copyOf(read))
                    total += read
                    if (rms(frame, read) > SILENCE_RMS) {
                        speech = true
                        silentMillis = 0
                    } else if (speech) {
                        silentMillis += FRAME_MILLIS
                        if (silentMillis >= silenceLimit) break
                    }
                }
            } catch (t: Throwable) {
                Log.w(TAG, "cannot record", t)
                failure = t
            } finally {
                runCatching { recorder?.stop() }
                runCatching { recorder?.release() }
            }

            _uiState.value = SttState.Loaded

            if (failure != null) {
                listener(InputEvent.Error(failure))
                return@launch
            }
            if (!speech || total < MIN_SAMPLES) {
                listener(InputEvent.None)
                return@launch
            }

            val samples = ShortArray(total)
            var position = 0
            for (chunk in chunks) {
                System.arraycopy(chunk, 0, samples, position, chunk.size)
                position += chunk.size
            }
            val text = withContext(Dispatchers.Default) {
                GigaAmEngine.recognize(appContext, samples)
            }
            Log.d(TAG, "heard: $text")
            if (text.isBlank()) {
                listener(InputEvent.None)
            } else {
                listener(InputEvent.Final(listOf(text to 1.0f)))
            }
        }
    }

    private fun rms(samples: ShortArray, count: Int): Double {
        var energy = 0.0
        for (i in 0 until count) {
            val value = samples[i].toDouble()
            energy += value * value
        }
        return sqrt(energy / count)
    }

    override suspend fun destroy() {
        stopListeningInternal(sendNone = false, listener = null)
        scope.cancel()
    }

    companion object {
        private const val TAG = "GigaAmInputDevice"
        private const val SAMPLE_RATE = 16000
        private const val FRAME_SAMPLES = 1600 // 100 ms
        private const val FRAME_MILLIS = 100
        private const val SILENCE_STEP_MILLIS = 300
        private const val SILENCE_RMS = 900.0
        private const val MIN_SAMPLES = 8000 // at least half a second of audio
        private const val MAX_LISTEN_MILLIS = 20_000L
    }
}
