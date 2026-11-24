package com.kevin.legion.backend

import android.content.Context
import com.kevin.legion.MidnightEvents
import com.kevin.legion.data.local.CarDatabase
import com.kevin.legion.data.local.Goal
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Per-table install-scoped high-water marks for [LastAspectsSync.pull] - same shape as
 * [LedgerConfigPullCursor], one watermark per table so a quiet table never holds back a busy one.
 */
internal object LastAspectsPullCursor {
    private const val PREFS = "last_aspects_pull_cursor"

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun lastPulledAtMs(context: Context, table: String): Long = prefs(context).getLong(table, 0L)

    fun advance(context: Context, table: String, atMs: Long) {
        prefs(context).edit().putLong(table, atMs).apply()
    }
}

/**
 * The generic merge [LastAspectsSync.pull] performs for one table - a deliberate copy of
 * [LedgerConfigMerge.merge], not a shared reference - see that object's own class doc for why
 * (never depend on another aspect's internals) and for the full reasoning behind each of the five
 * rules, which are reproduced here verbatim.
 */
internal object LastAspectsMerge {
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
 * live-sync's last aspect slice's live pull - one sub-pull for the one table this slice still
 * carries, following [LastAspectsMerge.merge]'s rules exactly. Mirrors [LedgerConfigSync]'s own
 * shape.
 *
 * **NARROWED by the lists redesign slice 1a (2026-11-24)** from four tables to `goals`. The
 * `item_lists`-before-`list_items` ordering this pull used to need, and the orphaned-child count it
 * reported, went with those two tables; see [LastAspectsBackend]'s own class doc.
 */
object LastAspectsSync {
    data class PullReport(
        val inserted: Int,
        val updated: Int,
        val skippedLocalNewer: Int,
        val tombstoned: Int,
        val skippedTombstoneNoLocalMatch: Int,
    )

    private fun LastAspectsMerge.MergeReport.toPullReport() = PullReport(inserted, updated, skippedLocalNewer, tombstoned, skippedTombstoneNoLocalMatch)

    private operator fun PullReport.plus(other: PullReport) = PullReport(
        inserted + other.inserted,
        updated + other.updated,
        skippedLocalNewer + other.skippedLocalNewer,
        tombstoned + other.tombstoned,
        skippedTombstoneNoLocalMatch + other.skippedTombstoneNoLocalMatch,
    )

    suspend fun pull(context: Context, backend: LastAspectsBackend): PullReport {
        var total = PullReport(0, 0, 0, 0, 0)
        total += pullGoals(context, backend)
        return total
    }

    private const val T_GOALS = "goals"

    private suspend fun pullGoals(context: Context, backend: LastAspectsBackend): PullReport {
        val db = CarDatabase.getDatabase(context)
        val sinceMs = LastAspectsPullCursor.lastPulledAtMs(context, T_GOALS)
        val remote = backend.fetchChangedGoalsSince(sinceMs).getOrThrow()
        val local = db.goalDao().getAllIncludingDeleted()
        val localBySyncId = local.associateBy { it.syncId }
        val report = LastAspectsMerge.merge(
            remoteRows = remote,
            localRows = local,
            remoteGuid = { it.originGuid },
            localGuid = { it.syncId },
            remoteDeleted = { it.deleted },
            remoteUpdatedAtMs = { it.updatedAtMs },
            localUpdatedAtMs = { it.updatedAt },
            localDeleted = { false }, // goals has no delete counterpart today (see Goal's class doc); a tombstone still applies as "deleted" below if one ever arrives.
            toInserted = { r ->
                Goal(
                    lineageId = r.lineageId,
                    aspect = r.aspect,
                    statement = r.statement,
                    targetValue = r.targetValue,
                    unit = r.unit,
                    metricKey = r.metricKey,
                    deadlineEpoch = r.deadlineEpoch,
                    status = r.status,
                    supersedesId = r.supersedesGuid?.let { g -> localBySyncId[g]?.id },
                    closedAt = r.closedAt,
                    createdAt = r.createdAt,
                    updatedAt = r.updatedAtMs,
                    syncId = r.originGuid,
                    serverId = r.serverId,
                    deleted = r.deleted,
                )
            },
            toMerged = { r, existing ->
                existing.copy(
                    aspect = r.aspect,
                    statement = r.statement,
                    targetValue = r.targetValue,
                    unit = r.unit,
                    metricKey = r.metricKey,
                    deadlineEpoch = r.deadlineEpoch,
                    status = r.status,
                    supersedesId = r.supersedesGuid?.let { g -> localBySyncId[g]?.id } ?: existing.supersedesId,
                    closedAt = r.closedAt,
                    updatedAt = r.updatedAtMs,
                    serverId = r.serverId,
                    deleted = r.deleted,
                )
            },
            withDeletedFlag = { existing, atMs -> existing.copy(deleted = true, updatedAt = atMs) },
            insert = { db.goalDao().insert(it) },
            update = { db.goalDao().update(it) },
        )
        remote.maxOfOrNull { it.updatedAtMs }?.let { LastAspectsPullCursor.advance(context, T_GOALS, it) }
        return report.toPullReport()
    }

    // --- Foreground auto-trigger, mirroring LedgerConfigSync's own shape -----------------------

    private val autoPullScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var lastAutoPullAt = 0L
    private const val AUTO_PULL_MIN_INTERVAL_MS = 5 * 60 * 1000L
    private const val AUTO_PULL_RETRY_DELAY_MS = 1_000L

    internal suspend fun resolveUserIdForAutoPull(auth: SupabaseAuth, retryDelayMs: Long = AUTO_PULL_RETRY_DELAY_MS): String? =
        auth.resolveSignedInUserId(retryDelayMs)

    /** `MainActivity.onResume`'s hook. No-ops silently when Supabase is not configured or nobody
     * is signed in. */
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
                val report = pull(app, SupabaseLastAspectsBackend(client))
                MidnightEvents.lastAspectsAutoPullSucceeded(report.inserted, report.updated, report.skippedLocalNewer, report.tombstoned, report.skippedTombstoneNoLocalMatch)
            } catch (e: Exception) {
                MidnightEvents.lastAspectsAutoPullFailed(e)
            }
        }
    }
}
