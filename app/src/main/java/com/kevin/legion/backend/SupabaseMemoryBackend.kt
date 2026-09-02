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

private const val MEMORIES_TABLE = "memories"
private const val COMPANION_MEMORIES_TABLE = "companion_memories"
private const val MEMORY_AUDIT_TABLE = "memory_audit"

private fun memTs(ms: Long): String = Instant.ofEpochMilli(ms).toString()
private fun memParseTs(s: String): Long = OffsetDateTime.parse(s).toInstant().toEpochMilli()

// ---------------------------------------------------------------------------------------------
// MEMORIES
// ---------------------------------------------------------------------------------------------

@Serializable
private data class MemoryEntryUpsertDto(
    val text: String,
    @SerialName("logged_at") val loggedAt: String,
    @SerialName("origin_guid") val originGuid: String,
)

@Serializable
private data class MemoryEntryRowDto(
    val id: String,
    val text: String,
    @SerialName("logged_at") val loggedAt: String,
    @SerialName("updated_at") val updatedAt: String,
    @SerialName("deleted_at") val deletedAt: String? = null,
    @SerialName("origin_guid") val originGuid: String,
) {
    fun toRemote() = RemoteMemoryEntry(
        serverId = id,
        text = text,
        loggedAtMs = memParseTs(loggedAt),
        updatedAtMs = memParseTs(updatedAt),
        deleted = deletedAt != null,
        originGuid = originGuid,
    )
}

@Serializable
private data class MemoryDeletedAtDto(@SerialName("deleted_at") val deletedAt: String)

// ---------------------------------------------------------------------------------------------
// COMPANION_MEMORIES
// ---------------------------------------------------------------------------------------------

@Serializable
private data class CompanionMemoryUpsertDto(
    @SerialName("vehicle_id") val vehicleId: String,
    val text: String,
    val category: String,
    val source: String,
    val importance: Int,
    @SerialName("logged_at") val loggedAt: String,
    @SerialName("last_accessed_at") val lastAccessedAt: String?,
    @SerialName("origin_guid") val originGuid: String,
)

@Serializable
private data class CompanionMemoryRowDto(
    val id: String,
    @SerialName("vehicle_id") val vehicleId: String,
    val text: String,
    val category: String,
    val source: String,
    val importance: Int,
    @SerialName("logged_at") val loggedAt: String,
    @SerialName("last_accessed_at") val lastAccessedAt: String? = null,
    @SerialName("updated_at") val updatedAt: String,
    @SerialName("deleted_at") val deletedAt: String? = null,
    @SerialName("origin_guid") val originGuid: String,
) {
    fun toRemote() = RemoteCompanionMemory(
        serverId = id,
        vehicleId = vehicleId,
        text = text,
        category = category,
        source = source,
        importance = importance,
        loggedAtMs = memParseTs(loggedAt),
        lastAccessedAtMs = lastAccessedAt?.let { memParseTs(it) },
        updatedAtMs = memParseTs(updatedAt),
        deleted = deletedAt != null,
        originGuid = originGuid,
    )
}

// ---------------------------------------------------------------------------------------------
// MEMORY_AUDIT
// ---------------------------------------------------------------------------------------------

@Serializable
private data class MemoryAuditUpsertDto(
    val event: String,
    val store: String,
    val detail: String,
    @SerialName("ref_id") val refId: Long,
    @SerialName("vehicle_id") val vehicleId: String,
    @SerialName("logged_at") val loggedAt: String,
    @SerialName("origin_guid") val originGuid: String,
)

@Serializable
private data class MemoryAuditRowDto(
    val id: String,
    val event: String,
    val store: String,
    val detail: String,
    @SerialName("ref_id") val refId: Long? = null,
    @SerialName("vehicle_id") val vehicleId: String? = null,
    @SerialName("logged_at") val loggedAt: String,
    @SerialName("updated_at") val updatedAt: String,
    @SerialName("deleted_at") val deletedAt: String? = null,
    @SerialName("origin_guid") val originGuid: String,
) {
    fun toRemote() = RemoteMemoryAudit(
        serverId = id,
        event = event,
        store = store,
        detail = detail,
        refId = refId ?: 0L,
        vehicleId = vehicleId ?: "",
        loggedAtMs = memParseTs(loggedAt),
        updatedAtMs = memParseTs(updatedAt),
        deleted = deletedAt != null,
        originGuid = originGuid,
    )
}

/**
 * [MemoryBackend]'s real implementation over Postgrest, against the three `public` tables
 * `supabase/migrations/20260902000300_aspect_memory.sql` creates. This is the deliberately
 * untested seam, same posture as [SupabaseBodyBackend] - exercising it for real needs a live
 * project. [MemoryBackend] is the fake-friendly interface; every branch here does nothing but
 * translate exceptions, decode DTOs, and pick `on conflict (origin_guid)` as the upsert key.
 */
class SupabaseMemoryBackend(private val client: SupabaseClient) : MemoryBackend {

    private suspend inline fun <T> translating(action: String, block: () -> T): Result<T> = try {
        Result.success(block())
    } catch (e: RestException) {
        Result.failure(MemoryBackendException("Supabase rejected the request to $action: ${e.error}"))
    } catch (e: IOException) {
        Result.failure(MemoryBackendException("Couldn't reach the server to $action."))
    } catch (e: Exception) {
        Result.failure(MemoryBackendException("Couldn't $action: ${e.message ?: "unknown error"}"))
    }

    private suspend fun softDeleteByOriginGuid(table: String, originGuid: String, action: String): Result<Boolean> =
        translating(action) {
            client.postgrest.from(table)
                .update(MemoryDeletedAtDto(deletedAt = OffsetDateTime.now().toString())) {
                    select()
                    filter {
                        eq("origin_guid", originGuid)
                        filter("deleted_at", FilterOperator.IS, "null")
                    }
                }
                .decodeList<kotlinx.serialization.json.JsonElement>()
                .isNotEmpty()
        }

    // --- Memories ------------------------------------------------------------------------------

    override suspend fun fetchChangedMemoryEntriesSince(sinceMs: Long): Result<List<RemoteMemoryEntry>> =
        translating("load changed memories") {
            client.postgrest.from(MEMORIES_TABLE)
                .select { filter { gte("updated_at", memTs(sinceMs)) } }
                .decodeList<MemoryEntryRowDto>()
                .map { it.toRemote() }
        }

    override suspend fun upsertMemoryEntry(originGuid: String, fields: MemoryEntryFields): Result<RemoteMemoryEntry> =
        translating("save that memory") {
            client.postgrest.from(MEMORIES_TABLE)
                .upsert(
                    MemoryEntryUpsertDto(
                        text = fields.text,
                        loggedAt = memTs(fields.loggedAtMs),
                        originGuid = originGuid,
                    ),
                ) {
                    onConflict = "origin_guid"
                    select()
                }
                .decodeSingle<MemoryEntryRowDto>()
                .toRemote()
        }

    override suspend fun softDeleteMemoryEntry(originGuid: String): Result<Boolean> =
        softDeleteByOriginGuid(MEMORIES_TABLE, originGuid, "remove that memory")

    // --- Companion memories ----------------------------------------------------------------------

    override suspend fun fetchChangedCompanionMemoriesSince(sinceMs: Long): Result<List<RemoteCompanionMemory>> =
        translating("load changed companion memories") {
            client.postgrest.from(COMPANION_MEMORIES_TABLE)
                .select { filter { gte("updated_at", memTs(sinceMs)) } }
                .decodeList<CompanionMemoryRowDto>()
                .map { it.toRemote() }
        }

    override suspend fun upsertCompanionMemory(originGuid: String, fields: CompanionMemoryFields): Result<RemoteCompanionMemory> =
        translating("save that companion memory") {
            client.postgrest.from(COMPANION_MEMORIES_TABLE)
                .upsert(
                    CompanionMemoryUpsertDto(
                        vehicleId = fields.vehicleId,
                        text = fields.text,
                        category = fields.category,
                        source = fields.source,
                        importance = fields.importance,
                        loggedAt = memTs(fields.loggedAtMs),
                        lastAccessedAt = fields.lastAccessedAtMs?.let { memTs(it) },
                        originGuid = originGuid,
                    ),
                ) {
                    onConflict = "origin_guid"
                    select()
                }
                .decodeSingle<CompanionMemoryRowDto>()
                .toRemote()
        }

    override suspend fun softDeleteCompanionMemory(originGuid: String): Result<Boolean> =
        softDeleteByOriginGuid(COMPANION_MEMORIES_TABLE, originGuid, "remove that companion memory")

    // --- Memory audit --------------------------------------------------------------------------

    override suspend fun fetchChangedMemoryAuditSince(sinceMs: Long): Result<List<RemoteMemoryAudit>> =
        translating("load changed memory audit rows") {
            client.postgrest.from(MEMORY_AUDIT_TABLE)
                .select { filter { gte("updated_at", memTs(sinceMs)) } }
                .decodeList<MemoryAuditRowDto>()
                .map { it.toRemote() }
        }

    override suspend fun upsertMemoryAudit(originGuid: String, fields: MemoryAuditFields): Result<RemoteMemoryAudit> =
        translating("save that audit line") {
            client.postgrest.from(MEMORY_AUDIT_TABLE)
                .upsert(
                    MemoryAuditUpsertDto(
                        event = fields.event,
                        store = fields.store,
                        detail = fields.detail,
                        refId = fields.refId,
                        vehicleId = fields.vehicleId,
                        loggedAt = memTs(fields.loggedAtMs),
                        originGuid = originGuid,
                    ),
                ) {
                    onConflict = "origin_guid"
                    select()
                }
                .decodeSingle<MemoryAuditRowDto>()
                .toRemote()
        }
}
