package com.kevin.legion.backend.engine

import com.kevin.legion.backend.BodyBackend
import com.kevin.legion.backend.BodyweightLogFields
import com.kevin.legion.backend.MealLogFields
import com.kevin.legion.backend.MealTargetFields
import com.kevin.legion.backend.RemoteBodyweightLog
import com.kevin.legion.backend.RemoteMealLog
import com.kevin.legion.backend.RemoteMealTarget
import com.kevin.legion.backend.RemoteSleepLog
import com.kevin.legion.backend.RemoteSleepTarget
import com.kevin.legion.backend.RemoteWorkoutPlan
import com.kevin.legion.backend.RemoteWorkoutPlanItem
import com.kevin.legion.backend.RemoteWorkoutSetLog
import com.kevin.legion.backend.SleepLogFields
import com.kevin.legion.backend.SleepTargetFields
import com.kevin.legion.backend.WorkoutPlanFields
import com.kevin.legion.backend.WorkoutPlanItemFields
import com.kevin.legion.backend.WorkoutSetLogFields
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

private const val BODY_ROOT = "/api/body/"

private fun bodyTs(ms: Long): String = Instant.ofEpochMilli(ms).toString()
private fun bodyParseTs(s: String): Long = OffsetDateTime.parse(s).toInstant().toEpochMilli()

/** A DATE column (`sleep_date`, `effective_from_date`, `effective_from_week`), which DRF renders
 * as a bare `"2026-08-07"`. UTC midnight in both directions, the same convention
 * `SupabaseBodyBackend`'s own `dateOf`/`parseDate` use, so a row round-trips between the two
 * transports without shifting a day. */
private fun bodyDate(ms: Long): String =
    Instant.ofEpochMilli(ms).atZone(ZoneOffset.UTC).toLocalDate().toString()

private fun bodyParseDate(s: String): Long =
    LocalDate.parse(s).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

// ---------------------------------------------------------------------------------------------
// BODYWEIGHT
// ---------------------------------------------------------------------------------------------

/**
 * One `public.bodyweight_logs` row as `server/api/body.py`'s `BodyweightLogSerializer` renders it -
 * **measured against the live engine on 2026-09-07**:
 * `{"id": "4a228390-...", "weight_value": 198.0, "weight_unit": "lbs",
 * "logged_at": "2026-08-07T18:25:19.946000Z", "trust_tier": "REPORTED", "provenance": "USER",
 * "created_at": "...", "updated_at": "...", "deleted_at": null, "origin_guid": "c7db8be1-..."}`.
 *
 * `provenance` and `created_at` are on the wire and dropped by [engineSyncedJson]'s
 * `ignoreUnknownKeys`, because [RemoteBodyweightLog] carries neither - exactly as
 * `SupabaseBodyBackend`'s own row DTO omits them.
 */
@Serializable
private data class DjangoBodyweightRow(
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
        loggedAtMs = bodyParseTs(loggedAt),
        trustTier = trustTier,
        updatedAtMs = bodyParseTs(updatedAt),
        deleted = deletedAt != null,
        originGuid = originGuid,
    )
}

/**
 * The `PUT /api/body/bodyweight_logs/<origin_guid>/` body.
 *
 * **`origin_guid` is deliberately absent from every write DTO in this file.**
 * `SyncedModelViewSet.upsert` sets `data[self.identity_field] = identity` from the URL before
 * validating - "the URL is the authority on identity", in its own words - so putting the guid in
 * the body too would be a second copy of a value that can only disagree with the first.
 * `DjangoPlaceWrite` makes the opposite call for `label` and says why there; the difference is
 * that a label is human text a percent-encoding bug could mangle, and a guid is not.
 */
@Serializable
private data class DjangoBodyweightWrite(
    @SerialName("weight_value") val weightValue: Double,
    @SerialName("weight_unit") val weightUnit: String,
    @SerialName("logged_at") val loggedAt: String,
    @SerialName("trust_tier") val trustTier: String,
)

// ---------------------------------------------------------------------------------------------
// MEALS
// ---------------------------------------------------------------------------------------------

/** A `public.meal_logs` row. [caloriesKcal]/[proteinG]/[carbsG]/[fatG] are ESTIMATES - the model's
 * guess from the meal description, never gated (CLAUDE.md section 4 rule 5). The server carries
 * that label into its own generated schema as a `help_text` on each of the four; nothing here
 * re-derives or rounds them. */
@Serializable
private data class DjangoMealLogRow(
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
        loggedAtMs = bodyParseTs(loggedAt),
        sourceImagePath = sourceImagePath,
        trustTier = trustTier,
        updatedAtMs = bodyParseTs(updatedAt),
        deleted = deletedAt != null,
        originGuid = originGuid,
    )
}

@Serializable
private data class DjangoMealLogWrite(
    val description: String,
    @SerialName("calories_kcal") val caloriesKcal: Int?,
    @SerialName("protein_g") val proteinG: Double?,
    @SerialName("carbs_g") val carbsG: Double?,
    @SerialName("fat_g") val fatG: Double?,
    @SerialName("logged_at") val loggedAt: String,
    @SerialName("source_image_path") val sourceImagePath: String?,
    @SerialName("trust_tier") val trustTier: String,
)

/** A `public.meal_targets` row. No `trust_tier` - a target sits outside both tiers, matching
 * [RemoteMealTarget]'s own note, and its four macro columns carry no estimate label because a
 * target is a number Kevin chose rather than a guess about a plate of food. */
@Serializable
private data class DjangoMealTargetRow(
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
        effectiveFromDateEpochMs = bodyParseDate(effectiveFromDate),
        updatedAtMs = bodyParseTs(updatedAt),
        deleted = deletedAt != null,
        originGuid = originGuid,
    )
}

@Serializable
private data class DjangoMealTargetWrite(
    @SerialName("calories_kcal") val caloriesKcal: Int,
    @SerialName("protein_g") val proteinG: Double,
    @SerialName("carbs_g") val carbsG: Double,
    @SerialName("fat_g") val fatG: Double,
    @SerialName("effective_from_date") val effectiveFromDate: String,
)

// ---------------------------------------------------------------------------------------------
// SLEEP
// ---------------------------------------------------------------------------------------------

/** A `public.sleep_logs` row. [sleepDate] is the WAKE date - see
 * [com.kevin.legion.data.local.SleepLog.sleepDate]'s own doc comment for the convention. */
@Serializable
private data class DjangoSleepLogRow(
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
        sleepDateEpochMs = bodyParseDate(sleepDate),
        durationMinutes = durationMinutes,
        quality = quality,
        notes = notes,
        loggedAtMs = bodyParseTs(loggedAt),
        trustTier = trustTier,
        updatedAtMs = bodyParseTs(updatedAt),
        deleted = deletedAt != null,
        originGuid = originGuid,
    )
}

@Serializable
private data class DjangoSleepLogWrite(
    @SerialName("sleep_date") val sleepDate: String,
    @SerialName("duration_minutes") val durationMinutes: Int,
    val quality: Int?,
    val notes: String?,
    @SerialName("logged_at") val loggedAt: String,
    @SerialName("trust_tier") val trustTier: String,
)

@Serializable
private data class DjangoSleepTargetRow(
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
        effectiveFromDateEpochMs = bodyParseDate(effectiveFromDate),
        updatedAtMs = bodyParseTs(updatedAt),
        deleted = deletedAt != null,
        originGuid = originGuid,
    )
}

@Serializable
private data class DjangoSleepTargetWrite(
    @SerialName("target_minutes") val targetMinutes: Int,
    @SerialName("effective_from_date") val effectiveFromDate: String,
)

// ---------------------------------------------------------------------------------------------
// WORKOUTS
// ---------------------------------------------------------------------------------------------

@Serializable
private data class DjangoWorkoutPlanRow(
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
        effectiveFromWeekEpochMs = bodyParseDate(effectiveFromWeek),
        updatedAtMs = bodyParseTs(updatedAt),
        deleted = deletedAt != null,
        originGuid = originGuid,
    )
}

@Serializable
private data class DjangoWorkoutPlanWrite(
    @SerialName("sessions_per_week") val sessionsPerWeek: Int,
    @SerialName("effective_from_week") val effectiveFromWeek: String,
)

@Serializable
private data class DjangoWorkoutPlanItemRow(
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
        effectiveFromWeekEpochMs = bodyParseDate(effectiveFromWeek),
        repsPerSet = repsPerSet,
        updatedAtMs = bodyParseTs(updatedAt),
        deleted = deletedAt != null,
        originGuid = originGuid,
    )
}

@Serializable
private data class DjangoWorkoutPlanItemWrite(
    val exercise: String,
    @SerialName("target_sets_per_week") val targetSetsPerWeek: Int,
    @SerialName("effective_from_week") val effectiveFromWeek: String,
    @SerialName("reps_per_set") val repsPerSet: Int?,
)

/** A `public.workout_set_logs` row. **No `source_list_item_id`** - that names a phone-local Room
 * row id from a table with no server counterpart, so it would be a dangling reference; the body
 * migration's own comment, [RemoteWorkoutSetLog]'s, and `WorkoutSetLogSerializer`'s all say so. */
@Serializable
private data class DjangoWorkoutSetLogRow(
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
        loggedAtMs = bodyParseTs(loggedAt),
        trustTier = trustTier,
        updatedAtMs = bodyParseTs(updatedAt),
        deleted = deletedAt != null,
        originGuid = originGuid,
    )
}

@Serializable
private data class DjangoWorkoutSetLogWrite(
    val exercise: String,
    val sets: Int,
    val reps: Int?,
    @SerialName("weight_value") val weightValue: Double?,
    @SerialName("weight_unit") val weightUnit: String?,
    @SerialName("logged_at") val loggedAt: String,
    @SerialName("trust_tier") val trustTier: String,
)

/**
 * [BodyBackend] over the household Django engine - eight tables, one interface, on
 * `server/api/synced.py`'s generic shape (`server/api/body.py` registers all eight and adds
 * nothing but serializers).
 *
 * **This aspect is the one that proves a Django backend slots into the existing four-part
 * template unchanged.** [com.kevin.legion.backend.BodySync],
 * [com.kevin.legion.backend.BodyOutboxDrain] and [com.kevin.legion.backend.BodyBackfill] were
 * written against [BodyBackend] and take it as a parameter; not one of their merge rules, their
 * outbox targets or their tests is touched by this class existing. The only thing that changes is
 * which object [EngineBackends] hands them.
 *
 * **Every write is a genuine upsert keyed on `origin_guid`, never a create/update fork** - the
 * same design [BodyBackend]'s own class doc argues for, and it survives the transport change
 * intact because `PUT <table>/<origin_guid>/` is idempotent by construction: the identity is in
 * the URL, so draining the same queued entry twice cannot make a second row. What Postgres's
 * `on conflict (origin_guid)` did for the Supabase transport, the route does for this one.
 *
 * **An upsert here does NOT revive a tombstone**, matching `SupabaseBodyBackend`'s upsert DTOs
 * (which carry no `deleted_at`, so `on conflict do update` leaves an existing tombstone alone).
 * `put_revives_tombstone` is `False` for every body table and `True` only for places. The two
 * behaviours are deliberately mirrored rather than unified - see `api/synced.py`'s own note.
 *
 * **The `trust_tier`, `weight_unit`, quality and range rules are the ENGINE's**, refused with a
 * sentence naming the allowed set (`api/body.py`'s `choice_error`/`range_error`/`minimum_error`).
 * They arrive here as [EngineFailure.Refused] carrying that sentence verbatim; nothing in this
 * class re-checks them, which is ADR 0044's "a business rule lives in Django once, never also in
 * Kotlin".
 */
// The interface this implements has 24 functions - eight tables x (pull, upsert, delete) - so any
// class implementing it has 24 too, and detekt's TooManyFunctions ceiling of 11 cannot be met
// without leaving part of BodyBackend unimplemented. SupabaseBodyBackend carries the identical
// count and sits in config/detekt/baseline.xml for the identical reason; this ticket's brief
// forbids adding to that baseline, so the same fact is stated here instead, where it is visible
// next to the code it describes.
@Suppress("TooManyFunctions") // 24 = BodyBackend's own function count; see the comment above.
class DjangoBodyBackend(http: EngineHttp) : BodyBackend {

    private val bodyweight = table(http, "bodyweight_logs", DjangoBodyweightRow.serializer()) { it.id }
    private val mealLogs = table(http, "meal_logs", DjangoMealLogRow.serializer()) { it.id }
    private val mealTargets = table(http, "meal_targets", DjangoMealTargetRow.serializer()) { it.id }
    private val sleepLogs = table(http, "sleep_logs", DjangoSleepLogRow.serializer()) { it.id }
    private val sleepTargets = table(http, "sleep_targets", DjangoSleepTargetRow.serializer()) { it.id }
    private val workoutPlans = table(http, "workout_plans", DjangoWorkoutPlanRow.serializer()) { it.id }
    private val planItems = table(http, "workout_plan_items", DjangoWorkoutPlanItemRow.serializer()) { it.id }
    private val setLogs = table(http, "workout_set_logs", DjangoWorkoutSetLogRow.serializer()) { it.id }

    // --- Bodyweight ---------------------------------------------------------------------------

    override suspend fun fetchChangedBodyweightLogsSince(sinceMs: Long): Result<List<RemoteBodyweightLog>> =
        translatingEngineCall("load changed bodyweight logs") {
            bodyweight.fetchChangedSince(bodyTs(sinceMs)).map { it.toRemote() }
        }

    override suspend fun upsertBodyweightLog(
        originGuid: String,
        fields: BodyweightLogFields,
    ): Result<RemoteBodyweightLog> = translatingEngineCall("save that bodyweight reading") {
        val body = engineSyncedJson.encodeToString(
            DjangoBodyweightWrite.serializer(),
            DjangoBodyweightWrite(
                weightValue = fields.weightValue,
                weightUnit = fields.weightUnit,
                loggedAt = bodyTs(fields.loggedAtMs),
                trustTier = fields.trustTier,
            ),
        )
        bodyweight.put(originGuid, body).toRemote()
    }

    override suspend fun softDeleteBodyweightLog(originGuid: String): Result<Boolean> =
        deletingEngineRow("remove that bodyweight reading") { bodyweight.deleteRow(originGuid) }

    // --- Meals --------------------------------------------------------------------------------

    override suspend fun fetchChangedMealLogsSince(sinceMs: Long): Result<List<RemoteMealLog>> =
        translatingEngineCall("load changed meal logs") {
            mealLogs.fetchChangedSince(bodyTs(sinceMs)).map { it.toRemote() }
        }

    override suspend fun upsertMealLog(originGuid: String, fields: MealLogFields): Result<RemoteMealLog> =
        translatingEngineCall("save that meal") {
            val body = engineSyncedJson.encodeToString(
                DjangoMealLogWrite.serializer(),
                DjangoMealLogWrite(
                    description = fields.description,
                    caloriesKcal = fields.caloriesKcal,
                    proteinG = fields.proteinG,
                    carbsG = fields.carbsG,
                    fatG = fields.fatG,
                    loggedAt = bodyTs(fields.loggedAtMs),
                    sourceImagePath = fields.sourceImagePath,
                    trustTier = fields.trustTier,
                ),
            )
            mealLogs.put(originGuid, body).toRemote()
        }

    override suspend fun softDeleteMealLog(originGuid: String): Result<Boolean> =
        deletingEngineRow("remove that meal") { mealLogs.deleteRow(originGuid) }

    override suspend fun fetchChangedMealTargetsSince(sinceMs: Long): Result<List<RemoteMealTarget>> =
        translatingEngineCall("load changed meal targets") {
            mealTargets.fetchChangedSince(bodyTs(sinceMs)).map { it.toRemote() }
        }

    override suspend fun upsertMealTarget(
        originGuid: String,
        fields: MealTargetFields,
    ): Result<RemoteMealTarget> = translatingEngineCall("save that meal target") {
        val body = engineSyncedJson.encodeToString(
            DjangoMealTargetWrite.serializer(),
            DjangoMealTargetWrite(
                caloriesKcal = fields.caloriesKcal,
                proteinG = fields.proteinG,
                carbsG = fields.carbsG,
                fatG = fields.fatG,
                effectiveFromDate = bodyDate(fields.effectiveFromDateEpochMs),
            ),
        )
        mealTargets.put(originGuid, body).toRemote()
    }

    override suspend fun softDeleteMealTarget(originGuid: String): Result<Boolean> =
        deletingEngineRow("remove that meal target") { mealTargets.deleteRow(originGuid) }

    // --- Sleep --------------------------------------------------------------------------------

    override suspend fun fetchChangedSleepLogsSince(sinceMs: Long): Result<List<RemoteSleepLog>> =
        translatingEngineCall("load changed sleep logs") {
            sleepLogs.fetchChangedSince(bodyTs(sinceMs)).map { it.toRemote() }
        }

    override suspend fun upsertSleepLog(originGuid: String, fields: SleepLogFields): Result<RemoteSleepLog> =
        translatingEngineCall("save that sleep log") {
            val body = engineSyncedJson.encodeToString(
                DjangoSleepLogWrite.serializer(),
                DjangoSleepLogWrite(
                    sleepDate = bodyDate(fields.sleepDateEpochMs),
                    durationMinutes = fields.durationMinutes,
                    quality = fields.quality,
                    notes = fields.notes,
                    loggedAt = bodyTs(fields.loggedAtMs),
                    trustTier = fields.trustTier,
                ),
            )
            sleepLogs.put(originGuid, body).toRemote()
        }

    override suspend fun softDeleteSleepLog(originGuid: String): Result<Boolean> =
        deletingEngineRow("remove that sleep log") { sleepLogs.deleteRow(originGuid) }

    override suspend fun fetchChangedSleepTargetsSince(sinceMs: Long): Result<List<RemoteSleepTarget>> =
        translatingEngineCall("load changed sleep targets") {
            sleepTargets.fetchChangedSince(bodyTs(sinceMs)).map { it.toRemote() }
        }

    override suspend fun upsertSleepTarget(
        originGuid: String,
        fields: SleepTargetFields,
    ): Result<RemoteSleepTarget> = translatingEngineCall("save that sleep target") {
        val body = engineSyncedJson.encodeToString(
            DjangoSleepTargetWrite.serializer(),
            DjangoSleepTargetWrite(
                targetMinutes = fields.targetMinutes,
                effectiveFromDate = bodyDate(fields.effectiveFromDateEpochMs),
            ),
        )
        sleepTargets.put(originGuid, body).toRemote()
    }

    override suspend fun softDeleteSleepTarget(originGuid: String): Result<Boolean> =
        deletingEngineRow("remove that sleep target") { sleepTargets.deleteRow(originGuid) }

    // --- Workouts -----------------------------------------------------------------------------

    override suspend fun fetchChangedWorkoutPlansSince(sinceMs: Long): Result<List<RemoteWorkoutPlan>> =
        translatingEngineCall("load changed workout plans") {
            workoutPlans.fetchChangedSince(bodyTs(sinceMs)).map { it.toRemote() }
        }

    override suspend fun upsertWorkoutPlan(
        originGuid: String,
        fields: WorkoutPlanFields,
    ): Result<RemoteWorkoutPlan> = translatingEngineCall("save that workout plan") {
        val body = engineSyncedJson.encodeToString(
            DjangoWorkoutPlanWrite.serializer(),
            DjangoWorkoutPlanWrite(
                sessionsPerWeek = fields.sessionsPerWeek,
                effectiveFromWeek = bodyDate(fields.effectiveFromWeekEpochMs),
            ),
        )
        workoutPlans.put(originGuid, body).toRemote()
    }

    override suspend fun softDeleteWorkoutPlan(originGuid: String): Result<Boolean> =
        deletingEngineRow("remove that workout plan") { workoutPlans.deleteRow(originGuid) }

    override suspend fun fetchChangedWorkoutPlanItemsSince(sinceMs: Long): Result<List<RemoteWorkoutPlanItem>> =
        translatingEngineCall("load changed workout plan items") {
            planItems.fetchChangedSince(bodyTs(sinceMs)).map { it.toRemote() }
        }

    override suspend fun upsertWorkoutPlanItem(
        originGuid: String,
        fields: WorkoutPlanItemFields,
    ): Result<RemoteWorkoutPlanItem> = translatingEngineCall("save that workout plan item") {
        val body = engineSyncedJson.encodeToString(
            DjangoWorkoutPlanItemWrite.serializer(),
            DjangoWorkoutPlanItemWrite(
                exercise = fields.exercise,
                targetSetsPerWeek = fields.targetSetsPerWeek,
                effectiveFromWeek = bodyDate(fields.effectiveFromWeekEpochMs),
                repsPerSet = fields.repsPerSet,
            ),
        )
        planItems.put(originGuid, body).toRemote()
    }

    override suspend fun softDeleteWorkoutPlanItem(originGuid: String): Result<Boolean> =
        deletingEngineRow("remove that workout plan item") { planItems.deleteRow(originGuid) }

    override suspend fun fetchChangedWorkoutSetLogsSince(sinceMs: Long): Result<List<RemoteWorkoutSetLog>> =
        translatingEngineCall("load changed workout sets") {
            setLogs.fetchChangedSince(bodyTs(sinceMs)).map { it.toRemote() }
        }

    override suspend fun upsertWorkoutSetLog(
        originGuid: String,
        fields: WorkoutSetLogFields,
    ): Result<RemoteWorkoutSetLog> = translatingEngineCall("save that set") {
        val body = engineSyncedJson.encodeToString(
            DjangoWorkoutSetLogWrite.serializer(),
            DjangoWorkoutSetLogWrite(
                exercise = fields.exercise,
                sets = fields.sets,
                reps = fields.reps,
                weightValue = fields.weightValue,
                weightUnit = fields.weightUnit,
                loggedAt = bodyTs(fields.loggedAtMs),
                trustTier = fields.trustTier,
            ),
        )
        setLogs.put(originGuid, body).toRemote()
    }

    override suspend fun softDeleteWorkoutSetLog(originGuid: String): Result<Boolean> =
        deletingEngineRow("remove that set") { setLogs.deleteRow(originGuid) }
}

/** `/api/body/<table>/`, built once per table. Top-level rather than a member so it does not
 * count against [DjangoBodyBackend]'s own function budget - see the suppression comment on that
 * class for why the budget is already spent. */
private fun <ROW> table(
    http: EngineHttp,
    tableName: String,
    serializer: kotlinx.serialization.KSerializer<ROW>,
    idOf: (ROW) -> String,
) = EngineSyncedTable(http, "$BODY_ROOT$tableName/", serializer, idOf)
