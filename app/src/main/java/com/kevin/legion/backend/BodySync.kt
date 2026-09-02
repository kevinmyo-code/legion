package com.kevin.legion.backend

import android.content.Context
import com.kevin.legion.MidnightEvents
import com.kevin.legion.data.local.BodyweightLog
import com.kevin.legion.data.local.CarDatabase
import com.kevin.legion.data.local.MealLog
import com.kevin.legion.data.local.MealTarget
import com.kevin.legion.data.local.SleepLog
import com.kevin.legion.data.local.SleepTarget
import com.kevin.legion.data.local.WorkoutPlan
import com.kevin.legion.data.local.WorkoutPlanItem
import com.kevin.legion.data.local.WorkoutSetLog
import com.kevin.legion.plan.TrustTier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Per-table install-scoped high-water marks for [BodySync.pull] - one watermark PER body table
 * (unlike [EventsPullCursor]'s single one), keyed in one shared `SharedPreferences` file. Eight
 * independent watermarks, not one shared minimum across all eight, so that a quiet table (no
 * bodyweight logged this week) never holds back a busy one (three meals a day) from advancing -
 * each table's own [BodyBackend.fetchChangedXSince] call is independent of every other table's,
 * so their cursors should be too.
 *
 * **A missing watermark means "fetch everything", never "fetch nothing"** - same [EventsPullCursor]
 * guarantee, same reasoning: defaults to 0 (1970-01-01), and every real row's `updated_at` is
 * `>= 0`, so a fresh install's first pull asks for the entire table.
 */
internal object BodyPullCursor {
    private const val PREFS = "body_pull_cursor"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun lastPulledAtMs(context: Context, table: String): Long = prefs(context).getLong(table, 0L)

    /** Persisted only after [BodySync.pull]'s own per-table sub-pull has fully merged its batch -
     * see [EventsSync.pull]'s own doc comment for why advancing on a partial run would be wrong;
     * identical reasoning applies per table here. */
    fun advance(context: Context, table: String, atMs: Long) {
        prefs(context).edit().putLong(table, atMs).apply()
    }
}

/**
 * The generic merge [EventsSync.pull] performs for one table, factored out so it is written and
 * tested exactly ONCE rather than by hand eight times - the accessor lambdas are the only thing
 * that varies per body table, and eight independent copies of this branch logic is eight
 * independent chances to get rule 6 ("a local row the server does not have is left alone") wrong
 * in exactly one of them. **The five rules below are [EventsSync.pull]'s own, verbatim, applied
 * generically**:
 *
 * - server row, no local match -> insert
 * - both present -> last-write-wins on updated-at, server wins an exact tie (this device has no
 *   guarantee every OTHER device has already pushed everything it knows, so a tie resolves toward
 *   the shared server state rather than toward whichever write happened to run first locally)
 * - server tombstone WITH a local match -> soft-delete locally (idempotent: an already-deleted
 *   local row is left untouched, never re-written)
 * - server tombstone with NO local match -> skip entirely, never inserted as fresh dead weight
 * - local row the server lacks -> untouched (this function never iterates local rows looking for
 *   one to delete; it only ever looks up a MATCH for a row that showed up in [remoteRows])
 */
internal object BodyMerge {
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

    /**
     * @param R the remote wire type ([RemoteBodyweightLog] etc).
     * @param L the local Room entity type ([BodyweightLog] etc).
     * @param remoteRows this run's [BodyBackend.fetchChangedXSince] result - active AND
     *   tombstoned, never pre-filtered.
     * @param localRows every local row for this table, INCLUDING already soft-deleted ones (never
     *   an active-only read) - see [EventsSync.pull]'s own "getAll(), not getAllActive()" comment
     *   for why: a server tombstone must be able to find an already-deleted local row rather than
     *   looking like "no local match" and resurrecting it.
     * @param remoteGuid/[localGuid] the natural-key accessor both sides are matched on - unlike
     *   [EventsSync.pull], no serverId fallback branch: every body row has a real guid from the
     *   moment it is created (see [BodyBackend]'s own class doc), so there is no placeholder-guid
     *   case to fall back past.
     * @param remoteUpdatedAtMs/[localUpdatedAtMs] the LWW clock - [MealTarget]/[SleepTarget]/
     *   [WorkoutPlan]/[WorkoutPlanItem] pass their own `updatedAt` here rather than a same-named
     *   `updatedAtMs`, per those entities' own v60 doc comments.
     * @param toInserted builds a brand-new local row (Room id = 0, autoincrement) from a remote
     *   row with no local match.
     * @param toMerged builds the merged local row - the existing row's OWN local [L] surrogate id
     *   preserved, every other column replaced by the remote row's values.
     * @param withDeletedFlag returns [existing] with only its own deleted-tombstone flag flipped
     *   true and its LWW clock bumped to [remoteUpdatedAtMs] of the tombstone - never a full
     *   [toMerged] overwrite on the tombstone branch, so a field this local row changed after the
     *   remote tombstone was read (impossible today, since nothing edits a body row in place, but
     *   kept correct on principle) is not silently clobbered by whatever the tombstoned row's
     *   other columns happened to read as of deletion.
     */
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
        // Rule 6, by omission: nothing above ever iterates localRows looking for a row to delete -
        // localByGuid is read only to find a match for a row that showed up in remoteRows.

        return MergeReport(inserted, updated, skippedLocalNewer, tombstoned, skippedTombstoneNoLocalMatch)
    }
}

/**
 * The body aspect's live pull - eight independent sub-pulls, one per table, each following
 * [BodyMerge.merge]'s rules exactly. Mirrors [EventsSync]'s own shape (per-table watermark,
 * `fetchChangedSince` not `fetchActive`, drain-then-pull ordering owned by the caller) - **this is
 * the template for six more aspects**, so the shape here is deliberately the one worth copying,
 * not a one-off.
 */
object BodySync {
    /** Combined across all eight tables - see [BodyMerge.MergeReport] for what each field counts. */
    data class PullReport(
        val inserted: Int,
        val updated: Int,
        val skippedLocalNewer: Int,
        val tombstoned: Int,
        val skippedTombstoneNoLocalMatch: Int,
    )

    private fun BodyMerge.MergeReport.toPullReport() =
        PullReport(inserted, updated, skippedLocalNewer, tombstoned, skippedTombstoneNoLocalMatch)

    private operator fun PullReport.plus(other: PullReport) = PullReport(
        inserted + other.inserted,
        updated + other.updated,
        skippedLocalNewer + other.skippedLocalNewer,
        tombstoned + other.tombstoned,
        skippedTombstoneNoLocalMatch + other.skippedTombstoneNoLocalMatch,
    )

    /** Pulls and merges all eight body tables. Each table's watermark advances independently and
     * only after that table's own batch is fully merged - a failure partway through (an exception
     * from one table's [BodyBackend.fetchChangedXSince]) throws straight out, same posture as
     * [EventsSync.pull], leaving every watermark this call has not yet reached untouched. */
    suspend fun pull(context: Context, backend: BodyBackend): PullReport {
        var total = PullReport(0, 0, 0, 0, 0)
        total += pullBodyweightLogs(context, backend)
        total += pullMealLogs(context, backend)
        total += pullMealTargets(context, backend)
        total += pullSleepLogs(context, backend)
        total += pullSleepTargets(context, backend)
        total += pullWorkoutPlans(context, backend)
        total += pullWorkoutPlanItems(context, backend)
        total += pullWorkoutSetLogs(context, backend)
        return total
    }

    private const val T_BODYWEIGHT = "bodyweight_logs"
    private const val T_MEAL_LOGS = "meal_logs"
    private const val T_MEAL_TARGETS = "meal_targets"
    private const val T_SLEEP_LOGS = "sleep_logs"
    private const val T_SLEEP_TARGETS = "sleep_targets"
    private const val T_WORKOUT_PLANS = "workout_plans"
    private const val T_WORKOUT_PLAN_ITEMS = "workout_plan_items"
    private const val T_WORKOUT_SET_LOGS = "workout_set_logs"

    private suspend fun pullBodyweightLogs(context: Context, backend: BodyBackend): PullReport {
        val db = CarDatabase.getDatabase(context)
        val sinceMs = BodyPullCursor.lastPulledAtMs(context, T_BODYWEIGHT)
        val remote = backend.fetchChangedBodyweightLogsSince(sinceMs).getOrThrow()
        val local = db.bodyweightLogDao().getAll()
        val report = BodyMerge.merge(
            remoteRows = remote,
            localRows = local,
            remoteGuid = { it.originGuid },
            localGuid = { it.guid },
            remoteDeleted = { it.deleted },
            remoteUpdatedAtMs = { it.updatedAtMs },
            localUpdatedAtMs = { it.updatedAtMs },
            localDeleted = { it.deleted },
            toInserted = { r ->
                BodyweightLog(
                    id = 0,
                    weightValue = r.weightValue,
                    weightUnit = r.weightUnit,
                    loggedAt = r.loggedAtMs,
                    trustTier = TrustTier.valueOf(r.trustTier),
                    guid = r.originGuid,
                    serverId = r.serverId,
                    updatedAtMs = r.updatedAtMs,
                    deleted = r.deleted,
                )
            },
            toMerged = { r, existing ->
                existing.copy(
                    weightValue = r.weightValue,
                    weightUnit = r.weightUnit,
                    loggedAt = r.loggedAtMs,
                    trustTier = TrustTier.valueOf(r.trustTier),
                    serverId = r.serverId,
                    updatedAtMs = r.updatedAtMs,
                    deleted = r.deleted,
                )
            },
            withDeletedFlag = { existing, atMs -> existing.copy(deleted = true, updatedAtMs = atMs) },
            insert = { db.bodyweightLogDao().insert(it) },
            update = { db.bodyweightLogDao().update(it) },
        )
        remote.maxOfOrNull { it.updatedAtMs }?.let { BodyPullCursor.advance(context, T_BODYWEIGHT, it) }
        return report.toPullReport()
    }

    private suspend fun pullMealLogs(context: Context, backend: BodyBackend): PullReport {
        val db = CarDatabase.getDatabase(context)
        val sinceMs = BodyPullCursor.lastPulledAtMs(context, T_MEAL_LOGS)
        val remote = backend.fetchChangedMealLogsSince(sinceMs).getOrThrow()
        val local = db.mealLogDao().getAll()
        val report = BodyMerge.merge(
            remoteRows = remote,
            localRows = local,
            remoteGuid = { it.originGuid },
            localGuid = { it.guid },
            remoteDeleted = { it.deleted },
            remoteUpdatedAtMs = { it.updatedAtMs },
            localUpdatedAtMs = { it.updatedAtMs },
            localDeleted = { it.deleted },
            toInserted = { r ->
                MealLog(
                    id = 0,
                    description = r.description,
                    caloriesKcal = r.caloriesKcal,
                    proteinG = r.proteinG,
                    carbsG = r.carbsG,
                    fatG = r.fatG,
                    loggedAt = r.loggedAtMs,
                    sourceImagePath = r.sourceImagePath,
                    trustTier = TrustTier.valueOf(r.trustTier),
                    guid = r.originGuid,
                    serverId = r.serverId,
                    updatedAtMs = r.updatedAtMs,
                    deleted = r.deleted,
                )
            },
            toMerged = { r, existing ->
                existing.copy(
                    description = r.description,
                    caloriesKcal = r.caloriesKcal,
                    proteinG = r.proteinG,
                    carbsG = r.carbsG,
                    fatG = r.fatG,
                    loggedAt = r.loggedAtMs,
                    sourceImagePath = r.sourceImagePath,
                    trustTier = TrustTier.valueOf(r.trustTier),
                    serverId = r.serverId,
                    updatedAtMs = r.updatedAtMs,
                    deleted = r.deleted,
                )
            },
            withDeletedFlag = { existing, atMs -> existing.copy(deleted = true, updatedAtMs = atMs) },
            insert = { db.mealLogDao().insert(it) },
            update = { db.mealLogDao().update(it) },
        )
        remote.maxOfOrNull { it.updatedAtMs }?.let { BodyPullCursor.advance(context, T_MEAL_LOGS, it) }
        return report.toPullReport()
    }

    private suspend fun pullMealTargets(context: Context, backend: BodyBackend): PullReport {
        val db = CarDatabase.getDatabase(context)
        val sinceMs = BodyPullCursor.lastPulledAtMs(context, T_MEAL_TARGETS)
        val remote = backend.fetchChangedMealTargetsSince(sinceMs).getOrThrow()
        val local = db.mealTargetDao().getAll()
        val report = BodyMerge.merge(
            remoteRows = remote,
            localRows = local,
            remoteGuid = { it.originGuid },
            localGuid = { it.guid },
            remoteDeleted = { it.deleted },
            remoteUpdatedAtMs = { it.updatedAtMs },
            // MealTarget has no updatedAtMs column - `updatedAt` doubles as the sync clock, per
            // that entity's own v60 doc comment.
            localUpdatedAtMs = { it.updatedAt },
            localDeleted = { it.deleted },
            toInserted = { r ->
                MealTarget(
                    id = 0,
                    caloriesKcal = r.caloriesKcal,
                    proteinG = r.proteinG,
                    carbsG = r.carbsG,
                    fatG = r.fatG,
                    effectiveFromDateEpoch = r.effectiveFromDateEpochMs,
                    updatedAt = r.updatedAtMs,
                    guid = r.originGuid,
                    serverId = r.serverId,
                    deleted = r.deleted,
                )
            },
            toMerged = { r, existing ->
                existing.copy(
                    caloriesKcal = r.caloriesKcal,
                    proteinG = r.proteinG,
                    carbsG = r.carbsG,
                    fatG = r.fatG,
                    effectiveFromDateEpoch = r.effectiveFromDateEpochMs,
                    updatedAt = r.updatedAtMs,
                    serverId = r.serverId,
                    deleted = r.deleted,
                )
            },
            withDeletedFlag = { existing, atMs -> existing.copy(deleted = true, updatedAt = atMs) },
            insert = { db.mealTargetDao().upsert(it) },
            update = { db.mealTargetDao().update(it) },
        )
        remote.maxOfOrNull { it.updatedAtMs }?.let { BodyPullCursor.advance(context, T_MEAL_TARGETS, it) }
        return report.toPullReport()
    }

    private suspend fun pullSleepLogs(context: Context, backend: BodyBackend): PullReport {
        val db = CarDatabase.getDatabase(context)
        val sinceMs = BodyPullCursor.lastPulledAtMs(context, T_SLEEP_LOGS)
        val remote = backend.fetchChangedSleepLogsSince(sinceMs).getOrThrow()
        val local = db.sleepLogDao().getAll()
        val report = BodyMerge.merge(
            remoteRows = remote,
            localRows = local,
            remoteGuid = { it.originGuid },
            localGuid = { it.guid },
            remoteDeleted = { it.deleted },
            remoteUpdatedAtMs = { it.updatedAtMs },
            localUpdatedAtMs = { it.updatedAtMs },
            localDeleted = { it.deleted },
            toInserted = { r ->
                SleepLog(
                    id = 0,
                    sleepDate = r.sleepDateEpochMs,
                    durationMinutes = r.durationMinutes,
                    quality = r.quality,
                    notes = r.notes,
                    loggedAt = r.loggedAtMs,
                    trustTier = TrustTier.valueOf(r.trustTier),
                    guid = r.originGuid,
                    serverId = r.serverId,
                    updatedAtMs = r.updatedAtMs,
                    deleted = r.deleted,
                )
            },
            toMerged = { r, existing ->
                existing.copy(
                    sleepDate = r.sleepDateEpochMs,
                    durationMinutes = r.durationMinutes,
                    quality = r.quality,
                    notes = r.notes,
                    loggedAt = r.loggedAtMs,
                    trustTier = TrustTier.valueOf(r.trustTier),
                    serverId = r.serverId,
                    updatedAtMs = r.updatedAtMs,
                    deleted = r.deleted,
                )
            },
            withDeletedFlag = { existing, atMs -> existing.copy(deleted = true, updatedAtMs = atMs) },
            insert = { db.sleepLogDao().insert(it) },
            update = { db.sleepLogDao().update(it) },
        )
        remote.maxOfOrNull { it.updatedAtMs }?.let { BodyPullCursor.advance(context, T_SLEEP_LOGS, it) }
        return report.toPullReport()
    }

    private suspend fun pullSleepTargets(context: Context, backend: BodyBackend): PullReport {
        val db = CarDatabase.getDatabase(context)
        val sinceMs = BodyPullCursor.lastPulledAtMs(context, T_SLEEP_TARGETS)
        val remote = backend.fetchChangedSleepTargetsSince(sinceMs).getOrThrow()
        val local = db.sleepTargetDao().getAll()
        val report = BodyMerge.merge(
            remoteRows = remote,
            localRows = local,
            remoteGuid = { it.originGuid },
            localGuid = { it.guid },
            remoteDeleted = { it.deleted },
            remoteUpdatedAtMs = { it.updatedAtMs },
            localUpdatedAtMs = { it.updatedAt },
            localDeleted = { it.deleted },
            toInserted = { r ->
                SleepTarget(
                    id = 0,
                    targetMinutes = r.targetMinutes,
                    effectiveFromDateEpoch = r.effectiveFromDateEpochMs,
                    updatedAt = r.updatedAtMs,
                    guid = r.originGuid,
                    serverId = r.serverId,
                    deleted = r.deleted,
                )
            },
            toMerged = { r, existing ->
                existing.copy(
                    targetMinutes = r.targetMinutes,
                    effectiveFromDateEpoch = r.effectiveFromDateEpochMs,
                    updatedAt = r.updatedAtMs,
                    serverId = r.serverId,
                    deleted = r.deleted,
                )
            },
            withDeletedFlag = { existing, atMs -> existing.copy(deleted = true, updatedAt = atMs) },
            insert = { db.sleepTargetDao().upsert(it) },
            update = { db.sleepTargetDao().update(it) },
        )
        remote.maxOfOrNull { it.updatedAtMs }?.let { BodyPullCursor.advance(context, T_SLEEP_TARGETS, it) }
        return report.toPullReport()
    }

    private suspend fun pullWorkoutPlans(context: Context, backend: BodyBackend): PullReport {
        val db = CarDatabase.getDatabase(context)
        val sinceMs = BodyPullCursor.lastPulledAtMs(context, T_WORKOUT_PLANS)
        val remote = backend.fetchChangedWorkoutPlansSince(sinceMs).getOrThrow()
        val local = db.workoutPlanDao().getAll()
        val report = BodyMerge.merge(
            remoteRows = remote,
            localRows = local,
            remoteGuid = { it.originGuid },
            localGuid = { it.guid },
            remoteDeleted = { it.deleted },
            remoteUpdatedAtMs = { it.updatedAtMs },
            localUpdatedAtMs = { it.updatedAt },
            localDeleted = { it.deleted },
            toInserted = { r ->
                WorkoutPlan(
                    id = 0,
                    sessionsPerWeek = r.sessionsPerWeek,
                    effectiveFromWeekEpoch = r.effectiveFromWeekEpochMs,
                    updatedAt = r.updatedAtMs,
                    guid = r.originGuid,
                    serverId = r.serverId,
                    deleted = r.deleted,
                )
            },
            toMerged = { r, existing ->
                existing.copy(
                    sessionsPerWeek = r.sessionsPerWeek,
                    effectiveFromWeekEpoch = r.effectiveFromWeekEpochMs,
                    updatedAt = r.updatedAtMs,
                    serverId = r.serverId,
                    deleted = r.deleted,
                )
            },
            withDeletedFlag = { existing, atMs -> existing.copy(deleted = true, updatedAt = atMs) },
            insert = { db.workoutPlanDao().upsert(it) },
            update = { db.workoutPlanDao().update(it) },
        )
        remote.maxOfOrNull { it.updatedAtMs }?.let { BodyPullCursor.advance(context, T_WORKOUT_PLANS, it) }
        return report.toPullReport()
    }

    private suspend fun pullWorkoutPlanItems(context: Context, backend: BodyBackend): PullReport {
        val db = CarDatabase.getDatabase(context)
        val sinceMs = BodyPullCursor.lastPulledAtMs(context, T_WORKOUT_PLAN_ITEMS)
        val remote = backend.fetchChangedWorkoutPlanItemsSince(sinceMs).getOrThrow()
        val local = db.workoutPlanItemDao().getAll()
        val report = BodyMerge.merge(
            remoteRows = remote,
            localRows = local,
            remoteGuid = { it.originGuid },
            localGuid = { it.guid },
            remoteDeleted = { it.deleted },
            remoteUpdatedAtMs = { it.updatedAtMs },
            localUpdatedAtMs = { it.updatedAt },
            localDeleted = { it.deleted },
            toInserted = { r ->
                WorkoutPlanItem(
                    id = 0,
                    exercise = r.exercise,
                    targetSetsPerWeek = r.targetSetsPerWeek,
                    effectiveFromWeekEpoch = r.effectiveFromWeekEpochMs,
                    updatedAt = r.updatedAtMs,
                    repsPerSet = r.repsPerSet,
                    guid = r.originGuid,
                    serverId = r.serverId,
                    deleted = r.deleted,
                )
            },
            toMerged = { r, existing ->
                existing.copy(
                    exercise = r.exercise,
                    targetSetsPerWeek = r.targetSetsPerWeek,
                    effectiveFromWeekEpoch = r.effectiveFromWeekEpochMs,
                    updatedAt = r.updatedAtMs,
                    repsPerSet = r.repsPerSet,
                    serverId = r.serverId,
                    deleted = r.deleted,
                )
            },
            withDeletedFlag = { existing, atMs -> existing.copy(deleted = true, updatedAt = atMs) },
            insert = { db.workoutPlanItemDao().upsert(it) },
            update = { db.workoutPlanItemDao().update(it) },
        )
        remote.maxOfOrNull { it.updatedAtMs }?.let { BodyPullCursor.advance(context, T_WORKOUT_PLAN_ITEMS, it) }
        return report.toPullReport()
    }

    private suspend fun pullWorkoutSetLogs(context: Context, backend: BodyBackend): PullReport {
        val db = CarDatabase.getDatabase(context)
        val sinceMs = BodyPullCursor.lastPulledAtMs(context, T_WORKOUT_SET_LOGS)
        val remote = backend.fetchChangedWorkoutSetLogsSince(sinceMs).getOrThrow()
        val local = db.workoutSetLogDao().getAll()
        val report = BodyMerge.merge(
            remoteRows = remote,
            localRows = local,
            remoteGuid = { it.originGuid },
            localGuid = { it.guid },
            remoteDeleted = { it.deleted },
            remoteUpdatedAtMs = { it.updatedAtMs },
            localUpdatedAtMs = { it.updatedAtMs },
            localDeleted = { it.deleted },
            toInserted = { r ->
                WorkoutSetLog(
                    id = 0,
                    exercise = r.exercise,
                    sets = r.sets,
                    reps = r.reps,
                    weightValue = r.weightValue,
                    weightUnit = r.weightUnit,
                    loggedAt = r.loggedAtMs,
                    trustTier = TrustTier.valueOf(r.trustTier),
                    // No server-side sourceListItemId - see RemoteWorkoutSetLog's own doc comment.
                    sourceListItemId = null,
                    guid = r.originGuid,
                    serverId = r.serverId,
                    updatedAtMs = r.updatedAtMs,
                    deleted = r.deleted,
                )
            },
            toMerged = { r, existing ->
                existing.copy(
                    exercise = r.exercise,
                    sets = r.sets,
                    reps = r.reps,
                    weightValue = r.weightValue,
                    weightUnit = r.weightUnit,
                    loggedAt = r.loggedAtMs,
                    trustTier = TrustTier.valueOf(r.trustTier),
                    serverId = r.serverId,
                    updatedAtMs = r.updatedAtMs,
                    deleted = r.deleted,
                )
            },
            withDeletedFlag = { existing, atMs -> existing.copy(deleted = true, updatedAtMs = atMs) },
            insert = { db.workoutSetLogDao().insert(it) },
            update = { db.workoutSetLogDao().update(it) },
        )
        remote.maxOfOrNull { it.updatedAtMs }?.let { BodyPullCursor.advance(context, T_WORKOUT_SET_LOGS, it) }
        return report.toPullReport()
    }

    // --- Foreground auto-trigger, mirroring EventsSync's own shape -----------------------------

    private val autoPullScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile private var lastAutoPullAt = 0L

    private const val AUTO_PULL_MIN_INTERVAL_MS = 5 * 60 * 1000L
    private const val AUTO_PULL_RETRY_DELAY_MS = 1_000L

    /** Same bounded-retry shape as [EventsSync.resolveUserIdForAutoPull] - `internal` so a test can
     * drive it directly. */
    internal suspend fun resolveUserIdForAutoPull(
        auth: SupabaseAuth,
        retryDelayMs: Long = AUTO_PULL_RETRY_DELAY_MS,
    ): String? {
        var readiness = auth.awaitCurrentUserId()
        if (readiness is UserIdReadiness.StillRestoring) {
            delay(retryDelayMs)
            readiness = auth.awaitCurrentUserId()
        }
        return (readiness as? UserIdReadiness.Settled)?.userId
    }

    /**
     * `MainActivity.onResume`'s hook, called alongside [EventsSync.maybeAutoPull] - see
     * [BodyOutboxDrain.maybeDrain]'s own doc comment for why the drain runs first. No-ops silently
     * when Supabase is not configured or nobody is signed in, same posture as
     * [EventsSync.maybeAutoPull].
     */
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
                val report = pull(app, SupabaseBodyBackend(client))
                MidnightEvents.bodyAutoPullSucceeded(
                    report.inserted, report.updated, report.skippedLocalNewer,
                    report.tombstoned, report.skippedTombstoneNoLocalMatch,
                )
            } catch (e: Exception) {
                MidnightEvents.bodyAutoPullFailed(e)
            }
        }
    }
}
