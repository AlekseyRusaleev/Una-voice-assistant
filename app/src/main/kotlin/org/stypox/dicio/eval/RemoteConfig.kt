package org.stypox.dicio.eval

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

/**
 * The command dictionary lives on the server, so that adding a new phrasing does not require
 * rebuilding and reinstalling the app. A copy is cached on disk and used when the server is
 * unreachable, and [defaults] is used on a completely fresh install.
 */
object RemoteConfig {
    private const val TAG = "RemoteConfig"

    const val DEFAULT_URL = "https://9.247.112.23.sslip.io"
    const val DEFAULT_TOKEN = "9d51cf1c6faa332bc3d8d2d60d6518353a3ab0924ba43396"

    data class Config(
        val previous: List<String>,
        val next: List<String>,
        val pause: List<String>,
        val play: List<String>,
        val musicContext: List<String>,
        val volumeUp: List<String>,
        val volumeDown: List<String>,
        val numberWords: Map<String, Int>,
        val yandexOpen: List<String>,
        val yandexWave: List<String>,
        val yandexLiked: List<String>,
        val wakeWord: String,
        val minConfidence: Double,
    )

    val defaults = Config(
        previous = listOf("предыдущ", "предыду", "прошл", "верни", "назад", "обратно"),
        next = listOf("следующ", "следущ", "следую", "переключ", "пропуст", "промота", "дальше"),
        pause = listOf("пауз", "останов", "приостанов", "стоп", "замолч", "затихн", "прекрат", "выключ"),
        play = listOf("продолж", "игра", "воспроизв", "плей", "включи"),
        musicContext = listOf("музык", "трек", "песн", "плеер", "плеере", "композици"),
        volumeUp = listOf("громче", "погромче", "прибав"),
        volumeDown = listOf("тише", "потише", "убав", "сбав"),
        numberWords = mapOf(
            "ноль" to 0, "один" to 1, "одну" to 1, "два" to 2, "две" to 2, "три" to 3,
            "четыре" to 4, "пять" to 5, "шесть" to 6, "семь" to 7, "восемь" to 8, "девять" to 9,
            "десять" to 10, "одиннадцать" to 11, "двенадцать" to 12, "тринадцать" to 13,
            "четырнадцать" to 14, "пятнадцать" to 15,
        ),
        yandexOpen = listOf("включ", "постав", "запуст", "откр"),
        yandexWave = listOf("мою волн", "моя волн", "моей волн", "волну", "волна"),
        yandexLiked = listOf("нравит", "любимы", "любимое"),
        wakeWord = "юна",
        minConfidence = 0.85,
    )

    @Volatile
    var current: Config = defaults
        private set

    private fun cacheFile(context: Context) = File(context.filesDir, "remote-config.json")

    /** Loads the last downloaded dictionary, if any. Cheap, safe to call on every start. */
    fun loadCached(context: Context) {
        try {
            val file = cacheFile(context)
            if (!file.exists()) {
                return
            }
            parse(JSONObject(file.readText()))?.let { current = it }
        } catch (t: Throwable) {
            Log.w(TAG, "cannot load the cached config", t)
        }
    }

    /** Downloads the dictionary in the background; failures are silently ignored. */
    fun refresh(context: Context, url: String = DEFAULT_URL, token: String = DEFAULT_TOKEN) {
        thread(name = "una-config") {
            try {
                val connection = (URL("$url/config").openConnection() as HttpURLConnection).apply {
                    connectTimeout = 5000
                    readTimeout = 5000
                    setRequestProperty("X-Una-Token", token)
                }
                if (connection.responseCode != 200) {
                    Log.w(TAG, "config: HTTP ${connection.responseCode}")
                    return@thread
                }
                val body = connection.inputStream.bufferedReader().use { it.readText() }
                val parsed = parse(JSONObject(body)) ?: return@thread
                cacheFile(context).writeText(body)
                current = parsed
                Log.i(TAG, "config updated from server")
            } catch (t: Throwable) {
                Log.w(TAG, "config fetch failed", t)
            }
        }
    }

    private fun parse(json: JSONObject): Config? = try {
        val media = json.getJSONObject("media")
        val volume = json.getJSONObject("volume")
        val yandex = json.getJSONObject("yandex")
        val wake = json.getJSONObject("wake")

        val words = mutableMapOf<String, Int>()
        val wordsJson = volume.getJSONObject("words")
        for (key in wordsJson.keys()) {
            words[key] = wordsJson.getInt(key)
        }

        Config(
            previous = media.strings("previous").ifEmpty { defaults.previous },
            next = media.strings("next").ifEmpty { defaults.next },
            pause = media.strings("pause").ifEmpty { defaults.pause },
            play = media.strings("play").ifEmpty { defaults.play },
            musicContext = media.strings("context").ifEmpty { defaults.musicContext },
            volumeUp = volume.strings("up").ifEmpty { defaults.volumeUp },
            volumeDown = volume.strings("down").ifEmpty { defaults.volumeDown },
            numberWords = words.ifEmpty { defaults.numberWords },
            yandexOpen = yandex.strings("open").ifEmpty { defaults.yandexOpen },
            yandexWave = yandex.strings("wave").ifEmpty { defaults.yandexWave },
            yandexLiked = yandex.strings("liked").ifEmpty { defaults.yandexLiked },
            wakeWord = wake.optString("word", defaults.wakeWord).ifBlank { defaults.wakeWord },
            minConfidence = wake.optDouble("minConfidence", defaults.minConfidence),
        )
    } catch (t: Throwable) {
        Log.w(TAG, "malformed config", t)
        null
    }

    private fun JSONObject.strings(key: String): List<String> {
        val array = optJSONArray(key) ?: return emptyList()
        return (0 until array.length())
            .map { array.optString(it) }
            .filter { it.isNotBlank() }
    }
}
