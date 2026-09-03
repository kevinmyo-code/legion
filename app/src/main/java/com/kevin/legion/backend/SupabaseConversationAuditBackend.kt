package com.kevin.legion.backend

import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.exceptions.RestException
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Count
import java.io.IOException
import java.time.Instant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

private const val CONVERSATION_AUDIT_TABLE = "conversation_audit"

/**
 * [ConversationAuditBackend]'s real implementation over Postgrest, against `public.conversation_audit`
 * (`supabase/migrations/20260829000100_obd_samples_and_conversation_audit.sql`, UNAPPLIED as of that
 * migration's own header - no CLI or project credentials in this environment). Same "deliberately
 * untested seam" posture as [SupabaseFleetBackend]/[SupabasePlacesBackend]/[SupabaseEventsBackend] -
 * exercising it for real needs a live project; [ConversationAuditBackend] is the fake-friendly
 * interface [ConversationAuditReconcileTest] exercises instead.
 */
class SupabaseConversationAuditBackend(private val client: SupabaseClient) : ConversationAuditBackend {

    private suspend inline fun <T> translating(action: String, block: () -> T): Result<T> = try {
        Result.success(block())
    } catch (e: RestException) {
        Result.failure(ConversationAuditBackendException("Supabase rejected the request to $action: ${e.error}"))
    } catch (e: IOException) {
        Result.failure(ConversationAuditBackendException("Couldn't reach the server to $action."))
    } catch (e: Exception) {
        Result.failure(ConversationAuditBackendException("Couldn't $action: ${e.message ?: "unknown error"}"))
    }

    /** The wire shape for [uploadConversationAuditBatch]. Every field is a plain column, no jsonb -
     *  [ConversationAuditUpload.args]/[ConversationAuditUpload.content] are already JSON-or-plain
     *  TEXT at the Room boundary (see [com.kevin.legion.data.local.ConversationAudit]'s own class
     *  doc) and stay TEXT all the way to Postgres, exactly as the migration's own DDL declares them. */
    @Serializable
    private data class ConversationAuditUpsertDto(
        @SerialName("device_id") val deviceId: String,
        @SerialName("local_id") val localId: Long,
        @SerialName("turn_seq") val turnSeq: Long,
        val kind: String,
        @SerialName("tool_name") val toolName: String,
        val args: String,
        val content: String,
        val redacted: Boolean,
        @SerialName("vehicle_id") val vehicleId: String,
        @SerialName("recorded_at") val recordedAt: String,
    )

    /**
     * A single Postgrest `upsert` call over the whole batch, `on_conflict` set to the table's own
     * natural key and `ignoreDuplicates = true` - same shape as
     * [SupabaseFleetBackend.uploadObdSampleBatch] and for the identical reason: a re-post of an
     * already-present row must be a free no-op, not a merge or an error, and a batch upload should
     * never pay for a response body it does not need.
     */
    override suspend fun uploadConversationAuditBatch(batch: List<ConversationAuditUpload>): Result<Unit> =
        translating("upload conversation audit rows") {
            if (batch.isNotEmpty()) {
                client.postgrest.from(CONVERSATION_AUDIT_TABLE).upsert(
                    batch.map { row ->
                        ConversationAuditUpsertDto(
                            deviceId = row.deviceId,
                            localId = row.localId,
                            turnSeq = row.turnSeq,
                            kind = row.kind,
                            toolName = row.toolName,
                            args = row.args,
                            content = row.content,
                            redacted = row.redacted,
                            vehicleId = row.vehicleId,
                            recordedAt = Instant.ofEpochMilli(row.recordedAtMs).toString(),
                        )
                    },
                ) {
                    onConflict = "device_id,local_id"
                    ignoreDuplicates = true
                }
            }
            Unit
        }

    /** Same HEAD-only `Count.EXACT` shape as [SupabaseFleetBackend.countObdSamples] - see that
     *  function's own doc comment for why a HEAD request, not a full fetch, is the right cost here. */
    override suspend fun countConversationAudit(): Result<Long> =
        translating("count conversation audit rows") {
            client.postgrest.from(CONVERSATION_AUDIT_TABLE).select {
                head = true
                count(Count.EXACT)
            }.countOrNull() ?: 0L
        }
}
