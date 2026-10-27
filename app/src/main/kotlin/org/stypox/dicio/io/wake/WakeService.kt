package org.stypox.dicio.io.wake

import android.Manifest.permission.RECORD_AUDIO
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.Intent.FLAG_ACTIVITY_NEW_TASK
import android.content.pm.PackageManager.PERMISSION_GRANTED
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.core.content.ContextCompat.getSystemService
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import org.stypox.dicio.MainActivity
import org.stypox.dicio.MainActivity.Companion.ACTION_WAKE_WORD
import org.stypox.dicio.R
import org.stypox.dicio.di.SttInputDeviceWrapper
import org.stypox.dicio.di.WakeDeviceWrapper
import org.stypox.dicio.eval.Journal
import org.stypox.dicio.eval.SkillEvaluator
import org.stypox.dicio.eval.Uplink
import org.stypox.dicio.io.input.gigaam.GigaAmModel
import org.stypox.dicio.io.input.gigaam.GigaAmEngine
import org.stypox.dicio.io.input.InputEvent
import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject

@AndroidEntryPoint
class WakeService : Service() {

    private val job = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.Default + job)

    private val listening = AtomicBoolean(false)
    private var lastUpdateCheck = 0L
    private val wakeTail = ArrayDeque<ShortArray>()
    private val wakeTailLock = Any()

    @Inject
    lateinit var skillEvaluator: SkillEvaluator
    @Inject
    lateinit var sttInputDevice: SttInputDeviceWrapper
    @Inject
    lateinit var wakeDevice: WakeDeviceWrapper

    private val handler = Handler(Looper.getMainLooper())
    private val releaseSttResourcesRunnable = Runnable {
        if (MainActivity.isCreated <= 0) {
            // if the main activity is neither visible nor in the background,
            // then unload the STT after a while because it would be using resources uselessly
            sttInputDevice.reinitializeToReleaseResources()
        }
    }

    private lateinit var notificationManager: NotificationManager

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    override fun onCreate() {
        super.onCreate()
        notificationManager = getSystemService(this, NotificationManager::class.java)!!

        scope.launch {
            // Recreate the notification so that it says the correct thing (i.e. there is a
            // different string for the "Hey Dicio" wake word and for a custom one).
            // Ignore the first one (i.e. the current value), which is handled in onStartCommand.
            wakeDevice.isHeyDicio.drop(1).collect { isHeyDicio ->
                createForegroundNotification(isHeyDicio)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP_WAKE_SERVICE) {
            listening.set(false)
            return START_NOT_STICKY
        }

        try {
            createForegroundNotification(wakeDevice.isHeyDicio.value)
        } catch (t: Throwable) {
            stopWithMessage("could not create WakeService foreground notification", t)
            return START_NOT_STICKY
        }

        if (listening.getAndSet(true)) {
            return START_STICKY // if we were already listening, do nothing more
        }

        if (ContextCompat.checkSelfPermission(this, RECORD_AUDIO) != PERMISSION_GRANTED) {
            stopWithMessage("Could not start WakeService: microphone permission not granted")
            return START_NOT_STICKY
        }

        when (wakeDevice.state.value) {
            WakeState.NotLoaded,
            WakeState.Loading,
            WakeState.Loaded -> {}
            else -> {
                stopWithMessage("Could not start WakeService: wake word device not ready")
                return START_NOT_STICKY
            }
        }

        scope.launch {
            try {
                listenForWakeWord()
                stopWithMessage() // exit normally, as the user just stopped the service
            } catch (t: Throwable) {
                stopWithMessage("Cannot continue listening for wake word", t)
            }
        }

        return START_STICKY
    }

    override fun onDestroy() {
        listening.set(false)
        job.cancel()
        wakeDevice.reinitializeToReleaseResources()
        super.onDestroy()
    }

    private fun stopWithMessage(message: String = "", throwable: Throwable? = null) {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()

        if (throwable != null) {
            Log.e(TAG, message, throwable)
        } else if (message.isNotEmpty()) {
            Log.e(TAG, message)
        }
    }

    private fun createForegroundNotification(isHeyDicio: Boolean) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                FOREGROUND_NOTIFICATION_CHANNEL_ID,
                getString(R.string.wake_service_label),
                NotificationManager.IMPORTANCE_LOW,
            )
            channel.description = getString(R.string.wake_service_foreground_notification_summary)
            notificationManager.createNotificationChannel(channel)
        }

        val notification = NotificationCompat.Builder(this, FOREGROUND_NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_hearing_white)
            .setContentTitle(
                getString(
                    if (isHeyDicio) R.string.wake_service_foreground_notification
                    else R.string.wake_custom_service_foreground_notification
                )
            )
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setShowWhen(false)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .addAction(NotificationCompat.Action(
                R.drawable.ic_stop_circle_white,
                getString(R.string.stop),
                PendingIntent.getService(
                    this,
                    0,
                    Intent(this, WakeService::class.java)
                        .apply { action = ACTION_STOP_WAKE_SERVICE },
                    PendingIntent.FLAG_IMMUTABLE,
                ),
            ))
            .build()

        startForeground(FOREGROUND_NOTIFICATION_ID, notification)
    }

    private fun listenForWakeWord() {
        @SuppressLint("MissingPermission")
        val ar = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            16000,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            6400,
        )

        var audio = ShortArray(0)
        var nextWakeWordAllowed = Instant.MIN

        try {
            ar.startRecording()
            while (listening.get()) {
                if (audio.size != wakeDevice.frameSize()) {
                    audio = ShortArray(wakeDevice.frameSize())
                }

                ar.read(audio, 0, audio.size)
                rememberFrame(audio)

                val wakeWordDetected = wakeDevice.processFrame(audio)
                if (wakeWordDetected && Instant.now() > nextWakeWordAllowed) {
                    nextWakeWordAllowed = Instant.now().plusMillis(WAKE_WORD_BACKOFF_MILLIS)
                    onWakeWordDetected(ar)
                }

                lastHeard.set(Instant.now())

                // the service outlives the activity, so updates must be checked here as well
                if (System.currentTimeMillis() - lastUpdateCheck > UPDATE_CHECK_INTERVAL_MILLIS) {
                    lastUpdateCheck = System.currentTimeMillis()
                    org.stypox.dicio.update.Updater.checkAndUpdate(this)
                }
            }
        } finally {
            ar.stop()
            ar.release()
        }
    }

    /**
     * Records the command that follows the wake word and transcribes it with the accurate
     * on-device model (GigaAM), which can hear names and arbitrary phrases.
     *
     * @return `true` when the command was recognised and handed over to the skill evaluator.
     */
    private fun transcribeWithGigaAm(ar: AudioRecord): Boolean {
        if (!GigaAmModel.isReady(this)) {
            return false
        }
        return try {
            val pcm = collectCommandAudio(ar) ?: return false
            val text = GigaAmEngine.recognize(this, pcm)
            if (text.isBlank()) {
                Log.d(TAG, "GigaAM heard nothing")
                return false
            }
            Log.d(TAG, "GigaAM heard: $text")
            WakeStats.onCommand(this)
            skillEvaluator.processInputEvent(InputEvent.Final(listOf(text to 1.0f)))
            true
        } catch (t: Throwable) {
            Log.e(TAG, "GigaAM recognition failed", t)
            false
        }
    }

    /** Reads microphone frames until about 0.8 s of silence, at most ~8 s in total. */
    private fun collectCommandAudio(ar: AudioRecord): ShortArray? {
        val frame = wakeDevice.frameSize().coerceAtLeast(320)
        val buffer = ShortArray(frame)
        val chunks = ArrayList<ShortArray>()
        var total = 0
        var speech = false
        var silentFrames = 0
        val started = System.currentTimeMillis()
        while (System.currentTimeMillis() - started < MAX_COMMAND_MILLIS) {
            val read = ar.read(buffer, 0, buffer.size)
            if (read <= 0) {
                continue
            }
            chunks.add(buffer.copyOf(read))
            total += read
            var energy = 0.0
            for (i in 0 until read) {
                val value = buffer[i].toDouble()
                energy += value * value
            }
            val rms = kotlin.math.sqrt(energy / read)
            if (rms > SILENCE_RMS) {
                speech = true
                silentFrames = 0
            } else if (speech) {
                silentFrames++
                if (silentFrames * FRAME_MILLIS >= SILENCE_MILLIS) {
                    break
                }
            }
        }
        if (!speech || total < MIN_COMMAND_SAMPLES) {
            return null
        }
        val out = ShortArray(total)
        var position = 0
        for (chunk in chunks) {
            chunk.copyInto(out, position)
            position += chunk.size
        }
        return out
    }

    private fun rememberFrame(frame: ShortArray) {
        synchronized(wakeTailLock) {
            wakeTail.addLast(frame.copyOf())
            while (wakeTail.size > WAKE_TAIL_FRAMES) {
                wakeTail.removeFirst()
            }
        }
    }

    private fun tailAudio(): ShortArray {
        synchronized(wakeTailLock) {
            val out = ShortArray(wakeTail.sumOf { it.size })
            var position = 0
            for (chunk in wakeTail) {
                chunk.copyInto(out, position)
                position += chunk.size
            }
            return out
        }
    }

    /**
     * Strict check: re-recognises the last couple of seconds and makes sure the phrase really
     * contains the wake word. An empty result does not reject the trigger.
     */
    private fun verifyWithGigaAm(): Boolean {
        if (!GigaAmModel.isReady(this)) {
            return true
        }
        return try {
            val text = GigaAmEngine.recognize(this, tailAudio()).lowercase().replace('ё', 'е')
            if (text.isBlank()) {
                return true
            }
            val heard = text.contains("юна") || text.contains("юнна") ||
                text.contains("уна") || text.contains("юно")
            Log.d(TAG, "strict check on: $text -> $heard")
            heard
        } catch (t: Throwable) {
            Log.e(TAG, "strict check failed", t)
            true
        }
    }

    private fun onWakeWordDetected(ar: AudioRecord) {
        WakeStats.onTrigger(this)
        Log.d(TAG, "Wake word detected")

        if (WakeStats.isStrict(this) && !verifyWithGigaAm()) {
            WakeStats.onRejected(this)
            Journal.record(this, "(ложное срабатывание)", "rejected")
            Uplink.sendAudio(this, tailAudio(), "rejected")
            Log.d(TAG, "strict check rejected the trigger")
            return
        }

        val intent = Intent(this, MainActivity::class.java)
        intent.setAction(ACTION_WAKE_WORD)
        intent.setFlags(FLAG_ACTIVITY_NEW_TASK)

        // If the command was spoken in the same phrase as the wake word (e.g. "Una, next track"),
        // the wake device already decoded it, so we can run the skills right away instead of
        // starting another listening round.
        val inlineCommand = wakeDevice.takeDetectedCommand()
        if (inlineCommand.isNullOrBlank() && !transcribeWithGigaAm(ar)) {
            // Start listening and pass STT events to the skill evaluator.
            // Note that this works even if the MainActivity is opened later!
            sttInputDevice.tryLoad(skillEvaluator::processInputEvent)

            // Unload the STT after a while because it would be using RAM uselessly
            handler.removeCallbacks(releaseSttResourcesRunnable)
            handler.postDelayed(releaseSttResourcesRunnable, RELEASE_STT_RESOURCES_MILLIS)
        } else {
            val inline = inlineCommand ?: ""
            if (inline.isNotEmpty()) {
                WakeStats.onCommand(this)
            }
            Log.d(TAG, "Running the command that came with the wake word: $inline")
            skillEvaluator.processInputEvent(InputEvent.Final(listOf(inline to 1.0f)))

            // A skill may ask a follow-up question (e.g. "Call mom?" before dialing), so keep
            // listening — but only after a short delay, otherwise the tail of the same phrase
            // would be executed a second time.
            handler.postDelayed({
                sttInputDevice.tryLoad(skillEvaluator::processInputEvent)
                handler.removeCallbacks(releaseSttResourcesRunnable)
                handler.postDelayed(releaseSttResourcesRunnable, RELEASE_STT_RESOURCES_MILLIS)
            }, INLINE_FOLLOW_UP_DELAY_MILLIS)
        }

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || MainActivity.isInForeground > 0) {
            // start the activity directly on versions prior to Android 10,
            // or if the MainActivity is already running in the foreground
            startActivity(intent)

        } else {
            // Android 10+ does not allow starting activities from the background,
            // so show a full-screen notification instead, which does actually result in starting
            // the activity from the background if the phone is off and Do Not Disturb is not active
            // Maybe we could also use the "Display over other apps" permission?

            val channel = NotificationChannel(
                TRIGGERED_NOTIFICATION_CHANNEL_ID,
                getString(R.string.wake_service_triggered_notification),
                NotificationManager.IMPORTANCE_HIGH
            )
            channel.description = getString(R.string.wake_service_triggered_notification_summary)
            notificationManager.createNotificationChannel(channel)

            val pendingIntent = PendingIntent.getActivity(
                this,
                0,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )

            val notification = NotificationCompat.Builder(this, TRIGGERED_NOTIFICATION_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_hearing_white)
                .setContentTitle(getString(R.string.wake_service_triggered_notification))
                .setStyle(NotificationCompat.BigTextStyle().bigText(
                    getString(R.string.wake_service_triggered_notification_summary)))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setFullScreenIntent(pendingIntent, true)
                .build()

            notificationManager.cancel(TRIGGERED_NOTIFICATION_ID)
            notificationManager.notify(TRIGGERED_NOTIFICATION_ID, notification)
        }
    }

    companion object {
        /**
         * Starting from Android 11, it is not possible to start a foreground service
         * that accesses the microphone from a BOOT_COMPLETED broadcast. So we show a
         * notification instead, which starts the foreground service when clicked.
         * https://developer.android.com/about/versions/15/behavior-changes-15#fgs-boot-completed
         */
        @RequiresApi(Build.VERSION_CODES.R)
        fun createNotificationToStartLater(context: Context) {
            val notificationManager = getSystemService(context, NotificationManager::class.java)
                ?: return

            val channel = NotificationChannel(
                START_NOTIFICATION_CHANNEL_ID,
                context.getString(R.string.wake_service_start_notification),
                NotificationManager.IMPORTANCE_DEFAULT
            )
            channel.description = context.getString(R.string.wake_service_start_notification_summary)
            notificationManager.createNotificationChannel(channel)

            val pendingIntent = PendingIntent.getForegroundService(
                context,
                0,
                Intent(context, WakeService::class.java),
                PendingIntent.FLAG_IMMUTABLE
            )

            val notification = NotificationCompat.Builder(context, START_NOTIFICATION_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_hearing_white)
                .setContentTitle(context.getString(R.string.wake_service_start_notification))
                .setStyle(NotificationCompat.BigTextStyle().bigText(
                    context.getString(R.string.wake_service_start_notification_summary)))
                .setOngoing(false)
                .setShowWhen(false)
                .setAutoCancel(true)
                .setContentIntent(pendingIntent)
                .build()

            notificationManager.notify(START_NOTIFICATION_ID, notification)
        }

        /**
         * Start the service. Call this only from a foreground part of the app (e.g. the main
         * activity), or from BOOT_COMPLETED only before Android 11. For BOOT_COMPLETED on Android
         * 11+ use [createNotificationToStartLater] instead.
         */
        fun start(context: Context) {
            Log.d(TAG, "WakeService.start() called from ${Throwable().stackTrace[1]}")
            val intent = Intent(context, WakeService::class.java)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            try {
                context.startService(Intent(context, WakeService::class.java)
                    .apply { action = ACTION_STOP_WAKE_SERVICE })
            } catch (_: IllegalStateException) {
                // Must not have been running. No problem with that.
            }
        }

        // Consider the service running if it processed any audio data within the past half second.
        fun isRunning(): Boolean = lastHeard.get()?.isAfter(Instant.now().minusMillis(500)) == true

        /**
         * On Android 10+ cancels any notification telling the user that the Dicio wake word was
         * triggered, which is not needed anymore after the main activity starts.
         */
        fun cancelTriggeredNotification(context: Context) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                getSystemService(context, NotificationManager::class.java)
                    ?.cancel(TRIGGERED_NOTIFICATION_ID)
            }
        }

        private val lastHeard = AtomicReference<Instant>()

        private val TAG = WakeService::class.simpleName
        private const val FOREGROUND_NOTIFICATION_CHANNEL_ID =
            "org.stypox.dicio.io.wake.WakeService.FOREGROUND"
        private const val START_NOTIFICATION_CHANNEL_ID =
            "org.stypox.dicio.io.wake.WakeService.START"
        private const val TRIGGERED_NOTIFICATION_CHANNEL_ID =
            "org.stypox.dicio.io.wake.WakeService.TRIGGERED"
        private const val FOREGROUND_NOTIFICATION_ID = 19803672
        private const val START_NOTIFICATION_ID = 48019274
        private const val TRIGGERED_NOTIFICATION_ID = 601398647
        private const val WAKE_WORD_BACKOFF_MILLIS = 4000L
        private const val ACTION_STOP_WAKE_SERVICE =
            "org.stypox.dicio.io.wake.WakeService.ACTION_STOP"
        private const val RELEASE_STT_RESOURCES_MILLIS = 1000L * 60 * 5 // 5 minutes

        /** How long to wait before listening again after a command that came with the wake word. */
        private const val INLINE_FOLLOW_UP_DELAY_MILLIS = 1200L

        /** Command capture for the accurate model: at most 8 s, stop after 0.8 s of silence. */
        private const val MAX_COMMAND_MILLIS = 8000L
        private const val SILENCE_MILLIS = 800L
        private const val FRAME_MILLIS = 80L
        private const val SILENCE_RMS = 500.0
        private const val MIN_COMMAND_SAMPLES = 1600

        /** How often the running service looks for a new version. */
        private const val UPDATE_CHECK_INTERVAL_MILLIS = 30L * 60 * 1000

        /** ~2.2 s of audio kept for the strict wake word check. */
        private const val WAKE_TAIL_FRAMES = 28
    }
}

