package com.kevin.legion.books

import android.content.Context
import android.content.Intent
import android.net.Uri

/**
 * The Books folder grant: its tree URI in a private prefs file (not the engine, not synced; ADR
 * 0061) plus a persisted READ-only grant. Whether the grant is still alive is asked of the system
 * each time ([isGranted]), never remembered, so a revoked grant shows as one. Same shape as
 * `games/GamesFolderStore`, a separate file so the two folders are independent.
 */
class BooksFolderStore(context: Context) {
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

    /** The last scan's book count, so the chip says a number before the next scan finishes. */
    fun cachedCount(): Int? = if (prefs.contains(KEY_COUNT)) prefs.getInt(KEY_COUNT, 0) else null

    fun cacheCount(count: Int) {
        prefs.edit().putInt(KEY_COUNT, count).apply()
    }

    private companion object {
        const val PREFS = "books_folder"
        const val KEY_TREE = "tree_uri"
        const val KEY_COUNT = "book_count"
    }
}
