package com.kevin.legion.data.local

import android.content.Context
import android.provider.DocumentsContract
import android.util.Log
import com.kevin.legion.engine.mirror.MirrorFolderPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val TAG = "ConversationAuditExport"

/**
 * Outcome of [ConversationAuditExport.export] - ticket 23's export path, same sealed-result shape
 * as [com.kevin.legion.ledger.LedgerIngestResult]: a Drive folder that isn't connected or a
 * revoked permission is an expected, common outcome on a phone that has been through a Google
 * account change, not a crash, and CLAUDE.md's own rule for this ticket is explicit - "never fail
 * silently".
 */
sealed class ConversationAuditExportResult {
    /** [fileName] is the display name Android actually gave the new document - SAF folders can
     *  silently rename on a collision, so this is what the driver should be told to look for. */
    data class Success(val fileName: String, val rowCount: Int) : ConversationAuditExportResult()
    /** No folder has ever been connected, or the persisted grant has been revoked since. Carries
     *  [reason] rather than a boolean so the caller has a real sentence to speak/show, per the
     *  ticket's "never fail silently" instruction. */
    data class NotConfigured(val reason: String) : ConversationAuditExportResult()
    /** The folder is connected and the grant is live, but the actual write failed - a full disk,
     *  a provider hiccup, the folder deleted out from under a still-valid grant. */
    data class Failed(val reason: String) : ConversationAuditExportResult()
}

/**
 * Writes the current [CONVERSATION_AUDIT_RETENTION_DAYS] window of [ConversationAudit] rows to a
 * human-readable text file in the driver's own connected Drive folder - ticket 23's build note E,
 * "a function that writes the recent window to a human-readable file in the user's Drive folder /
 * SAF location on demand".
 *
 * **Deliberately reuses [MirrorFolderPreferences] rather than minting a second folder-connection
 * flow.** This is a debugging export, not a new ingestion surface - asking Kevin to connect a
 * SECOND Drive folder just so the assistant can write its own audit trail out would be exactly the
 * kind of surface area CLAUDE.md's "clone-and-run" posture warns against for no real benefit: one
 * connected folder, one grant, one place to look. If that folder is ever wrong for this purpose,
 * splitting it is a one-function change, not a redesign.
 *
 * **AMENDED 2026-08-29, backend-erp ticket 25.** This used to reuse `LedgerFolderPreferences` (the
 * statement-ingestion Drive folder), which this ticket deleted along with the rest of phone-side
 * statement ingestion - the phone no longer connects a Drive folder for that purpose at all. This
 * is exactly the "one-function change" the paragraph above anticipated: [MirrorFolderPreferences]
 * (`engine/mirror/`, the xlsx-mirror/two-phone sync folder) is the one SAF-connected Drive folder
 * still live on the phone, and its `ConnectionStatus(uri, displayName, permissionGranted)` shape is
 * identical, so nothing below this point needed to change.
 *
 * On-device only, and only into the driver's own Drive `appDataFolder`-backed SAF tree - no
 * network call of its own, no Kevin-hosted anything (CLAUDE.md sec 7).
 */
object ConversationAuditExport {

    /** Plain-text, timestamped, human-readable - not JSON. This file exists to be read by a
     *  person mid-investigation, not parsed by code; see [ConversationAudit]'s class doc for why
     *  the on-device query path already covers the machine-readable case. */
    private val TIMESTAMP_FORMAT = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)

    /**
     * Writes the export and returns what happened. Never throws - a debugging tool that crashes
     * the app it exists to debug would be its own kind of bug.
     */
    suspend fun export(context: Context): ConversationAuditExportResult = withContext(Dispatchers.IO) {
        val status = MirrorFolderPreferences.connectionStatus(context)
        val treeUri = status.uri
        if (treeUri == null) {
            return@withContext ConversationAuditExportResult.NotConfigured(
                "No Drive folder is connected. Connect the sync folder from settings first.",
            )
        }
        if (!status.permissionGranted) {
            return@withContext ConversationAuditExportResult.NotConfigured(
                "The connected Drive folder's permission has been revoked. Reconnect it from settings.",
            )
        }

        val cutoff = System.currentTimeMillis() - CONVERSATION_AUDIT_RETENTION_DAYS * 24 * 60 * 60 * 1000
        val rows = try {
            CarDatabase.getDatabase(context).conversationAuditDao().since(cutoff)
        } catch (e: Exception) {
            Log.w(TAG, "read failed: ${e.message}")
            return@withContext ConversationAuditExportResult.Failed("Could not read the audit trail: ${e.message}")
        }

        val body = buildString {
            appendLine("LEGION conversation + tool-call audit trail")
            appendLine("Exported ${TIMESTAMP_FORMAT.format(Date())}")
            appendLine("Window: last $CONVERSATION_AUDIT_RETENTION_DAYS days, ${rows.size} rows")
            appendLine("=".repeat(60))
            var lastSeq = -1L
            for (row in rows) {
                if (row.turnSeq != lastSeq) {
                    appendLine()
                    appendLine("--- turn ${row.turnSeq} " + "-".repeat(40))
                    lastSeq = row.turnSeq
                }
                val stamp = TIMESTAMP_FORMAT.format(Date(row.at))
                when (row.kind) {
                    ConversationAudit.Kind.USER ->
                        appendLine("[$stamp] USER: ${row.content}")
                    ConversationAudit.Kind.COMPANION ->
                        appendLine("[$stamp] COMPANION${if (row.redacted) " (redacted)" else ""}: ${row.content}")
                    ConversationAudit.Kind.TOOL_RESULT ->
                        // row.content already IS the placeholder when row.redacted, since that is
                        // what got stored at write time - nothing further to branch on here.
                        appendLine("[$stamp] TOOL ${row.toolName} args=${row.args} result=${row.content}")
                    else -> appendLine("[$stamp] ${row.kind}: ${row.content}")
                }
            }
        }

        val fileName = "legion-audit-${SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())}.txt"
        try {
            val treeDocId = DocumentsContract.getTreeDocumentId(treeUri)
            val parentUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, treeDocId)
            val newDocUri = DocumentsContract.createDocument(
                context.contentResolver, parentUri, "text/plain", fileName,
            ) ?: return@withContext ConversationAuditExportResult.Failed(
                "The connected folder rejected the new file.",
            )
            context.contentResolver.openOutputStream(newDocUri)?.use { out ->
                out.write(body.toByteArray(Charsets.UTF_8))
            } ?: return@withContext ConversationAuditExportResult.Failed(
                "Could not open the new file for writing.",
            )
            val actualName = try {
                context.contentResolver.query(
                    newDocUri, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null,
                )?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
            } catch (e: Exception) {
                null
            } ?: fileName
            ConversationAuditExportResult.Success(actualName, rows.size)
        } catch (e: Exception) {
            Log.w(TAG, "write failed: ${e.message}")
            ConversationAuditExportResult.Failed("Could not write the export: ${e.message}")
        }
    }
}
