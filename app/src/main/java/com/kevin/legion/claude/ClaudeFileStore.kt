package com.kevin.legion.claude

import android.content.Context
import android.webkit.MimeTypeMap
import java.io.File
import java.io.IOException
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** A delivered file that is on this phone (ADR 0080). */
data class LocalClaudeFile(val taskId: Int, val fileId: Int, val name: String, val file: File, val mimeType: String) {
    val isImage: Boolean get() = mimeType.startsWith("image/")
}

/**
 * Where [ClaudeResultWatcher] keeps a `deliver: files` task's files (ADR 0080). The seam exists for
 * the watcher's tests; the one real shelf is [ClaudeFileStore].
 */
interface ClaudeFileShelf {
    fun has(taskId: Int, fileId: Int): Boolean

    /** True only once the bytes are on disk. */
    suspend fun keep(taskId: Int, fileId: Int, name: String, bytes: ByteArray): Boolean
}

/**
 * The phone's copy of the files Kevin asked Claude for (files to phone, ADR 0080), in APP-PRIVATE
 * storage: `filesDir/claude_files/<task>/<file id>-<name>`. No other app can read it; the viewer
 * hands one file at a time to another app through LEGION's FileProvider (`res/xml/provider_paths.xml`,
 * `claude_files/`), read-only, for that one intent. Kevin's share to WhatsApp or anywhere else is his
 * own tap on Android's share sheet; nothing here sends anything.
 *
 * **Not in Downloads.** The 2026-11-01 ruling "keep auto-save to Downloads" stays for the older
 * `send_file` path (a file offered and then asked for); a delivered file may be a scanned ID, and
 * public Downloads is readable by every app with media access. Kevin can save one with Share.
 * Excluded from Android's cloud backup and device transfer (`res/xml/data_extraction_rules.xml`,
 * `backup_rules.xml`), so a scan never lands in a backup. Kept [KEEP_DAYS] days, the engine's own
 * retention for the task, then deleted when the next file is kept ([prune]).
 *
 * Nothing logs: no file name, path or content goes to Logcat or the engine's device logs.
 */
class ClaudeFileStore(context: Context, private val now: () -> Long = System::currentTimeMillis) : ClaudeFileShelf {
    private val root = File(context.applicationContext.filesDir, DIR)

    private fun taskDir(taskId: Int) = File(root, taskId.toString())

    override fun has(taskId: Int, fileId: Int): Boolean =
        taskDir(taskId).listFiles()?.any { it.name.startsWith("$fileId-") } == true

    override suspend fun keep(taskId: Int, fileId: Int, name: String, bytes: ByteArray): Boolean =
        withContext(Dispatchers.IO) {
            prune()
            val dir = taskDir(taskId)
            val target = File(dir, "$fileId-${safeName(name)}")
            val part = File(dir, "$fileId.part")
            try {
                if (!dir.isDirectory && !dir.mkdirs()) return@withContext false
                part.writeBytes(bytes)
                // Written whole first, then renamed: a half-written file is never "on the phone".
                part.renameTo(target) || run {
                    part.delete()
                    false
                }
            } catch (e: IOException) {
                part.delete()
                false
            }
        }

    /** Every file of task [taskId] on this phone, oldest file id first. */
    fun list(taskId: Int): List<LocalClaudeFile> =
        taskDir(taskId).listFiles().orEmpty()
            .filter { it.isFile && !it.name.endsWith(".part") }
            .mapNotNull { f ->
                val dash = f.name.indexOf('-')
                val id = f.name.take(dash.coerceAtLeast(0)).toIntOrNull() ?: return@mapNotNull null
                val name = f.name.substring(dash + 1)
                LocalClaudeFile(taskId, id, name, f, mimeType(name))
            }
            .sortedBy { it.fileId }

    /** Deletes tasks' files older than [KEEP_DAYS]. Returns how many task folders went. */
    fun prune(): Int {
        val cutoff = now() - KEEP_DAYS * DAY_MS
        var gone = 0
        root.listFiles().orEmpty().filter { it.isDirectory }.forEach { dir ->
            val newest = dir.listFiles().orEmpty().maxOfOrNull { it.lastModified() } ?: 0L
            if (newest < cutoff && dir.deleteRecursively()) gone++
        }
        return gone
    }

    companion object {
        const val DIR = "claude_files"
        const val KEEP_DAYS = 30L
        private const val DAY_MS = 24L * 60 * 60 * 1000
        private const val NAME_MAX = 120

        /** A name safe as one path segment: no separators, no reserved characters, never blank. */
        fun safeName(name: String): String =
            name.substringAfterLast('/').substringAfterLast('\\')
                .replace(Regex("""[:*?"<>|\x00-\x1f]"""), "_")
                .trim().trimStart('.')
                .take(NAME_MAX)
                .ifBlank { "claude-file" }

        /** From the extension, the way the viewer and the share sheet need it; octet-stream otherwise. */
        fun mimeType(name: String): String {
            val ext = name.substringAfterLast('.', "").lowercase(Locale.US)
            return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: KNOWN[ext] ?: "application/octet-stream"
        }

        private val KNOWN = mapOf(
            "jpg" to "image/jpeg", "jpeg" to "image/jpeg", "png" to "image/png", "gif" to "image/gif",
            "webp" to "image/webp", "heic" to "image/heic", "pdf" to "application/pdf", "txt" to "text/plain",
            "doc" to "application/msword",
            "docx" to "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "xlsx" to "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
            "csv" to "text/csv", "mp4" to "video/mp4", "zip" to "application/zip",
        )
    }
}
