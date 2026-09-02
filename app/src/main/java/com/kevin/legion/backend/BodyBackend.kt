package com.kevin.legion.backend

/**
 * The body aspect's Supabase seam - eight tables
 * (`supabase/migrations/20260902000200_aspect_body.sql`), one interface, mirroring
 * [FleetBackend]'s "many entities, one interface" shape rather than [EventsBackend]'s single-table
 * one. **This is the TEMPLATE for six more aspects** (body-supabase ticket brief, verbatim) - the
 * design choices below are the ones worth copying forward, not just the code:
 *
 * - **Every write is a genuine upsert keyed on [originGuid], never a create/update fork.**
 *   [EventsBackend.upsert] has to fork on whether [serverId] is null because `public.events` has
 *   no natural key besides its own server-generated uuid - see that function's own doc comment.
 *   Every body table has one from the moment the Room migration that added sync columns ran
 *   (`origin_guid not null unique`, see that migration's own header comment for why it is NOT
 *   nullable migration provenance the way it is on the phase-4 tables) - a local
 *   [com.kevin.legion.data.local.BodyweightLog.guid] and its seven siblings are minted at row
 *   creation and never regenerated, so [BodyWriteThrough] never needs to know or care whether a
 *   given row has round-tripped before. `upsert(originGuid, fields)` below is the whole story:
 *   Postgres decides insert-vs-update via `on conflict (origin_guid)`, not this interface.
 * - **[fetchChangedSince] returns tombstones too, never [Boolean]-filtered active-only** - the
 *   exact fix [EventsBackend.fetchChangedSince]'s own doc comment traces at length for the
 *   `fetchActive`-only bug that made a tombstone unreachable end to end. Built in from the start
 *   here rather than discovered the same way twice.
 * - **[softDelete] takes [originGuid], never a server uuid** - a local row may not have round-
 *   tripped yet (a create still sitting in the outbox) and there is nothing to look up a real
 *   [RemoteBodyweightLog.serverId] BY in that case; [originGuid] always exists, from the moment
 *   the row is written, so it is what every write-side call addresses a row by.
 *
 * Every function returns [Result], no [io.github.jan.supabase.SupabaseClient] in any signature,
 * matching [PlacesBackend]/[PantryBackend]'s own seam discipline.
 */

// ---------------------------------------------------------------------------------------------
// BODYWEIGHT
// ---------------------------------------------------------------------------------------------

/** A `public.bodyweight_logs` row as Postgres reports it. */
data class RemoteBodyweightLog(
    val serverId: String,
    val weightValue: Double,
    val weightUnit: String,
    val loggedAtMs: Long,
    val trustTier: String,
    val updatedAtMs: Long,
    val deleted: Boolean,
    val originGuid: String,
)

/** Every writable column on `public.bodyweight_logs` except the identity/lifecycle ones an upsert
 * never sets directly ([RemoteBodyweightLog.serverId]/`origin_guid`/`updated_at`/`deleted_at`/
 * `provenance`) - same "caller intent only" shape [EventFields] establishes. */
data class BodyweightLogFields(
    val weightValue: Double,
    val weightUnit: String,
    val loggedAtMs: Long,
    val trustTier: String,
)

// ---------------------------------------------------------------------------------------------
// MEALS
// ---------------------------------------------------------------------------------------------

/** A `public.meal_logs` row as Postgres reports it. [caloriesKcal]/[proteinG]/[carbsG]/[fatG] are
 * estimates, never gated - see the migration's own header comment. */
data class RemoteMealLog(
    val serverId: String,
    val description: String,
    val caloriesKcal: Int?,
    val proteinG: Double?,
    val carbsG: Double?,
    val fatG: Double?,
    val loggedAtMs: Long,
    val sourceImagePath: String?,
    val trustTier: String,
    val updatedAtMs: Long,
    val deleted: Boolean,
    val originGuid: String,
)

data class MealLogFields(
    val description: String,
    val caloriesKcal: Int?,
    val proteinG: Double?,
    val carbsG: Double?,
    val fatG: Double?,
    val loggedAtMs: Long,
    val sourceImagePath: String?,
    val trustTier: String,
)

/** A `public.meal_targets` row as Postgres reports it. No [trustTier][RemoteMealLog.trustTier] -
 * a target sits outside both trust tiers (ticket 05 D3), matching [MealTarget]'s own Room shape. */
data class RemoteMealTarget(
    val serverId: String,
    val caloriesKcal: Int,
    val proteinG: Double,
    val carbsG: Double,
    val fatG: Double,
    val effectiveFromDateEpochMs: Long,
    val updatedAtMs: Long,
    val deleted: Boolean,
    val originGuid: String,
)

data class MealTargetFields(
    val caloriesKcal: Int,
    val proteinG: Double,
    val carbsG: Double,
    val fatG: Double,
    val effectiveFromDateEpochMs: Long,
)

// ---------------------------------------------------------------------------------------------
// SLEEP
// ---------------------------------------------------------------------------------------------

/** A `public.sleep_logs` row as Postgres reports it. [sleepDateEpochMs] is the WAKE date - see
 * [SleepLog.sleepDate]'s own doc comment for the convention this mirrors. */
data class RemoteSleepLog(
    val serverId: String,
    val sleepDateEpochMs: Long,
    val durationMinutes: Int,
    val quality: Int?,
    val notes: String?,
    val loggedAtMs: Long,
    val trustTier: String,
    val updatedAtMs: Long,
    val deleted: Boolean,
    val originGuid: String,
)

data class SleepLogFields(
    val sleepDateEpochMs: Long,
    val durationMinutes: Int,
    val quality: Int?,
    val notes: String?,
    val loggedAtMs: Long,
    val trustTier: String,
)

data class RemoteSleepTarget(
    val serverId: String,
    val targetMinutes: Int,
    val effectiveFromDateEpochMs: Long,
    val updatedAtMs: Long,
    val deleted: Boolean,
    val originGuid: String,
)

data class SleepTargetFields(
    val targetMinutes: Int,
    val effectiveFromDateEpochMs: Long,
)

// ---------------------------------------------------------------------------------------------
// WORKOUTS
// ---------------------------------------------------------------------------------------------

data class RemoteWorkoutPlan(
    val serverId: String,
    val sessionsPerWeek: Int,
    val effectiveFromWeekEpochMs: Long,
    val updatedAtMs: Long,
    val deleted: Boolean,
    val originGuid: String,
)

data class WorkoutPlanFields(
    val sessionsPerWeek: Int,
    val effectiveFromWeekEpochMs: Long,
)

data class RemoteWorkoutPlanItem(
    val serverId: String,
    val exercise: String,
    val targetSetsPerWeek: Int,
    val effectiveFromWeekEpochMs: Long,
    val repsPerSet: Int?,
    val updatedAtMs: Long,
    val deleted: Boolean,
    val originGuid: String,
)

data class WorkoutPlanItemFields(
    val exercise: String,
    val targetSetsPerWeek: Int,
    val effectiveFromWeekEpochMs: Long,
    val repsPerSet: Int?,
)

/** A `public.workout_set_logs` row as Postgres reports it. **No `sourceListItemId`** - see the
 * migration's own comment on that table for why a phone-local `ListItem` row id has nothing to
 * carry it server-side; the dedup/untick logic it supports stays entirely local. */
data class RemoteWorkoutSetLog(
    val serverId: String,
    val exercise: String,
    val sets: Int,
    val reps: Int?,
    val weightValue: Double?,
    val weightUnit: String?,
    val loggedAtMs: Long,
    val trustTier: String,
    val updatedAtMs: Long,
    val deleted: Boolean,
    val originGuid: String,
)

data class WorkoutSetLogFields(
    val exercise: String,
    val sets: Int,
    val reps: Int?,
    val weightValue: Double?,
    val weightUnit: String?,
    val loggedAtMs: Long,
    val trustTier: String,
)

/** See this file's own class doc for the shared shape every one of the following 24 functions
 * follows. */
interface BodyBackend {
    suspend fun fetchChangedBodyweightLogsSince(sinceMs: Long): Result<List<RemoteBodyweightLog>>
    suspend fun upsertBodyweightLog(originGuid: String, fields: BodyweightLogFields): Result<RemoteBodyweightLog>
    suspend fun softDeleteBodyweightLog(originGuid: String): Result<Boolean>

    suspend fun fetchChangedMealLogsSince(sinceMs: Long): Result<List<RemoteMealLog>>
    suspend fun upsertMealLog(originGuid: String, fields: MealLogFields): Result<RemoteMealLog>
    suspend fun softDeleteMealLog(originGuid: String): Result<Boolean>

    suspend fun fetchChangedMealTargetsSince(sinceMs: Long): Result<List<RemoteMealTarget>>
    suspend fun upsertMealTarget(originGuid: String, fields: MealTargetFields): Result<RemoteMealTarget>
    suspend fun softDeleteMealTarget(originGuid: String): Result<Boolean>

    suspend fun fetchChangedSleepLogsSince(sinceMs: Long): Result<List<RemoteSleepLog>>
    suspend fun upsertSleepLog(originGuid: String, fields: SleepLogFields): Result<RemoteSleepLog>
    suspend fun softDeleteSleepLog(originGuid: String): Result<Boolean>

    suspend fun fetchChangedSleepTargetsSince(sinceMs: Long): Result<List<RemoteSleepTarget>>
    suspend fun upsertSleepTarget(originGuid: String, fields: SleepTargetFields): Result<RemoteSleepTarget>
    suspend fun softDeleteSleepTarget(originGuid: String): Result<Boolean>

    suspend fun fetchChangedWorkoutPlansSince(sinceMs: Long): Result<List<RemoteWorkoutPlan>>
    suspend fun upsertWorkoutPlan(originGuid: String, fields: WorkoutPlanFields): Result<RemoteWorkoutPlan>
    suspend fun softDeleteWorkoutPlan(originGuid: String): Result<Boolean>

    suspend fun fetchChangedWorkoutPlanItemsSince(sinceMs: Long): Result<List<RemoteWorkoutPlanItem>>
    suspend fun upsertWorkoutPlanItem(originGuid: String, fields: WorkoutPlanItemFields): Result<RemoteWorkoutPlanItem>
    suspend fun softDeleteWorkoutPlanItem(originGuid: String): Result<Boolean>

    suspend fun fetchChangedWorkoutSetLogsSince(sinceMs: Long): Result<List<RemoteWorkoutSetLog>>
    suspend fun upsertWorkoutSetLog(originGuid: String, fields: WorkoutSetLogFields): Result<RemoteWorkoutSetLog>
    suspend fun softDeleteWorkoutSetLog(originGuid: String): Result<Boolean>
}

/** Thrown (wrapped in [Result.failure]) by [SupabaseBodyBackend] for every failure branch - owned
 * by this package, never a raw supabase-kt/Ktor exception, same posture as
 * [EventsBackendException]/[PlacesBackendException]. */
class BodyBackendException(message: String) : Exception(message)
