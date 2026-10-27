package org.stypox.dicio.eval

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.net.Uri
import android.provider.ContactsContract
import android.util.Log
import android.view.KeyEvent
import androidx.core.content.ContextCompat
import androidx.core.content.getSystemService

/**
 * Commands handled before the sentence-based skill matching.
 *
 * Matching works on word stems rather than exact phrases (a speech recogniser produces a different
 * word form every time) and the stem lists come from [RemoteConfig], so a new phrasing can be added
 * on the server without rebuilding the app.
 */
object FastCommands {
    private const val TAG = "FastCommands"
    private const val YANDEX_MUSIC_PACKAGE = "ru.yandex.music"
    private const val RESUME_DELAY_MILLIS = 1800L

    private val VOLUME = Regex(
        """(?:громкост|звук)\D{0,15}?(\d{1,3}|[а-я]+)""",
        RegexOption.IGNORE_CASE
    )

    private val CALL_VERBS = listOf("позвони", "позвонить", "набери", "набрать", "набери номер")
    /** Longest endings first, so that "ого" is stripped before "о". */
    private val STEM_SUFFIXES = listOf(
        "ами", "ями", "ого", "его", "ому", "ему", "ыми", "ими", "ая", "яя", "ой", "ей",
        "ый", "ий", "ом", "ем", "ах", "ях", "ам", "ям", "ов", "ев", "ия", "ие", "а", "я", "о",
        "е", "у", "ю", "ы", "и", "ь",
    )

    private val CALL_FILLER = listOf(
        "мне", "пожалуйста", "пожалуй", "срочно", "номер", "контакту", "контакт", "телефону", "телефон",
    )

    /** @return `true` if the utterance was fully handled here and must not reach the skills. */
    fun handle(context: Context, utterance: String): Boolean {
        val text = normalize(utterance)
        if (text.isBlank()) {
            return false
        }
        val config = RemoteConfig.current

        // 1. call someone: "позвони маме", "набери Лёше"
        if (CALL_VERBS.any { text.contains(it) }) {
            if (handleCall(context, text)) {
                return true
            }
        }

        // 1b. everyday things: notes, reminders, alarms, YouTube, battery, ringer
        if (Extras.handle(context, text, utterance)) {
            return true
        }

        // 2. exact volume: "громкость на семь"
        VOLUME.find(text)?.let { match ->
            parseNumber(match.groupValues[1])?.let { return setVolume(context, it) }
        }

        // 3. relative volume: "громче", "потише"
        if (config.volumeUp.any { text.contains(it) }) {
            return adjustVolume(context, AudioManager.ADJUST_RAISE)
        }
        if (config.volumeDown.any { text.contains(it) }) {
            return adjustVolume(context, AudioManager.ADJUST_LOWER)
        }

        // 4. Yandex Music sections
        if (config.yandexOpen.any { text.contains(it) }) {
            if (config.yandexWave.any { text.contains(it) }) {
                return openYandexMusic(context, "wave")
            }
            if (config.yandexLiked.any { text.contains(it) }) {
                return openYandexMusic(context, "liked")
            }
        }

        // 5. media control: "previous" first, because "переключи на предыдущий" contains both
        val hasMusicContext = config.musicContext.any { text.contains(it) }
        val mediaKey = when {
            config.previous.any { text.contains(it) } -> KeyEvent.KEYCODE_MEDIA_PREVIOUS
            config.next.any { text.contains(it) } -> KeyEvent.KEYCODE_MEDIA_NEXT
            config.pause.any { text.contains(it) } -> KeyEvent.KEYCODE_MEDIA_PAUSE
            config.play.any { text.contains(it) } && (hasMusicContext || text.length <= 24) ->
                KeyEvent.KEYCODE_MEDIA_PLAY
            else -> null
        }
        if (mediaKey != null) {
            return sendMediaKey(context, mediaKey)
        }

        return false
    }

    // -------------------------------------------------------------- calls

    private fun handleCall(context: Context, text: String): Boolean {
        var name = text
        for (verb in CALL_VERBS) {
            name = name.replace(verb, " ")
        }
        for (word in CALL_FILLER) {
            name = name.replace(Regex("\\b$word\\b"), " ")
        }
        name = name.replace(Regex("\\s+"), " ").trim()
        if (name.isEmpty()) {
            return false
        }

        // "позвони на номер 8 999 ..." — a number instead of a contact
        Extras.numberFrom(text)?.let { digits -> return dial(context, digits) }

        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            Log.w(TAG, "cannot call: contacts permission is not granted")
            return false
        }

        val number = findNumber(context, name) ?: return false
        return dial(context, number)
    }

    /**
     * Finds the best matching contact for the spoken name.
     *
     * Every contact with a number is scored at once, because a plain "contains" query picks the
     * wrong person when the phone book has both "Юлия" and "Юлия ЦПСС": both contain the word and
     * the first row wins. Words are compared by their stems, which also copes with Russian case
     * endings ("маме" → "мама", "юле" → "юлия").
     */
    private fun findNumber(context: Context, spoken: String): String? {
        val tokens = spoken.split(' ').filter { it.length >= 3 }.take(3)
        if (tokens.isEmpty()) {
            return null
        }
        val tokenStems = tokens.map { stem(it) }

        var bestNumber: String? = null
        var bestScore = 0
        var bestNameLength = Int.MAX_VALUE

        try {
            val uri = ContactsContract.CommonDataKinds.Phone.CONTENT_URI
            val projection = arrayOf(
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                ContactsContract.CommonDataKinds.Phone.NUMBER,
            )
            context.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
                while (cursor.moveToNext()) {
                    val name = cursor.getString(0) ?: continue
                    val number = cursor.getString(1)
                    if (number.isNullOrBlank()) {
                        continue
                    }
                    val words = name.lowercase().replace('ё', 'е')
                        .split(' ', '-')
                        .filter { it.isNotBlank() }
                    if (words.isEmpty()) {
                        continue
                    }

                    var score = 0
                    for (tokenStem in tokenStems) {
                        for (word in words) {
                            val wordStem = stem(word)
                            val value = when {
                                wordStem == tokenStem -> 4
                                wordStem.startsWith(tokenStem) || tokenStem.startsWith(wordStem) -> 3
                                else -> 0
                            }
                            if (value > score) {
                                score = value
                            }
                        }
                    }
                    if (score < 3) {
                        continue
                    }
                    // prefer the shorter name on a tie: "Юлия" over "Юлия ЦПСС"
                    if (score > bestScore || (score == bestScore && name.length < bestNameLength)) {
                        bestScore = score
                        bestNameLength = name.length
                        bestNumber = number
                    }
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "contact lookup failed", t)
        }
        return bestNumber
    }

    /** Crude Russian stemmer: drops the most common endings, which is enough for names. */
    private fun stem(word: String): String {
        var result = word.lowercase().replace('ё', 'е')
        for (suffix in STEM_SUFFIXES) {
            if (result.length - suffix.length >= 3 && result.endsWith(suffix)) {
                result = result.dropLast(suffix.length)
                break
            }
        }
        return result
    }

    /**
     * Places the call if the phone permission is granted; otherwise opens the dialer with the
     * number filled in, so the user only has to press "call".
     */
    private fun dial(context: Context, number: String): Boolean = try {
        val canCall = ContextCompat.checkSelfPermission(context, Manifest.permission.CALL_PHONE) ==
            PackageManager.PERMISSION_GRANTED
        val action = if (canCall) Intent.ACTION_CALL else Intent.ACTION_DIAL
        context.startActivity(
            Intent(action, Uri.parse("tel:${Uri.encode(number)}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        true
    } catch (t: Throwable) {
        Log.e(TAG, "cannot dial", t)
        false
    }

    // -------------------------------------------------------------- helpers

    /** Lowercase, "ё" → "е", punctuation to spaces, collapsed whitespace. */
    private fun normalize(utterance: String): String =
        utterance.lowercase()
            .replace('ё', 'е')
            .replace(Regex("[^а-яa-z0-9 ]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()

    private fun parseNumber(raw: String): Int? {
        raw.toIntOrNull()?.let { return it }
        return RemoteConfig.current.numberWords[raw.lowercase()]
    }

    private fun setVolume(context: Context, requested: Int): Boolean {
        val audio = context.getSystemService<AudioManager>() ?: return false
        val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        if (max <= 0) {
            return false
        }
        val index = requested.coerceIn(0, max)
        return try {
            audio.setStreamVolume(AudioManager.STREAM_MUSIC, index, 0)
            Log.d(TAG, "volume set to $index of $max (asked $requested)")
            true
        } catch (t: Throwable) {
            Log.e(TAG, "cannot set the volume", t)
            false
        }
    }

    private fun adjustVolume(context: Context, direction: Int): Boolean {
        val audio = context.getSystemService<AudioManager>() ?: return false
        return try {
            audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, direction, AudioManager.FLAG_SHOW_UI)
            true
        } catch (t: Throwable) {
            Log.e(TAG, "cannot adjust the volume", t)
            false
        }
    }

    private fun sendMediaKey(context: Context, key: Int): Boolean {
        val audio = context.getSystemService<AudioManager>() ?: return false
        return try {
            audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, key))
            audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, key))
            Log.d(TAG, "sent media key $key")
            true
        } catch (t: Throwable) {
            Log.e(TAG, "cannot send media key $key", t)
            false
        }
    }

    /**
     * Opens Yandex Music. Deep links like "yandexmusic://wave" are not supported by the app (it
     * shows "Нет такой страницы"), so we simply open the app and try to resume playback with a
     * media key. Selecting a section inside the app needs accessibility, which we do not use yet.
     */
    private fun openYandexMusic(context: Context, section: String): Boolean {
        val launch = context.packageManager.getLaunchIntentForPackage(YANDEX_MUSIC_PACKAGE)
            ?: run {
                Extras.notify(context, "Яндекс Музыка", "Приложение не установлено")
                return false
            }
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            context.startActivity(launch)
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                try {
                    val audio = context.getSystemService<AudioManager>()
                    audio?.dispatchMediaKeyEvent(
                        KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PLAY)
                    )
                    audio?.dispatchMediaKeyEvent(
                        KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PLAY)
                    )
                } catch (t: Throwable) {
                    Log.w(TAG, "cannot resume playback", t)
                }
            }, RESUME_DELAY_MILLIS)
            Extras.notify(
                context, "Яндекс Музыка",
                if (section == "wave") "Открыл приложение. Если «Моя волна» не заиграла — нажмите ▶"
                else "Открыл приложение. Раздел «Мне нравится» — вручную",
            )
            true
        } catch (t: Throwable) {
            Log.w(TAG, "cannot open Yandex Music", t)
            false
        }
}
}
