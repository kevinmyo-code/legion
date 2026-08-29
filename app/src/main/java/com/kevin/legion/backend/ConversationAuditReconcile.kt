package com.kevin.legion.backend

import android.content.Context
import com.kevin.legion.data.local.CarDatabase
import com.kevin.legion.engine.DeviceId

/**
 * Install-scoped high-water mark for [ConversationAuditReconcile]'s upload, keyed by [DeviceId] -
 * same cursor shape and same reasoning as [ObdSampleUploadCursor], applied to a much smaller table
 * (197 rows as of 2026-08-29 versus obd_samples' 26,059) where the cost this cursor avoids is
 * smaller in absolute terms but the pattern is identical: a re-post is CORRECT by construction
 * (the `(device_id, local_id)` natural key) but re-scanning the whole table every run is not
 * CHEAP, and this is what makes a routine re-run touch only what is new.
 *
 * Keyed by device id, not a single global key, purely for hygiene against the (never actually
 * expected) case of [DeviceId.current] changing under one install - a wrong device-scoped cursor
 * fails safe by re-uploading a small, already-idempotent table, not by silently losing rows.
 */
internal object ConversationAuditUploadCursor {
    private const val PREFS = "conversation_audit_upload_cursor"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun key(deviceId: String) = "last_uploaded_local_id_$deviceId"

    fun lastUploadedId(context: Context, deviceId: String): Long = prefs(context).getLong(key(deviceId), 0L)

    fun advance(context: Context, deviceId: String, id: Long) {
        prefs(context).edit().putLong(key(deviceId), id).apply()
    }
}

/**
 * The upload path for `conversation_audit`
 * (`.scratch/backend-erp/issues/24-do-the-conversation-logs-reach-the-server.md`, RULED
 * 2026-08-29: "same for conversations for audit"). Schema:
 * `supabase/migrations/20260829000100_obd_samples_and_conversation_audit.sql`, UNAPPLIED as of
 * that migration's own header - nothing here has run on device.
 *
 * **Why this is safe to sync at all, restated because it is the reason the ticket allowed this
 * table to exist server-side.** CLAUDE.md section 7 forbids persisting anything other people wrote
 * to Kevin. That rule is satisfied upstream, at WRITE, not here: a tool in
 * [com.kevin.legion.service.LiveToolbox.EPISODIC_EXCLUDED_TOOLS] already has its RESULT replaced
 * with [com.kevin.legion.data.local.READ_THROUGH_REDACTED] before the phone's own
 * `conversation_audit` table ever holds it (confirmed against the LIVE set and its one call site,
 * `LiveSessionController`'s `toolRedacted = GeminiLiveSession.isEpisodicExcludedTool(call.name)`,
 * which reads [com.kevin.legion.service.LiveToolbox.EPISODIC_EXCLUDED_TOOLS] directly rather than a
 * copy - a tool added to that set is redacted automatically, with no second list to keep in sync).
 * This reconcile therefore uploads [com.kevin.legion.data.local.ConversationAudit.content]/[redacted]
 * VERBATIM - there is nothing left to redact a second time, and re-deciding it here would risk
 * drifting from the one true membership test.
 *
 * **`Kind.USER` rows upload unredacted, per the ticket's own 2026-08-29 ruling on that exact
 * question** ("the app is the thing doing the fetching, and the rule stops the app from building
 * a durable store of other people's messages... a person speaking is not the app fetching") - this
 * reconcile does not special-case [com.kevin.legion.data.local.ConversationAudit.Kind.USER] at all,
 * which is the correct shape for a ruling that says nothing further needs to happen to that row.
 *
 * **No vehicle resolution, unlike [ObdSampleReconcile].**
 * [com.kevin.legion.data.local.ConversationAudit.vehicleId] rides along as an unresolved obdMac
 * string, exactly as `conversation_audit.vehicle_id`'s own migration column comment states
 * ("CONTEXT, never a filter, and deliberately not a FK... an audit row must survive a vehicle
 * being deleted") - so unlike obd_samples there is no per-row vehicle lookup and therefore nothing
 * that can be skipped-and-named for an unresolved one.
 *
 * **Batches and resumes for the same reason [ObdSampleReconcile] does, at a much smaller scale.**
 * 197 rows today does not need [BATCH_SIZE]'s batching to stay within a request's comfortable
 * size, but a busy fortnight is not measured yet either, and the identical shape means there is
 * exactly one pattern to review for this class of upload, not two.
 */
object ConversationAuditReconcile {
    private const val BATCH_SIZE = 500

    /**
     * @param sourceCount every `conversation_audit` row on this device right now - note this can
     *   SHRINK between runs, unlike every other reconcile's [sourceCount]: `ConversationAuditDao.trimOlderThan`
     *   deletes rows older than [com.kevin.legion.data.local.CONVERSATION_AUDIT_RETENTION_DAYS] on
     *   every write, independent of whether this reconcile has ever uploaded them. **A row trimmed
     *   locally before this reconcile ever runs is lost, not merely delayed** - this is a real gap
     *   this ticket's scope does not close (uploading, not scheduling), named here rather than
     *   left implicit: a reconcile that never runs for two weeks loses whatever aged out in that
     *   window.
     * @param uploaded rows this RUN sent, counted by batch size attempted - same convention as
     *   [ObdSampleReconcile.Report.uploaded].
     */
    data class Report(
        val sourceCount: Int,
        val uploaded: Int,
    )

    suspend fun run(context: Context, backend: ConversationAuditBackend): Result<Report> {
        val db = CarDatabase.getDatabase(context)
        val deviceId = DeviceId.current(context)

        var cursor = ConversationAuditUploadCursor.lastUploadedId(context, deviceId)
        var uploadedThisRun = 0

        while (true) {
            val batch = db.conversationAuditDao().getAfterId(cursor, BATCH_SIZE)
            if (batch.isEmpty()) break

            val uploads = batch.map { row ->
                ConversationAuditUpload(
                    deviceId = deviceId,
                    localId = row.id,
                    turnSeq = row.turnSeq,
                    kind = row.kind,
                    toolName = row.toolName,
                    args = row.args,
                    content = row.content,
                    redacted = row.redacted,
                    vehicleId = row.vehicleId,
                    recordedAtMs = row.at,
                )
            }
            backend.uploadConversationAuditBatch(uploads).getOrElse { return Result.failure(it) }
            uploadedThisRun += uploads.size
            cursor = batch.last().id
            ConversationAuditUploadCursor.advance(context, deviceId, cursor)
        }

        return Result.success(
            Report(
                sourceCount = db.conversationAuditDao().count(),
                uploaded = uploadedThisRun,
            ),
        )
    }
}
