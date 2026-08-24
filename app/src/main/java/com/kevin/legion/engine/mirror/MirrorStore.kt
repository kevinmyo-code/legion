package com.kevin.legion.engine.mirror

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.security.MessageDigest

/**
 * The SAF layer over [MirrorFolderPreferences.treeUri] - ticket 20 item 3, on ticket 01's research
 * (`.scratch/aspect-engine/research/01-drive-folder-write-back.md`). **Every write is a full
 * rewrite, read back and hash-verified**; there is no in-place patch, matching the research's
 * mandatory mitigation for the truncate bug ("`\"w\"` mode may not truncate - and for .xlsx that is
 * corruption, not staleness... `\"rwt\"` via `openFileDescriptor`, then read back and compare
 * length + SHA-256... mismatch quarantines the mirror").
 *
 * **A mismatch quarantines that ONE aspect's mirror, never the whole folder** - [MirrorSync] still
 * has other aspects' files to keep current, and [MirrorStateStore] tracks quarantine per aspect
 * slug for exactly that reason. A quarantined mirror is rendered in words on whatever surface reads
 * [MirrorStateStore] (CLAUDE.md §4's posture: never a colour or a glyph alone) - this class only
 * sets the flag and a reason string; it does not itself own any UI.
 *
 * Every function here is `suspend` and runs on [Dispatchers.IO] internally - no caller needs to
 * remember that a `ContentResolver` call is blocking I/O.
 */
object MirrorStore {
    private const val TAG = "MirrorStore"

    sealed class WriteResult {
        object Success : WriteResult()
        /** The write itself threw, or the read-back bytes didn't match what was written -
         * [MirrorSync] marks the aspect quarantined in [MirrorStateStore] on either variant. */
        data class Failure(val reason: String) : WriteResult()
    }

    sealed class ReadResult {
        data class Found(val bytes: ByteArray, val lastModified: Long, val sha256: String) : ReadResult()
        object NotFound : ReadResult()
        data class Failure(val reason: String) : ReadResult()
    }

    /** SHA-256 over [bytes], lowercase hex - the read-back verification hash and the change-
     * detection fingerprint both use this same function so a "did the file change" check and a
     * "did the write land intact" check can never quietly define "hash" two different ways. */
    fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    /**
     * Writes [bytes] as `<fileName>` under [treeUri] - creating the child document if it does not
     * already exist, otherwise rewriting it in place via `openFileDescriptor(uri, "rwt")` (full
     * truncate-and-rewrite, per the research above; plain `"w"`/`"wt"` are NOT used here on
     * purpose). Reads the bytes back immediately afterward and compares length + SHA-256 against
     * what was written - any mismatch, or any thrown exception at any step, is a [WriteResult.Failure]
     * with a worded reason, never a silent partial write.
     */
    suspend fun write(context: Context, treeUri: Uri, fileName: String, bytes: ByteArray): WriteResult =
        withContext(Dispatchers.IO) {
            try {
                val childUri = findOrCreateChild(context, treeUri, fileName)
                    ?: return@withContext WriteResult.Failure("couldn't create or find '$fileName' in the connected folder")

                context.contentResolver.openFileDescriptor(childUri, "rwt")?.use { pfd ->
                    java.io.FileOutputStream(pfd.fileDescriptor).use { out -> out.write(bytes) }
                } ?: return@withContext WriteResult.Failure("the connected folder refused to open '$fileName' for writing")

                val readBack = context.contentResolver.openInputStream(childUri)?.use { it.readBytes() }
                    ?: return@withContext WriteResult.Failure("wrote '$fileName' but couldn't read it back to verify")

                if (readBack.size != bytes.size || sha256(readBack) != sha256(bytes)) {
                    Log.w(TAG, "read-back mismatch for $fileName: wrote ${bytes.size}B, read back ${readBack.size}B")
                    return@withContext WriteResult.Failure(
                        "'$fileName' didn't read back the same as it was written - the mirror may be corrupt",
                    )
                }
                WriteResult.Success
            } catch (e: Exception) {
                Log.w(TAG, "write failed for $fileName: ${e.message}", e)
                WriteResult.Failure("couldn't write '$fileName': ${e.message}")
            }
        }

    /** Reads `<fileName>` under [treeUri], if it exists. [ReadResult.Found.sha256] is the SAME
     * [sha256] function [write]'s verification uses - [MirrorSync] compares it against
     * [MirrorStateStore]'s last-known hash for change detection (ticket 20 item 4: "lastModified
     * plus content hash"). */
    suspend fun read(context: Context, treeUri: Uri, fileName: String): ReadResult =
        withContext(Dispatchers.IO) {
            try {
                val child = findChild(context, treeUri, fileName) ?: return@withContext ReadResult.NotFound
                val bytes = context.contentResolver.openInputStream(child.uri)?.use { it.readBytes() }
                    ?: return@withContext ReadResult.Failure("couldn't open '$fileName' for reading")
                ReadResult.Found(bytes, child.lastModified, sha256(bytes))
            } catch (e: Exception) {
                Log.w(TAG, "read failed for $fileName: ${e.message}", e)
                ReadResult.Failure("couldn't read '$fileName': ${e.message}")
            }
        }

    private data class ChildDoc(val uri: Uri, val documentId: String, val lastModified: Long)

    private fun findChild(context: Context, treeUri: Uri, fileName: String): ChildDoc? {
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(
            treeUri, DocumentsContract.getTreeDocumentId(treeUri),
        )
        val columns = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
        )
        context.contentResolver.query(childrenUri, columns, null, null, null)?.use { c ->
            while (c.moveToNext()) {
                val name = c.getString(1)
                if (name == fileName) {
                    val docId = c.getString(0)
                    val lastModified = c.getLong(2)
                    return ChildDoc(
                        uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId),
                        documentId = docId,
                        lastModified = lastModified,
                    )
                }
            }
        }
        return null
    }

    private fun findOrCreateChild(context: Context, treeUri: Uri, fileName: String): Uri? {
        findChild(context, treeUri, fileName)?.let { return it.uri }
        val parentDocUri = DocumentsContract.buildDocumentUriUsingTree(
            treeUri, DocumentsContract.getTreeDocumentId(treeUri),
        )
        return try {
            DocumentsContract.createDocument(
                context.contentResolver, parentDocUri,
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                fileName,
            )
        } catch (e: Exception) {
            Log.w(TAG, "createDocument failed for $fileName: ${e.message}", e)
            null
        }
    }
}

/**
 * Per-aspect sync/quarantine bookkeeping - the small persisted state ticket 20 item 5's settings
 * stub reads (last-sync timestamp, last-known hash for change detection, quarantine flag and
 * reason). Deliberately plain `SharedPreferences`, same shape as
 * [com.kevin.legion.ledger.LedgerFolderPreferences] and [MirrorFolderPreferences] - this is app
 * state about the sync process itself, not engine data, so it has no reason to live in Room.
 */
object MirrorStateStore {
    private const val PREFS = "mirror_state"

    data class AspectSyncState(
        val lastExportAt: Long? = null,
        val lastExportHash: String? = null,
        val lastImportAt: Long? = null,
        val lastImportHash: String? = null,
        val quarantined: Boolean = false,
        val quarantineReason: String? = null,
    )

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun get(context: Context, aspectSlug: String): AspectSyncState {
        val p = prefs(context)
        val prefix = "$aspectSlug."
        return AspectSyncState(
            lastExportAt = p.getLong(prefix + "lastExportAt", -1L).takeIf { it >= 0 },
            lastExportHash = p.getString(prefix + "lastExportHash", null),
            lastImportAt = p.getLong(prefix + "lastImportAt", -1L).takeIf { it >= 0 },
            lastImportHash = p.getString(prefix + "lastImportHash", null),
            quarantined = p.getBoolean(prefix + "quarantined", false),
            quarantineReason = p.getString(prefix + "quarantineReason", null),
        )
    }

    fun recordExport(context: Context, aspectSlug: String, now: Long, hash: String) {
        val prefix = "$aspectSlug."
        prefs(context).edit()
            .putLong(prefix + "lastExportAt", now)
            .putString(prefix + "lastExportHash", hash)
            .putBoolean(prefix + "quarantined", false)
            .remove(prefix + "quarantineReason")
            .apply()
    }

    fun recordImport(context: Context, aspectSlug: String, now: Long, hash: String) {
        val prefix = "$aspectSlug."
        prefs(context).edit()
            .putLong(prefix + "lastImportAt", now)
            .putString(prefix + "lastImportHash", hash)
            .apply()
    }

    /** Sets the quarantine flag - stated in words, per this file's own [MirrorStore] doc comment,
     * never a bare boolean a UI would have to invent its own copy for. */
    fun quarantine(context: Context, aspectSlug: String, reason: String) {
        val prefix = "$aspectSlug."
        prefs(context).edit()
            .putBoolean(prefix + "quarantined", true)
            .putString(prefix + "quarantineReason", reason)
            .apply()
    }

    /** Every aspect slug this store has ever recorded state for - the settings stub's listing. */
    fun knownAspectSlugs(context: Context): Set<String> =
        prefs(context).all.keys.mapNotNull { it.substringBeforeLast('.', missingDelimiterValue = "").takeIf { s -> s.isNotEmpty() } }.toSet()
}
