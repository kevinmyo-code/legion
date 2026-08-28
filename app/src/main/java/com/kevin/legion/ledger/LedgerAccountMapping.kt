package com.kevin.legion.ledger

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/**
 * One subfolder directly under the connected statements folder. Kevin's
 * real Drive layout does NOT split per account - `USA Bank Statements/`
 * mixes BofA checking PDFs, BofA card PDFs, and the checking CSV together,
 * and `Singapore Statements/` holds DBS PDFs. Only the CSV needs a mapping
 * at all (it prints no account of its own); every PDF layout prints its own
 * account and ignores this mapping entirely - see
 * [LedgerAccountMappingPreferences]'s doc comment. [folderId] is the SAF
 * document id, never a name - see [LedgerAccountMappingPreferences]'s doc
 * comment for why that matters.
 */
data class DiscoveredAccountFolder(val folderId: String, val displayName: String)

/**
 * Pure resolution logic, extracted out of [LedgerAccountMappingPreferences]
 * so it's testable on a plain JVM without touching SharedPreferences/Context
 * (see `playbook-coding.md`'s "keep platform calls out of it" testing note).
 * [folderId] is [com.kevin.legion.service.SafChild]'s `containingFolderId` -
 * null for a file that sat directly in the connected root, which by
 * construction is never mapped to anything.
 */
fun resolveAccountHint(folderId: String?, mapping: Map<String, String>): String? =
    folderId?.let { mapping[it] }

/**
 * Persists which account each per-account Drive subfolder maps to - the
 * gap-filler [com.kevin.legion.ledger.parsers.StatementDispatcher] reads for
 * any file that states no account of its own (BofA's mid-cycle CSV export,
 * confirmed on Kevin's real file to print none - see
 * [com.kevin.legion.ledger.parsers.BofaCsvStatementParser]'s doc comment).
 * **Fills a gap only, never overrides a stated fact.** A PDF's own printed
 * account is a document-stated, falsifiable fact and is used as-is - PDF
 * parsers are never even handed this mapping (see
 * [com.kevin.legion.ledger.parsers.StatementDispatcher.dispatchDeterministic]'s
 * doc comment). This is deliberate: Kevin's real Drive layout puts every
 * BofA file type (checking PDF, card PDF, and the checking CSV) in ONE
 * subfolder rather than splitting per account, so a mapping describing one
 * account in that folder is expected to be irrelevant to files that state a
 * different account of their own - that is not a conflict, it is normal.
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
     * [com.kevin.legion.service.SafListing.listChildren]'s recursion cap
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
            // GUARDED (crash fix, 2026-08-07, found on Kevin's phone).
            //
            // This query was bare, and a SAF grant can vanish without this app
            // doing anything - an uninstall drops every persisted permission,
            // the driver can revoke one in Settings, the Drive account can sign
            // out. When it does, `query` does not return null here, it THROWS
            // SecurityException, and this runs on a Compose LaunchedEffect, so
            // the throw reached the main thread and killed the app on opening
            // the Money screen. It crash-looped: every relaunch reopened the
            // same screen.
            //
            // The permission check is live rather than cached because that is
            // the only honest way to ask (see LedgerFolderPreferences.
            // isPermissionGranted), and the catch is still required on top of
            // it: a grant can disappear between the check and the query, and a
            // stale document id throws IllegalArgumentException rather than
            // SecurityException.
            //
            // Returning empty is safe here, and specifically NOT the
            // "empty folder and unreadable folder look identical" bug that
            // SafListing.queryChildDocuments exists to avoid: the revoked
            // state is already surfaced, in words, by the folder-connection row
            // above this list. This function feeds a subfolder PICKER, and a
            // picker with no options next to a banner saying the folder is
            // unreadable is coherent.
            if (!LedgerFolderPreferences.isPermissionGranted(context, treeUri)) return@withContext out
            try {
                context.contentResolver.query(childrenUri, columns, null, null, null)?.use { c ->
                    while (c.moveToNext()) {
                        if (c.getString(2) != DocumentsContract.Document.MIME_TYPE_DIR) continue
                        val id = c.getString(0) ?: continue
                        val name = c.getString(1) ?: continue
                        out += DiscoveredAccountFolder(id, name)
                    }
                }
            } catch (e: Exception) {
                Log.w("LedgerAccountMapping", "listAccountFolders failed (grant revoked or tree gone): ${e.message}")
            }
            out
        }
}
