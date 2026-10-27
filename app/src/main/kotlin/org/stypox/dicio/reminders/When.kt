package org.stypox.dicio.reminders

import java.util.Calendar

/**
 * Very small Russian time parser for voice commands:
 * "через 10 минут", "через полчаса", "в 18:30", "в 7 часов утра", "завтра в 9".
 * It only needs to cover what people actually say to set a reminder.
 */
object When {
    private val UNITS = listOf(
        "секунд" to 1_000L,
        "минут" to 60_000L,
        "час" to 3_600_000L,
        "дн" to 86_400_000L,
        "сут" to 86_400_000L,
    )

    private val WORDS = mapOf(
        "ноль" to 0, "один" to 1, "одну" to 1, "одна" to 1, "два" to 2, "две" to 2, "три" to 3,
        "четыре" to 4, "пять" to 5, "шесть" to 6, "семь" to 7, "восемь" to 8, "девять" to 9,
        "десять" to 10, "одиннадцать" to 11, "двенадцать" to 12, "тринадцать" to 13,
        "четырнадцать" to 14, "пятнадцать" to 15, "шестнадцать" to 16, "семнадцать" to 17,
        "восемнадцать" to 18, "девятнадцать" to 19, "двадцать" to 20, "тридцать" to 30,
        "сорок" to 40, "пятьдесят" to 50, "полчаса" to 30,
    )

    /** @return absolute time in millis, or null if the phrase has no recognisable moment. */
    fun parse(text: String, now: Long = System.currentTimeMillis()): Long? {
        val phrase = text.lowercase().replace('ё', 'е')

        if (phrase.contains("полчаса")) {
            return now + 30 * 60_000L
        }

        // "через 10 минут", "через два часа"
        Regex("через\\s+([а-я0-9]+)\\s*(секунд|минут|час|дн|сут)").find(phrase)?.let { match ->
            val amount = number(match.groupValues[1]) ?: return@let
            val unit = UNITS.firstOrNull { match.groupValues[2].startsWith(it.first) } ?: return@let
            return now + amount * unit.second
        }

        // "в 18:30" / "в 18.30"
        Regex("\\bв\\s+(\\d{1,2})[:.](\\d{2})").find(phrase)?.let { match ->
            return atClock(now, match.groupValues[1].toInt(), match.groupValues[2].toInt(), phrase)
        }

        // "в 7 часов утра", "в 7", "в 19 часов"
        Regex("\\bв\\s+(\\d{1,2})(?:\\s*(?:час|утра|вечера|дня|ночи))?").find(phrase)?.let { match ->
            val hour = match.groupValues[1].toInt()
            val adjusted = when {
                phrase.contains("вечера") && hour < 12 -> hour + 12
                phrase.contains("дня") && hour < 12 -> hour + 12
                phrase.contains("ночи") && hour == 12 -> 0
                else -> hour
            }
            return atClock(now, adjusted, 0, phrase)
        }

        return null
    }

    private fun atClock(now: Long, hour: Int, minute: Int, phrase: String): Long? {
        if (hour !in 0..23 || minute !in 0..59) return null
        val calendar = Calendar.getInstance().apply {
            timeInMillis = now
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        if (phrase.contains("завтра")) {
            calendar.add(Calendar.DAY_OF_YEAR, 1)
            return calendar.timeInMillis
        }
        if (calendar.timeInMillis <= now) {
            // the time has already passed today — assume tomorrow
            calendar.add(Calendar.DAY_OF_YEAR, 1)
        }
        return calendar.timeInMillis
    }

    private fun number(raw: String): Long? {
        raw.toLongOrNull()?.let { return it }
        return WORDS[raw]?.toLong()
    }
}
