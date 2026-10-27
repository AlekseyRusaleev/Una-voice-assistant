package org.stypox.dicio.eval

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.ContactsContract
import android.util.Log
import androidx.core.content.ContextCompat

/**
 * Contact lookup for the calling and SMS commands. Names are compared by word stems, so Russian
 * case endings do not matter ("маме" → "мама").
 */
object Contacts {
    private const val TAG = "Contacts"

    private val STEM_SUFFIXES = listOf(
        "ами", "ями", "ого", "его", "ому", "ему", "ыми", "ими", "ая", "яя", "ой", "ей",
        "ый", "ий", "ом", "ем", "ах", "ях", "ам", "ям", "ов", "ев", "ия", "ие", "а", "я", "о",
        "е", "у", "ю", "ы", "и", "ь",
    )

    fun canRead(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) ==
            PackageManager.PERMISSION_GRANTED

    /** @return the phone number of the best matching contact, or null. */
    fun findNumber(context: Context, spoken: String): String? {
        if (!canRead(context)) {
            Log.w(TAG, "contacts permission is not granted")
            return null
        }
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
                    if (number.isNullOrBlank()) continue

                    val words = name.lowercase().replace('ё', 'е')
                        .split(' ', '-')
                        .filter { it.isNotBlank() }
                    if (words.isEmpty()) continue

                    var score = 0
                    for (tokenStem in tokenStems) {
                        for (word in words) {
                            val wordStem = stem(word)
                            val value = when {
                                wordStem == tokenStem -> 4
                                wordStem.startsWith(tokenStem) || tokenStem.startsWith(wordStem) -> 3
                                else -> 0
                            }
                            if (value > score) score = value
                        }
                    }
                    if (score < 3) continue
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
}
