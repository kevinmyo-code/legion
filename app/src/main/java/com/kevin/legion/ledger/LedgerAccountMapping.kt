package com.kevin.legion.ledger

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/**
 * One subfolder directly under the connected statements folder - Kevin's
 * real Drive layout puts `checking/` and `credit/` folders under one
 * connected root, each holding both a PDF (which prints its own account) and
 * a CSV (BofA's mid-cycle export, which prints none). [folderId] is the SAF
 * document id, never a name - see [LedgerAccountMappingPreferences]'s doc
 * comment for why that matters.
 */
data class DiscoveredAccountFolder(val folderId: String, val displayName: String)

/**
 * Pure resolution logic, extracted out of [LedgerAccountMappingPreferences]
 * so it's testable on a plain JVM without touching SharedPreferences/Context
 * (see `playbook-coding.md`'s "keep platform calls out of it" testing note).
 * [folderId] is [com.kevin.legion.service.IngestScanner.SafChild]'s
 * (private) `containingFolderId` - null for a file that sat directly in the
 * connected root, which by construction is never mapped to anything.
 */
fun resolveAccountHint(folderId: String?, mapping: Map<String, String>): String? =
    folderId?.let { mapping[it] }

/**
 * Persists which account each per-account Drive subfolder maps to - the
 * gap-filler [com.kevin.legion.ledger.parsers.StatementDispatcher] reads for
 * any file that states no account of its own (BofA's mid-cycle CSV export,
 * confirmed on Kevin's real file to print none - see
 * [com.kevin.legion.ledger.parsers.BofaCsvStatementParser]'s doc comment). A
 * PDF's own printed account always wins over this mapping; see
 * [com.kevin.legion.ledger.parsers.StatementDispatcher.dispatchDeterministic]'s
 * `accountConflict` check for what happens when the two disagree.
 *
 * **Keyed on the folder's SAF DOCUMENT ID, never its display name.** A
 * folder RENAME must not silently orphan an existing mapping - the same
 * reasoning [com.kevin.legion.data.local.IngestedFile] already applies to
 * `driveFileId` over `displayName` (ticket 03's resolution: a document id is
 * stable identity, a display name is presentation only). A name-keyed map
 * would quietly stop matching the instant Kevin renamed `checking/` to
 * something else, with no error and no signal - exactly the kind of silent
 * misfile CLAUDE.md §4 exists to prevent, just one layer up from the money
 * itself.
 *
 * Same seeding discipline as [LedgerFolderPreferences]/
 * [com.kevin.legion.ai.GeminiKeyProvider]/[com.kevin.legion.service.ProactivePreferences]
 * (lessons.md L12, `playbook-coding.md`'s "Application initialization and
 * process-global state"): [init] must run from
 * [com.kevin.legion.MidnightApplication.onCreate], never a conditionally-
 * started service, or the mapping would silently read empty on a normal
 * launch while the values sit correctly on disk.
 */
object LedgerAccountMappingPreferences {
    private const val PREFS = "ledger_account_mapping"
    private const val KEY_PREFIX = "folder_"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private val _mapping = MutableStateFlow<Map<String, String>>(emptyMap())
    /** folderId -> accountId. Exposed as a StateFlow so the mapping UI recomposes live on every assign, same shape as [LedgerFolderPreferences.treeUri]. */
    val mapping: StateFlow<Map<String, String>> = _mapping.asStateFlow()

    /** Call once, early (see this object's doc comment), to seed [mapping] from disk - same convention as [LedgerFolderPreferences.init]. */
    fun init(context: Context) {
        val loaded = mutableMapOf<String, String>()
        for ((key, value) in prefs(context).all) {
            if (key.startsWith(KEY_PREFIX) && value is String) {
                loaded[key.removePrefix(KEY_PREFIX)] = value
            }
        }
        _mapping.value = loaded
    }

    /** Assigns [folderId] to [accountId], or clears the mapping when [accountId] is null/blank - the mapping UI's CLEAR action. */
    fun setMapping(context: Context, folderId: String, accountId: String?) {
        val editor = prefs(context).edit()
        if (accountId.isNullOrBlank()) {
            editor.remove(KEY_PREFIX + folderId)
            _mapping.value = _mapping.value - folderId
        } else {
            editor.putString(KEY_PREFIX + folderId, accountId)
            _mapping.value = _mapping.value + (folderId to accountId)
        }
        editor.apply()
    }

    /** Read-only lookup off the in-memory cache - never suspends, safe to call from the scan's IO-bound phase 1. */
    fun accountFor(folderId: String?): String? = resolveAccountHint(folderId, mapping.value)

    /**
     * Every subfolder directly under [treeUri]'s root - one level, matching
     * [com.kevin.legion.service.IngestScanner.listChildren]'s recursion cap
     * (see that function's doc comment for why). Read-only discovery for the
     * mapping UI, independent of a running scan; a plain child-documents
     * query filtered to directories, the same shape as
     * [com.kevin.legion.service.IngestScanner]'s own listing query.
     */
    suspend fun listAccountFolders(context: Context, treeUri: Uri): List<DiscoveredAccountFolder> =
        withContext(Dispatchers.IO) {
            val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(
                treeUri, DocumentsContract.getTreeDocumentId(treeUri),
            )
            val columns = arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
            )
            val out = mutableListOf<DiscoveredAccountFolder>()
            context.contentResolver.query(childrenUri, columns, null, null, null)?.use { c ->
                while (c.moveToNext()) {
                    if (c.getString(2) != DocumentsContract.Document.MIME_TYPE_DIR) continue
                    val id = c.getString(0) ?: continue
                    val name = c.getString(1) ?: continue
                    out += DiscoveredAccountFolder(id, name)
                }
            }
            out
        }
}
