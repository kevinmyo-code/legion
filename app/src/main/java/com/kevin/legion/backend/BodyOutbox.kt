package com.kevin.legion.backend

import android.content.Context
import com.kevin.legion.MidnightEvents
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
 * **Local write always happens first, unconditionally** - the UI never blocks on or lies about a
 * network round trip, same posture [EventsAppointmentWriter]'s own class doc states. A push
 * failure never undoes the local write; it enqueues an [OutboxEntry] so [BodyOutboxDrain] can
 * retry it later. On an unconfigured install nothing is pushed and nothing is queued.
 */
object BodyWriteThrough {
    /** Test seam, same mechanism as [EventsAppointmentWriter.backendOverride]. */
    @Volatile
    internal var backendOverride: BodyBackend? = null

    private fun backend(context: Context): BodyBackend? {
        backendOverride?.let { return it }
        val client = SupabaseClientProvider.get(context) ?: return null
        return SupabaseBodyBackend(client)
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

    suspend fun addBodyweightLog(context: Context, row: BodyweightLog): BodyweightLog {
        val db = CarDatabase.getDatabase(context)
        db.bodyweightLogDao().insert(row)
        val backend = backend(context) ?: return row
        val result = backend.upsertBodyweightLog(row.guid, BodyweightLogPayload.from(row).toFields())
        if (result.isFailure) {
            enqueue(
                db, OutboxTarget.BODY_BODYWEIGHT_LOGS, OutboxOperation.UPSERT, row.id,
                Json.encodeToString(BodyweightLogPayload.serializer(), BodyweightLogPayload.from(row)),
                result.exceptionOrNull()?.message,
            )
        }
        return row
    }

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

    suspend fun addMealLog(context: Context, row: MealLog): MealLog {
        val db = CarDatabase.getDatabase(context)
        db.mealLogDao().insert(row)
        val backend = backend(context) ?: return row
        val result = backend.upsertMealLog(row.guid, MealLogPayload.from(row).toFields())
        if (result.isFailure) {
            enqueue(
                db, OutboxTarget.BODY_MEAL_LOGS, OutboxOperation.UPSERT, row.id,
                Json.encodeToString(MealLogPayload.serializer(), MealLogPayload.from(row)),
                result.exceptionOrNull()?.message,
            )
        }
        return row
    }

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
    suspend fun setMealTarget(context: Context, row: MealTarget): MealTarget {
        val db = CarDatabase.getDatabase(context)
        db.mealTargetDao().upsert(row)
        val backend = backend(context) ?: return row
        val result = backend.upsertMealTarget(row.guid, MealTargetPayload.from(row).toFields())
        if (result.isFailure) {
            enqueue(
                db, OutboxTarget.BODY_MEAL_TARGETS, OutboxOperation.UPSERT, row.id,
                Json.encodeToString(MealTargetPayload.serializer(), MealTargetPayload.from(row)),
                result.exceptionOrNull()?.message,
            )
        }
        return row
    }

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

    suspend fun addSleepLog(context: Context, row: SleepLog): SleepLog {
        val db = CarDatabase.getDatabase(context)
        db.sleepLogDao().insert(row)
        val backend = backend(context) ?: return row
        val result = backend.upsertSleepLog(row.guid, SleepLogPayload.from(row).toFields())
        if (result.isFailure) {
            enqueue(
                db, OutboxTarget.BODY_SLEEP_LOGS, OutboxOperation.UPSERT, row.id,
                Json.encodeToString(SleepLogPayload.serializer(), SleepLogPayload.from(row)),
                result.exceptionOrNull()?.message,
            )
        }
        return row
    }

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

    suspend fun setSleepTarget(context: Context, row: SleepTarget): SleepTarget {
        val db = CarDatabase.getDatabase(context)
        db.sleepTargetDao().upsert(row)
        val backend = backend(context) ?: return row
        val result = backend.upsertSleepTarget(row.guid, SleepTargetPayload.from(row).toFields())
        if (result.isFailure) {
            enqueue(
                db, OutboxTarget.BODY_SLEEP_TARGETS, OutboxOperation.UPSERT, row.id,
                Json.encodeToString(SleepTargetPayload.serializer(), SleepTargetPayload.from(row)),
                result.exceptionOrNull()?.message,
            )
        }
        return row
    }

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

    suspend fun setWorkoutPlan(context: Context, row: WorkoutPlan): WorkoutPlan {
        val db = CarDatabase.getDatabase(context)
        db.workoutPlanDao().upsert(row)
        val backend = backend(context) ?: return row
        val result = backend.upsertWorkoutPlan(row.guid, WorkoutPlanPayload.from(row).toFields())
        if (result.isFailure) {
            enqueue(
                db, OutboxTarget.BODY_WORKOUT_PLANS, OutboxOperation.UPSERT, row.id,
                Json.encodeToString(WorkoutPlanPayload.serializer(), WorkoutPlanPayload.from(row)),
                result.exceptionOrNull()?.message,
            )
        }
        return row
    }

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
     * per-plan. */
    suspend fun setWorkoutPlanItems(context: Context, rows: List<WorkoutPlanItem>): List<WorkoutPlanItem> {
        val db = CarDatabase.getDatabase(context)
        db.workoutPlanItemDao().upsertAll(rows)
        val backend = backend(context) ?: return rows
        for (row in rows) {
            val result = backend.upsertWorkoutPlanItem(row.guid, WorkoutPlanItemPayload.from(row).toFields())
            if (result.isFailure) {
                enqueue(
                    db, OutboxTarget.BODY_WORKOUT_PLAN_ITEMS, OutboxOperation.UPSERT, row.id,
                    Json.encodeToString(WorkoutPlanItemPayload.serializer(), WorkoutPlanItemPayload.from(row)),
                    result.exceptionOrNull()?.message,
                )
            }
        }
        return rows
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

    suspend fun addWorkoutSetLog(context: Context, row: WorkoutSetLog): WorkoutSetLog {
        val db = CarDatabase.getDatabase(context)
        db.workoutSetLogDao().insert(row)
        val backend = backend(context) ?: return row
        val result = backend.upsertWorkoutSetLog(row.guid, WorkoutSetLogPayload.from(row).toFields())
        if (result.isFailure) {
            enqueue(
                db, OutboxTarget.BODY_WORKOUT_SET_LOGS, OutboxOperation.UPSERT, row.id,
                Json.encodeToString(WorkoutSetLogPayload.serializer(), WorkoutSetLogPayload.from(row)),
                result.exceptionOrNull()?.message,
            )
        }
        return row
    }

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
     * matters. No-ops silently when Supabase is not configured or nobody is signed in. */
    suspend fun maybeDrain(context: Context) {
        val app = context.applicationContext
        val client = SupabaseClientProvider.get(app) ?: return
        if (SupabaseAuth(app).currentUserId() == null) return
        try {
            val report = drain(app, SupabaseBodyBackend(client))
            MidnightEvents.bodyOutboxDrainSucceeded(report.succeeded, report.stillPending, report.poisoned)
        } catch (e: Exception) {
            MidnightEvents.bodyOutboxDrainFailed(e)
        }
    }
}

