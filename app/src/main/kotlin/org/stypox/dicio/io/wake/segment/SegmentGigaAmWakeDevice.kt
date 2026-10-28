package org.stypox.dicio.io.wake.segment

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.stypox.dicio.io.input.gigaam.GigaAmEngine
import org.stypox.dicio.io.input.gigaam.GigaAmModel
import org.stypox.dicio.io.wake.WakeDevice
import org.stypox.dicio.io.wake.WakeState
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.sqrt

/**
 * Wake word detection as a side effect of speech recognition.
 *
 * There is no keyword model here on purpose. A cheap voice activity tracker in plain Kotlin waits
 * for a phrase to start and end; the finished phrase goes to the accurate model, and the name is
 * looked for in the recognised text. That fixes both weaknesses of an acoustic keyword matcher: a
 * short word like "юна" is recognised as a word rather than matched by sound, and the command that
 * was spoken in the same breath is already part of the transcript.
 */
class SegmentGigaAmWakeDevice(
    @param:ApplicationContext private val appContext: Context,
) : WakeDevice {

    private val _state = MutableStateFlow<WakeState>(
        if (GigaAmModel.isReady(appContext)) WakeState.NotLoaded else WakeState.NotDownloaded
    )
    override val state: StateFlow<WakeState> = _state

    private val scope = CoroutineScope(Dispatchers.Default)
    private val busy = AtomicBoolean(false)
    private val detected = AtomicBoolean(false)
    private val pendingCommand = AtomicReference<String?>(null)

    // voice activity tracking
    private var noiseFloor = 0.0
    private var speechActive = false
    private var silenceMillis = 0L
    private var speechMillis = 0L
    private val segment = ArrayList<ShortArray>()
    private val preroll = ArrayDeque<ShortArray>()
    private var segmentSamples = 0

    override fun download() {
        // the only model needed is the one GigaAM already downloads
        _state.value = if (GigaAmModel.isReady(appContext)) {
            WakeState.NotLoaded
        } else {
            WakeState.NotDownloaded
        }
    }

    override fun processFrame(audio16bitPcm: ShortArray): Boolean {
        if (!GigaAmModel.isReady(appContext)) {
            _state.value = WakeState.NotDownloaded
            return false
        }
        _state.value = WakeState.Loaded

        val level = rms(audio16bitPcm)
        val threshold = maxOf(MIN_LEVEL, noiseFloor * SPEECH_FACTOR)
        val isSpeech = level > threshold

        if (isSpeech) {
            if (!speechActive) {
                // start the segment a little early: the first syllable is often quieter
                speechActive = true
                segment.clear()
                segment.addAll(preroll)
                segmentSamples = preroll.sumOf { it.size }
                preroll.clear()
                speechMillis = 0
            }
            silenceMillis = 0
            segment.add(audio16bitPcm)
            segmentSamples += audio16bitPcm.size
            speechMillis += FRAME_MILLIS
        } else {
            noiseFloor = if (noiseFloor <= 0.0) level else noiseFloor * 0.98 + level * 0.02
            if (speechActive) {
                silenceMillis += FRAME_MILLIS
                segment.add(audio16bitPcm)
                segmentSamples += audio16bitPcm.size
                if (silenceMillis >= SEGMENT_SILENCE_MILLIS ||
                    speechMillis >= MAX_SPEECH_MILLIS
                ) {
                    finishSegment()
                }
            } else {
                preroll.addLast(audio16bitPcm.copyOf())
                while (preroll.size > PREROLL_FRAMES) {
                    preroll.removeFirst()
                }
            }
        }

        return detected.compareAndSet(true, false)
    }

    private fun finishSegment() {
        speechActive = false
        val samples = segment.toList()
        val total = segmentSamples
        segment.clear()
        segmentSamples = 0
        silenceMillis = 0

        if (total < MIN_SEGMENT_SAMPLES) {
            return
        }
        if (!busy.compareAndSet(false, true)) {
            return
        }

        scope.launch {
            try {
                val pcm = ShortArray(total)
                var position = 0
                for (chunk in samples) {
                    System.arraycopy(chunk, 0, pcm, position, chunk.size)
                    position += chunk.size
                }
                val text = GigaAmEngine.recognize(appContext, pcm)
                    .lowercase()
                    .replace('ё', 'е')
                if (text.isBlank()) {
                    return@launch
                }
                val match = WakePhrase.find(text)
                if (match == null) {
                    Log.d(TAG, "speech without the name: $text")
                    return@launch
                }
                val command = text.substring(match)
                    .trim()
                    .trimStart(',', '.', '!', '?', '—', '-', ':', ' ')
                    .trim()
                Log.i(TAG, "name in: \"$text\" -> command \"$command\"")
                pendingCommand.set(command.ifBlank { null })
                detected.set(true)
            } catch (t: Throwable) {
                Log.w(TAG, "cannot recognise the segment", t)
            } finally {
                busy.set(false)
            }
        }
    }

    override fun takeDetectedCommand(): String? = pendingCommand.getAndSet(null)

    fun takeDetectedConfidence(): Double = 1.0

    override fun frameSize(): Int = FRAME_SIZE

    override fun isOccupyingResources(): Boolean = true

    override fun destroy() {
        scope.cancel()
    }

    private fun rms(samples: ShortArray): Double {
        if (samples.isEmpty()) {
            return 0.0
        }
        var energy = 0.0
        for (sample in samples) {
            val value = sample.toDouble()
            energy += value * value
        }
        return sqrt(energy / samples.size)
    }

    companion object {
        private const val TAG = "SegmentWake"
        private const val FRAME_SIZE = 1280 // 80 ms at 16 kHz
        private const val FRAME_MILLIS = 80L
        private const val MIN_LEVEL = 120.0
        private const val SPEECH_FACTOR = 2.2
        private const val SEGMENT_SILENCE_MILLIS = 600L
        private const val MAX_SPEECH_MILLIS = 6000L
        private const val MIN_SEGMENT_SAMPLES = 16000 * 3 / 10 // 0.3 s
        private const val PREROLL_FRAMES = 4
    }
}

/** The name as the accurate model may write it, and where it ends. */
internal object WakePhrase {
    private val words = listOf("юна", "юнна", "уна", "юнона")

    fun find(text: String): Int? {
        for (word in words) {
            val index = text.indexOf(word)
            if (index >= 0) {
                return index + word.length
            }
        }
        return null
    }
}
