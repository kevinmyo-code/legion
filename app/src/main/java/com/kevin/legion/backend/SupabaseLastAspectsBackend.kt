package com.kevin.legion.backend

import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.exceptions.RestException
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.filter.FilterOperator
import java.io.IOException
import java.time.Instant
import java.time.OffsetDateTime
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

private const val GOALS_TABLE = "goals"
private fun lastAspectsTs(ms: Long): String = Instant.ofEpochMilli(ms).toString()
private fun lastAspectsParseTs(s: String): Long = OffsetDateTime.parse(s).toInstant().toEpochMilli()

// ---------------------------------------------------------------------------------------------
// GOALS
// ---------------------------------------------------------------------------------------------

@Serializable
private data class GoalUpsertDto(
    @SerialName("lineage_id") val lineageId: Long,
    val aspect: String,
    val statement: String,
    @SerialName("target_value") val targetValue: Double?,
    val unit: String?,
    @SerialName("metric_key") val metricKey: String?,
    @SerialName("deadline_epoch") val deadlineEpoch: String?,
    val status: String,
    @SerialName("supersedes_guid") val supersedesGuid: String?,
    @SerialName("closed_at") val closedAt: String?,
    @SerialName("created_at_client") val createdAtClient: String,
    @SerialName("origin_guid") val originGuid: String,
)

@Serializable
private data class GoalRowDto(
    val id: String,
    @SerialName("lineage_id") val lineageId: Long,
    val aspect: String,
    val statement: String,
    @SerialName("target_value") val targetValue: Double? = null,
    val unit: String? = null,
    @SerialName("metric_key") val metricKey: String? = null,
    @SerialName("deadline_epoch") val deadlineEpoch: String? = null,
    val status: String,
    @SerialName("supersedes_guid") val supersedesGuid: String? = null,
    @SerialName("closed_at") val closedAt: String? = null,
    @SerialName("created_at_client") val createdAtClient: String,
    @SerialName("updated_at") val updatedAt: String,
    @SerialName("deleted_at") val deletedAt: String? = null,
    @SerialName("origin_guid") val originGuid: String,
) {
    fun toRemote() = RemoteGoal(
        serverId = id,
        lineageId = lineageId,
        aspect = aspect,
        statement = statement,
        targetValue = targetValue,
        unit = unit,
        metricKey = metricKey,
        deadlineEpoch = deadlineEpoch?.let { lastAspectsParseTs(it) },
        status = status,
        supersedesGuid = supersedesGuid,
        closedAt = closedAt?.let { lastAspectsParseTs(it) },
        createdAt = lastAspectsParseTs(createdAtClient),
        updatedAtMs = lastAspectsParseTs(updatedAt),
        deleted = deletedAt != null,
        originGuid = originGuid,
    )
}

@Serializable
private data class LastAspectsDeletedAtDto(@SerialName("deleted_at") val deletedAt: String)

/**
 * [LastAspectsBackend]'s real implementation over Postgrest, against the one `public.goals` table
 * this slice still carries (`supabase/migrations/20260902000500_aspect_last.sql` creates it beside
 * the three the lists redesign slice 1a retired - see [LastAspectsBackend]'s own class doc).
 * Deliberately untested seam, same posture as
 * [SupabaseLedgerConfigBackend]/[SupabaseMemoryBackend] - exercising it for real needs a live
 * project. [LastAspectsBackend] is the fake-friendly interface; every branch here does nothing but
 * translate exceptions, decode DTOs, and pick `on conflict (origin_guid)` as the upsert key.
 */
class SupabaseLastAspectsBackend(private val client: SupabaseClient) : LastAspectsBackend {

    private suspend inline fun <T> translating(action: String, block: () -> T): Result<T> = try {
        Result.success(block())
    } catch (e: RestException) {
        Result.failure(LastAspectsBackendException("Supabase rejected the request to $action: ${e.error}"))
    } catch (e: IOException) {
        Result.failure(LastAspectsBackendException("Couldn't reach the server to $action."))
    } catch (e: Exception) {
        Result.failure(LastAspectsBackendException("Couldn't $action: ${e.message ?: "unknown error"}"))
    }

    private suspend fun softDeleteByOriginGuid(table: String, originGuid: String, action: String): Result<Boolean> =
        translating(action) {
            client.postgrest.from(table)
                .update(LastAspectsDeletedAtDto(deletedAt = OffsetDateTime.now().toString())) {
                    select()
                    filter {
                        eq("origin_guid", originGuid)
                        filter("deleted_at", FilterOperator.IS, "null")
                    }
                }
                .decodeList<kotlinx.serialization.json.JsonElement>()
                .isNotEmpty()
        }

    // --- Goals -------------------------------------------------------------------------------

    override suspend fun fetchChangedGoalsSince(sinceMs: Long): Result<List<RemoteGoal>> =
        translating("load changed goals") {
            client.postgrest.from(GOALS_TABLE)
                .select { filter { gte("updated_at", lastAspectsTs(sinceMs)) } }
                .decodeList<GoalRowDto>()
                .map { it.toRemote() }
        }

    override suspend fun upsertGoal(originGuid: String, fields: GoalFields): Result<RemoteGoal> =
        translating("save that goal") {
            client.postgrest.from(GOALS_TABLE)
                .upsert(
                    GoalUpsertDto(
                        lineageId = fields.lineageId,
                        aspect = fields.aspect,
                        statement = fields.statement,
                        targetValue = fields.targetValue,
                        unit = fields.unit,
                        metricKey = fields.metricKey,
                        deadlineEpoch = fields.deadlineEpoch?.let { lastAspectsTs(it) },
                        status = fields.status,
                        supersedesGuid = fields.supersedesGuid,
                        closedAt = fields.closedAt?.let { lastAspectsTs(it) },
                        createdAtClient = lastAspectsTs(fields.createdAt),
                        originGuid = originGuid,
                    ),
                ) {
                    onConflict = "origin_guid"
                    select()
                }
                .decodeSingle<GoalRowDto>()
                .toRemote()
        }

    override suspend fun softDeleteGoal(originGuid: String): Result<Boolean> =
        softDeleteByOriginGuid(GOALS_TABLE, originGuid, "remove that goal")
}
