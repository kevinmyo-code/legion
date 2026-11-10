package com.kevin.legion.claude

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider

/**
 * The two intents a delivered file leaves LEGION through (ADR 0080), both read-only grants on a
 * FileProvider URI for that one intent, never a path:
 *
 * - [view]: ACTION_VIEW, so a PDF or a document opens in whatever reads it on the phone.
 * - [share]: Android's own share sheet. Kevin picks WhatsApp, or anything else, and sends it
 *   himself; LEGION never chooses a recipient (the 2026-11-01 "WhatsApp dropped" ruling stands, save
 *   for this ordinary share).
 *
 * Authority `<package>.provider`, the one FileProvider in the manifest; `claude_files/` is its
 * `files-path` (`res/xml/provider_paths.xml`).
 */
object ClaudeFileIntents {
    /**
     * Test seam: FileProvider's root check compares paths with '/', so it refuses every file under
     * Robolectric on Windows (backslashes) while it works on the phone. Tests swap this for a
     * content URI of the same authority and shape.
     */
    internal var uriFor: (Context, LocalClaudeFile) -> Uri = { context, file ->
        FileProvider.getUriForFile(context, "${context.packageName}.provider", file.file)
    }

    fun uri(context: Context, file: LocalClaudeFile): Uri = uriFor(context, file)

    fun view(context: Context, file: LocalClaudeFile): Intent {
        val uri = uri(context, file)
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, file.mimeType)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        return Intent.createChooser(intent, "Open ${file.name} with")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    fun share(context: Context, files: List<LocalClaudeFile>): Intent {
        require(files.isNotEmpty()) { "nothing to share" }
        val uris = files.map { uri(context, it) }
        val type = files.map { it.mimeType }.distinct().singleOrNull() ?: "*/*"
        val send = if (uris.size == 1) {
            Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris.single())
        } else {
            Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
        }
        send.type = type
        // ClipData carries the read grant to whichever app is picked on the sheet.
        send.clipData = ClipData.newRawUri(files.first().name, uris.first()).apply {
            uris.drop(1).forEach { addItem(ClipData.Item(it)) }
        }
        send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        val title = if (files.size == 1) "Share ${files.single().name}" else "Share ${files.size} files"
        return Intent.createChooser(send, title).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
}
