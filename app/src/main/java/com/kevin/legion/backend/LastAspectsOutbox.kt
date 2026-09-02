package com.kevin.legion.backend

import android.content.Context
import com.kevin.legion.MidnightEvents
import com.kevin.legion.data.local.CarDatabase
import com.kevin.legion.data.local.Goal
import com.kevin.legion.data.local.GroceryStaple
import com.kevin.legion.data.local.ItemList
import com.kevin.legion.data.local.ListItem
import com.kevin.legion.data.local.OutboxEntry
import com.kevin.legion.data.local.OutboxOperation
import com.kevin.legion.data.local.OutboxTarget
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * live-sync's last aspect slice - the push half: write-through on the write sites this ticket
 * found, plus the durable outbox that makes an offline write survive - mirrors
 * [LedgerConfigWriteThrough]/[LedgerConfigOutboxDrain]'s own shape exactly.
 *
 * **Honest accounting of which write sites this file actually routes, per CLAUDE.md's own
 * instruction to state this plainly:**
 * - [addGoal]/[closeGoal] ARE wired - [com.kevin.legion.goals.GoalController.setGoal]/
 *   `.closeByLineage` call through here now (see that controller's own updated doc comment).
 * - [upsertStaple]/[forgetStaple] ARE wired - [com.kevin.legion.grocery.GroceryController]'s
 *   `completeTrip`/`forgetStaple` call through here now.
 * - [addItemList]/[addListItem] exist for interface completeness (every synced table gets an
 *   insert path someone could call) but have **no live caller today**. `notes/NotesController.kt`'s
 *   own class doc records that this table's read/write path was repointed onto `events` entirely
 *   (backend-erp ticket 15 step 4) BEFORE this ticket existed - there is no live code path left
 *   that creates a NEW `item_lists`/`list_items` row for these functions to intercept. Every
 *   individual DAO mutator ([com.kevin.legion.data.local.ListItemDao.markDone]/`.setTime`/etc.) is
 *   in the same position and is deliberately NOT wrapped here - fifteen wrapper functions with zero
 *   callers would be speculative plumbing, not a route this ticket found. [LastAspectsBackfill]
 *   still gives every EXISTING row (71 + 5) a server home; [LastAspectsSync]'s pull still keeps
 *   them live if a future write path is repointed onto this table again.
 *
 * **Local write always happens first, unconditionally** - same posture as
 * [LedgerConfigWriteThrough]'s own class doc.
 */
object LastAspectsWriteThrough {
    /** Test seam, same mechanism as [LedgerConfigWriteThrough.backendOverride]. */
    @Volatile
    internal var backendOverride: LastAspectsBackend? = null

    private fun backend(context: Context): LastAspectsBackend? {
        backendOverride?.let { return it }
        val client = SupabaseClientProvider.get(context) ?: return null
        return SupabaseLastAspectsBackend(client)
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

    // --- Goals -------------------------------------------------------------------------------------

    @Serializable
    internal data class GoalPayload(
        val syncId: String,
        val lineageId: Long,
        val aspect: String,
        val statement: String,
        val targetValue: Double?,
        val unit: String?,
        val metricKey: String?,
        val deadlineEpoch: Long?,
        val status: String,
        val supersedesGuid: String?,
        val closedAt: Long?,
        val createdAt: Long,
    ) {
        fun toFields() = GoalFields(lineageId, aspect, statement, targetValue, unit, metricKey, deadlineEpoch, status, supersedesGuid, closedAt, createdAt)
    }

    private suspend fun goalPayload(db: CarDatabase, row: Goal): GoalPayload {
        // supersedesId is a local autoincrement surrogate with no portable meaning across devices
        // (same reasoning as [RemoteListItem.listSyncId]'s own doc comment) - resolved to the prior
        // revision's own syncId here, at push time, rather than stored on the entity itself.
        val supersedesGuid = row.supersedesId?.let { priorId ->
            db.goalDao().history(row.lineageId).firstOrNull { it.id == priorId }?.syncId
        }
        return GoalPayload(row.syncId, row.lineageId, row.aspect, row.statement, row.targetValue, row.unit, row.metricKey, row.deadlineEpoch, row.status, supersedesGuid, row.closedAt, row.createdAt)
    }

    /** [com.kevin.legion.goals.GoalController.setGoal]'s write - inserts locally first
     * (unconditionally), returns the row with its real id, then pushes if configured, enqueueing
     * on failure. */
    suspend fun addGoal(context: Context, row: Goal): Goal {
        val db = CarDatabase.getDatabase(context)
        val id = db.goalDao().insert(row)
        val stored = row.copy(id = id)
        val backend = backend(context) ?: return stored
        val payload = goalPayload(db, stored)
        val result = backend.upsertGoal(stored.syncId, payload.toFields())
        if (result.isFailure) {
            enqueue(db, OutboxTarget.GOALS, OutboxOperation.UPSERT, stored.id, Json.encodeToString(GoalPayload.serializer(), payload), result.exceptionOrNull()?.message)
        }
        return stored
    }

    /** [com.kevin.legion.goals.GoalController.closeByLineage]'s write - the DAO's own in-place
     * UPDATE runs first (see [com.kevin.legion.data.local.GoalDao.close]'s own doc comment for why
     * it now also bumps `updatedAt`), then the freshly-closed CURRENT row is re-read and pushed as
     * a whole-row upsert, same "push what changed" shape [addGoal] uses. */
    suspend fun closeGoal(context: Context, lineageId: Long, status: String, closedAt: Long) {
        val db = CarDatabase.getDatabase(context)
        db.goalDao().close(lineageId, status, closedAt)
        val current = db.goalDao().history(lineageId).maxByOrNull { it.id } ?: return
        val backend = backend(context) ?: return
        val payload = goalPayload(db, current)
        val result = backend.upsertGoal(current.syncId, payload.toFields())
        if (result.isFailure) {
            enqueue(db, OutboxTarget.GOALS, OutboxOperation.UPSERT, current.id, Json.encodeToString(GoalPayload.serializer(), payload), result.exceptionOrNull()?.message)
        }
    }

    // --- Grocery staples -----------------------------------------------------------------------

    @Serializable
    internal data class GroceryStaplePayload(val syncId: String, val name: String, val displayName: String, val timesBought: Int, val lastBoughtAt: Long) {
        fun toFields() = GroceryStapleFields(name, displayName, timesBought, lastBoughtAt)
        companion object {
            fun from(row: GroceryStaple) = GroceryStaplePayload(row.syncId, row.name, row.displayName, row.timesBought, row.lastBoughtAt)
        }
    }

    /** [com.kevin.legion.grocery.GroceryController.completeTrip]'s write - `upsert` REPLACEs by
     * [GroceryStaple.name] locally first (that function already reuses the prior row's `syncId`
     * across trips, and now its `serverId` too - see that function's own comment), then pushes. */
    suspend fun upsertStaple(context: Context, row: GroceryStaple): GroceryStaple {
        val db = CarDatabase.getDatabase(context)
        db.groceryStapleDao().upsert(row)
        val backend = backend(context) ?: return row
        val payload = GroceryStaplePayload.from(row)
        val result = backend.upsertGroceryStaple(row.syncId, payload.toFields())
        if (result.isFailure) {
            enqueue(db, OutboxTarget.GROCERY_STAPLES, OutboxOperation.UPSERT, 0, Json.encodeToString(GroceryStaplePayload.serializer(), payload), result.exceptionOrNull()?.message)
        }
        return row
    }

    /**
     * [com.kevin.legion.grocery.GroceryController.forgetStaple]'s write - tombstones on a
     * configured install (so the forget can propagate to the other device via
     * [LastAspectsSync.pull]), falls back to the bare hard [com.kevin.legion.data.local.GroceryStapleDao.deleteByName]
     * on an unconfigured one (no server copy exists to reconcile a tombstone against - same
     * fallback shape [LedgerConfigWriteThrough.deleteCategoryRulesBySubstring] uses).
     */
    suspend fun forgetStaple(context: Context, name: String) {
        val db = CarDatabase.getDatabase(context)
        val backend = backend(context)
        if (backend == null) {
            db.groceryStapleDao().deleteByName(name)
            return
        }
        val existing = db.groceryStapleDao().getByName(name) ?: return
        val now = System.currentTimeMillis()
        db.groceryStapleDao().softDeleteByName(name, now)
        val result = backend.softDeleteGroceryStaple(existing.syncId)
        if (result.isFailure) {
            enqueue(db, OutboxTarget.GROCERY_STAPLES, OutboxOperation.SOFT_DELETE, 0, Json.encodeToString(LastAspectsDeletePayload.serializer(), LastAspectsDeletePayload(existing.syncId)), result.exceptionOrNull()?.message)
        }
    }

    // --- Item lists / list items ---------------------------------------------------------------
    // No live caller today - see this object's own class doc for the honest accounting of why.

    @Serializable
    internal data class ItemListPayload(val syncId: String, val name: String, val tickable: Boolean, val sortOrder: Int, val lastUsedAt: Long, val archived: Boolean, val createdAt: Long) {
        fun toFields() = ItemListFields(name, tickable, sortOrder, lastUsedAt, archived, createdAt)
        companion object {
            fun from(row: ItemList) = ItemListPayload(row.syncId, row.name, row.tickable, row.sortOrder, row.lastUsedAt, row.archived, row.createdAt)
        }
    }

    suspend fun addItemList(context: Context, row: ItemList): ItemList {
        val db = CarDatabase.getDatabase(context)
        val id = db.itemListDao().insert(row)
        val stored = row.copy(id = id)
        val backend = backend(context) ?: return stored
        val payload = ItemListPayload.from(stored)
        val result = backend.upsertItemList(stored.syncId, payload.toFields())
        if (result.isFailure) {
            enqueue(db, OutboxTarget.LISTS_ITEM_LISTS, OutboxOperation.UPSERT, stored.id, Json.encodeToString(ItemListPayload.serializer(), payload), result.exceptionOrNull()?.message)
        }
        return stored
    }

    @Serializable
    internal data class ListItemPayload(
        val syncId: String,
        val listSyncId: String,
        val text: String,
        val done: Boolean,
        val doneAt: Long?,
        val sortOrder: Int,
        val createdAt: Long,
        val startsAt: Long?,
        val endsAt: Long?,
        val allDay: Boolean,
        val triggerPlaceLabel: String?,
        val repeatKind: String?,
        val repeatEvery: Int?,
        val repeatDaysOfWeek: String?,
        val repeatDay: Int?,
        val repeatMonth: Int?,
        val repeatEndKind: String?,
        val repeatEndDate: Long?,
        val repeatEndCount: Int?,
        val exact: Boolean,
        val exactDowngraded: Boolean,
        val missedAt: Long?,
        val missedDismissedAt: Long?,
        val loggedAt: Long?,
    ) {
        fun toFields() = ListItemFields(listSyncId, text, done, doneAt, sortOrder, createdAt, startsAt, endsAt, allDay, triggerPlaceLabel, repeatKind, repeatEvery, repeatDaysOfWeek, repeatDay, repeatMonth, repeatEndKind, repeatEndDate, repeatEndCount, exact, exactDowngraded, missedAt, missedDismissedAt, loggedAt)
    }

    private suspend fun listItemPayload(db: CarDatabase, row: ListItem): ListItemPayload? {
        val list = db.itemListDao().getAllIncludingDeleted().firstOrNull { it.id == row.listId } ?: return null
        return ListItemPayload(row.syncId, list.syncId, row.text, row.done, row.doneAt, row.sortOrder, row.createdAt, row.startsAt, row.endsAt, row.allDay, row.triggerPlaceLabel, row.repeatKind, row.repeatEvery, row.repeatDaysOfWeek, row.repeatDay, row.repeatMonth, row.repeatEndKind, row.repeatEndDate, row.repeatEndCount, row.exact, row.exactDowngraded, row.missedAt, row.missedDismissedAt, row.loggedAt)
    }

    suspend fun addListItem(context: Context, row: ListItem): ListItem {
        val db = CarDatabase.getDatabase(context)
        val id = db.listItemDao().insert(row)
        val stored = row.copy(id = id)
        val backend = backend(context) ?: return stored
        val payload = listItemPayload(db, stored) ?: return stored
        val result = backend.upsertListItem(stored.syncId, payload.toFields())
        if (result.isFailure) {
            enqueue(db, OutboxTarget.LISTS_LIST_ITEMS, OutboxOperation.UPSERT, stored.id, Json.encodeToString(ListItemPayload.serializer(), payload), result.exceptionOrNull()?.message)
        }
        return stored
    }
}

/** The wire shape queued for every [OutboxOperation.SOFT_DELETE] entry across this slice's
 * write-through tables - just the [LastAspectsBackend]'s own upsert key, matching
 * [LedgerConfigDeletePayload]'s shape. */
@Serializable
internal data class LastAspectsDeletePayload(val guid: String)

/**
 * Retries every still-pending last-aspects-slice [OutboxEntry], across all four
 * [OutboxTarget] constants this slice owns - mirrors [LedgerConfigOutboxDrain]'s own shape and
 * bounded-attempts reasoning exactly. `ui/MainActivity.kt`'s `onResume` hook calls this BEFORE
 * [LastAspectsBackfill.maybeAutoRun] and [LastAspectsSync.maybeAutoPull], same load-bearing
 * ordering [LedgerConfigOutboxDrain]'s own class doc explains.
 */
object LastAspectsOutboxDrain {
    const val MAX_ATTEMPTS = EventsOutboxDrain.MAX_ATTEMPTS

    data class DrainReport(val succeeded: Int, val stillPending: Int, val poisoned: Int) {
        operator fun plus(other: DrainReport) = DrainReport(succeeded + other.succeeded, stillPending + other.stillPending, poisoned + other.poisoned)
    }

    suspend fun drain(context: Context, backend: LastAspectsBackend): DrainReport {
        val db = CarDatabase.getDatabase(context)
        var total = DrainReport(0, 0, 0)

        total += drainOne(db, OutboxTarget.GOALS) { entry ->
            when (entry.operation) {
                OutboxOperation.UPSERT -> {
                    val p = Json.decodeFromString(LastAspectsWriteThrough.GoalPayload.serializer(), entry.payload)
                    backend.upsertGoal(p.syncId, p.toFields())
                }
                OutboxOperation.SOFT_DELETE -> {
                    val p = Json.decodeFromString(LastAspectsDeletePayload.serializer(), entry.payload)
                    backend.softDeleteGoal(p.guid)
                }
                else -> Result.success(Unit)
            }
        }
        total += drainOne(db, OutboxTarget.GROCERY_STAPLES) { entry ->
            when (entry.operation) {
                OutboxOperation.UPSERT -> {
                    val p = Json.decodeFromString(LastAspectsWriteThrough.GroceryStaplePayload.serializer(), entry.payload)
                    backend.upsertGroceryStaple(p.syncId, p.toFields())
                }
                OutboxOperation.SOFT_DELETE -> {
                    val p = Json.decodeFromString(LastAspectsDeletePayload.serializer(), entry.payload)
                    backend.softDeleteGroceryStaple(p.guid)
                }
                else -> Result.success(Unit)
            }
        }
        total += drainOne(db, OutboxTarget.LISTS_ITEM_LISTS) { entry ->
            when (entry.operation) {
                OutboxOperation.UPSERT -> {
                    val p = Json.decodeFromString(LastAspectsWriteThrough.ItemListPayload.serializer(), entry.payload)
                    backend.upsertItemList(p.syncId, p.toFields())
                }
                OutboxOperation.SOFT_DELETE -> {
                    val p = Json.decodeFromString(LastAspectsDeletePayload.serializer(), entry.payload)
                    backend.softDeleteItemList(p.guid)
                }
                else -> Result.success(Unit)
            }
        }
        total += drainOne(db, OutboxTarget.LISTS_LIST_ITEMS) { entry ->
            when (entry.operation) {
                OutboxOperation.UPSERT -> {
                    val p = Json.decodeFromString(LastAspectsWriteThrough.ListItemPayload.serializer(), entry.payload)
                    backend.upsertListItem(p.syncId, p.toFields())
                }
                OutboxOperation.SOFT_DELETE -> {
                    val p = Json.decodeFromString(LastAspectsDeletePayload.serializer(), entry.payload)
                    backend.softDeleteListItem(p.guid)
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
            val report = drain(app, SupabaseLastAspectsBackend(client))
            MidnightEvents.lastAspectsOutboxDrainSucceeded(report.succeeded, report.stillPending, report.poisoned)
        } catch (e: Exception) {
            MidnightEvents.lastAspectsOutboxDrainFailed(e)
        }
    }
}
