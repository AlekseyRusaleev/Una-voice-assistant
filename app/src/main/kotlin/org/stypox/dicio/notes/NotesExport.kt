package org.stypox.dicio.notes

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread

/**
 * Exports notes as Markdown files into a folder the user picks (for example an Obsidian vault).
 *
 * Nothing leaves the device: files are written through the Storage Access Framework, straight into
 * the folder the user granted. Once a folder has been chosen, every new note is written there
 * automatically, which is what makes the whole thing feel like real sync.
 */
object NotesExport {
    private const val TAG = "NotesExport"
    private const val PREFS = "una_notes_export"
    private const val KEY_URI = "target"

    data class Result(val written: Int, val folder: String)

    fun target(context: Context): Uri? =
        prefs(context).getString(KEY_URI, null)?.let { Uri.parse(it) }

    fun setTarget(context: Context, uri: Uri?) {
        prefs(context).edit().apply {
            if (uri == null) remove(KEY_URI) else putString(KEY_URI, uri.toString())
        }.apply()
    }

    /** Writes one note into the configured folder, in the background. */
    fun autoExport(context: Context, note: Notes.Note) {
        val uri = target(context) ?: return
        thread(name = "una-note-export") {
            try {
                val tree = DocumentFile.fromTreeUri(context, uri) ?: return@thread
                write(context, tree, note)
            } catch (t: Throwable) {
                Log.w(TAG, "auto export failed", t)
            }
        }
    }

    /** Writes every note into [treeUri]; also remembers it as the auto-export target. */
    fun exportAll(context: Context, treeUri: Uri): Result? = try {
        setTarget(context, treeUri)
        val tree = DocumentFile.fromTreeUri(context, treeUri) ?: return null
        var written = 0
        for (note in Notes.all(context)) {
            if (write(context, tree, note)) {
                written++
            }
        }
        Log.i(TAG, "exported $written notes to ${tree.name}")
        Result(written, tree.name ?: "выбранная папка")
    } catch (t: Throwable) {
        Log.w(TAG, "export failed", t)
        null
    }

    private fun write(context: Context, tree: DocumentFile, note: Notes.Note): Boolean = try {
        val name = fileName(note)
        val file = tree.findFile(name) ?: tree.createFile("text/markdown", name)
        if (file == null) {
            false
        } else {
            context.contentResolver.openOutputStream(file.uri)?.use { stream ->
                stream.write(markdown(note).toByteArray(Charsets.UTF_8))
            }
            true
        }
    } catch (t: Throwable) {
        Log.w(TAG, "cannot write ${note.id}", t)
        false
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

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
