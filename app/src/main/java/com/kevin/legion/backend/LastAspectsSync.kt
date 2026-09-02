package com.kevin.legion.backend

import android.content.Context
import com.kevin.legion.MidnightEvents
import com.kevin.legion.data.local.CarDatabase
import com.kevin.legion.data.local.Goal
import com.kevin.legion.data.local.GroceryStaple
import com.kevin.legion.data.local.ItemList
import com.kevin.legion.data.local.ListItem
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
 * live-sync's last aspect slice's live pull - four independent sub-pulls, one per table, each
 * following [LastAspectsMerge.merge]'s rules exactly. Mirrors [LedgerConfigSync]'s own shape.
 *
 * **`item_lists` is pulled and fully merged BEFORE `list_items`** - a `list_items` row's parent is
 * addressed by the parent's own `syncId` (see [LastAspectsBackend]'s own class doc), and this
 * ordering guarantees that parent already exists locally (freshly inserted by this same call, if
 * it didn't already) by the time a child item is resolved. A child item whose parent genuinely
 * cannot be found locally even after that (a defensive case that should not occur against a
 * well-formed server) is skipped rather than guessed at - see [pullListItems]'s own comment.
 */
object LastAspectsSync {
    data class PullReport(
        val inserted: Int,
        val updated: Int,
        val skippedLocalNewer: Int,
        val tombstoned: Int,
        val skippedTombstoneNoLocalMatch: Int,
        /** `list_items` rows whose `listSyncId` matched no local [ItemList] - see [pullListItems]'s
         * own comment. Always 0 for every other table. */
        val skippedOrphanedListItem: Int = 0,
    )

    private fun LastAspectsMerge.MergeReport.toPullReport() = PullReport(inserted, updated, skippedLocalNewer, tombstoned, skippedTombstoneNoLocalMatch)

    private operator fun PullReport.plus(other: PullReport) = PullReport(
        inserted + other.inserted,
        updated + other.updated,
        skippedLocalNewer + other.skippedLocalNewer,
        tombstoned + other.tombstoned,
        skippedTombstoneNoLocalMatch + other.skippedTombstoneNoLocalMatch,
        skippedOrphanedListItem + other.skippedOrphanedListItem,
    )

    suspend fun pull(context: Context, backend: LastAspectsBackend): PullReport {
        var total = PullReport(0, 0, 0, 0, 0)
        total += pullGoals(context, backend)
        total += pullGroceryStaples(context, backend)
        total += pullItemLists(context, backend)
        total += pullListItems(context, backend)
        return total
    }

    private const val T_GOALS = "goals"
    private const val T_GROCERY_STAPLES = "grocery_staples"
    private const val T_ITEM_LISTS = "item_lists"
    private const val T_LIST_ITEMS = "list_items"

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

    private suspend fun pullGroceryStaples(context: Context, backend: LastAspectsBackend): PullReport {
        val db = CarDatabase.getDatabase(context)
        val sinceMs = LastAspectsPullCursor.lastPulledAtMs(context, T_GROCERY_STAPLES)
        val remote = backend.fetchChangedGroceryStaplesSince(sinceMs).getOrThrow()
        val local = db.groceryStapleDao().getAllIncludingDeleted()
        val report = LastAspectsMerge.merge(
            remoteRows = remote,
            localRows = local,
            remoteGuid = { it.originGuid },
            localGuid = { it.syncId },
            remoteDeleted = { it.deleted },
            remoteUpdatedAtMs = { it.updatedAtMs },
            localUpdatedAtMs = { it.updatedAtMs },
            localDeleted = { it.deleted },
            toInserted = { r ->
                GroceryStaple(
                    name = r.name,
                    displayName = r.displayName,
                    timesBought = r.timesBought,
                    lastBoughtAt = r.lastBoughtAt,
                    syncId = r.originGuid,
                    serverId = r.serverId,
                    updatedAtMs = r.updatedAtMs,
                    deleted = r.deleted,
                )
            },
            toMerged = { r, existing ->
                existing.copy(
                    displayName = r.displayName,
                    timesBought = r.timesBought,
                    lastBoughtAt = r.lastBoughtAt,
                    serverId = r.serverId,
                    updatedAtMs = r.updatedAtMs,
                    deleted = r.deleted,
                )
            },
            withDeletedFlag = { existing, atMs -> existing.copy(deleted = true, updatedAtMs = atMs) },
            // GroceryStapleDao has no bare insert, only `upsert` (REPLACE on the `name` PK) - safe
            // here for the identical reason [LedgerConfigSync.pullBudgetTargets]'s own comment
            // gives: a genuinely new remote row cannot conflict with anything local by definition.
            insert = { db.groceryStapleDao().upsert(it) },
            update = { db.groceryStapleDao().upsert(it) },
        )
        remote.maxOfOrNull { it.updatedAtMs }?.let { LastAspectsPullCursor.advance(context, T_GROCERY_STAPLES, it) }
        return report.toPullReport()
    }

    private suspend fun pullItemLists(context: Context, backend: LastAspectsBackend): PullReport {
        val db = CarDatabase.getDatabase(context)
        val sinceMs = LastAspectsPullCursor.lastPulledAtMs(context, T_ITEM_LISTS)
        val remote = backend.fetchChangedItemListsSince(sinceMs).getOrThrow()
        val local = db.itemListDao().getAllIncludingDeleted()
        val report = LastAspectsMerge.merge(
            remoteRows = remote,
            localRows = local,
            remoteGuid = { it.originGuid },
            localGuid = { it.syncId },
            remoteDeleted = { it.deleted },
            remoteUpdatedAtMs = { it.updatedAtMs },
            localUpdatedAtMs = { it.updatedAt },
            localDeleted = { it.deleted },
            toInserted = { r ->
                ItemList(
                    name = r.name,
                    tickable = r.tickable,
                    sortOrder = r.sortOrder,
                    lastUsedAt = r.lastUsedAt,
                    archived = r.archived,
                    createdAt = r.createdAt,
                    updatedAt = r.updatedAtMs,
                    syncId = r.originGuid,
                    serverId = r.serverId,
                    deleted = r.deleted,
                )
            },
            toMerged = { r, existing ->
                existing.copy(
                    name = r.name,
                    tickable = r.tickable,
                    sortOrder = r.sortOrder,
                    lastUsedAt = r.lastUsedAt,
                    archived = r.archived,
                    updatedAt = r.updatedAtMs,
                    serverId = r.serverId,
                    deleted = r.deleted,
                )
            },
            withDeletedFlag = { existing, atMs -> existing.copy(deleted = true, updatedAt = atMs) },
            insert = { db.itemListDao().insert(it) },
            update = { db.itemListDao().update(it) },
        )
        remote.maxOfOrNull { it.updatedAtMs }?.let { LastAspectsPullCursor.advance(context, T_ITEM_LISTS, it) }
        return report.toPullReport()
    }

    /**
     * `list_items` resolves each remote row's [RemoteListItem.listSyncId] back to a local `listId`
     * by looking the parent list up by ITS OWN `syncId` in the SAME snapshot [pullItemLists] just
     * merged - see [LastAspectsBackend]'s own class doc for why the wire format never carries a
     * raw local id. A remote item whose parent cannot be found locally (a malformed or
     * out-of-order server state that should not occur in practice, since `item_lists` is always
     * merged first in the same [pull] call) is SKIPPED, not guessed at - counted in
     * [PullReport.skippedOrphanedListItem] rather than silently dropped with no trace, the same
     * "an absence must be visible somewhere" posture the map's own §"what was actually wrong"
     * section describes for the events wipe this whole effort exists to avoid repeating.
     */
    private suspend fun pullListItems(context: Context, backend: LastAspectsBackend): PullReport {
        val db = CarDatabase.getDatabase(context)
        val sinceMs = LastAspectsPullCursor.lastPulledAtMs(context, T_LIST_ITEMS)
        val remote = backend.fetchChangedListItemsSince(sinceMs).getOrThrow()
        val listsBySyncId = db.itemListDao().getAllIncludingDeleted().associateBy { it.syncId }
        val (resolvable, orphaned) = remote.partition { listsBySyncId.containsKey(it.listSyncId) }
        val local = db.listItemDao().getAllIncludingDeleted()
        val report = LastAspectsMerge.merge(
            remoteRows = resolvable,
            localRows = local,
            remoteGuid = { it.originGuid },
            localGuid = { it.syncId },
            remoteDeleted = { it.deleted },
            remoteUpdatedAtMs = { it.updatedAtMs },
            localUpdatedAtMs = { it.updatedAt },
            localDeleted = { it.deleted },
            toInserted = { r ->
                ListItem(
                    listId = listsBySyncId.getValue(r.listSyncId).id,
                    text = r.text,
                    done = r.done,
                    doneAt = r.doneAt,
                    sortOrder = r.sortOrder,
                    createdAt = r.createdAt,
                    updatedAt = r.updatedAtMs,
                    syncId = r.originGuid,
                    serverId = r.serverId,
                    deleted = r.deleted,
                    startsAt = r.startsAt,
                    endsAt = r.endsAt,
                    allDay = r.allDay,
                    triggerPlaceLabel = r.triggerPlaceLabel,
                    repeatKind = r.repeatKind,
                    repeatEvery = r.repeatEvery,
                    repeatDaysOfWeek = r.repeatDaysOfWeek,
                    repeatDay = r.repeatDay,
                    repeatMonth = r.repeatMonth,
                    repeatEndKind = r.repeatEndKind,
                    repeatEndDate = r.repeatEndDate,
                    repeatEndCount = r.repeatEndCount,
                    exact = r.exact,
                    exactDowngraded = r.exactDowngraded,
                    missedAt = r.missedAt,
                    missedDismissedAt = r.missedDismissedAt,
                    loggedAt = r.loggedAt,
                )
            },
            toMerged = { r, existing ->
                existing.copy(
                    listId = listsBySyncId.getValue(r.listSyncId).id,
                    text = r.text,
                    done = r.done,
                    doneAt = r.doneAt,
                    sortOrder = r.sortOrder,
                    updatedAt = r.updatedAtMs,
                    serverId = r.serverId,
                    deleted = r.deleted,
                    startsAt = r.startsAt,
                    endsAt = r.endsAt,
                    allDay = r.allDay,
                    triggerPlaceLabel = r.triggerPlaceLabel,
                    repeatKind = r.repeatKind,
                    repeatEvery = r.repeatEvery,
                    repeatDaysOfWeek = r.repeatDaysOfWeek,
                    repeatDay = r.repeatDay,
                    repeatMonth = r.repeatMonth,
                    repeatEndKind = r.repeatEndKind,
                    repeatEndDate = r.repeatEndDate,
                    repeatEndCount = r.repeatEndCount,
                    exact = r.exact,
                    exactDowngraded = r.exactDowngraded,
                    missedAt = r.missedAt,
                    missedDismissedAt = r.missedDismissedAt,
                    loggedAt = r.loggedAt,
                )
            },
            withDeletedFlag = { existing, atMs -> existing.copy(deleted = true, updatedAt = atMs) },
            insert = { db.listItemDao().insert(it) },
            update = { db.listItemDao().update(it) },
        )
        remote.maxOfOrNull { it.updatedAtMs }?.let { LastAspectsPullCursor.advance(context, T_LIST_ITEMS, it) }
        return report.toPullReport().copy(skippedOrphanedListItem = orphaned.size)
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
