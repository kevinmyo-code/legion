package com.kevin.legion.service

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log

/**
 * One SAF child document, flattened one level under a connected root's `documentId`.
 * `containingFolderId` is the per-account subfolder's own document id (see [SafListing.listChildren]'s
 * doc comment for the one-level recursion this comes from), null for a file that sat directly in the
 * connected root.
 */
data class SafChild(
    val documentId: String,
    val displayName: String,
    val mimeType: String,
    val size: Long,
    val lastModified: Long,
    val containingFolderId: String? = null,
)

/**
 * The SAF enumeration/read core [com.kevin.legion.service.IngestScanner]'s folder scan used to own
 * privately, extracted out (2026-08-28, ticket 19's content-fallback resolver -
 * `.scratch/backend-erp/issues/19-re-ingest-historical-statements.md`) so a second caller -
 * [com.kevin.legion.ledger.ReingestDryRun]'s content-match fallback, wired from
 * `ui/settings/ReingestDryRunScreen.kt` - can enumerate and read the CURRENTLY connected folder the
 * exact same way a real scan does, rather than a second reimplementation of the same binder calls.
 * Behaviour is carried over unchanged from `IngestScanner`'s own former private `listChildren`/
 * `queryChildDocuments`/`openBytes` - see each function's own doc comment below for the reasoning
 * that used to live there.
 */
object SafListing {
    private const val TAG = "SafListing"

    /**
     * Queries the tree's children directly via [android.content.ContentResolver]
     * (not `DocumentFile.listFiles()`, which costs one IPC per attribute per
     * file and discards the cursor's extras) - one binder call for every
     * column this pipeline needs. `.scratch/ledger-drive-ingestion/research/01-saf-drive-folder-findings.md`
     * §2c point 2.
     *
     * **Recurses ONE level into subfolders** - Kevin's real layout puts
     * per-account folders (`checking/`, `credit/`) directly under the
     * connected root, each holding both a PDF (prints its own account) and a
     * CSV (BofA's export, which doesn't). Every file found inside a
     * subfolder carries that subfolder's document id as
     * [SafChild.containingFolderId], the hint
     * [com.kevin.legion.ledger.LedgerAccountMappingPreferences] resolves into an account for any
     * file that states none of its own.
     *
     * **Capped at one level on purpose.** The stated layout is exactly one
     * folder deep; an unbounded walk risks unbounded binder-call fan-out
     * against a provider that already serves stale-empty results with no
     * signal at all (ticket 05's finding). A folder nested two levels down
     * (a folder inside `checking/`) is simply not descended into again -
     * that is a known, documented limit, not a silent gap.
     *
     * A subfolder is NEVER itself staged as a file - only its children come
     * back from this function.
     */
    fun listChildren(context: Context, treeUri: Uri): List<SafChild>? {
        // Null from the ROOT query is fatal to a caller's scan and reported, not
        // absorbed. Null from a SUBFOLDER is logged and skipped: one
        // uncooperative subfolder must not discard the files another one
        // listed fine, and the caller has no way to act on it anyway.
        val topLevel = queryChildDocuments(context, treeUri, DocumentsContract.getTreeDocumentId(treeUri))
            ?: return null
        val out = mutableListOf<SafChild>()
        for (child in topLevel) {
            if (child.mimeType == DocumentsContract.Document.MIME_TYPE_DIR) {
                out += queryChildDocuments(context, treeUri, child.documentId)
                    .orEmpty()
                    .filter { it.mimeType != DocumentsContract.Document.MIME_TYPE_DIR }
                    .map { it.copy(containingFolderId = child.documentId) }
            } else {
                out += child
            }
        }
        return out
    }

    /** One binder call: every direct child of [parentDocumentId] within [treeUri]. Shared by the root listing and the one-level subfolder recursion in [listChildren]. */
    fun queryChildDocuments(context: Context, treeUri: Uri, parentDocumentId: String): List<SafChild>? {
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentDocumentId)
        val columns = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
        )
        val out = mutableListOf<SafChild>()
        // A THROWN cursor is the same event as a null one (crash fix,
        // 2026-08-07). The null case below is handled carefully and at length -
        // but a revoked SAF grant does not politely return null, it throws
        // SecurityException, and nothing here caught it before this fix.
        val cursor = try {
            context.contentResolver.query(childrenUri, columns, null, null, null)
        } catch (e: Exception) {
            Log.w(TAG, "child listing threw (grant revoked or tree gone): ${e.message}")
            null
        }
        if (cursor == null) {
            // A null cursor is NOT an empty folder. It is the provider
            // declining the query outright - a dropped persisted grant, a
            // signed-out account, a provider that failed to start. Still not
            // thrown: a scan finding nothing is a normal outcome, and a
            // mid-listing failure must not abort a batch. Logged loudly instead.
            Log.w(TAG, "listing REFUSED for parent=$parentDocumentId (null cursor, not an empty folder)")
            return null
        }
        cursor.use { c ->
            while (c.moveToNext()) {
                val documentId = c.getString(0) ?: continue
                out += SafChild(
                    documentId = documentId,
                    displayName = c.getString(1) ?: "statement.pdf",
                    mimeType = c.getString(2).orEmpty(),
                    size = c.getLong(3),
                    lastModified = c.getLong(4),
                )
            }
        }
        Log.d(TAG, "listing for parent=$parentDocumentId returned ${out.size} children")
        return out
    }

    /** Reads one child's bytes. Null (never a thrown exception past this point) on any failure - a Drive read can fail offline or on a stream hiccup, and that must route to the caller's own "unreadable" handling, not crash. */
    fun openBytes(context: Context, treeUri: Uri, documentId: String): ByteArray? {
        val docUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId)
        return try {
            context.contentResolver.openInputStream(docUri)?.use { it.readBytes() }
        } catch (e: Exception) {
            Log.w(TAG, "read failed for $documentId: ${e.message}")
            null
        }
    }
}
