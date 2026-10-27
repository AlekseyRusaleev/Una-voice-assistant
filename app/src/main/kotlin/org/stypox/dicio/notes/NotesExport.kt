package org.stypox.dicio.notes

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Exports notes as Markdown files into a folder the user picks (for example an Obsidian vault).
 * Nothing leaves the device: files are written through the Storage Access Framework, straight
 * into the folder the user granted.
 */
object NotesExport {
    private const val TAG = "NotesExport"

    data class Result(val written: Int, val folder: String)

    /** @return number of written files, or -1 on failure. */
    fun exportAll(context: Context, treeUri: Uri): Result? = try {
        val tree = DocumentFile.fromTreeUri(context, treeUri) ?: return null
        var written = 0
        for (note in Notes.all(context)) {
            val name = fileName(note)
            val existing = tree.findFile(name)
            val file = existing ?: tree.createFile("text/markdown", name) ?: continue
            context.contentResolver.openOutputStream(file.uri)?.use { stream ->
                stream.write(markdown(note).toByteArray(Charsets.UTF_8))
            }
            written++
        }
        Log.i(TAG, "exported $written notes to ${tree.name}")
        Result(written, tree.name ?: "выбранная папка")
    } catch (t: Throwable) {
        Log.w(TAG, "export failed", t)
        null
    }

    private fun fileName(note: Notes.Note): String {
        val date = SimpleDateFormat("yyyy-MM-dd HH.mm", Locale.US).format(Date(note.created))
        val title = note.text.lineSequence().firstOrNull().orEmpty()
            .replace(Regex("""[\\/:*?"<>|]"""), " ")
            .trim()
            .take(40)
            .ifBlank { "Заметка" }
        return "$date $title.md"
    }

    private fun markdown(note: Notes.Note): String {
        val stamp = SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault())
            .format(Date(note.created))
        return buildString {
            append("# ").append(note.text.lineSequence().first()).append("\n\n")
            append(note.text).append("\n\n")
            append("---\n")
            append("Создано: ").append(stamp).append("\n")
            append("Теги: #una #заметки\n")
        }
    }
}
