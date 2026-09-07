package com.kevin.legion.backend.engine

import com.kevin.legion.backend.CompanionMemoryFields
import com.kevin.legion.backend.MemoryAuditFields
import com.kevin.legion.backend.MemoryBackend
import com.kevin.legion.backend.MemoryEntryFields
import com.kevin.legion.backend.RemoteCompanionMemory
import com.kevin.legion.backend.RemoteMemoryAudit
import com.kevin.legion.backend.RemoteMemoryEntry
import java.time.Instant
import java.time.OffsetDateTime
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

private const val MEMORIES_PATH = "/api/memory/memories/"
private const val COMPANION_MEMORIES_PATH = "/api/memory/companion_memories/"
private const val MEMORY_AUDIT_PATH = "/api/memory/memory_audit/"

private fun memTs(ms: Long): String = Instant.ofEpochMilli(ms).toString()
private fun memParseTs(s: String): Long = OffsetDateTime.parse(s).toInstant().toEpochMilli()

// ---------------------------------------------------------------------------------------------
// MEMORIES
// ---------------------------------------------------------------------------------------------

/** One `public.memories` row as `server/api/memory.py`'s `MemorySerializer` renders it -
 * **measured against the live engine on 2026-09-07**: `{"id": "2af976ec-...",
 * "text": "Work address is 945 Bunker Hill Road, Houston, TX 77024",
 * "logged_at": "2026-07-26T21:06:18.969000Z", "provenance": "USER", "created_at": "...",
 * "updated_at": "...", "deleted_at": null, "origin_guid": "3b501785-..."}`. */
@Serializable
private data class DjangoMemoryRow(
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

/** See [DjangoBodyBackend]'s own write-DTO note for why `origin_guid` is absent: the identity
 * comes from the URL and `SyncedModelViewSet.upsert` overwrites the body's copy with it anyway. */
@Serializable
private data class DjangoMemoryWrite(
    val text: String,
    @SerialName("logged_at") val loggedAt: String,
)

// ---------------------------------------------------------------------------------------------
// COMPANION_MEMORIES
// ---------------------------------------------------------------------------------------------

/**
 * One `public.companion_memories` row.
 *
 * **No `embedding_vector` and no `embedding_model`, on either side of the wire.**
 * [com.kevin.legion.data.local.CompanionMemory] carries both and they never leave the device;
 * [RemoteCompanionMemory] has never listed them, `public.companion_memories` has no such column
 * (confirmed against the live schema by `api/memory.py`'s own module doc), and
 * `CompanionMemorySerializer.Meta.fields` names the eleven that do cross. So there is nothing here
 * to filter, forget to filter, or accidentally widen - which is the same "the guarantee is that it
 * was never stored, not that something remembered to exclude it" shape CLAUDE.md section 7 asks
 * for elsewhere.
 *
 * `vehicle_id` is a plain text label (the phone's own `ActiveVehicle` key), not a foreign key into
 * any server table - the fleet aspect is not routed by this ticket and this column would not
 * connect to it if it were.
 */
@Serializable
private data class DjangoCompanionMemoryRow(
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

@Serializable
private data class DjangoCompanionMemoryWrite(
    @SerialName("vehicle_id") val vehicleId: String,
    val text: String,
    val category: String,
    val source: String,
    val importance: Int,
    @SerialName("logged_at") val loggedAt: String,
    @SerialName("last_accessed_at") val lastAccessedAt: String?,
)

// ---------------------------------------------------------------------------------------------
// MEMORY_AUDIT
// ---------------------------------------------------------------------------------------------

/** One `public.memory_audit` row. [refId] and [vehicleId] are typed nullable with a fallback
 * because the columns are nullable server-side; [RemoteMemoryAudit] declares them non-null, so the
 * `?: 0L` / `?: ""` below is where that difference is absorbed - the identical shape (and the
 * identical fallback values) `SupabaseMemoryBackend`'s own `MemoryAuditRowDto` uses. */
@Serializable
private data class DjangoMemoryAuditRow(
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

@Serializable
private data class DjangoMemoryAuditWrite(
    val event: String,
    val store: String,
    val detail: String,
    @SerialName("ref_id") val refId: Long,
    @SerialName("vehicle_id") val vehicleId: String,
    @SerialName("logged_at") val loggedAt: String,
)

/**
 * [MemoryBackend] over the household Django engine - three tables on `server/api/synced.py`'s
 * generic shape (`server/api/memory.py`).
 *
 * **`memory_audit` is append-only and this class has no way to delete from it.** That is true
 * three times over, deliberately: [MemoryBackend] declares no `softDeleteMemoryAudit`, so there is
 * no override here to write; `MemoryAuditViewSet.allow_delete = False` leaves `delete` out of the
 * URL map entirely, so the refusal comes from routing rather than from a guard inside a view a
 * later edit could drop; and a DELETE that reaches it anyway answers 405 saying *"memory_audit is
 * append-only: it is an audit trail, and a trail with rows removed from it is not one"*. A trail
 * that can be edited is not evidence, which is the same reasoning CLAUDE.md section 4 rule 8 gives
 * for keeping a gate's own inputs.
 *
 * **`conversation_audit` is a SEPARATE interface** ([com.kevin.legion.backend.ConversationAuditBackend],
 * with its own reconcile) and is not routed by this class or by the engine's `memory` aspect at
 * all.
 *
 * Every write is an upsert keyed on `origin_guid`, idempotent by construction because the identity
 * is in the URL - see [DjangoBodyBackend]'s own class doc for the full argument, which applies
 * unchanged here.
 */
class DjangoMemoryBackend(http: EngineHttp) : MemoryBackend {

    private val memories = EngineSyncedTable(
        http, MEMORIES_PATH, DjangoMemoryRow.serializer(),
    ) { it.id }

    private val companionMemories = EngineSyncedTable(
        http, COMPANION_MEMORIES_PATH, DjangoCompanionMemoryRow.serializer(),
    ) { it.id }

    /** `GET` and `PUT` only. There is no delete route on this table and this class never asks for
     * one - see the class doc. */
    private val memoryAudit = EngineSyncedTable(
        http, MEMORY_AUDIT_PATH, DjangoMemoryAuditRow.serializer(),
    ) { it.id }

    // --- memories -----------------------------------------------------------------------------

    override suspend fun fetchChangedMemoryEntriesSince(sinceMs: Long): Result<List<RemoteMemoryEntry>> =
        translatingEngineCall("load changed memories") {
            memories.fetchChangedSince(memTs(sinceMs)).map { it.toRemote() }
        }

    override suspend fun upsertMemoryEntry(
        originGuid: String,
        fields: MemoryEntryFields,
    ): Result<RemoteMemoryEntry> = translatingEngineCall("save that memory") {
        val body = engineSyncedJson.encodeToString(
            DjangoMemoryWrite.serializer(),
            DjangoMemoryWrite(text = fields.text, loggedAt = memTs(fields.loggedAtMs)),
        )
        memories.put(originGuid, body).toRemote()
    }

    override suspend fun softDeleteMemoryEntry(originGuid: String): Result<Boolean> =
        deletingEngineRow("forget that memory") { memories.deleteRow(originGuid) }

    // --- companion_memories -------------------------------------------------------------------

    override suspend fun fetchChangedCompanionMemoriesSince(
        sinceMs: Long,
    ): Result<List<RemoteCompanionMemory>> = translatingEngineCall("load changed companion memories") {
        companionMemories.fetchChangedSince(memTs(sinceMs)).map { it.toRemote() }
    }

    override suspend fun upsertCompanionMemory(
        originGuid: String,
        fields: CompanionMemoryFields,
    ): Result<RemoteCompanionMemory> = translatingEngineCall("save that companion memory") {
        val body = engineSyncedJson.encodeToString(
            DjangoCompanionMemoryWrite.serializer(),
            DjangoCompanionMemoryWrite(
                vehicleId = fields.vehicleId,
                text = fields.text,
                category = fields.category,
                source = fields.source,
                importance = fields.importance,
                loggedAt = memTs(fields.loggedAtMs),
                lastAccessedAt = fields.lastAccessedAtMs?.let { memTs(it) },
            ),
        )
        companionMemories.put(originGuid, body).toRemote()
    }

    override suspend fun softDeleteCompanionMemory(originGuid: String): Result<Boolean> =
        deletingEngineRow("forget that companion memory") { companionMemories.deleteRow(originGuid) }

    // --- memory_audit -------------------------------------------------------------------------

    override suspend fun fetchChangedMemoryAuditSince(sinceMs: Long): Result<List<RemoteMemoryAudit>> =
        translatingEngineCall("load changed memory audit rows") {
            memoryAudit.fetchChangedSince(memTs(sinceMs)).map { it.toRemote() }
        }

    override suspend fun upsertMemoryAudit(
        originGuid: String,
        fields: MemoryAuditFields,
    ): Result<RemoteMemoryAudit> = translatingEngineCall("record that memory audit row") {
        val body = engineSyncedJson.encodeToString(
            DjangoMemoryAuditWrite.serializer(),
            DjangoMemoryAuditWrite(
                event = fields.event,
                store = fields.store,
                detail = fields.detail,
                refId = fields.refId,
                vehicleId = fields.vehicleId,
                loggedAt = memTs(fields.loggedAtMs),
            ),
        )
        memoryAudit.put(originGuid, body).toRemote()
    }

    // No softDeleteMemoryAudit - see this class's own doc comment, and MemoryBackend's.
}
