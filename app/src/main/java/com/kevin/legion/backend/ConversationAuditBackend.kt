package com.kevin.legion.backend

/**
 * One [com.kevin.legion.data.local.ConversationAudit] row, ready for
 * [ConversationAuditBackend.uploadConversationAuditBatch]. Built by [ConversationAuditReconcile],
 * never by [SupabaseConversationAuditBackend] - the same "reconcile does the translating, the
 * backend only decodes DTOs" split [FleetBackend]'s own class doc states for
 * [com.kevin.legion.backend.ObdSampleUpload].
 *
 * **Redaction happens upstream, at write, and is carried verbatim here - never re-decided.**
 * [content]/[redacted] are exactly what [com.kevin.legion.data.local.ConversationAuditDao.record]
 * already wrote to the phone's own table (see that function's own doc and
 * `.scratch/backend-erp/issues/24-do-the-conversation-logs-reach-the-server.md`'s ruling): a tool
 * in [com.kevin.legion.service.LiveToolbox.EPISODIC_EXCLUDED_TOOLS] already had its RESULT replaced
 * with [com.kevin.legion.data.local.READ_THROUGH_REDACTED] before the phone ever stored it, so this
 * upload cannot leak what CLAUDE.md section 7 protects even in principle - there is nothing left in
 * the row to leak. [deviceId]/[localId] together are the table's server-side identity
 * (`conversation_audit`'s own migration comment: "a conversation row is what ONE phone heard and
 * said, not a shared fact").
 */
data class ConversationAuditUpload(
    val deviceId: String,
    val localId: Long,
    /**
     * The row's server identity - [com.kevin.legion.data.local.ConversationAudit.clientUuid],
     * carried verbatim, never re-minted here. **This replaced `(deviceId, localId)` as the upsert
     * key on 2026-09-06**; see that property's own doc comment for the three days of evidence the
     * old key silently discarded. [deviceId]/[localId] still ride along, because "which phone, and
     * which row on it" remains worth recording even though neither is an identity any more.
     */
    val clientUuid: String,
    val turnSeq: Long,
    val kind: String,
    val toolName: String,
    val args: String,
    val content: String,
    val redacted: Boolean,
    val vehicleId: String,
    val recordedAtMs: Long,
)

/**
 * The last of the two tables `.scratch/backend-erp/issues/24-do-the-conversation-logs-reach-the-server.md`
 * and `.scratch/backend-erp/issues/14-a-vehicle-row-is-co-owned.md` sent to Supabase (ruled
 * 2026-08-29, `20260829000100_obd_samples_and_conversation_audit.sql`, UNAPPLIED as of that
 * migration's own header). A standalone interface, not a [FleetBackend] method, because a
 * conversation row has no vehicle to resolve and nothing else fleet-shaped about it - see
 * `conversation_audit.vehicle_id`'s own column comment for why that string rides along as
 * unresolved CONTEXT rather than a foreign key [ObdSampleReconcile]'s vehicle map would need to
 * translate.
 */
interface ConversationAuditBackend {
    /**
     * Bulk-upserts a batch onto `client_uuid` (`on conflict do nothing` server-side), and returns
     * **how many rows the server actually accepted** - not how many were offered.
     *
     * **That return value is the point, and `Result<Unit>` is what let this table lose three days.**
     * The previous key was `(device_id, local_id)`, and `local_id` is an `AUTOINCREMENT` rowid that
     * restarts when the phone's table is emptied while `device_id` does not - so after a reset the
     * phone offered rows 1..142 against 142 unrelated August rows already holding those ids,
     * Postgres discarded every one under `do nothing`, and returned success. The caller counted the
     * batch SIZE as uploaded and advanced its watermark over all of them. A count of accepted rows
     * makes that failure arithmetically impossible to mistake for an upload, whatever the key later
     * becomes - which is why it is a count and not a boolean.
     *
     * A re-post of an already-present row is still a free no-op (it simply does not count), which
     * under a client-minted UUID is correct: the same `client_uuid` genuinely is the same row, so a
     * batch replayed after an interrupted run can never double-count.
     */
    suspend fun uploadConversationAuditBatch(batch: List<ConversationAuditUpload>): Result<Int>

    /**
     * A HEAD-only exact count of `conversation_audit`, no rows downloaded - same shape and same
     * reason as [FleetBackend.countObdSamples]: cheap enough to call after every
     * [ConversationAuditReconcile.maybeAutoRun] pass without re-opening this table's own
     * batch-and-resume tradeoff for the sake of a report line.
     */
    suspend fun countConversationAudit(): Result<Long>
}

/** Thrown (wrapped in [Result.failure]) by [SupabaseConversationAuditBackend] - owned by this
 *  package, never a raw supabase-kt/Ktor exception, same posture as [FleetBackendException]. */
class ConversationAuditBackendException(message: String) : Exception(message)
