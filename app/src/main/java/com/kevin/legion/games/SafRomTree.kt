package com.kevin.legion.games

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract

/** [RomTree] over the Storage Access Framework tree the user granted. */
class SafRomTree(private val resolver: ContentResolver, private val treeUri: Uri) : RomTree {
    override fun children(documentId: String): List<TreeChild> {
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, documentId)
        val columns = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
        )
        val out = mutableListOf<TreeChild>()
        resolver.query(childrenUri, columns, null, null, null)?.use { c ->
            while (c.moveToNext()) {
                out += TreeChild(
                    documentId = c.getString(0),
                    name = c.getString(1) ?: "",
                    isDirectory = c.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR,
                )
            }
        }
        return out
    }

    companion object {
        fun rootDocumentId(treeUri: Uri): String = DocumentsContract.getTreeDocumentId(treeUri)

        /** The content URI of one document in the tree, for emulators that take a URI. */
        fun documentUri(treeUri: Uri, documentId: String): Uri =
            DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId)
    }
}

/**
 * The ROMs folder grant: its tree URI in a private prefs file (not the engine, not synced; ADR 0058)
 * plus a persisted READ-only grant. Whether the grant is still alive is asked of the system each
 * time ([isGranted]), never remembered, so a revoked grant shows as one.
 */
class GamesFolderStore(context: Context) {
    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun treeUri(): Uri? = prefs.getString(KEY_TREE, null)?.let(Uri::parse)

    fun isGranted(uri: Uri): Boolean =
        app.contentResolver.persistedUriPermissions.any { it.uri == uri && it.isReadPermission }

    /** [uri] must be the raw `ACTION_OPEN_DOCUMENT_TREE` result. */
    fun connect(uri: Uri) {
        treeUri()?.takeIf { it != uri }?.let { previous ->
            runCatching {
                app.contentResolver.releasePersistableUriPermission(previous, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        }
        app.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        prefs.edit().putString(KEY_TREE, uri.toString()).remove(KEY_COUNT).apply()
    }

    /** The last scan's game count, so the chip says a number before the next scan finishes. */
    fun cachedCount(): Int? = if (prefs.contains(KEY_COUNT)) prefs.getInt(KEY_COUNT, 0) else null

    fun cacheCount(count: Int) {
        prefs.edit().putInt(KEY_COUNT, count).apply()
    }

    private companion object {
        const val PREFS = "games_folder"
        const val KEY_TREE = "tree_uri"
        const val KEY_COUNT = "game_count"
    }
}
