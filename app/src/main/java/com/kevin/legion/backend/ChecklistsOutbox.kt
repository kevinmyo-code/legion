package com.kevin.legion.backend

import android.content.Context
import com.kevin.legion.MidnightEvents
import com.kevin.legion.backend.engine.EngineBackends
import com.kevin.legion.backend.engine.EngineFailure
import com.kevin.legion.backend.engine.EngineHttpException
import com.kevin.legion.backend.engine.guardingForeground
import com.kevin.legion.data.local.CarDatabase
import com.kevin.legion.data.local.Checklist
import com.kevin.legion.data.local.ChecklistItem
import com.kevin.legion.data.local.ChecklistTick
import com.kevin.legion.data.local.OutboxEntry
import com.kevin.legion.data.local.OutboxOperation
import com.kevin.legion.data.local.OutboxTarget
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** The only payload any checklist outbox entry carries: the day a queued tick/untick is FOR. Every
 * other queued write needs nothing beyond [OutboxEntry.localId], because the drain re-reads the
 * row - see [ChecklistsOutboxDrain]'s own class doc. */
@Serializable
internal data class ChecklistTickOutboxPayload(val day: Int)

/**
 * Resolving a local checklist row to the pair of server ids every item/tick write needs, pushing
 * the parent first when it has none yet.
 *
 * **The parent has to exist server-side before a child can be addressed at all.**
 * `checklists/urls.py` nests every item route under `<uuid:checklist_id>` and every tick route
 * under that plus `<uuid:item_id>`, so there is no URL to POST an item to until its checklist has
 * a real uuid. That is why this is a shared helper rather than a line in each caller: the
 * write-through, the backfill and the drain all hit the same wall, and three copies of
 * "push the parent, then the child" is three places to get the ordering wrong.
 */
internal class ChecklistsPush(private val db: CarDatabase, private val backend: ChecklistsBackend) {

    /** The checklist's server uuid, pushing the row first if it has none. Null means the push
     * itself failed - [failure] then holds why, so a caller can tell "queue this" from
     * "the engine refused it". */
    var failure: Throwable? = null
        private set

    suspend fun checklistServerId(row: Checklist): String? =
        row.serverId ?: recorded(backend.upsertChecklist(row.syncId, row.toFields()))
            ?.serverId
            ?.also { db.checklistSyncDao().setServerId(row.id, it) }

    /** As [checklistServerId], for an item - pushing its parent first when needed. */
    suspend fun itemServerIds(item: ChecklistItem): Pair<String, String>? {
        val parent = db.checklistSyncDao().getByIdIncludingDeleted(item.checklistId)
        if (parent == null) {
            failure = ChecklistsBackendException(
                "That item is on a checklist this device no longer holds, so there was nothing to send it under.",
            )
            return null
        }
        // The parent's push happens first and may itself fail - a null here means the child has no
        // URL to be addressed at, so nothing about the child is attempted.
        val parentServerId = checklistServerId(parent)
        val itemServerId = parentServerId?.let { parentId ->
            item.serverId ?: recorded(backend.upsertItem(parentId, item.syncId, item.toFields()))
                ?.serverId
                ?.also { db.checklistItemSyncDao().setServerId(item.id, it) }
        }
        return if (parentServerId == null || itemServerId == null) null else parentServerId to itemServerId
    }

    /** Pushes [row]'s current state: a create when it has no server id yet (idempotent on
     * `sync_id`), a patch when it does. */
    suspend fun pushChecklist(row: Checklist): Boolean {
        val serverId = row.serverId
        return if (serverId == null) {
            checklistServerId(row) != null
        } else {
            succeeded(backend.patchChecklist(serverId, row.syncId, row.toFields()))
        }
    }

    suspend fun pushItem(row: ChecklistItem): Boolean {
        val ids = itemServerIds(row)
        return when {
            ids == null -> false
            // itemServerIds just created it, carrying exactly these values - a patch straight
            // afterwards would be a second request saying the same thing.
            row.serverId == null -> true
            else -> succeeded(backend.patchItem(ids.first, ids.second, row.syncId, row.toFields()))
        }
    }

    /** A row with no server id never reached the engine, so there is nothing there to remove and
     * "it is not on the engine" is already true of it. */
    suspend fun deleteChecklist(row: Checklist): Boolean =
        row.serverId?.let { succeeded(backend.deleteChecklist(it)) } ?: true

    suspend fun deleteItem(row: ChecklistItem): Boolean {
        val parentServerId = db.checklistSyncDao().getByIdIncludingDeleted(row.checklistId)?.serverId
        val serverId = row.serverId
        return if (serverId == null || parentServerId == null) {
            true
        } else {
            succeeded(backend.deleteItem(parentServerId, serverId))
        }
    }

    suspend fun pushTick(item: ChecklistItem, tick: ChecklistTick): Boolean {
        val ids = itemServerIds(item) ?: return false
        val remote = recorded(backend.tick(ids.first, ids.second, tick.day, tick.value, tick.source))
        remote?.let { db.checklistTickSyncDao().setServerId(tick.id, it.serverId) }
        return remote != null
    }

    suspend fun pushUntick(item: ChecklistItem, day: Int): Boolean {
        val ids = itemServerIds(item) ?: return false
        return succeeded(backend.untick(ids.first, ids.second, day))
    }

    /** Unwraps a backend [Result], stashing the cause in [failure] so a caller can tell "queue
     * this" from "the engine refused it" - the whole reason this class carries a mutable [failure]
     * rather than throwing. */
    private fun <T> recorded(result: Result<T>): T? = result.onFailure { failure = it }.getOrNull()

    private fun succeeded(result: Result<*>): Boolean = recorded(result) != null
}

internal fun Checklist.toFields() = ChecklistFields(
    name = name,
    scheduleKind = scheduleKind,
    scheduleEvery = scheduleEvery,
    scheduleDaysOfWeek = scheduleDaysOfWeek,
    sortOrder = sortOrder,
    archived = archived,
)

internal fun ChecklistItem.toFields() = ChecklistItemFields(
    text = text,
    sortOrder = sortOrder,
    measureUnit = measureUnit,
    measureTarget = measureTarget,
    measureDirection = measureDirection,
)

/**
 * The push half of checklist sync: [com.kevin.legion.checklists.ChecklistController]'s writes call
 * through here after their local write has already happened, and anything that could not be sent
 * becomes an [OutboxEntry] rather than being lost.
 *
 * **Local write first, unconditionally, then push** - the same ordering
 * [EventsAppointmentWriter]'s own class doc argues for and for the same reason: a checklist tick
 * has to appear on screen the instant it is tapped, and a phone that is off Wi-Fi when someone
 * ticks "3 sets goblet squats" must not need them to remember to do it again later. This is
 * deliberately NOT `NotesController.applyChange`'s server-first ordering, which exists there so a
 * REJECTED write is never applied locally at all.
 *
 * **What the user sees when the engine is unreachable, stated plainly because CLAUDE.md section 7
 * turns on it:** the tick is in Room and renders ticked immediately, an outbox entry is queued,
 * and the calendar day view labels that row **"queued - not on the engine yet"**
 * ([ChecklistsOutboxDrain.queuedItemIdsForDay] is what it reads). Nothing anywhere says the tick
 * reached the engine, because it did not.
 *
 * **A REFUSED write is not queued.** Only [EngineFailure.Unreachable] enqueues - a 400 (the
 * measured-item refusal, a validation error) means the engine saw the request and said no in
 * words, and retrying it every foreground forever would be a retry loop around a rejection. The
 * refusal's own sentence is returned to the caller instead. This is the classification
 * [EventsOutboxDrain.MAX_ATTEMPTS]'s doc comment says the Supabase path CANNOT make ("there is no
 * HTTP status code left by the time a caller of EventsBackend ever sees the failure") - the engine
 * transport keeps the status, so this side can do the thing that file could only approximate with
 * an attempt cap.
 *
 * **No `object` singleton** - a class taking its collaborators as constructor parameters. The
 * [backend] is resolved by the caller ([com.kevin.legion.checklists.ChecklistController]), which is
 * also where a test substitutes a fake.
 */
class ChecklistsWriteThrough(
    private val context: Context,
    private val backend: ChecklistsBackend?,
) {
    /** What a write-through attempt did. [Refused] carries the engine's own sentence for a caller
     * to show in words; [Queued] and [Sent] are both "the local write stands", differing only in
     * whether the engine has it yet. */
    sealed interface PushOutcome {
        object Sent : PushOutcome
        object NotConfigured : PushOutcome
        data class Queued(val reason: String) : PushOutcome
        data class Refused(val message: String) : PushOutcome
    }

    suspend fun checklistChanged(checklistId: Long): PushOutcome = run(
        target = OutboxTarget.CHECKLISTS,
        operation = OutboxOperation.UPSERT,
        localId = checklistId,
    ) { push ->
        val row = db().checklistSyncDao().getByIdIncludingDeleted(checklistId)
        if (row == null) true else if (row.deleted) push.deleteChecklist(row) else push.pushChecklist(row)
    }

    suspend fun checklistDeleted(checklistId: Long): PushOutcome = run(
        target = OutboxTarget.CHECKLISTS,
        operation = OutboxOperation.SOFT_DELETE,
        localId = checklistId,
    ) { push ->
        val row = db().checklistSyncDao().getByIdIncludingDeleted(checklistId)
        if (row == null) true else push.deleteChecklist(row)
    }

    suspend fun itemChanged(itemId: Long): PushOutcome = run(
        target = OutboxTarget.CHECKLIST_ITEMS,
        operation = OutboxOperation.UPSERT,
        localId = itemId,
    ) { push ->
        val row = db().checklistItemDao().getByIdIncludingDeleted(itemId)
        if (row == null) true else if (row.deleted) push.deleteItem(row) else push.pushItem(row)
    }

    suspend fun itemDeleted(itemId: Long): PushOutcome = run(
        target = OutboxTarget.CHECKLIST_ITEMS,
        operation = OutboxOperation.SOFT_DELETE,
        localId = itemId,
    ) { push ->
        val row = db().checklistItemDao().getByIdIncludingDeleted(itemId)
        if (row == null) true else push.deleteItem(row)
    }

    suspend fun ticked(itemId: Long, day: Int): PushOutcome = run(
        target = OutboxTarget.CHECKLIST_TICKS,
        operation = OutboxOperation.UPSERT,
        localId = itemId,
        payload = Json.encodeToString(ChecklistTickOutboxPayload.serializer(), ChecklistTickOutboxPayload(day)),
    ) { push ->
        val item = db().checklistItemDao().getByIdIncludingDeleted(itemId)
        val tick = db().checklistTickDao().getForItemOnDayIncludingDeleted(itemId, day)
        if (item == null || tick == null) true else push.pushTick(item, tick)
    }

    suspend fun unticked(itemId: Long, day: Int): PushOutcome = run(
        target = OutboxTarget.CHECKLIST_TICKS,
        operation = OutboxOperation.SOFT_DELETE,
        localId = itemId,
        payload = Json.encodeToString(ChecklistTickOutboxPayload.serializer(), ChecklistTickOutboxPayload(day)),
    ) { push ->
        val item = db().checklistItemDao().getByIdIncludingDeleted(itemId)
        if (item == null) true else push.pushUntick(item, day)
    }

    private fun db() = CarDatabase.getDatabase(context)

    /** One shape for every write above: no backend means no engine to talk to at all and nothing
     * is queued (an install that has never been pointed at one would otherwise accumulate a queue
     * for a server that does not exist); a success is a success; an unreachable engine queues; a
     * refusal is reported in the engine's own words and queues nothing. */
    private suspend fun run(
        target: String,
        operation: String,
        localId: Long,
        payload: String = "{}",
        attempt: suspend (ChecklistsPush) -> Boolean,
    ): PushOutcome {
        val backend = this.backend
        if (backend == null) return PushOutcome.NotConfigured
        val db = db()
        val push = ChecklistsPush(db, backend)
        val sent = attempt(push)
        val cause = push.failure
        val engineFailure = (cause as? EngineHttpException)?.failure
        return when {
            sent -> PushOutcome.Sent
            // Not queued, deliberately: the engine saw this request and said no in words, so a
            // retry every foreground would be a loop around a rejection. The words go back to the
            // caller instead.
            engineFailure is EngineFailure.Refused -> PushOutcome.Refused(engineFailure.body)
            else -> {
                val reason = cause?.message ?: "unknown error"
                enqueue(db, target, operation, localId, payload, reason)
                PushOutcome.Queued(reason)
            }
        }
    }

    /** Deduplicated on `(target, operation, localId)` - a row renamed three times offline queues
     * ONE entry, because the drain re-reads the row's current state anyway and three identical
     * entries would just be three identical pushes. */
    private suspend fun enqueue(
        db: CarDatabase,
        target: String,
        operation: String,
        localId: Long,
        payload: String,
        error: String,
    ) {
        val dao = db.outboxDao()
        val alreadyQueued = dao.pendingForTable(target, ChecklistsOutboxDrain.MAX_ATTEMPTS)
            .any { it.operation == operation && it.localId == localId && it.payload == payload }
        if (alreadyQueued) return
        dao.insert(
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
}

/**
 * Retries every still-pending checklist [OutboxEntry] across the slice's three targets.
 *
 * **A queued checklist entry names a row; it does not carry a snapshot of it.** Every other outbox
 * in this codebase serialises the row's field values at enqueue time (see
 * `EventUpdateOutboxPayload`, `LastAspectsWriteThrough.GoalPayload`); this one stores only
 * [OutboxEntry.localId] and re-reads the row when it drains. Two consequences, both deliberate:
 * the drain always sends the row's CURRENT state, so a rename queued behind an offline create needs
 * none of `EventsAppointmentWriter.repointPendingCreate`'s delete-and-reinsert dance; and a row
 * hard-deleted before the drain would have nothing left to send - which these three tables never
 * do, since `ChecklistDao`/`ChecklistItemDao`/`ChecklistTickDao` only ever tombstone (each DAO's
 * own "soft delete" comment).
 *
 * The bounded-attempt mechanics are [EventsOutboxDrain]'s, unchanged: an entry past
 * [MAX_ATTEMPTS] stops being retried and says why in its own `lastError`, rather than retrying a
 * poisoned write on every foreground forever.
 */
object ChecklistsOutboxDrain {
    const val MAX_ATTEMPTS = EventsOutboxDrain.MAX_ATTEMPTS

    data class DrainReport(val succeeded: Int, val stillPending: Int, val poisoned: Int) {
        operator fun plus(other: DrainReport) =
            DrainReport(succeeded + other.succeeded, stillPending + other.stillPending, poisoned + other.poisoned)
    }

    suspend fun drain(context: Context, backend: ChecklistsBackend): DrainReport {
        val db = CarDatabase.getDatabase(context)
        var total = DrainReport(0, 0, 0)
        total += drainOne(db, backend, OutboxTarget.CHECKLISTS) { push, entry ->
            val row = db.checklistSyncDao().getByIdIncludingDeleted(entry.localId)
            when {
                row == null -> true
                entry.operation == OutboxOperation.SOFT_DELETE || row.deleted -> push.deleteChecklist(row)
                else -> push.pushChecklist(row)
            }
        }
        total += drainOne(db, backend, OutboxTarget.CHECKLIST_ITEMS) { push, entry ->
            val row = db.checklistItemDao().getByIdIncludingDeleted(entry.localId)
            when {
                row == null -> true
                entry.operation == OutboxOperation.SOFT_DELETE || row.deleted -> push.deleteItem(row)
                else -> push.pushItem(row)
            }
        }
        total += drainOne(db, backend, OutboxTarget.CHECKLIST_TICKS) { push, entry ->
            val day = Json.decodeFromString(ChecklistTickOutboxPayload.serializer(), entry.payload).day
            val item = db.checklistItemDao().getByIdIncludingDeleted(entry.localId)
            val tick = db.checklistTickDao().getForItemOnDayIncludingDeleted(entry.localId, day)
            when {
                item == null -> true
                entry.operation == OutboxOperation.SOFT_DELETE || tick == null || tick.deleted ->
                    push.pushUntick(item, day)
                else -> push.pushTick(item, tick)
            }
        }
        return total
    }

    private suspend fun drainOne(
        db: CarDatabase,
        backend: ChecklistsBackend,
        target: String,
        attempt: suspend (ChecklistsPush, OutboxEntry) -> Boolean,
    ): DrainReport {
        val dao = db.outboxDao()
        var succeeded = 0
        var stillPending = 0
        var poisoned = 0
        for (entry in dao.pendingForTable(target, MAX_ATTEMPTS)) {
            val push = ChecklistsPush(db, backend)
            if (attempt(push, entry)) {
                dao.delete(entry.id)
                succeeded++
                continue
            }
            val attempts = entry.attempts + 1
            dao.recordAttempt(entry.id, attempts, push.failure?.message ?: "unknown error")
            if (attempts >= MAX_ATTEMPTS) poisoned++ else stillPending++
        }
        return DrainReport(succeeded, stillPending, poisoned)
    }

    /**
     * Which checklist ITEM ids have a tick or untick still queued for [day] - what the calendar
     * day view reads to label a row "queued - not on the engine yet". Poisoned entries (past
     * [MAX_ATTEMPTS]) are deliberately EXCLUDED, matching
     * [com.kevin.legion.data.local.OutboxDao.pendingForTable]'s own WHERE clause: a row nothing
     * will retry any more is not "queued", and calling it that would be the quiet lie this label
     * exists to prevent.
     */
    suspend fun queuedItemIdsForDay(context: Context, day: Int): Set<Long> =
        runCatching {
            CarDatabase.getDatabase(context).outboxDao()
                .pendingForTable(OutboxTarget.CHECKLIST_TICKS, MAX_ATTEMPTS)
                .filter { entry ->
                    runCatching {
                        Json.decodeFromString(ChecklistTickOutboxPayload.serializer(), entry.payload).day
                    }.getOrNull() == day
                }
                .map { it.localId }
                .toSet()
        }.getOrDefault(emptySet())

    /** `MainActivity.onResume`'s hook - runs BEFORE the backfill and the pull, the same
     * load-bearing ordering [EventsOutboxDrain]'s own class doc explains (a local mutation must at
     * least be ATTEMPTED against the engine before that engine's state is read back). No-ops
     * silently when checklists are not on the Django transport or this device is not signed in. */
    suspend fun maybeDrain(context: Context) {
        val app = context.applicationContext
        val backend = EngineBackends(app).checklistsBackend() ?: return
        guardingForeground(onFailure = { MidnightEvents.checklistsOutboxDrainFailed(it) }) {
            val report = drain(app, backend)
            MidnightEvents.checklistsOutboxDrainSucceeded(report.succeeded, report.stillPending, report.poisoned)
        }
    }
}
