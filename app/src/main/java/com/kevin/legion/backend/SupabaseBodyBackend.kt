package com.kevin.legion.backend

import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.exceptions.RestException
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.filter.FilterOperator
import java.io.IOException
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

private const val BODYWEIGHT_LOGS_TABLE = "bodyweight_logs"
private const val MEAL_LOGS_TABLE = "meal_logs"
private const val MEAL_TARGETS_TABLE = "meal_targets"
private const val SLEEP_LOGS_TABLE = "sleep_logs"
private const val SLEEP_TARGETS_TABLE = "sleep_targets"
private const val WORKOUT_PLANS_TABLE = "workout_plans"
private const val WORKOUT_PLAN_ITEMS_TABLE = "workout_plan_items"
private const val WORKOUT_SET_LOGS_TABLE = "workout_set_logs"

private fun ts(ms: Long): String = Instant.ofEpochMilli(ms).toString()
private fun parseTs(s: String): Long = OffsetDateTime.parse(s).toInstant().toEpochMilli()
private fun dateOf(ms: Long): String = Instant.ofEpochMilli(ms).atZone(ZoneOffset.UTC).toLocalDate().toString()
private fun parseDate(s: String): Long = LocalDate.parse(s).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

// ---------------------------------------------------------------------------------------------
// BODYWEIGHT
// ---------------------------------------------------------------------------------------------

@Serializable
private data class BodyweightLogUpsertDto(
    @SerialName("weight_value") val weightValue: Double,
    @SerialName("weight_unit") val weightUnit: String,
    @SerialName("logged_at") val loggedAt: String,
    @SerialName("trust_tier") val trustTier: String,
    @SerialName("origin_guid") val originGuid: String,
)

@Serializable
private data class BodyweightLogRowDto(
    val id: String,
    @SerialName("weight_value") val weightValue: Double,
    @SerialName("weight_unit") val weightUnit: String,
    @SerialName("logged_at") val loggedAt: String,
    @SerialName("trust_tier") val trustTier: String,
    @SerialName("updated_at") val updatedAt: String,
    @SerialName("deleted_at") val deletedAt: String? = null,
    @SerialName("origin_guid") val originGuid: String,
) {
    fun toRemote() = RemoteBodyweightLog(
        serverId = id,
        weightValue = weightValue,
        weightUnit = weightUnit,
        loggedAtMs = parseTs(loggedAt),
        trustTier = trustTier,
        updatedAtMs = parseTs(updatedAt),
        deleted = deletedAt != null,
        originGuid = originGuid,
    )
}

@Serializable
private data class DeletedAtDto(@SerialName("deleted_at") val deletedAt: String)

// ---------------------------------------------------------------------------------------------
// MEALS
// ---------------------------------------------------------------------------------------------

@Serializable
private data class MealLogUpsertDto(
    val description: String,
    @SerialName("calories_kcal") val caloriesKcal: Int?,
    @SerialName("protein_g") val proteinG: Double?,
    @SerialName("carbs_g") val carbsG: Double?,
    @SerialName("fat_g") val fatG: Double?,
    @SerialName("logged_at") val loggedAt: String,
    @SerialName("source_image_path") val sourceImagePath: String?,
    @SerialName("trust_tier") val trustTier: String,
    @SerialName("origin_guid") val originGuid: String,
)

@Serializable
private data class MealLogRowDto(
    val id: String,
    val description: String,
    @SerialName("calories_kcal") val caloriesKcal: Int? = null,
    @SerialName("protein_g") val proteinG: Double? = null,
    @SerialName("carbs_g") val carbsG: Double? = null,
    @SerialName("fat_g") val fatG: Double? = null,
    @SerialName("logged_at") val loggedAt: String,
    @SerialName("source_image_path") val sourceImagePath: String? = null,
    @SerialName("trust_tier") val trustTier: String,
    @SerialName("updated_at") val updatedAt: String,
    @SerialName("deleted_at") val deletedAt: String? = null,
    @SerialName("origin_guid") val originGuid: String,
) {
    fun toRemote() = RemoteMealLog(
        serverId = id,
        description = description,
        caloriesKcal = caloriesKcal,
        proteinG = proteinG,
        carbsG = carbsG,
        fatG = fatG,
        loggedAtMs = parseTs(loggedAt),
        sourceImagePath = sourceImagePath,
        trustTier = trustTier,
        updatedAtMs = parseTs(updatedAt),
        deleted = deletedAt != null,
        originGuid = originGuid,
    )
}

@Serializable
private data class MealTargetUpsertDto(
    @SerialName("calories_kcal") val caloriesKcal: Int,
    @SerialName("protein_g") val proteinG: Double,
    @SerialName("carbs_g") val carbsG: Double,
    @SerialName("fat_g") val fatG: Double,
    @SerialName("effective_from_date") val effectiveFromDate: String,
    @SerialName("origin_guid") val originGuid: String,
)

@Serializable
private data class MealTargetRowDto(
    val id: String,
    @SerialName("calories_kcal") val caloriesKcal: Int,
    @SerialName("protein_g") val proteinG: Double,
    @SerialName("carbs_g") val carbsG: Double,
    @SerialName("fat_g") val fatG: Double,
    @SerialName("effective_from_date") val effectiveFromDate: String,
    @SerialName("updated_at") val updatedAt: String,
    @SerialName("deleted_at") val deletedAt: String? = null,
    @SerialName("origin_guid") val originGuid: String,
) {
    fun toRemote() = RemoteMealTarget(
        serverId = id,
        caloriesKcal = caloriesKcal,
        proteinG = proteinG,
        carbsG = carbsG,
        fatG = fatG,
        effectiveFromDateEpochMs = parseDate(effectiveFromDate),
        updatedAtMs = parseTs(updatedAt),
        deleted = deletedAt != null,
        originGuid = originGuid,
    )
}

// ---------------------------------------------------------------------------------------------
// SLEEP
// ---------------------------------------------------------------------------------------------

@Serializable
private data class SleepLogUpsertDto(
    @SerialName("sleep_date") val sleepDate: String,
    @SerialName("duration_minutes") val durationMinutes: Int,
    val quality: Int?,
    val notes: String?,
    @SerialName("logged_at") val loggedAt: String,
    @SerialName("trust_tier") val trustTier: String,
    @SerialName("origin_guid") val originGuid: String,
)

@Serializable
private data class SleepLogRowDto(
    val id: String,
    @SerialName("sleep_date") val sleepDate: String,
    @SerialName("duration_minutes") val durationMinutes: Int,
    val quality: Int? = null,
    val notes: String? = null,
    @SerialName("logged_at") val loggedAt: String,
    @SerialName("trust_tier") val trustTier: String,
    @SerialName("updated_at") val updatedAt: String,
    @SerialName("deleted_at") val deletedAt: String? = null,
    @SerialName("origin_guid") val originGuid: String,
) {
    fun toRemote() = RemoteSleepLog(
        serverId = id,
        sleepDateEpochMs = parseDate(sleepDate),
        durationMinutes = durationMinutes,
        quality = quality,
        notes = notes,
        loggedAtMs = parseTs(loggedAt),
        trustTier = trustTier,
        updatedAtMs = parseTs(updatedAt),
        deleted = deletedAt != null,
        originGuid = originGuid,
    )
}

@Serializable
private data class SleepTargetUpsertDto(
    @SerialName("target_minutes") val targetMinutes: Int,
    @SerialName("effective_from_date") val effectiveFromDate: String,
    @SerialName("origin_guid") val originGuid: String,
)

@Serializable
private data class SleepTargetRowDto(
    val id: String,
    @SerialName("target_minutes") val targetMinutes: Int,
    @SerialName("effective_from_date") val effectiveFromDate: String,
    @SerialName("updated_at") val updatedAt: String,
    @SerialName("deleted_at") val deletedAt: String? = null,
    @SerialName("origin_guid") val originGuid: String,
) {
    fun toRemote() = RemoteSleepTarget(
        serverId = id,
        targetMinutes = targetMinutes,
        effectiveFromDateEpochMs = parseDate(effectiveFromDate),
        updatedAtMs = parseTs(updatedAt),
        deleted = deletedAt != null,
        originGuid = originGuid,
    )
}

// ---------------------------------------------------------------------------------------------
// WORKOUTS
// ---------------------------------------------------------------------------------------------

@Serializable
private data class WorkoutPlanUpsertDto(
    @SerialName("sessions_per_week") val sessionsPerWeek: Int,
    @SerialName("effective_from_week") val effectiveFromWeek: String,
    @SerialName("origin_guid") val originGuid: String,
)

@Serializable
private data class WorkoutPlanRowDto(
    val id: String,
    @SerialName("sessions_per_week") val sessionsPerWeek: Int,
    @SerialName("effective_from_week") val effectiveFromWeek: String,
    @SerialName("updated_at") val updatedAt: String,
    @SerialName("deleted_at") val deletedAt: String? = null,
    @SerialName("origin_guid") val originGuid: String,
) {
    fun toRemote() = RemoteWorkoutPlan(
        serverId = id,
        sessionsPerWeek = sessionsPerWeek,
        effectiveFromWeekEpochMs = parseDate(effectiveFromWeek),
        updatedAtMs = parseTs(updatedAt),
        deleted = deletedAt != null,
        originGuid = originGuid,
    )
}

@Serializable
private data class WorkoutPlanItemUpsertDto(
    val exercise: String,
    @SerialName("target_sets_per_week") val targetSetsPerWeek: Int,
    @SerialName("effective_from_week") val effectiveFromWeek: String,
    @SerialName("reps_per_set") val repsPerSet: Int?,
    @SerialName("origin_guid") val originGuid: String,
)

@Serializable
private data class WorkoutPlanItemRowDto(
    val id: String,
    val exercise: String,
    @SerialName("target_sets_per_week") val targetSetsPerWeek: Int,
    @SerialName("effective_from_week") val effectiveFromWeek: String,
    @SerialName("reps_per_set") val repsPerSet: Int? = null,
    @SerialName("updated_at") val updatedAt: String,
    @SerialName("deleted_at") val deletedAt: String? = null,
    @SerialName("origin_guid") val originGuid: String,
) {
    fun toRemote() = RemoteWorkoutPlanItem(
        serverId = id,
        exercise = exercise,
        targetSetsPerWeek = targetSetsPerWeek,
        effectiveFromWeekEpochMs = parseDate(effectiveFromWeek),
        repsPerSet = repsPerSet,
        updatedAtMs = parseTs(updatedAt),
        deleted = deletedAt != null,
        originGuid = originGuid,
    )
}

@Serializable
private data class WorkoutSetLogUpsertDto(
    val exercise: String,
    val sets: Int,
    val reps: Int?,
    @SerialName("weight_value") val weightValue: Double?,
    @SerialName("weight_unit") val weightUnit: String?,
    @SerialName("logged_at") val loggedAt: String,
    @SerialName("trust_tier") val trustTier: String,
    @SerialName("origin_guid") val originGuid: String,
)

@Serializable
private data class WorkoutSetLogRowDto(
    val id: String,
    val exercise: String,
    val sets: Int,
    val reps: Int? = null,
    @SerialName("weight_value") val weightValue: Double? = null,
    @SerialName("weight_unit") val weightUnit: String? = null,
    @SerialName("logged_at") val loggedAt: String,
    @SerialName("trust_tier") val trustTier: String,
    @SerialName("updated_at") val updatedAt: String,
    @SerialName("deleted_at") val deletedAt: String? = null,
    @SerialName("origin_guid") val originGuid: String,
) {
    fun toRemote() = RemoteWorkoutSetLog(
        serverId = id,
        exercise = exercise,
        sets = sets,
        reps = reps,
        weightValue = weightValue,
        weightUnit = weightUnit,
        loggedAtMs = parseTs(loggedAt),
        trustTier = trustTier,
        updatedAtMs = parseTs(updatedAt),
        deleted = deletedAt != null,
        originGuid = originGuid,
    )
}

/**
 * [BodyBackend]'s real implementation over Postgrest, against the eight `public` tables
 * `supabase/migrations/20260902000200_aspect_body.sql` creates. This is the deliberately untested
 * seam, same posture as [SupabaseEventsBackend]/[SupabasePlacesBackend] - exercising it for real
 * needs a live project. [BodyBackend] is the fake-friendly interface; every branch here does
 * nothing but translate exceptions, decode DTOs, and pick `on conflict (origin_guid)` as the
 * upsert key - see [BodyBackend]'s own class doc for why that key, not a create/update fork.
 */
class SupabaseBodyBackend(private val client: SupabaseClient) : BodyBackend {

    private suspend inline fun <T> translating(action: String, block: () -> T): Result<T> = try {
        Result.success(block())
    } catch (e: RestException) {
        Result.failure(BodyBackendException("Supabase rejected the request to $action: ${e.error}"))
    } catch (e: IOException) {
        Result.failure(BodyBackendException("Couldn't reach the server to $action."))
    } catch (e: Exception) {
        Result.failure(BodyBackendException("Couldn't $action: ${e.message ?: "unknown error"}"))
    }

    private suspend fun softDeleteByOriginGuid(table: String, originGuid: String, action: String): Result<Boolean> =
        translating(action) {
            client.postgrest.from(table)
                .update(DeletedAtDto(deletedAt = OffsetDateTime.now().toString())) {
                    select()
                    filter {
                        eq("origin_guid", originGuid)
                        filter("deleted_at", FilterOperator.IS, "null")
                    }
                }
                .decodeList<kotlinx.serialization.json.JsonElement>()
                .isNotEmpty()
        }

    // --- Bodyweight ---------------------------------------------------------------------------

    override suspend fun fetchChangedBodyweightLogsSince(sinceMs: Long): Result<List<RemoteBodyweightLog>> =
        translating("load changed bodyweight logs") {
            client.postgrest.from(BODYWEIGHT_LOGS_TABLE)
                .select { filter { gte("updated_at", ts(sinceMs)) } }
                .decodeList<BodyweightLogRowDto>()
                .map { it.toRemote() }
        }

    override suspend fun upsertBodyweightLog(originGuid: String, fields: BodyweightLogFields): Result<RemoteBodyweightLog> =
        translating("save that bodyweight reading") {
            client.postgrest.from(BODYWEIGHT_LOGS_TABLE)
                .upsert(
                    BodyweightLogUpsertDto(
                        weightValue = fields.weightValue,
                        weightUnit = fields.weightUnit,
                        loggedAt = ts(fields.loggedAtMs),
                        trustTier = fields.trustTier,
                        originGuid = originGuid,
                    ),
                ) {
                    onConflict = "origin_guid"
                    select()
                }
                .decodeSingle<BodyweightLogRowDto>()
                .toRemote()
        }

    override suspend fun softDeleteBodyweightLog(originGuid: String): Result<Boolean> =
        softDeleteByOriginGuid(BODYWEIGHT_LOGS_TABLE, originGuid, "remove that bodyweight reading")

    // --- Meals ---------------------------------------------------------------------------------

    override suspend fun fetchChangedMealLogsSince(sinceMs: Long): Result<List<RemoteMealLog>> =
        translating("load changed meal logs") {
            client.postgrest.from(MEAL_LOGS_TABLE)
                .select { filter { gte("updated_at", ts(sinceMs)) } }
                .decodeList<MealLogRowDto>()
                .map { it.toRemote() }
        }

    override suspend fun upsertMealLog(originGuid: String, fields: MealLogFields): Result<RemoteMealLog> =
        translating("save that meal") {
            client.postgrest.from(MEAL_LOGS_TABLE)
                .upsert(
                    MealLogUpsertDto(
                        description = fields.description,
                        caloriesKcal = fields.caloriesKcal,
                        proteinG = fields.proteinG,
                        carbsG = fields.carbsG,
                        fatG = fields.fatG,
                        loggedAt = ts(fields.loggedAtMs),
                        sourceImagePath = fields.sourceImagePath,
                        trustTier = fields.trustTier,
                        originGuid = originGuid,
                    ),
                ) {
                    onConflict = "origin_guid"
                    select()
                }
                .decodeSingle<MealLogRowDto>()
                .toRemote()
        }

    override suspend fun softDeleteMealLog(originGuid: String): Result<Boolean> =
        softDeleteByOriginGuid(MEAL_LOGS_TABLE, originGuid, "remove that meal")

    override suspend fun fetchChangedMealTargetsSince(sinceMs: Long): Result<List<RemoteMealTarget>> =
        translating("load changed meal targets") {
            client.postgrest.from(MEAL_TARGETS_TABLE)
                .select { filter { gte("updated_at", ts(sinceMs)) } }
                .decodeList<MealTargetRowDto>()
                .map { it.toRemote() }
        }

    override suspend fun upsertMealTarget(originGuid: String, fields: MealTargetFields): Result<RemoteMealTarget> =
        translating("save that meal target") {
            client.postgrest.from(MEAL_TARGETS_TABLE)
                .upsert(
                    MealTargetUpsertDto(
                        caloriesKcal = fields.caloriesKcal,
                        proteinG = fields.proteinG,
                        carbsG = fields.carbsG,
                        fatG = fields.fatG,
                        effectiveFromDate = dateOf(fields.effectiveFromDateEpochMs),
                        originGuid = originGuid,
                    ),
                ) {
                    onConflict = "origin_guid"
                    select()
                }
                .decodeSingle<MealTargetRowDto>()
                .toRemote()
        }

    override suspend fun softDeleteMealTarget(originGuid: String): Result<Boolean> =
        softDeleteByOriginGuid(MEAL_TARGETS_TABLE, originGuid, "remove that meal target")

    // --- Sleep ---------------------------------------------------------------------------------

    override suspend fun fetchChangedSleepLogsSince(sinceMs: Long): Result<List<RemoteSleepLog>> =
        translating("load changed sleep logs") {
            client.postgrest.from(SLEEP_LOGS_TABLE)
                .select { filter { gte("updated_at", ts(sinceMs)) } }
                .decodeList<SleepLogRowDto>()
                .map { it.toRemote() }
        }

    override suspend fun upsertSleepLog(originGuid: String, fields: SleepLogFields): Result<RemoteSleepLog> =
        translating("save that sleep log") {
            client.postgrest.from(SLEEP_LOGS_TABLE)
                .upsert(
                    SleepLogUpsertDto(
                        sleepDate = dateOf(fields.sleepDateEpochMs),
                        durationMinutes = fields.durationMinutes,
                        quality = fields.quality,
                        notes = fields.notes,
                        loggedAt = ts(fields.loggedAtMs),
                        trustTier = fields.trustTier,
                        originGuid = originGuid,
                    ),
                ) {
                    onConflict = "origin_guid"
                    select()
                }
                .decodeSingle<SleepLogRowDto>()
                .toRemote()
        }

    override suspend fun softDeleteSleepLog(originGuid: String): Result<Boolean> =
        softDeleteByOriginGuid(SLEEP_LOGS_TABLE, originGuid, "remove that sleep log")

    override suspend fun fetchChangedSleepTargetsSince(sinceMs: Long): Result<List<RemoteSleepTarget>> =
        translating("load changed sleep targets") {
            client.postgrest.from(SLEEP_TARGETS_TABLE)
                .select { filter { gte("updated_at", ts(sinceMs)) } }
                .decodeList<SleepTargetRowDto>()
                .map { it.toRemote() }
        }

    override suspend fun upsertSleepTarget(originGuid: String, fields: SleepTargetFields): Result<RemoteSleepTarget> =
        translating("save that sleep target") {
            client.postgrest.from(SLEEP_TARGETS_TABLE)
                .upsert(
                    SleepTargetUpsertDto(
                        targetMinutes = fields.targetMinutes,
                        effectiveFromDate = dateOf(fields.effectiveFromDateEpochMs),
                        originGuid = originGuid,
                    ),
                ) {
                    onConflict = "origin_guid"
                    select()
                }
                .decodeSingle<SleepTargetRowDto>()
                .toRemote()
        }

    override suspend fun softDeleteSleepTarget(originGuid: String): Result<Boolean> =
        softDeleteByOriginGuid(SLEEP_TARGETS_TABLE, originGuid, "remove that sleep target")

    // --- Workouts ------------------------------------------------------------------------------

    override suspend fun fetchChangedWorkoutPlansSince(sinceMs: Long): Result<List<RemoteWorkoutPlan>> =
        translating("load changed workout plans") {
            client.postgrest.from(WORKOUT_PLANS_TABLE)
                .select { filter { gte("updated_at", ts(sinceMs)) } }
                .decodeList<WorkoutPlanRowDto>()
                .map { it.toRemote() }
        }

    override suspend fun upsertWorkoutPlan(originGuid: String, fields: WorkoutPlanFields): Result<RemoteWorkoutPlan> =
        translating("save that workout plan") {
            client.postgrest.from(WORKOUT_PLANS_TABLE)
                .upsert(
                    WorkoutPlanUpsertDto(
                        sessionsPerWeek = fields.sessionsPerWeek,
                        effectiveFromWeek = dateOf(fields.effectiveFromWeekEpochMs),
                        originGuid = originGuid,
                    ),
                ) {
                    onConflict = "origin_guid"
                    select()
                }
                .decodeSingle<WorkoutPlanRowDto>()
                .toRemote()
        }

    override suspend fun softDeleteWorkoutPlan(originGuid: String): Result<Boolean> =
        softDeleteByOriginGuid(WORKOUT_PLANS_TABLE, originGuid, "remove that workout plan")

    override suspend fun fetchChangedWorkoutPlanItemsSince(sinceMs: Long): Result<List<RemoteWorkoutPlanItem>> =
        translating("load changed workout plan items") {
            client.postgrest.from(WORKOUT_PLAN_ITEMS_TABLE)
                .select { filter { gte("updated_at", ts(sinceMs)) } }
                .decodeList<WorkoutPlanItemRowDto>()
                .map { it.toRemote() }
        }

    override suspend fun upsertWorkoutPlanItem(originGuid: String, fields: WorkoutPlanItemFields): Result<RemoteWorkoutPlanItem> =
        translating("save that workout plan item") {
            client.postgrest.from(WORKOUT_PLAN_ITEMS_TABLE)
                .upsert(
                    WorkoutPlanItemUpsertDto(
                        exercise = fields.exercise,
                        targetSetsPerWeek = fields.targetSetsPerWeek,
                        effectiveFromWeek = dateOf(fields.effectiveFromWeekEpochMs),
                        repsPerSet = fields.repsPerSet,
                        originGuid = originGuid,
                    ),
                ) {
                    onConflict = "origin_guid"
                    select()
                }
                .decodeSingle<WorkoutPlanItemRowDto>()
                .toRemote()
        }

    override suspend fun softDeleteWorkoutPlanItem(originGuid: String): Result<Boolean> =
        softDeleteByOriginGuid(WORKOUT_PLAN_ITEMS_TABLE, originGuid, "remove that workout plan item")

    override suspend fun fetchChangedWorkoutSetLogsSince(sinceMs: Long): Result<List<RemoteWorkoutSetLog>> =
        translating("load changed workout set logs") {
            client.postgrest.from(WORKOUT_SET_LOGS_TABLE)
                .select { filter { gte("updated_at", ts(sinceMs)) } }
                .decodeList<WorkoutSetLogRowDto>()
                .map { it.toRemote() }
        }

    override suspend fun upsertWorkoutSetLog(originGuid: String, fields: WorkoutSetLogFields): Result<RemoteWorkoutSetLog> =
        translating("save that workout set") {
            client.postgrest.from(WORKOUT_SET_LOGS_TABLE)
                .upsert(
                    WorkoutSetLogUpsertDto(
                        exercise = fields.exercise,
                        sets = fields.sets,
                        reps = fields.reps,
                        weightValue = fields.weightValue,
                        weightUnit = fields.weightUnit,
                        loggedAt = ts(fields.loggedAtMs),
                        trustTier = fields.trustTier,
                        originGuid = originGuid,
                    ),
                ) {
                    onConflict = "origin_guid"
                    select()
                }
                .decodeSingle<WorkoutSetLogRowDto>()
                .toRemote()
        }

    override suspend fun softDeleteWorkoutSetLog(originGuid: String): Result<Boolean> =
        softDeleteByOriginGuid(WORKOUT_SET_LOGS_TABLE, originGuid, "remove that workout set")
}
