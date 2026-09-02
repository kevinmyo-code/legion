package com.kevin.legion.backend

import android.content.Context
import com.kevin.legion.MidnightEvents
import com.kevin.legion.data.local.CarDatabase
import com.kevin.legion.data.local.CompanionMemory
import com.kevin.legion.data.local.MemoryEntry
import com.kevin.legion.data.local.OutboxEntry
import com.kevin.legion.data.local.OutboxOperation
import com.kevin.legion.data.local.OutboxTarget
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The push half of memory sync: write-through on every create/delete the four existing writers
 * already perform ([com.kevin.legion.ai.AriaBrain.remember], [com.kevin.legion.ai.MemoryConsolidator],
 * [com.kevin.legion.ai.ReflectionEngine], `LiveToolbox.rememberGoalPlanConstraint`), plus the
 * durable outbox that makes an offline write survive - mirrors [BodyWriteThrough]/
 * [BodyOutboxDrain]'s own shape.
 *
 * **`memory_audit` is deliberately absent from this file.** Nothing on the phone soft-deletes an
 * individual audit row, and routing every one of `MemoryAuditDao.record()`'s five call sites
 * through a live write-through push would mean touching all five (that function has no [Context]
 * to reach [SupabaseClientProvider] with) for a table nothing ever reads back into a prompt or
 * shows the driver live. `backend/MemoryBackfill.kt`'s periodic sweep already reaches every new
 * row within its own auto-run interval with zero call-site changes - see that file's own class doc
 * for the full reasoning. This is a genuine, reasoned deviation from [BodyWriteThrough]'s "every
 * table gets live write-through" shape, not an oversight: audit's own doc comment
 * ([com.kevin.legion.data.local.MemoryAudit]'s class doc) already states it is "a record of what
 * happened, never an input to behaviour" with nothing reading it back live, so a few minutes'
 * push latency costs nothing a driver would ever notice.
 *
 * **Local write always happens first, unconditionally** - same posture as [BodyWriteThrough]'s own
 * class doc.
 */
object MemoryWriteThrough {
    /** Test seam, same mechanism as [BodyWriteThrough.backendOverride]. */
    @Volatile
    internal var backendOverride: MemoryBackend? = null

    private fun backend(context: Context): MemoryBackend? {
        backendOverride?.let { return it }
        val client = SupabaseClientProvider.get(context) ?: return null
        return SupabaseMemoryBackend(client)
    }

    /** See [BodyWriteThrough.cancelPendingCreateIfPending]'s own doc comment for the full
     * reasoning - identical shape, applied to memory's own outbox targets. */
    private suspend fun cancelPendingCreateIfPending(db: CarDatabase, target: String, localId: Long): Boolean {
        val dao = db.outboxDao()
        val pending = dao.pendingForTable(target, Int.MAX_VALUE)
            .filter { it.operation == OutboxOperation.UPSERT && it.localId == localId }
        for (entry in pending) dao.delete(entry.id)
        return pending.isNotEmpty()
    }

    private suspend fun enqueue(db: CarDatabase, target: String, operation: String, localId: Long, payload: String, error: String?) {
        db.outboxDao().insert(
            OutboxEntry(
                targetTable = target,
                operation = operation,
                localId = localId,
                payload = payload,
                createdAt = System.currentTimeMillis(),
                attempts = 0,
                lastError = error,
            ),
        )
    }

    // --- Memories --------------------------------------------------------------------------------

    @Serializable
    internal data class MemoryEntryPayload(
        val guid: String,
        val text: String,
        val loggedAtMs: Long,
    ) {
        fun toFields() = MemoryEntryFields(text, loggedAtMs)
        companion object {
            fun from(row: MemoryEntry) = MemoryEntryPayload(row.syncId, row.text, row.timestamp)
        }
    }

    suspend fun addMemoryEntry(context: Context, row: MemoryEntry): MemoryEntry {
        val db = CarDatabase.getDatabase(context)
        db.memoryDao().insert(row)
        val backend = backend(context) ?: return row
        val result = backend.upsertMemoryEntry(row.syncId, MemoryEntryPayload.from(row).toFields())
        if (result.isFailure) {
            enqueue(
                db, OutboxTarget.MEMORY_MEMORIES, OutboxOperation.UPSERT, row.id,
                Json.encodeToString(MemoryEntryPayload.serializer(), MemoryEntryPayload.from(row)),
                result.exceptionOrNull()?.message,
            )
        }
        return row
    }

    /** Soft-deletes and pushes a tombstone for one remembered fact - the driver rejecting a row on
     * the memory screen, or [deleteAllMemoryEntries]'s per-row loop. On an unconfigured install
     * (or a still-pending create this call cancels outright), falls back to a hard delete, same
     * shape as [BodyWriteThrough.deleteBodyweightLog]. */
    suspend fun deleteMemoryEntry(context: Context, entry: MemoryEntry) {
        val db = CarDatabase.getDatabase(context)
        val backend = backend(context)
        if (backend == null) {
            db.memoryDao().deleteById(entry.id)
            return
        }
        if (cancelPendingCreateIfPending(db, OutboxTarget.MEMORY_MEMORIES, entry.id)) {
            db.memoryDao().deleteById(entry.id)
            return
        }
        db.memoryDao().update(entry.copy(deleted = true, updatedAtMs = System.currentTimeMillis()))
        val result = backend.softDeleteMemoryEntry(entry.syncId)
        if (result.isFailure) {
            enqueue(
                db, OutboxTarget.MEMORY_MEMORIES, OutboxOperation.SOFT_DELETE, entry.id,
                Json.encodeToString(MemoryDeletePayload.serializer(), MemoryDeletePayload(entry.syncId)),
                result.exceptionOrNull()?.message,
            )
        }
    }

    /** The "forget everything" reset's configured-install path - see
     * [com.kevin.legion.ai.CompanionReset.resetMemories]. Tombstones and pushes every currently
     * active row rather than a bare local `deleteAll()`, so the rows do not simply get pulled
     * straight back down on the next [MemorySync.pull] (the exact resurrection shape
     * `.scratch/live-sync/map.md` documents EventsReconcile's own wipe-and-refill producing). On an
     * unconfigured install, falls back to the original bare `deleteAll()` - unchanged behaviour for
     * every install that never had a server copy to resurrect from. */
    suspend fun deleteAllMemoryEntries(context: Context) {
        val db = CarDatabase.getDatabase(context)
        if (backend(context) == null) {
            db.memoryDao().deleteAll()
            return
        }
        for (row in db.memoryDao().getAll().filter { !it.deleted }) {
            deleteMemoryEntry(context, row)
        }
    }

    // --- Companion memories ------------------------------------------------------------------------

    @Serializable
    internal data class CompanionMemoryPayload(
        val guid: String,
        val vehicleId: String,
        val text: String,
        val category: String,
        val source: String,
        val importance: Int,
        val loggedAtMs: Long,
        val lastAccessedAtMs: Long?,
    ) {
        fun toFields() = CompanionMemoryFields(vehicleId, text, category, source, importance, loggedAtMs, lastAccessedAtMs)
        companion object {
            fun from(row: CompanionMemory) = CompanionMemoryPayload(
                row.syncId, row.vehicleId, row.text, row.category, row.source, row.importance,
                row.createdAt, row.lastAccessedAt.takeIf { it != 0L },
            )
        }
    }

    suspend fun addCompanionMemory(context: Context, row: CompanionMemory): CompanionMemory {
        val db = CarDatabase.getDatabase(context)
        val id = db.companionMemoryDao().insert(row)
        val withId = row.copy(id = id)
        val backend = backend(context) ?: return withId
        val result = backend.upsertCompanionMemory(row.syncId, CompanionMemoryPayload.from(row).toFields())
        if (result.isFailure) {
            enqueue(
                db, OutboxTarget.MEMORY_COMPANION_MEMORIES, OutboxOperation.UPSERT, id,
                Json.encodeToString(CompanionMemoryPayload.serializer(), CompanionMemoryPayload.from(row)),
                result.exceptionOrNull()?.message,
            )
        }
        return withId
    }

    /** Soft-deletes and pushes a tombstone for one learned memory - same shape as
     * [deleteMemoryEntry]. */
    suspend fun deleteCompanionMemory(context: Context, memory: CompanionMemory) {
        val db = CarDatabase.getDatabase(context)
        val backend = backend(context)
        if (backend == null) {
            db.companionMemoryDao().deleteById(memory.id)
            return
        }
        if (cancelPendingCreateIfPending(db, OutboxTarget.MEMORY_COMPANION_MEMORIES, memory.id)) {
            db.companionMemoryDao().deleteById(memory.id)
            return
        }
        db.companionMemoryDao().update(memory.copy(deleted = true, updatedAtMs = System.currentTimeMillis()))
        val result = backend.softDeleteCompanionMemory(memory.syncId)
        if (result.isFailure) {
            enqueue(
                db, OutboxTarget.MEMORY_COMPANION_MEMORIES, OutboxOperation.SOFT_DELETE, memory.id,
                Json.encodeToString(MemoryDeletePayload.serializer(), MemoryDeletePayload(memory.syncId)),
                result.exceptionOrNull()?.message,
            )
        }
    }

    /** The "forget everything" reset's configured-install path for `companion_memories` - see
     * [deleteAllMemoryEntries]'s own doc comment for the full reasoning; identical shape. */
    suspend fun deleteAllCompanionMemories(context: Context) {
        val db = CarDatabase.getDatabase(context)
        if (backend(context) == null) {
            db.companionMemoryDao().deleteAll()
            return
        }
        for (row in db.companionMemoryDao().getAll().filter { !it.deleted }) {
            deleteCompanionMemory(context, row)
        }
    }
}

/** The wire shape queued for every [OutboxOperation.SOFT_DELETE] entry across memory's two
 * write-through tables - just the [MemoryBackend]'s own upsert key, matching
 * [BodyDeletePayload]'s shape. */
@Serializable
internal data class MemoryDeletePayload(val guid: String)

/**
 * Retries every still-pending memory-table [OutboxEntry], across [OutboxTarget.MEMORY_MEMORIES]
 * and [OutboxTarget.MEMORY_COMPANION_MEMORIES] - mirrors [BodyOutboxDrain]'s own shape and
 * bounded-attempts reasoning exactly. `ui/MainActivity.kt`'s `onResume` hook calls this BEFORE
 * [MemoryBackfill.maybeAutoRun] and [MemorySync.maybeAutoPull], same load-bearing ordering
 * [BodyOutboxDrain]'s own class doc explains for body.
 */
object MemoryOutboxDrain {
    const val MAX_ATTEMPTS = EventsOutboxDrain.MAX_ATTEMPTS

    data class DrainReport(val succeeded: Int, val stillPending: Int, val poisoned: Int) {
        operator fun plus(other: DrainReport) = DrainReport(
            succeeded + other.succeeded, stillPending + other.stillPending, poisoned + other.poisoned,
        )
    }

    suspend fun drain(context: Context, backend: MemoryBackend): DrainReport {
        val db = CarDatabase.getDatabase(context)
        var total = DrainReport(0, 0, 0)

        total += drainOne(db, OutboxTarget.MEMORY_MEMORIES) { entry ->
            when (entry.operation) {
                OutboxOperation.UPSERT -> {
                    val p = Json.decodeFromString(MemoryWriteThrough.MemoryEntryPayload.serializer(), entry.payload)
                    backend.upsertMemoryEntry(p.guid, p.toFields())
                }
                OutboxOperation.SOFT_DELETE -> {
                    val p = Json.decodeFromString(MemoryDeletePayload.serializer(), entry.payload)
                    backend.softDeleteMemoryEntry(p.guid)
                }
                else -> Result.success(Unit)
            }
        }
        total += drainOne(db, OutboxTarget.MEMORY_COMPANION_MEMORIES) { entry ->
            when (entry.operation) {
                OutboxOperation.UPSERT -> {
                    val p = Json.decodeFromString(MemoryWriteThrough.CompanionMemoryPayload.serializer(), entry.payload)
                    backend.upsertCompanionMemory(p.guid, p.toFields())
                }
                OutboxOperation.SOFT_DELETE -> {
                    val p = Json.decodeFromString(MemoryDeletePayload.serializer(), entry.payload)
                    backend.softDeleteCompanionMemory(p.guid)
                }
                else -> Result.success(Unit)
            }
        }
        return total
    }

    private suspend fun drainOne(db: CarDatabase, target: String, run: suspend (OutboxEntry) -> Result<*>): DrainReport {
        val dao = db.outboxDao()
        val pending = dao.pendingForTable(target, MAX_ATTEMPTS)
        var succeeded = 0
        var stillPending = 0
        var poisoned = 0
        for (entry in pending) {
            val result = run(entry)
            if (result.isSuccess) {
                dao.delete(entry.id)
                succeeded++
                continue
            }
            val attempts = entry.attempts + 1
            val message = result.exceptionOrNull()?.message ?: "unknown error"
            dao.recordAttempt(entry.id, attempts, message)
            if (attempts >= MAX_ATTEMPTS) poisoned++ else stillPending++
        }
        return DrainReport(succeeded, stillPending, poisoned)
    }

    /** `MainActivity.onResume`'s hook - see this object's own class doc for the ordering that
     * matters. No-ops silently when Supabase is not configured or nobody is signed in. */
    suspend fun maybeDrain(context: Context) {
        val app = context.applicationContext
        val client = SupabaseClientProvider.get(app) ?: return
        if (SupabaseAuth(app).resolveSignedInUserId() == null) return
        try {
            val report = drain(app, SupabaseMemoryBackend(client))
            MidnightEvents.memoryOutboxDrainSucceeded(report.succeeded, report.stillPending, report.poisoned)
        } catch (e: Exception) {
            MidnightEvents.memoryOutboxDrainFailed(e)
        }
    }
}
