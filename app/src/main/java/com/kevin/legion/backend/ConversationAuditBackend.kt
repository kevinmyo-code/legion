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
     * Bulk-upserts a batch onto `(device_id, local_id)` (the table's own natural key, `on conflict
     * do nothing` server-side). A re-post of an already-uploaded row is silently ignored, matching
     * [FleetBackend.uploadObdSampleBatch]'s own idempotency contract and for the same reason: this
     * device's `local_id` sequence never changes once written, so replaying a batch after an
     * interrupted run can never double-count.
     */
    suspend fun uploadConversationAuditBatch(batch: List<ConversationAuditUpload>): Result<Unit>

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
