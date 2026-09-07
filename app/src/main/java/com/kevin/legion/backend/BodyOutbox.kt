package com.kevin.legion.backend

import android.content.Context
import com.kevin.legion.MidnightEvents
import com.kevin.legion.backend.engine.EngineBackends
import com.kevin.legion.backend.engine.EngineTransport
import com.kevin.legion.backend.engine.Transport
import com.kevin.legion.data.local.BodyweightLog
import com.kevin.legion.data.local.CarDatabase
import com.kevin.legion.data.local.MealLog
import com.kevin.legion.data.local.MealTarget
import com.kevin.legion.data.local.OutboxEntry
import com.kevin.legion.data.local.OutboxOperation
import com.kevin.legion.data.local.OutboxTarget
import com.kevin.legion.data.local.SleepLog
import com.kevin.legion.data.local.SleepTarget
import com.kevin.legion.data.local.WorkoutPlan
import com.kevin.legion.data.local.WorkoutPlanItem
import com.kevin.legion.data.local.WorkoutSetLog
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The push half of body sync: write-through on every create the eight controllers
 * ([com.kevin.legion.meals.MealController], [com.kevin.legion.sleep.SleepController],
 * [com.kevin.legion.workouts.WorkoutController]) already perform, plus the durable outbox that
 * makes an offline write survive - mirrors [EventsAppointmentWriter]/[EventsOutboxDrain]'s own
 * shape (see this file's own doc on [BodyWriteThrough] for where the two designs deliberately
 * diverge). **This is the template for six more aspects.**
 *
 * **This paragraph used to read "Local write always happens first, unconditionally", and for the
 * Supabase transport it still does.** That ordering is now conditional on the transport, because
 * it was load-bearing in the wrong direction: `.scratch/django-engine/issues/14-*` found eight
 * validation rules duplicated between Kotlin and Python that could not be deleted while this file
 * wrote locally first, since deleting one would let Room accept a row Django refuses forever - the
 * drain retries three times, poisons it, **and the user was told the write succeeded before any of
 * that**. CLAUDE.md section 7 violated by the storage layer. So:
 *
 * - **On [com.kevin.legion.backend.engine.Transport.DJANGO], every UPSERT pushes FIRST.** A 4xx
 *   refusal writes nothing locally, queues nothing, and hands the engine's own sentence back
 *   ([WriteThroughOutcome.Refused]). Anything else that failed - unreachable, 5xx, a dead token -
 *   writes locally, enqueues an [OutboxEntry], and the caller says so in words
 *   ([WriteThroughOutcome.Queued], ADR 0044 rule 4). This is [ChecklistsWriteThrough]'s shape,
 *   with the local write moved behind the push.
 * - **On [com.kevin.legion.backend.engine.Transport.SUPABASE] (still the default for `body`),
 *   nothing changed at all**: local write first, unconditionally, push after, enqueue on failure,
 *   and no new words spoken ([WriteThroughOutcome.StoredLocally]). The Supabase failure path
 *   cannot tell a refusal from a dropped connection in the first place (see
 *   [EventsOutboxDrain.MAX_ATTEMPTS]'s own doc comment), so server-first there would risk
 *   discarding a write that was merely offline.
 * - On an unconfigured install nothing is pushed and nothing is queued, unchanged.
 *
 * **DELETES are deliberately still local-first on BOTH transports.** Every guard ticket 14 lists
 * sits in front of a CREATE, and a refusal-shaped 4xx on a delete is routinely a 404 for a row the
 * engine never had - refusing to tombstone locally on that would leave a driver unable to delete a
 * row that only ever existed on his phone. A delete's failure still queues, exactly as before.
 */
object BodyWriteThrough {
    /** Test seam, same mechanism as [EventsAppointmentWriter.backendOverride]. */
    @Volatile
    internal var backendOverride: BodyBackend? = null

    /**
     * **This used to read `SupabaseClientProvider.get(context) ?: return null` followed by
     * `SupabaseBodyBackend(client)`, i.e. Supabase or nothing.** It now asks [EngineBackends]
     * which transport `body` is on and gets that same Supabase backend, a `DjangoBodyBackend`, or
     * null when neither is configured (django-engine Phase 5). `body` still DEFAULTS to Supabase,
     * so an untouched install behaves exactly as it did.
     *
     * **No auth wait, unchanged.** This is the write-through path and it has never waited for a
     * Supabase session - see [EngineBackends.bodyBackend]'s own doc comment for why the wait stays
     * at the call sites that already do it rather than moving inside the resolver.
     */
    private fun backend(context: Context): BodyBackend? {
        backendOverride?.let { return it }
        return EngineBackends(context).bodyBackend()
    }

    /**
     * One upsert, described rather than performed - the five things that differ between body's
     * eight write-through functions, so [write] can hold the ordering rule once instead of eight
     * times. A plain class, not a data class: it is a parameter bundle, never compared or copied.
     *
     * [localId] is read off the INPUT row, exactly as all eight functions did before this bundle
     * existed. (Room's autoincrement means that is 0 for a fresh insert, so a queued create's
     * `localId` does not match the row's real id - a pre-existing inaccuracy this ticket
     * deliberately does not change, since [BodyOutboxDrain] pushes by `guid` from the payload and
     * never reads `localId` at all.)
     */
    private class Upsert(
        val target: String,
        val localId: Long,
        val payload: String,
        val store: suspend (CarDatabase) -> Unit,
        val push: suspend (BodyBackend) -> Result<*>,
    )

    /**
     * The ordering rule, in one place: see this object's own class doc for why it forks on the
     * transport. [row] is handed back unchanged on every branch that wrote - none of body's eight
     * tables needs the id Room assigned (contrast [MemoryWriteThrough.addCompanionMemory], whose
     * audit line does).
     *
     * **The transport read is what a test flips**, not [backendOverride]: an override supplies a
     * fake backend and says nothing about ordering, so a test wanting the server-first path calls
     * `EngineTransport(context).setTransport(EngineBackends.ASPECT_BODY, Transport.DJANGO)` and
     * gets it with whatever backend it already installed. [BodyOutboxDrain.maybeDrain] reads the
     * transport the same way and for the same reason.
     */
    private suspend fun <T> write(context: Context, row: T, upsert: Upsert): WriteThroughOutcome<T> {
        val db = CarDatabase.getDatabase(context)
        val backend = backend(context)
        val serverFirst = EngineTransport(context).transportFor(EngineBackends.ASPECT_BODY) == Transport.DJANGO
        if (backend == null || !serverFirst) {
            // Supabase (or an unconfigured install), unchanged: local write first, unconditionally,
            // then a push whose failure queues, and not a word about any of it.
            upsert.store(db)
            val legacyResult = backend?.let { upsert.push(it) }
            if (legacyResult != null && legacyResult.isFailure) {
                enqueue(
                    db, upsert.target, OutboxOperation.UPSERT, upsert.localId, upsert.payload,
                    legacyResult.exceptionOrNull()?.message,
                )
            }
            return WriteThroughOutcome.StoredLocally(row)
        }
        val result = upsert.push(backend)
        // A refusal ends it BEFORE the local write: nothing in Room, nothing in the outbox, and the
        // server's own words go back to the caller.
        val refusal = refusalSentence(result.exceptionOrNull())
        return if (refusal != null) {
            WriteThroughOutcome.Refused(refusal)
        } else {
            upsert.store(db)
            if (result.isSuccess) {
                WriteThroughOutcome.Sent(row)
            } else {
                val reason = result.exceptionOrNull()?.message ?: "unknown error"
                enqueue(db, upsert.target, OutboxOperation.UPSERT, upsert.localId, upsert.payload, reason)
                WriteThroughOutcome.Queued(row, reason)
            }
        }
    }

    /**
     * True when an [OutboxOperation.UPSERT] entry for [localId] on [target] is still sitting in
     * the outbox, and cancels it if so. **This is how a delete tells "was this row's create ever
     * actually sent" apart from Event's own [Event.serverId]-nullness check** - a deliberate
     * improvement, not a copy of that precedent: [EventsOutboxDrain]'s own class doc records a
     * "known, narrow gap" where a create can drain successfully before the following pull refills
     * [Event.serverId], and a delete landing in exactly that window hard-deletes locally with no
     * tombstone sent even though the row now exists server-side. Every body upsert is idempotent
     * on [BodyBackend]'s own `origin_guid` conflict key (see that interface's own class doc), so
     * body's [serverId][BodyweightLog.serverId] can never be used as a "did this reach the server"
     * signal EITHER (same nullness-until-next-pull behaviour) - checking the OUTBOX ITSELF for a
     * still-pending create is the one source of truth that is actually accurate: if nothing is
     * pending, the create either already succeeded (an ordinary push, no outbox entry ever made)
     * or was never attempted (unconfigured install), and either way a soft-delete pushed by
     * [origin_guid] reaches the right row - there is no create left in flight that could still
     * land AFTER this delete and resurrect it.
     */
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

    // --- Bodyweight ------------------------------------------------------------------------------

    @Serializable
    internal data class BodyweightLogPayload(
        val guid: String,
        val weightValue: Double,
        val weightUnit: String,
        val loggedAtMs: Long,
        val trustTier: String,
    ) {
        fun toFields() = BodyweightLogFields(weightValue, weightUnit, loggedAtMs, trustTier)
        companion object {
            fun from(row: BodyweightLog) = BodyweightLogPayload(row.guid, row.weightValue, row.weightUnit, row.loggedAt, row.trustTier.name)
        }
    }

    suspend fun addBodyweightLog(context: Context, row: BodyweightLog): WriteThroughOutcome<BodyweightLog> = write(
        context, row,
        Upsert(
            target = OutboxTarget.BODY_BODYWEIGHT_LOGS,
            localId = row.id,
            payload = Json.encodeToString(BodyweightLogPayload.serializer(), BodyweightLogPayload.from(row)),
            store = { db -> db.bodyweightLogDao().insert(row) },
            push = { backend -> backend.upsertBodyweightLog(row.guid, BodyweightLogPayload.from(row).toFields()) },
        ),
    )

    suspend fun deleteBodyweightLog(context: Context, log: BodyweightLog) {
        val db = CarDatabase.getDatabase(context)
        val backend = backend(context)
        if (backend == null) {
            db.bodyweightLogDao().deleteById(log.id)
            return
        }
        if (cancelPendingCreateIfPending(db, OutboxTarget.BODY_BODYWEIGHT_LOGS, log.id)) {
            db.bodyweightLogDao().deleteById(log.id)
            return
        }
        db.bodyweightLogDao().update(log.copy(deleted = true, updatedAtMs = System.currentTimeMillis()))
        val result = backend.softDeleteBodyweightLog(log.guid)
        if (result.isFailure) {
            enqueue(
                db, OutboxTarget.BODY_BODYWEIGHT_LOGS, OutboxOperation.SOFT_DELETE, log.id,
                Json.encodeToString(BodyDeletePayload.serializer(), BodyDeletePayload(log.guid)),
                result.exceptionOrNull()?.message,
            )
        }
    }

    // --- Meals -----------------------------------------------------------------------------------

    @Serializable
    internal data class MealLogPayload(
        val guid: String,
        val description: String,
        val caloriesKcal: Int?,
        val proteinG: Double?,
        val carbsG: Double?,
        val fatG: Double?,
        val loggedAtMs: Long,
        val sourceImagePath: String?,
        val trustTier: String,
    ) {
        fun toFields() = MealLogFields(description, caloriesKcal, proteinG, carbsG, fatG, loggedAtMs, sourceImagePath, trustTier)
        companion object {
            fun from(row: MealLog) = MealLogPayload(
                row.guid, row.description, row.caloriesKcal, row.proteinG, row.carbsG, row.fatG,
                row.loggedAt, row.sourceImagePath, row.trustTier.name,
            )
        }
    }

    suspend fun addMealLog(context: Context, row: MealLog): WriteThroughOutcome<MealLog> = write(
        context, row,
        Upsert(
            target = OutboxTarget.BODY_MEAL_LOGS,
            localId = row.id,
            payload = Json.encodeToString(MealLogPayload.serializer(), MealLogPayload.from(row)),
            store = { db -> db.mealLogDao().insert(row) },
            push = { backend -> backend.upsertMealLog(row.guid, MealLogPayload.from(row).toFields()) },
        ),
    )

    suspend fun deleteMealLog(context: Context, log: MealLog) {
        val db = CarDatabase.getDatabase(context)
        val backend = backend(context)
        if (backend == null) {
            db.mealLogDao().deleteById(log.id)
            return
        }
        if (cancelPendingCreateIfPending(db, OutboxTarget.BODY_MEAL_LOGS, log.id)) {
            db.mealLogDao().deleteById(log.id)
            return
        }
        db.mealLogDao().update(log.copy(deleted = true, updatedAtMs = System.currentTimeMillis()))
        val result = backend.softDeleteMealLog(log.guid)
        if (result.isFailure) {
            enqueue(
                db, OutboxTarget.BODY_MEAL_LOGS, OutboxOperation.SOFT_DELETE, log.id,
                Json.encodeToString(BodyDeletePayload.serializer(), BodyDeletePayload(log.guid)),
                result.exceptionOrNull()?.message,
            )
        }
    }

    @Serializable
    internal data class MealTargetPayload(
        val guid: String,
        val caloriesKcal: Int,
        val proteinG: Double,
        val carbsG: Double,
        val fatG: Double,
        val effectiveFromDateEpochMs: Long,
    ) {
        fun toFields() = MealTargetFields(caloriesKcal, proteinG, carbsG, fatG, effectiveFromDateEpochMs)
        companion object {
            fun from(row: MealTarget) = MealTargetPayload(row.guid, row.caloriesKcal, row.proteinG, row.carbsG, row.fatG, row.effectiveFromDateEpoch)
        }
    }

    /** No delete counterpart - [com.kevin.legion.meals.MealController] never deletes a target row,
     * only writes a new effective-dated one (the "copy forward" shape every target table uses). */
    suspend fun setMealTarget(context: Context, row: MealTarget): WriteThroughOutcome<MealTarget> = write(
        context, row,
        Upsert(
            target = OutboxTarget.BODY_MEAL_TARGETS,
            localId = row.id,
            payload = Json.encodeToString(MealTargetPayload.serializer(), MealTargetPayload.from(row)),
            store = { db -> db.mealTargetDao().upsert(row) },
            push = { backend -> backend.upsertMealTarget(row.guid, MealTargetPayload.from(row).toFields()) },
        ),
    )

    // --- Sleep -----------------------------------------------------------------------------------

    @Serializable
    internal data class SleepLogPayload(
        val guid: String,
        val sleepDateEpochMs: Long,
        val durationMinutes: Int,
        val quality: Int?,
        val notes: String?,
        val loggedAtMs: Long,
        val trustTier: String,
    ) {
        fun toFields() = SleepLogFields(sleepDateEpochMs, durationMinutes, quality, notes, loggedAtMs, trustTier)
        companion object {
            fun from(row: SleepLog) = SleepLogPayload(row.guid, row.sleepDate, row.durationMinutes, row.quality, row.notes, row.loggedAt, row.trustTier.name)
        }
    }

    suspend fun addSleepLog(context: Context, row: SleepLog): WriteThroughOutcome<SleepLog> = write(
        context, row,
        Upsert(
            target = OutboxTarget.BODY_SLEEP_LOGS,
            localId = row.id,
            payload = Json.encodeToString(SleepLogPayload.serializer(), SleepLogPayload.from(row)),
            store = { db -> db.sleepLogDao().insert(row) },
            push = { backend -> backend.upsertSleepLog(row.guid, SleepLogPayload.from(row).toFields()) },
        ),
    )

    suspend fun deleteSleepLog(context: Context, log: SleepLog) {
        val db = CarDatabase.getDatabase(context)
        val backend = backend(context)
        if (backend == null) {
            db.sleepLogDao().deleteById(log.id)
            return
        }
        if (cancelPendingCreateIfPending(db, OutboxTarget.BODY_SLEEP_LOGS, log.id)) {
            db.sleepLogDao().deleteById(log.id)
            return
        }
        db.sleepLogDao().update(log.copy(deleted = true, updatedAtMs = System.currentTimeMillis()))
        val result = backend.softDeleteSleepLog(log.guid)
        if (result.isFailure) {
            enqueue(
                db, OutboxTarget.BODY_SLEEP_LOGS, OutboxOperation.SOFT_DELETE, log.id,
                Json.encodeToString(BodyDeletePayload.serializer(), BodyDeletePayload(log.guid)),
                result.exceptionOrNull()?.message,
            )
        }
    }

    @Serializable
    internal data class SleepTargetPayload(
        val guid: String,
        val targetMinutes: Int,
        val effectiveFromDateEpochMs: Long,
    ) {
        fun toFields() = SleepTargetFields(targetMinutes, effectiveFromDateEpochMs)
        companion object {
            fun from(row: SleepTarget) = SleepTargetPayload(row.guid, row.targetMinutes, row.effectiveFromDateEpoch)
        }
    }

    suspend fun setSleepTarget(context: Context, row: SleepTarget): WriteThroughOutcome<SleepTarget> = write(
        context, row,
        Upsert(
            target = OutboxTarget.BODY_SLEEP_TARGETS,
            localId = row.id,
            payload = Json.encodeToString(SleepTargetPayload.serializer(), SleepTargetPayload.from(row)),
            store = { db -> db.sleepTargetDao().upsert(row) },
            push = { backend -> backend.upsertSleepTarget(row.guid, SleepTargetPayload.from(row).toFields()) },
        ),
    )

    // --- Workouts --------------------------------------------------------------------------------

    @Serializable
    internal data class WorkoutPlanPayload(
        val guid: String,
        val sessionsPerWeek: Int,
        val effectiveFromWeekEpochMs: Long,
    ) {
        fun toFields() = WorkoutPlanFields(sessionsPerWeek, effectiveFromWeekEpochMs)
        companion object {
            fun from(row: WorkoutPlan) = WorkoutPlanPayload(row.guid, row.sessionsPerWeek, row.effectiveFromWeekEpoch)
        }
    }

    suspend fun setWorkoutPlan(context: Context, row: WorkoutPlan): WriteThroughOutcome<WorkoutPlan> = write(
        context, row,
        Upsert(
            target = OutboxTarget.BODY_WORKOUT_PLANS,
            localId = row.id,
            payload = Json.encodeToString(WorkoutPlanPayload.serializer(), WorkoutPlanPayload.from(row)),
            store = { db -> db.workoutPlanDao().upsert(row) },
            push = { backend -> backend.upsertWorkoutPlan(row.guid, WorkoutPlanPayload.from(row).toFields()) },
        ),
    )

    @Serializable
    internal data class WorkoutPlanItemPayload(
        val guid: String,
        val exercise: String,
        val targetSetsPerWeek: Int,
        val effectiveFromWeekEpochMs: Long,
        val repsPerSet: Int?,
    ) {
        fun toFields() = WorkoutPlanItemFields(exercise, targetSetsPerWeek, effectiveFromWeekEpochMs, repsPerSet)
        companion object {
            fun from(row: WorkoutPlanItem) = WorkoutPlanItemPayload(row.guid, row.exercise, row.targetSetsPerWeek, row.effectiveFromWeekEpoch, row.repsPerSet)
        }
    }

    /** One row at a time, matching [com.kevin.legion.workouts.WorkoutController.generatePlan]'s
     * own `upsertAll` local write - each item gets its own [WorkoutPlanItem.guid] and its own
     * upsert/outbox entry, since `origin_guid` (this table's server upsert key) is per-row, not
     * per-plan.
     *
     * **Returns one outcome PER ROW, not one for the list**, because on the server-first path a
     * plan's items no longer share a fate: the engine can take four exercises and refuse the
     * fifth (a blank name, a non-positive set count - `server/api/body.py`'s
     * `WorkoutPlanItemSerializer`), and folding that into a single verdict would either hide four
     * rows that landed or claim one that did not.
     * [com.kevin.legion.workouts.WorkoutController.generatePlan] is what turns these into words.
     *
     * The local write was `upsertAll(rows)` in one call before this ticket; it is per-row now for
     * the same reason - a refused row must not be written, and a bulk write cannot skip one. */
    suspend fun setWorkoutPlanItems(
        context: Context,
        rows: List<WorkoutPlanItem>,
    ): List<WriteThroughOutcome<WorkoutPlanItem>> =
        rows.map { row ->
            write(
                context, row,
                Upsert(
                    target = OutboxTarget.BODY_WORKOUT_PLAN_ITEMS,
                    localId = row.id,
                    payload = Json.encodeToString(
                        WorkoutPlanItemPayload.serializer(),
                        WorkoutPlanItemPayload.from(row),
                    ),
                    store = { db -> db.workoutPlanItemDao().upsert(row) },
                    push = { backend ->
                        backend.upsertWorkoutPlanItem(row.guid, WorkoutPlanItemPayload.from(row).toFields())
                    },
                ),
            )
        }

    @Serializable
    internal data class WorkoutSetLogPayload(
        val guid: String,
        val exercise: String,
        val sets: Int,
        val reps: Int?,
        val weightValue: Double?,
        val weightUnit: String?,
        val loggedAtMs: Long,
        val trustTier: String,
    ) {
        fun toFields() = WorkoutSetLogFields(exercise, sets, reps, weightValue, weightUnit, loggedAtMs, trustTier)
        companion object {
            fun from(row: WorkoutSetLog) = WorkoutSetLogPayload(row.guid, row.exercise, row.sets, row.reps, row.weightValue, row.weightUnit, row.loggedAt, row.trustTier.name)
        }
    }

    suspend fun addWorkoutSetLog(context: Context, row: WorkoutSetLog): WriteThroughOutcome<WorkoutSetLog> = write(
        context, row,
        Upsert(
            target = OutboxTarget.BODY_WORKOUT_SET_LOGS,
            localId = row.id,
            payload = Json.encodeToString(WorkoutSetLogPayload.serializer(), WorkoutSetLogPayload.from(row)),
            store = { db -> db.workoutSetLogDao().insert(row) },
            push = { backend -> backend.upsertWorkoutSetLog(row.guid, WorkoutSetLogPayload.from(row).toFields()) },
        ),
    )

    suspend fun deleteWorkoutSetLog(context: Context, log: WorkoutSetLog) {
        val db = CarDatabase.getDatabase(context)
        val backend = backend(context)
        if (backend == null) {
            db.workoutSetLogDao().deleteById(log.id)
            return
        }
        if (cancelPendingCreateIfPending(db, OutboxTarget.BODY_WORKOUT_SET_LOGS, log.id)) {
            db.workoutSetLogDao().deleteById(log.id)
            return
        }
        db.workoutSetLogDao().update(log.copy(deleted = true, updatedAtMs = System.currentTimeMillis()))
        val result = backend.softDeleteWorkoutSetLog(log.guid)
        if (result.isFailure) {
            enqueue(
                db, OutboxTarget.BODY_WORKOUT_SET_LOGS, OutboxOperation.SOFT_DELETE, log.id,
                Json.encodeToString(BodyDeletePayload.serializer(), BodyDeletePayload(log.guid)),
                result.exceptionOrNull()?.message,
            )
        }
    }
}

/** The wire shape queued for every [OutboxOperation.SOFT_DELETE] entry across all four body log
 * tables - just the [BodyBackend]'s own upsert key, matching [EventDeleteOutboxPayload]'s shape
 * (a delete needs nothing but the identity of what to delete). Shared across tables because
 * every body softDelete function takes the identical single `originGuid: String` argument. */
@Serializable
internal data class BodyDeletePayload(val guid: String)

/**
 * Retries every still-pending body-table [OutboxEntry], across all eight
 * [com.kevin.legion.data.local.OutboxTarget] BODY_* constants - mirrors [EventsOutboxDrain]'s own
 * shape and bounded-attempts reasoning exactly (see that object's own class doc for why attempt
 * count, not failure type, is the bound). `ui/MainActivity.kt`'s `onResume` hook calls this BEFORE
 * [BodySync.maybeAutoPull], same load-bearing ordering [EventsOutboxDrain]'s own class doc explains
 * for events: a local mutation must be attempted against the server before that server's own state
 * is read back, or a same-timestamp LWW tie has nothing local yet to reconcile against.
 */
object BodyOutboxDrain {
    const val MAX_ATTEMPTS = EventsOutboxDrain.MAX_ATTEMPTS

    data class DrainReport(val succeeded: Int, val stillPending: Int, val poisoned: Int) {
        operator fun plus(other: DrainReport) = DrainReport(
            succeeded + other.succeeded, stillPending + other.stillPending, poisoned + other.poisoned,
        )
    }

    suspend fun drain(context: Context, backend: BodyBackend): DrainReport {
        val db = CarDatabase.getDatabase(context)
        var total = DrainReport(0, 0, 0)

        total += drainOne(db, OutboxTarget.BODY_BODYWEIGHT_LOGS) { entry ->
            when (entry.operation) {
                OutboxOperation.UPSERT -> {
                    val p = Json.decodeFromString(BodyWriteThrough.BodyweightLogPayload.serializer(), entry.payload)
                    backend.upsertBodyweightLog(p.guid, p.toFields())
                }
                OutboxOperation.SOFT_DELETE -> {
                    val p = Json.decodeFromString(BodyDeletePayload.serializer(), entry.payload)
                    backend.softDeleteBodyweightLog(p.guid)
                }
                else -> Result.success(Unit)
            }
        }
        total += drainOne(db, OutboxTarget.BODY_MEAL_LOGS) { entry ->
            when (entry.operation) {
                OutboxOperation.UPSERT -> {
                    val p = Json.decodeFromString(BodyWriteThrough.MealLogPayload.serializer(), entry.payload)
                    backend.upsertMealLog(p.guid, p.toFields())
                }
                OutboxOperation.SOFT_DELETE -> {
                    val p = Json.decodeFromString(BodyDeletePayload.serializer(), entry.payload)
                    backend.softDeleteMealLog(p.guid)
                }
                else -> Result.success(Unit)
            }
        }
        total += drainOne(db, OutboxTarget.BODY_MEAL_TARGETS) { entry ->
            when (entry.operation) {
                OutboxOperation.UPSERT -> {
                    val p = Json.decodeFromString(BodyWriteThrough.MealTargetPayload.serializer(), entry.payload)
                    backend.upsertMealTarget(p.guid, p.toFields())
                }
                else -> Result.success(Unit)
            }
        }
        total += drainOne(db, OutboxTarget.BODY_SLEEP_LOGS) { entry ->
            when (entry.operation) {
                OutboxOperation.UPSERT -> {
                    val p = Json.decodeFromString(BodyWriteThrough.SleepLogPayload.serializer(), entry.payload)
                    backend.upsertSleepLog(p.guid, p.toFields())
                }
                OutboxOperation.SOFT_DELETE -> {
                    val p = Json.decodeFromString(BodyDeletePayload.serializer(), entry.payload)
                    backend.softDeleteSleepLog(p.guid)
                }
                else -> Result.success(Unit)
            }
        }
        total += drainOne(db, OutboxTarget.BODY_SLEEP_TARGETS) { entry ->
            when (entry.operation) {
                OutboxOperation.UPSERT -> {
                    val p = Json.decodeFromString(BodyWriteThrough.SleepTargetPayload.serializer(), entry.payload)
                    backend.upsertSleepTarget(p.guid, p.toFields())
                }
                else -> Result.success(Unit)
            }
        }
        total += drainOne(db, OutboxTarget.BODY_WORKOUT_PLANS) { entry ->
            when (entry.operation) {
                OutboxOperation.UPSERT -> {
                    val p = Json.decodeFromString(BodyWriteThrough.WorkoutPlanPayload.serializer(), entry.payload)
                    backend.upsertWorkoutPlan(p.guid, p.toFields())
                }
                else -> Result.success(Unit)
            }
        }
        total += drainOne(db, OutboxTarget.BODY_WORKOUT_PLAN_ITEMS) { entry ->
            when (entry.operation) {
                OutboxOperation.UPSERT -> {
                    val p = Json.decodeFromString(BodyWriteThrough.WorkoutPlanItemPayload.serializer(), entry.payload)
                    backend.upsertWorkoutPlanItem(p.guid, p.toFields())
                }
                else -> Result.success(Unit)
            }
        }
        total += drainOne(db, OutboxTarget.BODY_WORKOUT_SET_LOGS) { entry ->
            when (entry.operation) {
                OutboxOperation.UPSERT -> {
                    val p = Json.decodeFromString(BodyWriteThrough.WorkoutSetLogPayload.serializer(), entry.payload)
                    backend.upsertWorkoutSetLog(p.guid, p.toFields())
                }
                OutboxOperation.SOFT_DELETE -> {
                    val p = Json.decodeFromString(BodyDeletePayload.serializer(), entry.payload)
                    backend.softDeleteWorkoutSetLog(p.guid)
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
     * matters. No-ops silently when Supabase is not configured or nobody is signed in - via
     * [SupabaseAuth.resolveSignedInUserId] (cold-start fix, 2026-09-02: a raw `currentUserId()`
     * guard here used to race the async session restore the same way [EventsSync.maybeAutoPull]'s
     * own doc comment traces; this function is already `suspend` so it can just await the
     * shared resolver instead). */
    suspend fun maybeDrain(context: Context) {
        val app = context.applicationContext
        // Transport switch (django-engine Phase 5). The backend comes from EngineBackends now,
        // which answers Supabase or Django per EngineTransport; `body` still defaults to Supabase.
        // The Supabase session gate below is SKIPPED on the Django branch, and that is the whole
        // reason the transport is read here rather than only inside the resolver: a device signed
        // in to an engine has no Supabase session to resolve, so gating on one would leave a
        // flipped aspect silently never draining.
        val onDjango = EngineTransport(app).transportFor(EngineBackends.ASPECT_BODY) == Transport.DJANGO
        val backend = EngineBackends(app).bodyBackend() ?: return
        if (!onDjango && SupabaseAuth(app).resolveSignedInUserId() == null) return
        try {
            val report = drain(app, backend)
            MidnightEvents.bodyOutboxDrainSucceeded(report.succeeded, report.stillPending, report.poisoned)
        } catch (e: Exception) {
            MidnightEvents.bodyOutboxDrainFailed(e)
        }
    }
}

