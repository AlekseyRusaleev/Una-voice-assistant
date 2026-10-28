package org.stypox.dicio.eval

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaSessionManager
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.provider.AlarmClock
import android.provider.CalendarContract
import android.telephony.SmsManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.core.content.getSystemService
import org.stypox.dicio.R
import org.stypox.dicio.notes.Notes
import org.stypox.dicio.reminders.Reminders
import org.stypox.dicio.reminders.When
import org.stypox.dicio.skills.notify.NotifyHandler
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

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
    private val ALARM = Regex("""будильник\s+(?:на\s+)?([\dа-я]+)(?:[:.](\d{2}))?""", RegexOption.IGNORE_CASE)
    private val TIMER = Regex("""таймер\s*(?:на\s+)?(.+)""", RegexOption.IGNORE_CASE)
    private val FLASHLIGHT = Regex(
        """(включи|вруби|зажги|включить)\s+(фонарик|фонарь|свет|подсветк\w*)""",
        RegexOption.IGNORE_CASE,
    )
    private val FLASHLIGHT_OFF = Regex(
        """(выключи|выруби|погаси|отключи|выключить)\s+(фонарик|фонарь|свет|подсветк\w*)""",
        RegexOption.IGNORE_CASE,
    )
    private val YOUTUBE = Regex(
        """ютуб\w*\s+(?:включи\s+)?(.+)""", RegexOption.IGNORE_CASE
    )
    private val NUMBER = Regex("""[\d\s\-()]{5,}""")
    private val CALENDAR = Regex(
        """(?:запиши|записать|добавь|поставь|создай|назначь)\s+в\s+календар\w*\s+(.+)""",
        RegexOption.IGNORE_CASE,
    )
    private val SMS = Regex(
        """(?:отправь|отправить|напиши|пошли|скинь|шлепни)\s+(?:смс|эсэмэс|сообщение)\s+(.+)""",
        RegexOption.IGNORE_CASE,
    )
    private val OPEN_APP = Regex("""открой\s+(.+)""", RegexOption.IGNORE_CASE)

    /** @return true when the phrase was handled here. */
    fun handle(context: Context, text: String, utterance: String): Boolean {
        // ---- flashlight: "включи фонарик" must never be read as "turn the music on"
        if (FLASHLIGHT.containsMatchIn(text)) {
            setTorch(context, true)
            return true
        }
        if (FLASHLIGHT_OFF.containsMatchIn(text)) {
            setTorch(context, false)
            return true
        }

        // ---- calendar first: the notes handler below also matches "запиши ..."
        CALENDAR.find(text)?.let { match ->
            val rest = match.groupValues[1].trim()
            if (rest.length >= 2) {
                addCalendarEvent(context, rest)
                return true
            }
        }

        // ---- sms before the quick reply handler, which also matches "напиши ..."
        // the raw utterance keeps punctuation, so «смс маме: привет» still splits by the colon
        val spoken = if (utterance.isBlank()) text else utterance.lowercase().replace('ё', 'е')
        (SMS.find(spoken) ?: SMS.find(text))?.let { match ->
            val body = match.groupValues[1].trim()
            val separator = body.indexOfAny(charArrayOf(':', '：', '—', ','))
            val who: String
            val message: String
            if (separator > 0) {
                who = body.substring(0, separator).trim()
                message = body.substring(separator + 1).trim()
            } else {
                val parts = body.split(' ', limit = 2)
                who = parts.getOrNull(0).orEmpty()
                message = parts.getOrNull(1).orEmpty()
            }
            if (who.isNotEmpty() && message.length >= 2) {
                sendSms(context, who, message)
                return true
            }
        }

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
            val rawHour = match.groupValues[1]
            val hour = rawHour.toIntOrNull() ?: RemoteConfig.current.numberWords[rawHour] ?: return@let
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

        // ---- open an installed app by name
        OPEN_APP.find(text)?.let { match ->
            val name = match.groupValues[1].trim()
            if (name.length >= 2) {
                val intent = findApp(context, name)
                if (intent != null) {
                    try {
                        context.startActivity(intent)
                        return true
                    } catch (t: Throwable) {
                        Log.w(TAG, "cannot launch app", t)
                    }
                }
            }
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

    /** Creates a calendar event, silently when it is allowed and through the calendar app otherwise. */
    private fun addCalendarEvent(context: Context, rest: String) {
        val begin = When.parse(rest) ?: (System.currentTimeMillis() + 3_600_000L)
        val title = cleanEvent(rest)
        val end = begin + 3_600_000L
        val label = SimpleDateFormat("dd.MM в HH:mm", Locale.getDefault()).format(Date(begin))

        if (silentInsertEvent(context, title, begin, end)) {
            notify(context, "Записано в календарь: $label", title)
            return
        }

        val intent = Intent(Intent.ACTION_INSERT).apply {
            data = CalendarContract.Events.CONTENT_URI
            putExtra(CalendarContract.Events.TITLE, title)
            putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, begin)
            putExtra(CalendarContract.EXTRA_EVENT_END_TIME, end)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            context.startActivity(intent)
            notify(context, "Событие: $label", "$title\nОсталось нажать «Сохранить»")
        } catch (t: Throwable) {
            Log.w(TAG, "cannot open the calendar", t)
            notify(context, "Календарь", "Не нашёл приложение календаря")
        }
    }

    /** Inserts the event straight into the default writable calendar. Needs READ/WRITE_CALENDAR. */
    private fun silentInsertEvent(context: Context, title: String, begin: Long, end: Long): Boolean {
        val read = ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALENDAR)
        val write = ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_CALENDAR)
        if (read != PackageManager.PERMISSION_GRANTED ||
            write != PackageManager.PERMISSION_GRANTED
        ) {
            return false
        }
        return try {
            val projection = arrayOf(CalendarContract.Calendars._ID)
            val selection = CalendarContract.Calendars.VISIBLE + "=1 AND " +
                CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL + ">=?"
            val args = arrayOf(CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR.toString())
            val calendarId = context.contentResolver
                .query(CalendarContract.Calendars.CONTENT_URI, projection, selection, args, null)
                ?.use { cursor -> if (cursor.moveToFirst()) cursor.getLong(0) else null }
                ?: return false

            val values = ContentValues().apply {
                put(CalendarContract.Events.DTSTART, begin)
                put(CalendarContract.Events.DTEND, end)
                put(CalendarContract.Events.TITLE, title)
                put(CalendarContract.Events.CALENDAR_ID, calendarId)
                put(CalendarContract.Events.EVENT_TIMEZONE, TimeZone.getDefault().id)
            }
            context.contentResolver.insert(CalendarContract.Events.CONTENT_URI, values) != null
        } catch (t: Throwable) {
            Log.w(TAG, "cannot insert the event silently", t)
            false
        }
    }

    /** Strips the command and time words so the event title reads naturally. */
    private fun cleanEvent(text: String): String =
        text.replace(
            Regex("""(?:запиши|записать|добавь|поставь|создай|назначь|мне|пожалуйста)""",
                RegexOption.IGNORE_CASE), " ",
        )
            .replace(Regex("""в\s+календар\w*""", RegexOption.IGNORE_CASE), " ")
            .replace(Regex("""через\s+\d+\s*(?:минут\w*|час\w*|секунд\w*)""", RegexOption.IGNORE_CASE), " ")
            .replace(Regex("""\bв\s+\d{1,2}(?:[:.]\d{2})?(?:\s*(?:час\w*|утра|вечера|дня|ночи))?""",
                RegexOption.IGNORE_CASE), " ")
            .replace(Regex("""\b(?:завтра|сегодня|послезавтра|утра|вечера|дня|ночи)\b""",
                RegexOption.IGNORE_CASE), " ")
            .replace(Regex("""\s+"""), " ")
            .trim()
            .ifBlank { "Событие" }

    /** Sends an SMS: the recipient is either a spoken number or a contact name. */
    private fun sendSms(context: Context, spokenTo: String, message: String) {
        val number = numberFrom(spokenTo)?.let(::normalizeNumber)
            ?: Contacts.findNumber(context, spokenTo)
        if (number == null) {
            notify(context, "СМС", "Не нашёл контакт «$spokenTo». Скажите номер или имя из книги.")
            return
        }

        // SEND_SMS is refused for many sideloaded apps and some ROMs, so the command must still
        // work: fall back to the messaging app with the recipient and text already filled in.
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.SEND_SMS) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            try {
                val manager = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    context.getSystemService(SmsManager::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    SmsManager.getDefault()
                } ?: throw IllegalStateException("нет SmsManager")
                manager.sendMultipartTextMessage(
                    number, null, manager.divideMessage(message), null, null,
                )
                notify(context, "СМС отправлено на $number", message)
                return
            } catch (t: Throwable) {
                Log.w(TAG, "cannot send an sms silently", t)
            }
        }

        try {
            val intent = Intent(
                Intent.ACTION_SENDTO, Uri.parse("smsto:" + Uri.encode(number)),
            ).apply {
                putExtra("sms_body", message)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            notify(
                context, "СМС на $number",
                "$message\nОсталось нажать «Отправить»: система не даёт Una отправлять самой.",
            )
        } catch (t: Throwable) {
            Log.w(TAG, "cannot open the messaging app", t)
            notify(context, "СМС", "Нет приложения для сообщений — отправка невозможна")
        }
    }

    /** Russian numbers are said as 10 digits; add the country code so the SMS actually leaves. */
    private fun normalizeNumber(digits: String): String = when {
        digits.length == 10 && digits.startsWith("9") -> "+7$digits"
        digits.length == 11 && digits.startsWith("8") -> "+7" + digits.substring(1)
        digits.startsWith("+") -> digits
        digits.length > 11 -> "+$digits"
        else -> digits
    }

    /** Finds a launchable app whose label starts with or contains the spoken name. */
    private fun findApp(context: Context, name: String): Intent? {
        val query = name.lowercase().replace('ё', 'е')
        val manager = context.packageManager
        return try {
            manager.getInstalledApplications(0)
                .asSequence()
                .filter { manager.getLaunchIntentForPackage(it.packageName) != null }
                .map { it to manager.getApplicationLabel(it).toString().lowercase().replace('ё', 'е') }
                .filter { (_, label) -> label.isNotBlank() }
                .sortedBy { (_, label) -> if (label.startsWith(query)) 0 else 1 }
                .firstOrNull { (_, label) -> label.startsWith(query) || label.contains(query) }
                ?.first
                ?.let { info ->
                    manager.getLaunchIntentForPackage(info.packageName)
                        ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
        } catch (t: Throwable) {
            Log.w(TAG, "cannot list apps", t)
            null
        }
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

    /** Switches the camera flash on or off; needs the camera permission. */
    private fun setTorch(context: Context, on: Boolean) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notify(context, "Фонарик", "Нужен доступ к камере — включите его в «Разрешениях»")
            return
        }
        try {
            val manager = context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager
                ?: throw IllegalStateException("нет камеры")
            val cameraId = manager.cameraIdList.firstOrNull()
                ?: throw IllegalStateException("камера не найдена")
            manager.setTorchMode(cameraId, on)
            notify(context, "Фонарик", if (on) "Включён" else "Выключен")
        } catch (t: Throwable) {
            Log.w(TAG, "cannot switch the torch", t)
            notify(context, "Фонарик", "Не получилось: ${t.message ?: "ошибка"}")
        }
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
