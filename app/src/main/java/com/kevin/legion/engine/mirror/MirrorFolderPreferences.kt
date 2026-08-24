package com.kevin.legion.engine.mirror

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/**
 * Persists the connected mirror folder's tree URI - the aspect-engine twin of
 * [com.kevin.legion.ledger.LedgerFolderPreferences], whose doc comment this one deliberately
 * matches line for line where the reasoning is identical. **A separate persisted URI, its own
 * SharedPreferences file** - the mirror folder is a general "every aspect's xlsx workbooks" Drive
 * folder, not the ledger statements-import folder; Kevin may point them at the same physical Drive
 * folder by hand, but this app never assumes that, and picking one connects only that one.
 *
 * Same split as ledger's: [treeUri] is "what was connected" (a `StateFlow` seeded from disk on
 * [init]), [isPermissionGranted] is "is it still valid" (checked live against
 * [android.content.ContentResolver.persistedUriPermissions], never cached) - a revoked grant must
 * be visible to whatever surfaces the mirror's connection state without this class itself
 * silently going stale.
 */
object MirrorFolderPreferences {
    private const val TAG = "MirrorFolderPrefs"
    private const val PREFS = "mirror_folder_preferences"
    private const val KEY_TREE_URI = "tree_uri"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private val _treeUri = MutableStateFlow<Uri?>(null)
    val treeUri: StateFlow<Uri?> = _treeUri.asStateFlow()

    /** Call once, early, to seed [treeUri] from disk - same convention as `ProactivePreferences.init`/`LedgerFolderPreferences.init`. */
    fun init(context: Context) {
        _treeUri.value = prefs(context).getString(KEY_TREE_URI, null)?.let(Uri::parse)
    }

    /** Persists [uri] and takes a persistable READ+WRITE grant - the mirror needs write, unlike
     * ledger's read-only statements folder, since [com.kevin.legion.engine.mirror.MirrorStore]
     * creates and rewrites xlsx files here. [uri] must be the raw `ACTION_OPEN_DOCUMENT_TREE`
     * result, before anything else touches it. */
    fun connect(context: Context, uri: Uri) {
        _treeUri.value?.takeIf { it != uri }?.let { previous ->
            try {
                context.contentResolver.releasePersistableUriPermission(
                    previous,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
            } catch (e: Exception) {
                Log.w(TAG, "couldn't release previous grant for $previous: ${e.message}")
            }
        }
        context.contentResolver.takePersistableUriPermission(
            uri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
        )
        prefs(context).edit().putString(KEY_TREE_URI, uri.toString()).apply()
        _treeUri.value = uri
    }

    /** Forgets the connected folder. Does not touch anything already written to Room or to the
     * folder itself - disconnecting only stops future sync, matching ledger's own disconnect(). */
    fun disconnect(context: Context) {
        _treeUri.value?.let { uri ->
            try {
                context.contentResolver.releasePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
            } catch (e: Exception) {
                Log.w(TAG, "release failed (likely already revoked): ${e.message}")
            }
        }
        prefs(context).edit().remove(KEY_TREE_URI).apply()
        _treeUri.value = null
    }

    fun isPermissionGranted(context: Context, uri: Uri): Boolean =
        context.contentResolver.persistedUriPermissions.any {
            it.uri == uri && it.isReadPermission && it.isWritePermission
        }

    private fun displayName(context: Context, uri: Uri): String {
        val queried = try {
            val treeDocId = DocumentsContract.getTreeDocumentId(uri)
            val docUri = DocumentsContract.buildDocumentUriUsingTree(uri, treeDocId)
            context.contentResolver.query(
                docUri, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null,
            )?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
        } catch (e: Exception) {
            null
        }
        return queried ?: uri.lastPathSegment ?: "connected folder"
    }

    data class ConnectionStatus(val uri: Uri?, val displayName: String, val permissionGranted: Boolean)

    suspend fun connectionStatus(context: Context): ConnectionStatus = withContext(Dispatchers.IO) {
        val uri = treeUri.value ?: return@withContext ConnectionStatus(null, "", permissionGranted = false)
        ConnectionStatus(
            uri = uri,
            displayName = displayName(context, uri),
            permissionGranted = isPermissionGranted(context, uri),
        )
    }
}
