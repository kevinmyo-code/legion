package com.kevin.legion.ledger

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
 * Persists the connected Drive statements folder's tree URI - ticket 08 Part
 * 6 item 1 ("Folder connection"). Plain app-global SharedPreferences, same
 * shape as [com.kevin.legion.service.ProactivePreferences]: a [StateFlow]
 * seeded from disk on [init] so the ledger tab reads current connection
 * state immediately on first composition rather than waiting on a suspend
 * read, and so a rotation (a fresh `LedgerScreen` composition) never flashes
 * "not connected" before the real value loads.
 *
 * Storing only the URI **string**, never a grant object of any kind - the
 * actual permission lives with Android's own
 * `ContentResolver.persistedUriPermissions`
 * ([android.content.ContentResolver.takePersistableUriPermission] /
 * [isPermissionGranted]), so this class cannot itself drift out of sync with
 * what the OS actually grants. A revoked grant (the driver removes the
 * Google account, clears the Drive app's data, or Android trims grants
 * under storage pressure) shows up as [isPermissionGranted] going false
 * while [treeUri] still holds the last-connected value - that split is
 * deliberate. Ticket 08 item 1 calls out "include the revoked-permission
 * state" by name: the UI needs BOTH "what was connected" (to name what to
 * reconnect) and "is it still valid" (to know whether to warn), and
 * [connectionStatus] hands back both at once.
 */
object LedgerFolderPreferences {
    private const val TAG = "LedgerFolderPrefs"
    private const val PREFS = "ledger_folder_preferences"
    private const val KEY_TREE_URI = "tree_uri"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private val _treeUri = MutableStateFlow<Uri?>(null)
    val treeUri: StateFlow<Uri?> = _treeUri.asStateFlow()

    /** Call once, early (e.g. Application/Activity init), to seed [treeUri] from disk - same convention as ProactivePreferences.init. */
    fun init(context: Context) {
        _treeUri.value = prefs(context).getString(KEY_TREE_URI, null)?.let(Uri::parse)
    }

    /**
     * Persists [uri] as the connected folder and takes a persistable READ
     * grant on it (ticket 08 item 1: `ACTION_OPEN_DOCUMENT_TREE` result +
     * `takePersistableUriPermission`). [uri] must be the raw picker result,
     * before anything else has touched it - a persistable permission can
     * only be taken on the exact URI the system handed back.
     */
    fun connect(context: Context, uri: Uri) {
        // Release the outgoing grant first. CHANGE FOLDER routes here too, and
        // persisted URI permissions are a capped OS-level resource - without
        // this, every folder change leaks one for the lifetime of the install.
        // Same reasoning disconnect() already applies; it just never covered
        // the replace path.
        _treeUri.value?.takeIf { it != uri }?.let { previous ->
            try {
                context.contentResolver.releasePersistableUriPermission(
                    previous, Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            } catch (e: Exception) {
                Log.w(TAG, "couldn't release previous grant for $previous: ${e.message}")
            }
        }
        context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        prefs(context).edit().putString(KEY_TREE_URI, uri.toString()).apply()
        _treeUri.value = uri
    }

    /**
     * Forgets the connected folder. Ticket 08 item 1's disconnect action.
     * Deliberately does NOT touch a single row in `ingested_files` or
     * `ledger_transactions` - disconnecting is a decision about where FUTURE
     * scans look, not an undo of past imports (the resolution: "nothing
     * already imported is affected"). Also releases the persisted URI grant,
     * which is otherwise an OS-level resource that outlives the app's own
     * interest in it; releasing an already-revoked grant throws, which is
     * caught and ignored since disconnecting is the right outcome either way.
     */
    fun disconnect(context: Context) {
        _treeUri.value?.let { uri ->
            try {
                context.contentResolver.releasePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (e: Exception) {
                Log.w(TAG, "release failed (likely already revoked): ${e.message}")
            }
        }
        prefs(context).edit().remove(KEY_TREE_URI).apply()
        _treeUri.value = null
    }

    /**
     * Whether [uri]'s READ grant is still live in
     * [android.content.ContentResolver.getPersistedUriPermissions] - the
     * revoked-permission check the ticket 08 resolution calls out by name.
     * Must be checked live, not cached: a grant can disappear without this
     * app doing anything.
     */
    fun isPermissionGranted(context: Context, uri: Uri): Boolean =
        context.contentResolver.persistedUriPermissions.any { it.uri == uri && it.isReadPermission }

    /**
     * Best-effort human-readable name for the connected folder's tree root,
     * for display only - never used as identity (that stays the URI/document
     * id, per ticket 01's finding that Drive exposes no content hash and the
     * ingested-file ledger keys on a LEGION-computed one instead). Plain
     * [DocumentsContract] query rather than pulling in the `documentfile`
     * artifact for one field this repo doesn't otherwise need.
     */
    private fun displayName(context: Context, uri: Uri): String {
        val queried = try {
            val treeDocId = DocumentsContract.getTreeDocumentId(uri)
            val docUri = DocumentsContract.buildDocumentUriUsingTree(uri, treeDocId)
            context.contentResolver.query(
                docUri, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null,
            )?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
        } catch (e: Exception) {
            null // revoked permission or a transient provider hiccup - fall through to the URI itself
        }
        return queried ?: uri.lastPathSegment ?: "connected folder"
    }

    /** [treeUri]'s connected/disconnected/revoked status plus a display name, for the folder connection row. */
    data class ConnectionStatus(val uri: Uri?, val displayName: String, val permissionGranted: Boolean)

    /**
     * Resolves [ConnectionStatus] for the currently persisted [treeUri].
     * `Dispatchers.IO` because both the permission check and the display-name
     * query are content-resolver calls - never run this from composition.
     */
    suspend fun connectionStatus(context: Context): ConnectionStatus = withContext(Dispatchers.IO) {
        val uri = treeUri.value ?: return@withContext ConnectionStatus(null, "", permissionGranted = false)
        ConnectionStatus(
            uri = uri,
            displayName = displayName(context, uri),
            permissionGranted = isPermissionGranted(context, uri),
        )
    }
}
