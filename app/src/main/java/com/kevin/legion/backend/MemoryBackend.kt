package com.kevin.legion.backend

/**
 * The memory aspect's Supabase seam - three tables
 * (`supabase/migrations/20260902000300_aspect_memory.sql`), one interface, following
 * [BodyBackend]'s own "many entities, one interface" shape exactly - **this is the SECOND aspect
 * built off that template**, copied from the current file rather than an older description of it
 * (see the memory-supabase ticket brief's own opening line for why that distinction mattered
 * enough to write down).
 *
 * - **Every write is a genuine upsert keyed on [originGuid], never a create/update fork** - same
 *   reasoning as [BodyBackend]'s own class doc. [originGuid] is backed by an EXISTING phone-side
 *   column for two of the three tables ([com.kevin.legion.data.local.MemoryEntry.syncId] /
 *   [com.kevin.legion.data.local.CompanionMemory.syncId]) and a freshly-minted one for the third
 *   ([com.kevin.legion.data.local.MemoryAudit.guid]) - see each entity's own v61 doc comment.
 * - **[fetchChangedSince] returns tombstones too, never active-only** - same [BodyBackend] posture,
 *   built in from the start rather than discovered the hard way.
 * - **[softDeleteMemoryEntry]/[softDeleteCompanionMemory] take [originGuid], never a server uuid** -
 *   same reasoning as [BodyBackend.softDeleteBodyweightLog]'s own doc comment.
 * - **No delete function for [RemoteMemoryAudit] at all** - nothing on the phone ever soft-deletes
 *   an individual audit row; see [com.kevin.legion.data.local.MemoryAudit.deleted]'s own doc
 *   comment and `MemoryBackfill.kt`'s class doc for why that table is pushed by backfill alone.
 *
 * Every function returns [Result], no [io.github.jan.supabase.SupabaseClient] in any signature,
 * matching [BodyBackend]'s own seam discipline.
 */

// ---------------------------------------------------------------------------------------------
// MEMORIES (the flat, explicitly-remembered-fact table)
// ---------------------------------------------------------------------------------------------

/** A `public.memories` row as Postgres reports it. */
data class RemoteMemoryEntry(
    val serverId: String,
    val text: String,
    val loggedAtMs: Long,
    val updatedAtMs: Long,
    val deleted: Boolean,
    val originGuid: String,
)

data class MemoryEntryFields(
    val text: String,
    val loggedAtMs: Long,
)

// ---------------------------------------------------------------------------------------------
// COMPANION_MEMORIES (the consolidated/reflected/stated table)
// ---------------------------------------------------------------------------------------------

/** A `public.companion_memories` row as Postgres reports it. **No `embeddingVector`/
 * `embeddingModel`** - see [com.kevin.legion.data.local.CompanionMemory.embeddingVector]'s own
 * doc comment for why that pair deliberately does not travel to Supabase. */
data class RemoteCompanionMemory(
    val serverId: String,
    val vehicleId: String,
    val text: String,
    val category: String,
    val source: String,
    val importance: Int,
    val loggedAtMs: Long,
    val lastAccessedAtMs: Long?,
    val updatedAtMs: Long,
    val deleted: Boolean,
    val originGuid: String,
)

data class CompanionMemoryFields(
    val vehicleId: String,
    val text: String,
    val category: String,
    val source: String,
    val importance: Int,
    val loggedAtMs: Long,
    val lastAccessedAtMs: Long?,
)

// ---------------------------------------------------------------------------------------------
// MEMORY_AUDIT (the append-only audit trail)
// ---------------------------------------------------------------------------------------------

/** A `public.memory_audit` row as Postgres reports it. */
data class RemoteMemoryAudit(
    val serverId: String,
    val event: String,
    val store: String,
    val detail: String,
    val refId: Long,
    val vehicleId: String,
    val loggedAtMs: Long,
    val updatedAtMs: Long,
    val deleted: Boolean,
    val originGuid: String,
)

data class MemoryAuditFields(
    val event: String,
    val store: String,
    val detail: String,
    val refId: Long,
    val vehicleId: String,
    val loggedAtMs: Long,
)

/** See this file's own class doc for the shared shape every one of the following functions
 * follows. */
interface MemoryBackend {
    suspend fun fetchChangedMemoryEntriesSince(sinceMs: Long): Result<List<RemoteMemoryEntry>>
    suspend fun upsertMemoryEntry(originGuid: String, fields: MemoryEntryFields): Result<RemoteMemoryEntry>
    suspend fun softDeleteMemoryEntry(originGuid: String): Result<Boolean>

    suspend fun fetchChangedCompanionMemoriesSince(sinceMs: Long): Result<List<RemoteCompanionMemory>>
    suspend fun upsertCompanionMemory(originGuid: String, fields: CompanionMemoryFields): Result<RemoteCompanionMemory>
    suspend fun softDeleteCompanionMemory(originGuid: String): Result<Boolean>

    suspend fun fetchChangedMemoryAuditSince(sinceMs: Long): Result<List<RemoteMemoryAudit>>
    suspend fun upsertMemoryAudit(originGuid: String, fields: MemoryAuditFields): Result<RemoteMemoryAudit>
    // No softDeleteMemoryAudit - see this file's own class doc.
}

/** Thrown (wrapped in [Result.failure]) by [SupabaseMemoryBackend] for every failure branch - same
 * posture as [BodyBackendException]. */
class MemoryBackendException(message: String) : Exception(message)
