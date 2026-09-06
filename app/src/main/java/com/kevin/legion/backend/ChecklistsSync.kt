package com.kevin.legion.backend

import android.content.Context
import com.kevin.legion.MidnightEvents
import com.kevin.legion.data.local.CarDatabase
import com.kevin.legion.data.local.Checklist
import com.kevin.legion.data.local.ChecklistItem
import com.kevin.legion.data.local.ChecklistTick
import com.kevin.legion.backend.engine.EngineBackends
import com.kevin.legion.backend.engine.guardingForeground
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * Install-scoped high-water mark for [ChecklistsSync.pull] - **the engine's own `server_time`,
 * stored verbatim as the ISO string it arrived as**, never a millisecond value derived from the
 * rows that came back.
 *
 * Two reasons, both the server's own:
 * 1. `api/changes.py` captures `server_time` BEFORE any query runs and says why in words - "a row
 *    committed the same instant this request is being served is never silently skipped by a
 *    client-computed max(updated_at) that ran a moment too early." Deriving a watermark here would
 *    reintroduce exactly that.
 * 2. Django stamps microseconds. Round-tripping through epoch millis would truncate, and a
 *    truncated watermark is either three digits too early (harmless re-fetch) or, if rounded, three
 *    digits too late - a row skipped forever. Keeping the string sidesteps the choice.
 *
 * **A missing watermark means "fetch everything", never "fetch nothing"** - [lastPulledAt] returns
 * null on a fresh install, [ChecklistsBackend.fetchChanges] omits the parameter entirely, and
 * `api/sync.parse_since` reads an absent `since` as EPOCH. There is no "never pulled" sentinel to
 * get wrong, exactly as [EventsPullCursor]'s own doc comment establishes for its side.
 */
internal object ChecklistsPullCursor {
    private const val PREFS = "checklists_pull_cursor"
    private const val KEY_SERVER_TIME = "last_server_time"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun lastPulledAt(context: Context): String? = prefs(context).getString(KEY_SERVER_TIME, null)

    /** Persisted only after [ChecklistsSync.pull] has merged every row in the batch - advancing on
     * a partial run would skip whatever the failure interrupted. */
    fun advance(context: Context, serverTime: String) {
        prefs(context).edit().putString(KEY_SERVER_TIME, serverTime).apply()
    }
}

/**
 * The generic merge one checklist table gets - **a deliberate copy of [LastAspectsMerge.merge],
 * which is itself a deliberate copy of `LedgerConfigMerge.merge`**, and the copying is the
 * convention, not laziness: see [LastAspectsMerge]'s own class doc ("never depend on another
 * aspect's internals"). The five rules below are [EventsSync.pull]'s, reproduced verbatim in
 * behaviour, because this ticket's brief requires exactly that reuse and forbids touching
 * `EventsSync` itself:
 *
 * 1. A server row with no local match at all is INSERTED.
 * 2. A server row whose `updated_at` is at least as new as the local row's overwrites it
 *    (last-write-wins, the server winning an exact tie), and an unchanged merge writes nothing.
 * 3. A local row strictly newer than the server's is left completely alone.
 * 4. A server tombstone WITH a local match soft-deletes locally; one with NO local match is
 *    inserted nowhere and counted separately (the 88-row bug [EventsSync.PullReport] names).
 * 5. **By omission, and it is the most important line here: nothing ever iterates the local rows
 *    looking for something to delete.** A local row the server does not have is never visited.
 */
internal object ChecklistsMerge {
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

    /** [match] resolves a remote row to the local row that IS it, or null - each table answers
     * that question differently (checklists and items by `syncId` then `serverId`, ticks by
     * `(item, day)`, since the tick endpoint carries no `sync_id` at all), so the lookup is the
     * caller's business and only the five rules live here. */
    suspend fun <R, L> merge(
        remoteRows: List<R>,
        rules: MergeRules<R, L>,
    ): MergeReport {
        var report = MergeReport()
        for (remote in remoteRows) {
            report += mergeOne(remote, rules)
        }
        // Rule 5, by omission - nothing above ever iterates the local rows looking for something
        // to delete. A local row the engine does not have is never visited at all.
        return report
    }

    /** One remote row's whole decision, as its own delta - the five rules read top to bottom here.
     * Split out of [merge]'s loop rather than written inline: the four branches each ended in a
     * `continue`, which is both harder to read and what detekt's `LoopWithTooManyJumpStatements`
     * objects to. The behaviour is unchanged, branch for branch. */
    private suspend fun <R, L> mergeOne(remote: R, rules: MergeRules<R, L>): MergeReport {
        val local = rules.match(remote)
        val remoteDeleted = rules.remoteDeleted(remote)
        return when {
            // A tombstone with nothing local to mark is inserted nowhere - it is not an insert and
            // not a tombstone-applied. Checked FIRST so it never falls into the plain no-match
            // branch below and gets written as a brand-new, already-dead local row (the 88-row bug
            // EventsSync.pull names by hand).
            local == null && remoteDeleted -> MergeReport(skippedTombstoneNoLocalMatch = 1)
            local == null -> {
                rules.insert(rules.toInserted(remote))
                MergeReport(inserted = 1)
            }
            // Honour a tombstone with a local SOFT delete, and only once: a row already deleted
            // locally is left untouched rather than written again with identical values.
            remoteDeleted && rules.localDeleted(local) -> MergeReport()
            remoteDeleted -> {
                rules.update(rules.withDeletedFlag(local, rules.remoteUpdatedAtMs(remote)))
                MergeReport(tombstoned = 1)
            }
            // Last-write-wins. A local row STRICTLY newer than the engine's is left alone; an
            // exactly-equal timestamp falls through to the branch below and resolves toward the
            // SERVER, the shared destination every device converges on.
            rules.remoteUpdatedAtMs(remote) < rules.localUpdatedAtMs(local) ->
                MergeReport(skippedLocalNewer = 1)
            else -> applyServerVersion(remote, local, rules)
        }
    }

    /** The server-wins branch of [mergeOne], split out only so that function has one exit rather
     * than six. **Idempotency lives here:** a second consecutive pull of the same server state
     * produces a byte-identical row, which writes nothing and counts nothing - so a repeat pull is
     * a genuine no-op on disk as well as in the report. */
    private suspend fun <R, L> applyServerVersion(remote: R, local: L, rules: MergeRules<R, L>): MergeReport {
        val merged = rules.toMerged(remote, local)
        if (merged == local) return MergeReport()
        rules.update(merged)
        return MergeReport(updated = 1)
    }

    /** The per-table half of [merge], bundled into one object because thirteen loose lambda
     * parameters is what [LastAspectsMerge.merge]'s signature already is and detekt's
     * `LongParameterList` (5) rightly refuses it on anything written since. */
    class MergeRules<R, L>(
        val match: (R) -> L?,
        val remoteDeleted: (R) -> Boolean,
        val remoteUpdatedAtMs: (R) -> Long,
        val localUpdatedAtMs: (L) -> Long,
        val localDeleted: (L) -> Boolean,
        val toInserted: (R) -> L,
        val toMerged: (R, L) -> L,
        val withDeletedFlag: (L, Long) -> L,
        val insert: suspend (L) -> Unit,
        val update: suspend (L) -> Unit,
    )
}

/**
 * The first live sync `checklists`/`checklist_items`/`checklist_ticks` have ever had
 * (`.scratch/django-engine/research/execution-plan.md` Phase 2 step 4). One `GET /api/changes`
 * round trip brings all three tables back together; [ChecklistsMerge] applies the same five rules
 * [EventsSync.pull] does.
 *
 * **The three tables are merged in dependency order - checklists, then items, then ticks - and the
 * order is load-bearing twice over.** An item's parent is addressed by the parent's SERVER uuid
 * (`ChecklistItemSerializer` emits `checklist` as that uuid, not as a `sync_id`), and a tick's
 * item likewise, so each pass resolves its parent against a local table the PREVIOUS pass has
 * already brought up to date - including rows it inserted a moment ago. A child whose parent still
 * cannot be found after that is SKIPPED and counted
 * ([PullReport.skippedOrphanedItem]/[PullReport.skippedOrphanedTick]), never guessed at and never
 * silently dropped, the same posture [LastAspectsSync.pullListItems] takes for the identical shape.
 *
 * **A local `createdAt` is never overwritten by the engine's.** `ChecklistSerializer` makes
 * `created_at` read-only, so the engine's value for any row this phone pushed is the UPLOAD
 * instant, not the checklist's real birthday - and `ChecklistController`'s "trap 1" gate reads
 * exactly that column to decide which days may show history. Letting the merge copy it back would
 * silently erase a checklist's own past. See [RemoteChecklist]'s own doc comment for the residual
 * gap this does not close (a second device inserting the row for the first time has nothing truer
 * to use).
 */
object ChecklistsSync {

    data class PullReport(
        val inserted: Int,
        val updated: Int,
        val skippedLocalNewer: Int,
        val tombstoned: Int,
        val skippedTombstoneNoLocalMatch: Int,
        /** Server items whose parent checklist could not be resolved locally - see this object's
         * own class doc. */
        val skippedOrphanedItem: Int = 0,
        /** Server ticks whose item could not be resolved locally. */
        val skippedOrphanedTick: Int = 0,
    )

    private fun ChecklistsMerge.MergeReport.toPullReport() =
        PullReport(inserted, updated, skippedLocalNewer, tombstoned, skippedTombstoneNoLocalMatch)

    private operator fun PullReport.plus(other: PullReport) = PullReport(
        inserted + other.inserted,
        updated + other.updated,
        skippedLocalNewer + other.skippedLocalNewer,
        tombstoned + other.tombstoned,
        skippedTombstoneNoLocalMatch + other.skippedTombstoneNoLocalMatch,
        skippedOrphanedItem + other.skippedOrphanedItem,
        skippedOrphanedTick + other.skippedOrphanedTick,
    )

    suspend fun pull(context: Context, backend: ChecklistsBackend): PullReport {
        val changes = backend.fetchChanges(ChecklistsPullCursor.lastPulledAt(context)).getOrThrow()
        var total = PullReport(0, 0, 0, 0, 0)
        total += mergeChecklists(context, changes.checklists)
        total += mergeItems(context, changes.items)
        total += mergeTicks(context, changes.ticks)
        // Advanced only now, with every row merged - see ChecklistsPullCursor's own doc comment
        // for why the value is the engine's own server_time and not a max() over these rows.
        ChecklistsPullCursor.advance(context, changes.serverTime)
        return total
    }

    private suspend fun mergeChecklists(context: Context, remote: List<RemoteChecklist>): PullReport {
        val dao = CarDatabase.getDatabase(context).checklistSyncDao()
        val local = dao.getAll()
        // syncId first, serverId second - the same matching ORDER (and the same reason for it)
        // EventsSync.pull states for guid-before-serverId: syncId is the identity this device
        // minted and carries forward, serverId is only real after one round trip. A server row
        // with a null sync_id (created in the PWA) can only ever match by serverId.
        val bySyncId = local.filter { it.syncId.isNotBlank() }.associateBy { it.syncId }
        val byServerId = local.mapNotNull { row -> row.serverId?.let { it to row } }.toMap()
        return ChecklistsMerge.merge(
            remote,
            ChecklistsMerge.MergeRules(
                match = { r -> r.syncId?.let { bySyncId[it] } ?: byServerId[r.serverId] },
                remoteDeleted = { it.deleted },
                remoteUpdatedAtMs = { it.updatedAtMs },
                localUpdatedAtMs = { it.updatedAt },
                localDeleted = { it.deleted },
                toInserted = { r ->
                    Checklist(
                        name = r.name,
                        sortOrder = r.sortOrder,
                        // The engine's created_at is the only value available for a row this
                        // device has never held - see this object's own class doc for why it is
                        // NOT applied on the update path.
                        createdAt = r.createdAtMs,
                        archived = r.archived,
                        updatedAt = r.updatedAtMs,
                        syncId = r.syncId?.ifBlank { null } ?: UUID.randomUUID().toString(),
                        serverId = r.serverId,
                        deleted = r.deleted,
                        scheduleKind = r.scheduleKind,
                        scheduleEvery = r.scheduleEvery,
                        scheduleDaysOfWeek = r.scheduleDaysOfWeek,
                    )
                },
                toMerged = { r, existing ->
                    existing.copy(
                        name = r.name,
                        sortOrder = r.sortOrder,
                        archived = r.archived,
                        updatedAt = r.updatedAtMs,
                        serverId = r.serverId,
                        deleted = r.deleted,
                        scheduleKind = r.scheduleKind,
                        scheduleEvery = r.scheduleEvery,
                        scheduleDaysOfWeek = r.scheduleDaysOfWeek,
                    )
                },
                withDeletedFlag = { existing, atMs -> existing.copy(deleted = true, updatedAt = atMs) },
                insert = { dao.insert(it) },
                update = { dao.update(it) },
            ),
        ).toPullReport()
    }

    private suspend fun mergeItems(context: Context, remote: List<RemoteChecklistItem>): PullReport {
        val db = CarDatabase.getDatabase(context)
        val dao = db.checklistItemSyncDao()
        // Read AFTER mergeChecklists ran, so a parent that arrived in this same batch is already
        // here to be found.
        val parentsByServerId = db.checklistSyncDao().getAll()
            .mapNotNull { row -> row.serverId?.let { it to row } }.toMap()
        val (resolvable, orphaned) = remote.partition { parentsByServerId.containsKey(it.checklistServerId) }
        val local = dao.getAll()
        val bySyncId = local.filter { it.syncId.isNotBlank() }.associateBy { it.syncId }
        val byServerId = local.mapNotNull { row -> row.serverId?.let { it to row } }.toMap()
        return ChecklistsMerge.merge(
            resolvable,
            ChecklistsMerge.MergeRules(
                match = { r -> r.syncId?.let { bySyncId[it] } ?: byServerId[r.serverId] },
                remoteDeleted = { it.deleted },
                remoteUpdatedAtMs = { it.updatedAtMs },
                localUpdatedAtMs = { it.updatedAt },
                localDeleted = { it.deleted },
                toInserted = { r ->
                    ChecklistItem(
                        checklistId = parentsByServerId.getValue(r.checklistServerId).id,
                        text = r.text,
                        sortOrder = r.sortOrder,
                        createdAt = r.createdAtMs,
                        updatedAt = r.updatedAtMs,
                        syncId = r.syncId?.ifBlank { null } ?: UUID.randomUUID().toString(),
                        serverId = r.serverId,
                        deleted = r.deleted,
                        measureUnit = r.measureUnit,
                        measureTarget = r.measureTarget,
                        measureDirection = r.measureDirection,
                    )
                },
                toMerged = { r, existing ->
                    existing.copy(
                        checklistId = parentsByServerId.getValue(r.checklistServerId).id,
                        text = r.text,
                        sortOrder = r.sortOrder,
                        updatedAt = r.updatedAtMs,
                        serverId = r.serverId,
                        deleted = r.deleted,
                        measureUnit = r.measureUnit,
                        measureTarget = r.measureTarget,
                        measureDirection = r.measureDirection,
                    )
                },
                withDeletedFlag = { existing, atMs -> existing.copy(deleted = true, updatedAt = atMs) },
                insert = { dao.insert(it) },
                update = { dao.update(it) },
            ),
        ).toPullReport().copy(skippedOrphanedItem = orphaned.size)
    }

    /**
     * Ticks match on `(local item id, day)`, NOT on `syncId` - see [RemoteChecklistTick]'s own doc
     * comment: `TickRequestSerializer` has no `sync_id` field at all, so a phone-minted tick
     * identity never reaches the engine and a server tick's `sync_id` is null for anything this
     * app wrote. `(item, day)` is unique on both sides and is the identity that actually exists.
     */
    private suspend fun mergeTicks(context: Context, remote: List<RemoteChecklistTick>): PullReport {
        val db = CarDatabase.getDatabase(context)
        val dao = db.checklistTickSyncDao()
        val itemsByServerId = db.checklistItemSyncDao().getAll()
            .mapNotNull { row -> row.serverId?.let { it to row } }.toMap()
        val (resolvable, orphaned) = remote.partition { itemsByServerId.containsKey(it.itemServerId) }
        val localByItemDay = dao.getAll().associateBy { it.itemId to it.day }
        return ChecklistsMerge.merge(
            resolvable,
            ChecklistsMerge.MergeRules(
                match = { r -> localByItemDay[itemsByServerId.getValue(r.itemServerId).id to r.day] },
                remoteDeleted = { it.deleted },
                remoteUpdatedAtMs = { it.updatedAtMs },
                localUpdatedAtMs = { it.updatedAt },
                localDeleted = { it.deleted },
                toInserted = { r ->
                    ChecklistTick(
                        itemId = itemsByServerId.getValue(r.itemServerId).id,
                        day = r.day,
                        tickedAt = r.tickedAtMs,
                        updatedAt = r.updatedAtMs,
                        syncId = r.syncId?.ifBlank { null } ?: UUID.randomUUID().toString(),
                        serverId = r.serverId,
                        deleted = r.deleted,
                        value = r.value,
                        source = r.source,
                    )
                },
                toMerged = { r, existing ->
                    existing.copy(
                        tickedAt = r.tickedAtMs,
                        updatedAt = r.updatedAtMs,
                        serverId = r.serverId,
                        deleted = r.deleted,
                        value = r.value,
                        source = r.source,
                    )
                },
                withDeletedFlag = { existing, atMs -> existing.copy(deleted = true, updatedAt = atMs) },
                insert = { dao.insert(it) },
                update = { dao.update(it) },
            ),
        ).toPullReport().copy(skippedOrphanedTick = orphaned.size)
    }

    // --- Foreground auto-trigger, mirroring LastAspectsSync's own shape ------------------------

    private val autoPullScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile private var lastAutoPullAt = 0L
    private const val AUTO_PULL_MIN_INTERVAL_MS = 5 * 60 * 1000L

    /**
     * `MainActivity.onResume`'s hook. No-ops silently, with a logged breadcrumb rather than a
     * dialog, when checklists are not on the Django transport or this device is not signed in to
     * the engine - both ordinary states (the transport toggle ships defaulted to Supabase, and
     * checklists have no Supabase backend at all, so an untouched install syncs nothing here and
     * behaves exactly as it did before this file existed).
     *
     * The throttle slot is reserved SYNCHRONOUSLY, before any suspending work, for the same reason
     * [EventsSync.maybeAutoPull]'s own doc comment gives: a second `onResume` arriving mid-pull
     * must not launch a second one.
     */
    fun maybeAutoPull(context: Context) {
        val now = System.currentTimeMillis()
        if (now - lastAutoPullAt < AUTO_PULL_MIN_INTERVAL_MS) return
        val app = context.applicationContext
        val backend = EngineBackends(app).checklistsBackend() ?: return
        lastAutoPullAt = now
        autoPullScope.launch {
            guardingForeground(onFailure = { MidnightEvents.checklistsAutoPullFailed(it) }) {
                val report = pull(app, backend)
                MidnightEvents.checklistsAutoPullSucceeded(
                    report.inserted,
                    report.updated,
                    report.skippedLocalNewer,
                    report.tombstoned,
                    report.skippedOrphanedItem + report.skippedOrphanedTick,
                )
            }
        }
    }
}
