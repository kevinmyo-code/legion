package com.kevin.legion.backend

import android.content.Context
import com.kevin.legion.MidnightEvents
import com.kevin.legion.data.local.CarDatabase
import com.kevin.legion.data.local.CompanionMemory
import com.kevin.legion.data.local.MemoryAudit
import com.kevin.legion.data.local.MemoryEntry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Per-table install-scoped high-water marks for [MemorySync.pull] - same shape as
 * [BodyPullCursor], one watermark per memory table rather than one shared minimum, so a quiet
 * table never holds back a busy one. See [BodyPullCursor]'s own class doc for the full reasoning;
 * unchanged here.
 */
internal object MemoryPullCursor {
    private const val PREFS = "memory_pull_cursor"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun lastPulledAtMs(context: Context, table: String): Long = prefs(context).getLong(table, 0L)

    fun advance(context: Context, table: String, atMs: Long) {
        prefs(context).edit().putLong(table, atMs).apply()
    }
}

/**
 * The generic merge [MemorySync.pull] performs for one table - a deliberate copy of
 * [BodyMerge.merge], not a shared reference to it. Copied rather than imported so this aspect
 * never depends on body's own internals (the memory-supabase brief's own "do not touch body sync"
 * boundary) - if the two ever drift, that is a decision for whoever notices, not an accident of
 * one file quietly changing under the other. **The five rules are [BodyMerge.merge]'s own,
 * verbatim** - see that object's own class doc for the full reasoning behind each one.
 */
internal object MemoryMerge {
    data class MergeReport(
        val inserted: Int = 0,
        val updated: Int = 0,
        val skippedLocalNewer: Int = 0,
        val tombstoned: Int = 0,
        val skippedTombstoneNoLocalMatch: Int = 0,
    ) {
        operator fun plus(other: MergeReport) = MergeReport(
            inserted + other.inserted,
            updated + other.updated,
            skippedLocalNewer + other.skippedLocalNewer,
            tombstoned + other.tombstoned,
            skippedTombstoneNoLocalMatch + other.skippedTombstoneNoLocalMatch,
        )
    }

    suspend fun <R, L> merge(
        remoteRows: List<R>,
        localRows: List<L>,
        remoteGuid: (R) -> String,
        localGuid: (L) -> String,
        remoteDeleted: (R) -> Boolean,
        remoteUpdatedAtMs: (R) -> Long,
        localUpdatedAtMs: (L) -> Long,
        localDeleted: (L) -> Boolean,
        toInserted: (R) -> L,
        toMerged: (R, L) -> L,
        withDeletedFlag: (L, Long) -> L,
        insert: suspend (L) -> Unit,
        update: suspend (L) -> Unit,
    ): MergeReport {
        val localByGuid = localRows.associateBy { localGuid(it) }
        var inserted = 0
        var updated = 0
        var skippedLocalNewer = 0
        var tombstoned = 0
        var skippedTombstoneNoLocalMatch = 0

        for (remote in remoteRows) {
            val local = localByGuid[remoteGuid(remote)]

            if (local == null && remoteDeleted(remote)) {
                skippedTombstoneNoLocalMatch++
                continue
            }

            if (local == null) {
                insert(toInserted(remote))
                inserted++
                continue
            }

            if (remoteDeleted(remote)) {
                if (!localDeleted(local)) {
                    update(withDeletedFlag(local, remoteUpdatedAtMs(remote)))
                    tombstoned++
                }
                continue
            }

            if (remoteUpdatedAtMs(remote) >= localUpdatedAtMs(local)) {
                val merged = toMerged(remote, local)
                if (merged != local) {
                    update(merged)
                    updated++
                }
            } else {
                skippedLocalNewer++
            }
        }
        // Rule 6, by omission: nothing above ever iterates localRows looking for a row to delete.

        return MergeReport(inserted, updated, skippedLocalNewer, tombstoned, skippedTombstoneNoLocalMatch)
    }
}

/**
 * The memory aspect's live pull - three independent sub-pulls, one per table, each following
 * [MemoryMerge.merge]'s rules exactly. Mirrors [BodySync]'s own shape.
 */
object MemorySync {
    data class PullReport(
        val inserted: Int,
        val updated: Int,
        val skippedLocalNewer: Int,
        val tombstoned: Int,
        val skippedTombstoneNoLocalMatch: Int,
    )

    private fun MemoryMerge.MergeReport.toPullReport() =
        PullReport(inserted, updated, skippedLocalNewer, tombstoned, skippedTombstoneNoLocalMatch)

    private operator fun PullReport.plus(other: PullReport) = PullReport(
        inserted + other.inserted,
        updated + other.updated,
        skippedLocalNewer + other.skippedLocalNewer,
        tombstoned + other.tombstoned,
        skippedTombstoneNoLocalMatch + other.skippedTombstoneNoLocalMatch,
    )

    /** Pulls and merges all three memory tables. Each table's watermark advances independently and
     * only after that table's own batch is fully merged - a failure partway through throws
     * straight out, same posture as [BodySync.pull]. */
    suspend fun pull(context: Context, backend: MemoryBackend): PullReport {
        var total = PullReport(0, 0, 0, 0, 0)
        total += pullMemoryEntries(context, backend)
        total += pullCompanionMemories(context, backend)
        total += pullMemoryAudit(context, backend)
        return total
    }

    private const val T_MEMORIES = "memories"
    private const val T_COMPANION_MEMORIES = "companion_memories"
    private const val T_MEMORY_AUDIT = "memory_audit"

    private suspend fun pullMemoryEntries(context: Context, backend: MemoryBackend): PullReport {
        val db = CarDatabase.getDatabase(context)
        val sinceMs = MemoryPullCursor.lastPulledAtMs(context, T_MEMORIES)
        val remote = backend.fetchChangedMemoryEntriesSince(sinceMs).getOrThrow()
        val local = db.memoryDao().getAll()
        val report = MemoryMerge.merge(
            remoteRows = remote,
            localRows = local,
            remoteGuid = { it.originGuid },
            localGuid = { it.syncId },
            remoteDeleted = { it.deleted },
            remoteUpdatedAtMs = { it.updatedAtMs },
            localUpdatedAtMs = { it.updatedAtMs },
            localDeleted = { it.deleted },
            toInserted = { r ->
                MemoryEntry(
                    id = 0,
                    text = r.text,
                    timestamp = r.loggedAtMs,
                    syncId = r.originGuid,
                    serverId = r.serverId,
                    updatedAtMs = r.updatedAtMs,
                    deleted = r.deleted,
                )
            },
            toMerged = { r, existing ->
                existing.copy(
                    text = r.text,
                    timestamp = r.loggedAtMs,
                    serverId = r.serverId,
                    updatedAtMs = r.updatedAtMs,
                    deleted = r.deleted,
                )
            },
            withDeletedFlag = { existing, atMs -> existing.copy(deleted = true, updatedAtMs = atMs) },
            insert = { db.memoryDao().insert(it) },
            update = { db.memoryDao().update(it) },
        )
        remote.maxOfOrNull { it.updatedAtMs }?.let { MemoryPullCursor.advance(context, T_MEMORIES, it) }
        return report.toPullReport()
    }

    private suspend fun pullCompanionMemories(context: Context, backend: MemoryBackend): PullReport {
        val db = CarDatabase.getDatabase(context)
        val sinceMs = MemoryPullCursor.lastPulledAtMs(context, T_COMPANION_MEMORIES)
        val remote = backend.fetchChangedCompanionMemoriesSince(sinceMs).getOrThrow()
        val local = db.companionMemoryDao().getAll()
        val report = MemoryMerge.merge(
            remoteRows = remote,
            localRows = local,
            remoteGuid = { it.originGuid },
            localGuid = { it.syncId },
            remoteDeleted = { it.deleted },
            remoteUpdatedAtMs = { it.updatedAtMs },
            localUpdatedAtMs = { it.updatedAtMs },
            localDeleted = { it.deleted },
            toInserted = { r ->
                CompanionMemory(
                    id = 0,
                    vehicleId = r.vehicleId,
                    text = r.text,
                    category = r.category,
                    source = r.source,
                    importance = r.importance,
                    createdAt = r.loggedAtMs,
                    lastAccessedAt = r.lastAccessedAtMs ?: 0L,
                    // No embeddingVector/embeddingModel from the server - see RemoteCompanionMemory's
                    // own doc comment. A row pulled fresh onto a new device simply has none yet,
                    // same as any row created locally before semantic recall is wired.
                    embeddingVector = null,
                    embeddingModel = null,
                    syncId = r.originGuid,
                    serverId = r.serverId,
                    updatedAtMs = r.updatedAtMs,
                    deleted = r.deleted,
                )
            },
            toMerged = { r, existing ->
                existing.copy(
                    vehicleId = r.vehicleId,
                    text = r.text,
                    category = r.category,
                    source = r.source,
                    importance = r.importance,
                    createdAt = r.loggedAtMs,
                    lastAccessedAt = r.lastAccessedAtMs ?: existing.lastAccessedAt,
                    // A local embedding (if this device already computed one) survives a merge -
                    // the server never had one to overwrite it with, so this branch never clobbers
                    // it back to null.
                    serverId = r.serverId,
                    updatedAtMs = r.updatedAtMs,
                    deleted = r.deleted,
                )
            },
            withDeletedFlag = { existing, atMs -> existing.copy(deleted = true, updatedAtMs = atMs) },
            insert = { db.companionMemoryDao().insert(it) },
            update = { db.companionMemoryDao().update(it) },
        )
        remote.maxOfOrNull { it.updatedAtMs }?.let { MemoryPullCursor.advance(context, T_COMPANION_MEMORIES, it) }
        return report.toPullReport()
    }

    private suspend fun pullMemoryAudit(context: Context, backend: MemoryBackend): PullReport {
        val db = CarDatabase.getDatabase(context)
        val sinceMs = MemoryPullCursor.lastPulledAtMs(context, T_MEMORY_AUDIT)
        val remote = backend.fetchChangedMemoryAuditSince(sinceMs).getOrThrow()
        val local = db.memoryAuditDao().getAll()
        val report = MemoryMerge.merge(
            remoteRows = remote,
            localRows = local,
            remoteGuid = { it.originGuid },
            localGuid = { it.guid },
            remoteDeleted = { it.deleted },
            remoteUpdatedAtMs = { it.updatedAtMs },
            localUpdatedAtMs = { it.updatedAtMs },
            localDeleted = { it.deleted },
            toInserted = { r ->
                MemoryAudit(
                    id = 0,
                    event = r.event,
                    store = r.store,
                    detail = r.detail,
                    refId = r.refId,
                    vehicleId = r.vehicleId,
                    at = r.loggedAtMs,
                    guid = r.originGuid,
                    serverId = r.serverId,
                    updatedAtMs = r.updatedAtMs,
                    deleted = r.deleted,
                )
            },
            toMerged = { r, existing ->
                existing.copy(
                    event = r.event,
                    store = r.store,
                    detail = r.detail,
                    refId = r.refId,
                    vehicleId = r.vehicleId,
                    at = r.loggedAtMs,
                    serverId = r.serverId,
                    updatedAtMs = r.updatedAtMs,
                    deleted = r.deleted,
                )
            },
            withDeletedFlag = { existing, atMs -> existing.copy(deleted = true, updatedAtMs = atMs) },
            insert = { db.memoryAuditDao().insert(it) },
            update = { db.memoryAuditDao().update(it) },
        )
        remote.maxOfOrNull { it.updatedAtMs }?.let { MemoryPullCursor.advance(context, T_MEMORY_AUDIT, it) }
        return report.toPullReport()
    }

    // --- Foreground auto-trigger, mirroring BodySync's own shape --------------------------------

    private val autoPullScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile private var lastAutoPullAt = 0L

    private const val AUTO_PULL_MIN_INTERVAL_MS = 5 * 60 * 1000L
    private const val AUTO_PULL_RETRY_DELAY_MS = 1_000L

    /** Thin delegation to [SupabaseAuth.resolveSignedInUserId], same shape as
     * [BodySync.resolveUserIdForAutoPull] - `internal` so a test can drive it directly. */
    internal suspend fun resolveUserIdForAutoPull(
        auth: SupabaseAuth,
        retryDelayMs: Long = AUTO_PULL_RETRY_DELAY_MS,
    ): String? = auth.resolveSignedInUserId(retryDelayMs)

    /** `MainActivity.onResume`'s hook, called alongside [BodySync.maybeAutoPull] - see
     * [MemoryOutboxDrain.maybeDrain]'s own doc comment for why the drain (and [MemoryBackfill])
     * run first. No-ops silently when Supabase is not configured or nobody is signed in. */
    fun maybeAutoPull(context: Context) {
        val now = System.currentTimeMillis()
        if (now - lastAutoPullAt < AUTO_PULL_MIN_INTERVAL_MS) return
        val app = context.applicationContext
        val client = SupabaseClientProvider.get(app) ?: return
        lastAutoPullAt = now
        autoPullScope.launch {
            try {
                val userId = resolveUserIdForAutoPull(SupabaseAuth(app))
                if (userId == null) return@launch
                val report = pull(app, SupabaseMemoryBackend(client))
                MidnightEvents.memoryAutoPullSucceeded(
                    report.inserted, report.updated, report.skippedLocalNewer,
                    report.tombstoned, report.skippedTombstoneNoLocalMatch,
                )
            } catch (e: Exception) {
                MidnightEvents.memoryAutoPullFailed(e)
            }
        }
    }
}
