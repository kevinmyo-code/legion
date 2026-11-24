package com.kevin.legion.backend

import android.content.Context
import com.kevin.legion.MidnightEvents
import com.kevin.legion.data.local.CarDatabase
import com.kevin.legion.data.local.Goal
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
 * instruction to state this plainly:** [addGoal]/[closeGoal] ARE wired -
 * [com.kevin.legion.goals.GoalController.setGoal]/`.closeByLineage` call through here now (see
 * that controller's own updated doc comment). They are the only two left.
 *
 * **NARROWED by the lists redesign slice 1a (2026-11-24).** `addItemList`/`addListItem` never had a
 * live caller (`notes/NotesController.kt` repointed every `item_lists`/`list_items` read and write
 * onto `events` in backend-erp ticket 15 step 4, before this slice was written), and
 * `upsertStaple`/`forgetStaple` lost theirs when `grocery/GroceryController.kt` was deleted. All
 * four are gone, with the three tables' halves of [LastAspectsBackend], [LastAspectsBackfill],
 * [LastAspectsSync] and [LastAspectsRealtime].
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
        // (see RemoteGoal's own doc comment) - resolved to the prior revision's own syncId here, at
        // push time, rather than stored on the entity itself.
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
}

/** The wire shape queued for every [OutboxOperation.SOFT_DELETE] entry on this slice's
 * write-through table - just the [LastAspectsBackend]'s own upsert key, matching
 * [LedgerConfigDeletePayload]'s shape. */
@Serializable
internal data class LastAspectsDeletePayload(val guid: String)

/**
 * Retries every still-pending last-aspects-slice [OutboxEntry] for the one
 * [OutboxTarget] constant this slice still owns - mirrors [LedgerConfigOutboxDrain]'s own shape and
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
