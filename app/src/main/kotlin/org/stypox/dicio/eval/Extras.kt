package org.stypox.dicio.eval

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaSessionManager
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.provider.AlarmClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.getSystemService
import org.stypox.dicio.R
import org.stypox.dicio.notes.Notes
import org.stypox.dicio.reminders.Reminders
import org.stypox.dicio.reminders.When
import org.stypox.dicio.skills.notify.NotifyHandler
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Everyday commands that do not need the wake grammar: notes, reminders, alarms, playback info,
 * battery and a few system toggles. Every result is reported through a notification, because the
 * app performs these actions without opening its own window.
 */
object Extras {
    private const val TAG = "Extras"

    private val NOTE_ADD = Regex(
        """(?:запиши|записать|добавь|сохрани)(?:\s+в\s+заметки)?\s+(.+)""", RegexOption.IGNORE_CASE
    )
    private val NOTE_DELETE = Regex("""удали\s+заметку\s+(.+)""", RegexOption.IGNORE_CASE)
    private val ALARM = Regex("""будильник\s+на\s+(\d{1,2})(?:[:.](\d{2}))?""", RegexOption.IGNORE_CASE)
    private val TIMER = Regex("""таймер\s+на\s+(.+)""", RegexOption.IGNORE_CASE)
    private val YOUTUBE = Regex(
        """(?:включи|найди|поищи|открой)\s+(?:на\s+)?ютуб\w*\s+(.+)""", RegexOption.IGNORE_CASE
    )
    private val NUMBER = Regex("""[\d\s\-()]{5,}""")

    /** @return true when the phrase was handled here. */
    fun handle(context: Context, text: String, utterance: String): Boolean {
        // ---- notes
        NOTE_DELETE.find(text)?.let { match ->
            val query = match.groupValues[1].trim()
            val removed = Notes.removeContaining(context, query)
            notify(
                context, "Заметки",
                if (removed) "Удалил заметку про «$query»" else "Не нашёл заметку про «$query»",
            )
            return true
        }
        NOTE_ADD.find(text)?.let { match ->
            val body = match.groupValues[1].trim()
            if (body.length >= 2 && !body.startsWith("заметк")) {
                Notes.add(context, body)
                notify(context, "Заметка сохранена", body)
                return true
            }
        }
        if (text.contains("заметк") &&
            listOf("что", "какие", "покажи", "мои", "прочитай", "список").any { text.contains(it) }
        ) {
            val notes = Notes.all(context)
            notify(
                context, "Заметки (${notes.size})",
                if (notes.isEmpty()) "Пока пусто"
                else notes.takeLast(10).joinToString("\n") { "• ${it.text}" },
            )
            return true
        }

        // ---- reminders
        if (text.contains("напомн")) {
            val at = When.parse(text)
            if (at == null) {
                notify(context, "Напоминание", "Не понял время. Скажите, например: «напомни в 18:30»")
            } else {
                val body = cleanReminder(text)
                Reminders.schedule(context, at, body)
                val label = SimpleDateFormat("dd.MM в HH:mm", Locale.getDefault()).format(Date(at))
                notify(context, "Напомню $label", body)
            }
            return true
        }

        // ---- alarm clock
        ALARM.find(text)?.let { match ->
            val hour = match.groupValues[1].toIntOrNull() ?: return@let
            val minute = match.groupValues[2].toIntOrNull() ?: 0
            if (hour in 0..23 && minute in 0..59) {
                setAlarm(context, hour, minute)
                return true
            }
        }

        // ---- timer
        TIMER.find(text)?.let { match ->
            val at = When.parse("через " + match.groupValues[1])
            if (at != null) {
                val seconds = ((at - System.currentTimeMillis()) / 1000).toInt().coerceAtLeast(1)
                setTimer(context, seconds)
                return true
            }
        }

        // ---- what is playing
        if (listOf("что играет", "какая песня", "что за песня", "что за трек").any { text.contains(it) }) {
            val track = currentTrack(context)
            notify(context, "Сейчас играет", track ?: "Не вижу активного плеера")
            return true
        }

        // ---- notifications: read
        if (listOf("уведомлен", "что мне писали", "кто писал", "что писали").any { text.contains(it) }) {
            if (!NotifyHandler.available()) {
                notify(
                    context, "Уведомления",
                    "Нет доступа: Настройки → Специальный доступ → Доступ к уведомлениям → Una",
                )
            } else {
                val lines = NotifyHandler.Instance?.summary() ?: emptyList()
                notify(
                    context, "Уведомления (${lines.size})",
                    if (lines.isEmpty()) "Новых нет" else lines.joinToString("\n"),
                )
            }
            return true
        }

        // ---- notifications: quick reply
        val replyVerb = when {
            text.startsWith("ответь") -> "ответь"
            text.startsWith("напиши") -> "напиши"
            else -> null
        }
        if (replyVerb != null) {
            val rest = text.removePrefix(replyVerb).trim()
            val separator = rest.indexOfAny(charArrayOf(':', '—'))
            val who: String
            val message: String
            if (separator > 0) {
                who = rest.substring(0, separator).trim()
                message = rest.substring(separator + 1).trim()
            } else {
                val parts = rest.split(' ', limit = 2)
                who = parts.getOrNull(0).orEmpty()
                message = parts.getOrNull(1).orEmpty()
            }
            if (who.isNotEmpty() && message.isNotEmpty()) {
                val sent = NotifyHandler.Instance?.reply(who, message) ?: false
                notify(
                    context, "Ответ",
                    if (sent) "Отправил «$who»: $message"
                    else "Не нашёл чат «$who» с быстрым ответом",
                )
                return true
            }
        }

        // ---- battery
        if (text.contains("заряд") || text.contains("батаре")) {
            val manager = context.getSystemService<BatteryManager>()
            val level = manager?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
            notify(context, "Батарея", if (level >= 0) "$level%" else "Не удалось узнать")
            return true
        }

        // ---- ringer
        if (text.contains("беззвучн") || text.contains("на беззвучный")) {
            setRinger(context, AudioManager.RINGER_MODE_SILENT, "Беззвучный режим включён")
            return true
        }
        if (text.contains("обычный режим") || text.contains("со звуком")) {
            setRinger(context, AudioManager.RINGER_MODE_NORMAL, "Звук включён")
            return true
        }
        if (text.contains("вибрац") || text.contains("вибро")) {
            setRinger(context, AudioManager.RINGER_MODE_VIBRATE, "Вибрация включена")
            return true
        }

        // ---- youtube search
        YOUTUBE.find(text)?.let { match ->
            val query = match.groupValues[1].trim()
            if (query.length >= 2) {
                openYoutubeSearch(context, query)
                notify(context, "YouTube", "Ищу: $query")
                return true
            }
        }

        return false
    }

    /** Strips the command words so that the reminder text reads naturally. */
    private fun cleanReminder(text: String): String =
        text.replace(Regex("""(?:напомни|напомнить|мне|пожалуйста)""", RegexOption.IGNORE_CASE), " ")
            .replace(Regex("""через\s+\d+\s*(?:минут\w*|час\w*|секунд\w*)""", RegexOption.IGNORE_CASE), " ")
            .replace(Regex("""\bв\s+\d{1,2}(?:[:.]\d{2})?""", RegexOption.IGNORE_CASE), " ")
            .replace(Regex("""\b(?:завтра|сегодня|утра|вечера|дня|ночи)\b""", RegexOption.IGNORE_CASE), " ")
            .replace(Regex("""\s+"""), " ")
            .trim()
            .ifBlank { "Напоминание" }

    private fun setAlarm(context: Context, hour: Int, minute: Int) {
        val intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
            putExtra(AlarmClock.EXTRA_HOUR, hour)
            putExtra(AlarmClock.EXTRA_MINUTES, minute)
            putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            context.startActivity(intent)
            notify(context, "Будильник", String.format(Locale.getDefault(), "%02d:%02d", hour, minute))
        } catch (t: Throwable) {
            Log.w(TAG, "cannot set alarm", t)
            notify(context, "Будильник", "Не получилось открыть часы")
        }
    }

    private fun setTimer(context: Context, seconds: Int) {
        val intent = Intent(AlarmClock.ACTION_SET_TIMER).apply {
            putExtra(AlarmClock.EXTRA_LENGTH, seconds)
            putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            context.startActivity(intent)
            notify(context, "Таймер", "Поставил на ${seconds / 60} мин")
        } catch (t: Throwable) {
            Log.w(TAG, "cannot set timer", t)
        }
    }

    private fun setRinger(context: Context, mode: Int, message: String) {
        val audio = context.getSystemService<AudioManager>()
        try {
            audio?.ringerMode = mode
            notify(context, "Звук", message)
        } catch (t: Throwable) {
            Log.w(TAG, "cannot change ringer mode", t)
        }
    }

    private fun currentTrack(context: Context): String? = try {
        val manager = context.getSystemService<MediaSessionManager>() ?: return null
        val component = ComponentName(context, NotifyHandler::class.java)
        val sessions = manager.getActiveSessions(component)
        val metadata = sessions.firstOrNull()?.metadata ?: return null
        val title = metadata.getString(MediaMetadata.METADATA_KEY_TITLE)
        val artist = metadata.getString(MediaMetadata.METADATA_KEY_ARTIST)
        when {
            title.isNullOrBlank() -> null
            artist.isNullOrBlank() -> title
            else -> "$title — $artist"
        }
    } catch (t: Throwable) {
        Log.w(TAG, "cannot read the media session", t)
        null
    }

    private fun openYoutubeSearch(context: Context, query: String) {
        val uri = Uri.parse("https://www.youtube.com/results?search_query=" + Uri.encode(query))
        try {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (t: Throwable) {
            Log.w(TAG, "cannot open YouTube", t)
        }
    }

    /** Extracts a phone number from an utterance, if it looks like one. */
    fun numberFrom(text: String): String? {
        val match = NUMBER.find(text) ?: return null
        val digits = match.value.filter { it.isDigit() }
        return if (digits.length >= 5) digits else null
    }

    fun notify(context: Context, title: String, text: String) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        val channelId = "una_quick"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(channelId, "Una", NotificationManager.IMPORTANCE_DEFAULT)
            )
        }
        val open = PendingIntent.getActivity(
            context, 0, Intent(context, org.stypox.dicio.MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(R.drawable.ic_hearing_white)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setContentIntent(open)
            .build()
        manager.notify((title + text).hashCode(), notification)
    }
}
