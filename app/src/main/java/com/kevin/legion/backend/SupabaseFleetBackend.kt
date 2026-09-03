package com.kevin.legion.backend

import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.exceptions.RestException
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Count
import io.github.jan.supabase.postgrest.query.filter.FilterOperator
import java.io.IOException
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

private const val VEHICLES_TABLE = "vehicles"
private const val SERVICE_HISTORY_TABLE = "service_history"
private const val DRIVES_TABLE = "drives"
private const val CODE_EVENTS_TABLE = "code_events"
private const val CODE_CLEAR_EVENTS_TABLE = "code_clear_events"
private const val OIL_ANALYSES_TABLE = "oil_analyses"
private const val CHASSIS_QUIRKS_TABLE = "chassis_quirks"
private const val VEHICLE_SPECS_TABLE = "vehicle_specs"
private const val MAINTENANCE_SCHEDULES_TABLE = "maintenance_schedules"
private const val BUILD_ENTRIES_TABLE = "build_entries"
private const val DRIVE_REASSIGNMENTS_TABLE = "drive_reassignments"
private const val OBD_SAMPLES_TABLE = "obd_samples"

// codes/freeze_frame/codes_after are jsonb server-side but raw JSON TEXT at the FleetBackend
// interface boundary (see RemoteCodeEvent's own doc comment) - these two helpers are the one place
// that crosses between the two, same pattern EventUpsertDto.from/RemoteEvent use for structured_meta.
private fun jsonOrNull(text: String?): JsonElement? = text?.let { Json.parseToJsonElement(it) }
private fun textOrNull(json: JsonElement?): String? = json?.toString()

private fun tsOrNull(ms: Long?): String? = ms?.let { Instant.ofEpochMilli(it).toString() }
private fun parseTs(s: String): Long = OffsetDateTime.parse(s).toInstant().toEpochMilli()
private fun parseTsOrNull(s: String?): Long? = s?.let { parseTs(it) }
// service_history.service_date is a `date`, not a `timestamptz` - same UTC-midnight convention
// EventUpsertDto's repeat_end_date uses, reused verbatim rather than reinvented.
private fun dateOrNull(ms: Long?): String? = ms?.let { Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate().toString() }
private fun parseDateOrNull(s: String?): Long? = s?.let { LocalDate.parse(it).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli() }

/**
 * The wire shape for [SupabaseFleetBackend.uploadMigratedVehicle]. Every nullable property is
 * deliberately required (no `= null` default) - same trick [PlaceUpsertDto.deletedAt]'s own doc
 * comment explains at length: `encodeDefaults = false` drops a property equal to its declared
 * default, and `null` is still a default, so an un-set nullable would silently vanish from the
 * outgoing JSON. This is a one-shot INSERT, not a partial PATCH, so every column must be present.
 */
@Serializable
private data class VehicleInsertDto(
    val name: String,
    val make: String,
    val model: String,
    val year: Int,
    val trim: String?,
    val engine: String?,
    val confirmed: Boolean,
    @SerialName("odometer_baseline") val odometerBaseline: Int?,
    @SerialName("odometer_baseline_at") val odometerBaselineAt: String?,
    @SerialName("origin_guid") val originGuid: String,
)

/**
 * The wire shape for [SupabaseFleetBackend.upsertVehicle] (ticket 26's live write, as opposed to
 * [VehicleInsertDto]'s one-time migration insert). **Deliberately carries no `origin_guid`** -
 * unlike [VehicleInsertDto], which always writes one, a live-created or live-edited vehicle has no
 * migration provenance to assert. This same DTO serves BOTH the insert branch (a fresh row, every
 * field meaningful) and the update branch (`PATCH` semantics: only the columns present here are
 * touched, so an existing row's `origin_guid` - if any - rides along untouched because this type
 * never mentions that column at all, the identical "omission preserves the existing value" posture
 * every partial-update DTO in this file already relies on for `updated_at`/`deleted_at`).
 */
@Serializable
private data class VehicleWriteDto(
    val name: String,
    val make: String,
    val model: String,
    val year: Int,
    val trim: String?,
    val engine: String?,
    val confirmed: Boolean,
    @SerialName("odometer_baseline") val odometerBaseline: Int?,
    @SerialName("odometer_baseline_at") val odometerBaselineAt: String?,
    val archived: Boolean,
)

/** The wire shape read back off `public.vehicles` for every operation. */
@Serializable
private data class VehicleRowDto(
    val id: String,
    val name: String,
    val make: String,
    val model: String,
    val year: Int,
    val trim: String? = null,
    val engine: String? = null,
    val confirmed: Boolean = false,
    @SerialName("odometer_baseline") val odometerBaseline: Int? = null,
    @SerialName("odometer_baseline_at") val odometerBaselineAt: String? = null,
    @SerialName("origin_guid") val originGuid: String? = null,
    @SerialName("updated_at") val updatedAt: String,
    @SerialName("deleted_at") val deletedAt: String? = null,
    val archived: Boolean = false,
) {
    fun toRemoteVehicle() = RemoteVehicle(
        serverId = id,
        name = name,
        make = make,
        model = model,
        year = year,
        trim = trim,
        engine = engine,
        confirmed = confirmed,
        odometerBaseline = odometerBaseline,
        odometerBaselineAtMs = parseTsOrNull(odometerBaselineAt),
        updatedAtMs = parseTs(updatedAt),
        deleted = deletedAt != null,
        originGuid = originGuid,
        archived = archived,
    )
}

/** The wire shape for [SupabaseFleetBackend.uploadMigratedServiceHistory] - same "every column
 * required, no defaults" posture as [VehicleInsertDto]. */
@Serializable
private data class ServiceHistoryInsertDto(
    @SerialName("vehicle_id") val vehicleId: String,
    @SerialName("service_name") val serviceName: String,
    val mileage: Int?,
    @SerialName("service_date") val serviceDate: String?,
    @SerialName("cost_cents") val costCents: Long?,
    val kind: String,
    @SerialName("origin_guid") val originGuid: String,
)

/**
 * The wire shape for [SupabaseFleetBackend.upsertServiceHistory] (ticket 26 step 2's live write, as
 * opposed to [ServiceHistoryInsertDto]'s one-time migration insert) - same "no origin_guid, serves
 * both the insert and the PATCH branch" posture as [VehicleWriteDto]. See that type's own doc
 * comment for the full reasoning; it applies here verbatim.
 */
@Serializable
private data class ServiceHistoryWriteDto(
    @SerialName("vehicle_id") val vehicleId: String,
    @SerialName("service_name") val serviceName: String,
    val mileage: Int?,
    @SerialName("service_date") val serviceDate: String?,
    @SerialName("cost_cents") val costCents: Long?,
    val kind: String,
)

/** The wire shape read back off `public.service_history` for every operation. */
@Serializable
private data class ServiceHistoryRowDto(
    val id: String,
    @SerialName("vehicle_id") val vehicleId: String,
    @SerialName("service_name") val serviceName: String,
    val mileage: Int? = null,
    @SerialName("service_date") val serviceDate: String? = null,
    @SerialName("cost_cents") val costCents: Long? = null,
    val kind: String,
    @SerialName("origin_guid") val originGuid: String? = null,
    @SerialName("updated_at") val updatedAt: String,
    @SerialName("deleted_at") val deletedAt: String? = null,
) {
    fun toRemoteServiceHistory() = RemoteServiceHistory(
        serverId = id,
        vehicleServerId = vehicleId,
        serviceName = serviceName,
        mileage = mileage,
        serviceDateEpochMs = parseDateOrNull(serviceDate),
        costCents = costCents,
        kind = kind,
        updatedAtMs = parseTs(updatedAt),
        deleted = deletedAt != null,
        originGuid = originGuid,
    )
}

/**
 * The wire shape for [SupabaseFleetBackend.upsertDrive]. Unlike [VehicleInsertDto]/
 * [ServiceHistoryInsertDto], there is no `origin_guid` here at all - see [DriveUpload]'s own class
 * doc for why drives key on `sync_id` instead.
 */
@Serializable
private data class DriveUpsertDto(
    @SerialName("sync_id") val syncId: String,
    @SerialName("vehicle_id") val vehicleId: String,
    @SerialName("started_at") val startedAt: String,
    @SerialName("ended_at") val endedAt: String,
    val miles: Double,
    // Deliberately required, no `= null` default - a genuinely fuel-unmeasured drive must send an
    // explicit JSON null, not omit the key, or a later upsert-with-a-real-reading could never tell
    // "still unmeasured" apart from "this write forgot to mention gallons at all". Postgres also has
    // no existing value to preserve here the way an UPDATE would, since this table has no live edit
    // path - every upsert is either a fresh insert or a harmless re-post of identical data.
    val gallons: Double?,
    @SerialName("end_reason") val endReason: String,
)

/** The wire shape read back off `public.drives` for every operation. */
@Serializable
private data class DriveRowDto(
    val id: String,
    @SerialName("sync_id") val syncId: String,
    @SerialName("vehicle_id") val vehicleId: String,
    @SerialName("started_at") val startedAt: String,
    @SerialName("ended_at") val endedAt: String,
    val miles: Double,
    val gallons: Double? = null,
    @SerialName("end_reason") val endReason: String,
    @SerialName("updated_at") val updatedAt: String,
    @SerialName("deleted_at") val deletedAt: String? = null,
) {
    fun toRemoteDrive() = RemoteDrive(
        serverId = id,
        syncId = syncId,
        vehicleServerId = vehicleId,
        startedAtMs = parseTs(startedAt),
        endedAtMs = parseTs(endedAt),
        miles = miles,
        gallons = gallons,
        endReason = endReason,
        updatedAtMs = parseTs(updatedAt),
        deleted = deletedAt != null,
    )
}

/** The wire shape for [SupabaseFleetBackend.upsertCodeEvent]. `codes`/`freeze_frame` are `jsonb` -
 * [jsonOrNull] converts the already-nullable text this DTO receives (freeze_frame having already
 * been translated from the phone's `""` to a real `null` by [FleetReconcile]). `provenance` is on
 * the wire explicitly, sourced from [CodeEventUpload.provenance] - CLAUDE.md section 4 rule 4 is a
 * claim the phone makes, not a value the server column's own default gets to supply on its behalf. */
@Serializable
private data class CodeEventUpsertDto(
    @SerialName("sync_id") val syncId: String,
    @SerialName("vehicle_id") val vehicleId: String,
    @SerialName("occurred_at") val occurredAt: String,
    val mileage: Int?,
    val codes: JsonElement,
    @SerialName("freeze_frame") val freezeFrame: JsonElement?,
    val provenance: String,
)

/** The wire shape read back off `public.code_events` for every operation. */
@Serializable
private data class CodeEventRowDto(
    val id: String,
    @SerialName("sync_id") val syncId: String,
    @SerialName("vehicle_id") val vehicleId: String,
    @SerialName("occurred_at") val occurredAt: String,
    val mileage: Int? = null,
    val codes: JsonElement,
    @SerialName("freeze_frame") val freezeFrame: JsonElement? = null,
    val provenance: String,
    @SerialName("updated_at") val updatedAt: String,
    @SerialName("deleted_at") val deletedAt: String? = null,
) {
    fun toRemoteCodeEvent() = RemoteCodeEvent(
        serverId = id,
        syncId = syncId,
        vehicleServerId = vehicleId,
        occurredAtMs = parseTs(occurredAt),
        mileage = mileage,
        codesJson = codes.toString(),
        freezeFrameJson = textOrNull(freezeFrame),
        provenance = provenance,
        updatedAtMs = parseTs(updatedAt),
        deleted = deletedAt != null,
    )
}

/** The wire shape for [SupabaseFleetBackend.upsertCodeClearEvent]. `codes_after` is `null` for
 * UNVERIFIED, a real `jsonb` array otherwise - see [RemoteCodeClearEvent]'s own doc comment for why
 * that three-way distinction must never collapse. */
@Serializable
private data class CodeClearEventUpsertDto(
    @SerialName("sync_id") val syncId: String,
    @SerialName("vehicle_id") val vehicleId: String,
    @SerialName("occurred_at") val occurredAt: String,
    val mileage: Int?,
    @SerialName("codes_before") val codesBefore: JsonElement,
    @SerialName("freeze_frame") val freezeFrame: JsonElement?,
    @SerialName("codes_after") val codesAfter: JsonElement?,
    val outcome: String,
    @SerialName("ack_raw") val ackRaw: String,
    val provenance: String,
)

/** The wire shape read back off `public.code_clear_events` for every operation. */
@Serializable
private data class CodeClearEventRowDto(
    val id: String,
    @SerialName("sync_id") val syncId: String,
    @SerialName("vehicle_id") val vehicleId: String,
    @SerialName("occurred_at") val occurredAt: String,
    val mileage: Int? = null,
    @SerialName("codes_before") val codesBefore: JsonElement,
    @SerialName("freeze_frame") val freezeFrame: JsonElement? = null,
    @SerialName("codes_after") val codesAfter: JsonElement? = null,
    val outcome: String,
    @SerialName("ack_raw") val ackRaw: String = "",
    val provenance: String,
    @SerialName("updated_at") val updatedAt: String,
    @SerialName("deleted_at") val deletedAt: String? = null,
) {
    fun toRemoteCodeClearEvent() = RemoteCodeClearEvent(
        serverId = id,
        syncId = syncId,
        vehicleServerId = vehicleId,
        occurredAtMs = parseTs(occurredAt),
        mileage = mileage,
        codesBeforeJson = codesBefore.toString(),
        freezeFrameJson = textOrNull(freezeFrame),
        codesAfterJson = textOrNull(codesAfter),
        outcome = outcome,
        ackRaw = ackRaw,
        provenance = provenance,
        updatedAtMs = parseTs(updatedAt),
        deleted = deletedAt != null,
    )
}

/** The wire shape for [SupabaseFleetBackend.upsertOilAnalysis]. `provenance` is on the wire
 * explicitly, sourced from [OilAnalysisUpload.provenance] - always `'USER'` for this table (see
 * [RemoteOilAnalysis]'s own doc comment), same "the phone asserts it, the server does not guess"
 * posture as [CodeEventUpsertDto]. */
@Serializable
private data class OilAnalysisUpsertDto(
    @SerialName("sync_id") val syncId: String,
    @SerialName("vehicle_id") val vehicleId: String,
    @SerialName("analyzed_at") val analyzedAt: String,
    val mileage: Int?,
    @SerialName("oil_brand") val oilBrand: String,
    @SerialName("oil_grade") val oilGrade: String,
    @SerialName("drain_interval_miles") val drainIntervalMiles: Int?,
    val iron: Int?,
    val copper: Int?,
    val lead: Int?,
    val tin: Int?,
    val aluminum: Int?,
    val chromium: Int?,
    val nickel: Int?,
    val sodium: Int?,
    val potassium: Int?,
    val silicon: Int?,
    val boron: Int?,
    val magnesium: Int?,
    @SerialName("fuel_percent") val fuelPercent: Double?,
    @SerialName("water_percent") val waterPercent: Double?,
    val tbn: Double?,
    @SerialName("viscosity_cst") val viscosityCst: Double?,
    @SerialName("lab_notes") val labNotes: String,
    val provenance: String,
)

/** The wire shape read back off `public.oil_analyses` for every operation. */
@Serializable
private data class OilAnalysisRowDto(
    val id: String,
    @SerialName("sync_id") val syncId: String,
    @SerialName("vehicle_id") val vehicleId: String,
    @SerialName("analyzed_at") val analyzedAt: String,
    val mileage: Int? = null,
    @SerialName("oil_brand") val oilBrand: String = "",
    @SerialName("oil_grade") val oilGrade: String = "",
    @SerialName("drain_interval_miles") val drainIntervalMiles: Int? = null,
    val iron: Int? = null,
    val copper: Int? = null,
    val lead: Int? = null,
    val tin: Int? = null,
    val aluminum: Int? = null,
    val chromium: Int? = null,
    val nickel: Int? = null,
    val sodium: Int? = null,
    val potassium: Int? = null,
    val silicon: Int? = null,
    val boron: Int? = null,
    val magnesium: Int? = null,
    @SerialName("fuel_percent") val fuelPercent: Double? = null,
    @SerialName("water_percent") val waterPercent: Double? = null,
    val tbn: Double? = null,
    @SerialName("viscosity_cst") val viscosityCst: Double? = null,
    @SerialName("lab_notes") val labNotes: String = "",
    val provenance: String,
    @SerialName("updated_at") val updatedAt: String,
    @SerialName("deleted_at") val deletedAt: String? = null,
) {
    fun toRemoteOilAnalysis() = RemoteOilAnalysis(
        serverId = id,
        syncId = syncId,
        vehicleServerId = vehicleId,
        analyzedAtMs = parseTs(analyzedAt),
        mileage = mileage,
        oilBrand = oilBrand,
        oilGrade = oilGrade,
        drainIntervalMiles = drainIntervalMiles,
        iron = iron,
        copper = copper,
        lead = lead,
        tin = tin,
        aluminum = aluminum,
        chromium = chromium,
        nickel = nickel,
        sodium = sodium,
        potassium = potassium,
        silicon = silicon,
        boron = boron,
        magnesium = magnesium,
        fuelPercent = fuelPercent,
        waterPercent = waterPercent,
        tbn = tbn,
        viscosityCst = viscosityCst,
        labNotes = labNotes,
        provenance = provenance,
        updatedAtMs = parseTs(updatedAt),
        deleted = deletedAt != null,
    )
}

/** The wire shape for [SupabaseFleetBackend.upsertChassisQuirk]. No `vehicle_id` at all - see
 * [RemoteChassisQuirk]'s own doc comment for why this table is not per-vehicle. `provenance` IS on
 * the wire, sourced from [ChassisQuirkUpload.provenance] - always `'DETERMINISTIC'` (parsed from a
 * bundled JSON asset by code), same "the phone asserts it explicitly" posture as
 * [CodeEventUpsertDto]. */
@Serializable
private data class ChassisQuirkUpsertDto(
    @SerialName("quirk_id") val quirkId: String,
    val chassis: String,
    val engine: String,
    val title: String,
    val symptom: String,
    @SerialName("verification_steps") val verificationSteps: String,
    @SerialName("mileage_low") val mileageLow: Int?,
    @SerialName("mileage_high") val mileageHigh: Int?,
    val severity: String,
    @SerialName("cost_low_cents") val costLowCents: Long?,
    @SerialName("cost_high_cents") val costHighCents: Long?,
    @SerialName("fix_notes") val fixNotes: String,
    @SerialName("source_url") val sourceUrl: String,
    val provenance: String,
)

/** The wire shape read back off `public.chassis_quirks` for every operation. */
@Serializable
private data class ChassisQuirkRowDto(
    @SerialName("quirk_id") val quirkId: String,
    val chassis: String,
    val engine: String = "",
    val title: String,
    val symptom: String,
    @SerialName("verification_steps") val verificationSteps: String,
    @SerialName("mileage_low") val mileageLow: Int? = null,
    @SerialName("mileage_high") val mileageHigh: Int? = null,
    val severity: String,
    @SerialName("cost_low_cents") val costLowCents: Long? = null,
    @SerialName("cost_high_cents") val costHighCents: Long? = null,
    @SerialName("fix_notes") val fixNotes: String = "",
    @SerialName("source_url") val sourceUrl: String = "",
    val provenance: String,
    @SerialName("updated_at") val updatedAt: String,
) {
    fun toRemoteChassisQuirk() = RemoteChassisQuirk(
        quirkId = quirkId,
        chassis = chassis,
        engine = engine,
        title = title,
        symptom = symptom,
        verificationSteps = verificationSteps,
        mileageLow = mileageLow,
        mileageHigh = mileageHigh,
        severity = severity,
        costLowCents = costLowCents,
        costHighCents = costHighCents,
        fixNotes = fixNotes,
        sourceUrl = sourceUrl,
        provenance = provenance,
        updatedAtMs = parseTs(updatedAt),
    )
}

/** The wire shape for [SupabaseFleetBackend.upsertMaintenanceSchedule]. Every column always
 * present, no defaults - same "this is a full REPLACE, not a partial PATCH" reasoning as
 * [ChassisQuirkUpsertDto]. No `id`/`origin_guid` - see [MaintenanceScheduleUpload]'s own doc for
 * why `(vehicle_id, service_name)` is the whole identity. */
@Serializable
private data class MaintenanceScheduleUpsertDto(
    @SerialName("vehicle_id") val vehicleId: String,
    @SerialName("service_name") val serviceName: String,
    @SerialName("interval_miles") val intervalMiles: Int?,
    @SerialName("interval_months") val intervalMonths: Int?,
    @SerialName("interval_source") val intervalSource: String,
    @SerialName("never_done") val neverDone: Boolean,
    val provenance: String,
)

/** The wire shape read back off `public.maintenance_schedules` for every operation. */
@Serializable
private data class MaintenanceScheduleRowDto(
    val id: String,
    @SerialName("vehicle_id") val vehicleId: String,
    @SerialName("service_name") val serviceName: String,
    @SerialName("interval_miles") val intervalMiles: Int? = null,
    @SerialName("interval_months") val intervalMonths: Int? = null,
    @SerialName("interval_source") val intervalSource: String,
    @SerialName("never_done") val neverDone: Boolean,
    val provenance: String,
    @SerialName("updated_at") val updatedAt: String,
    @SerialName("deleted_at") val deletedAt: String? = null,
) {
    fun toRemoteMaintenanceSchedule() = RemoteMaintenanceSchedule(
        serverId = id,
        vehicleServerId = vehicleId,
        serviceName = serviceName,
        intervalMiles = intervalMiles,
        intervalMonths = intervalMonths,
        intervalSource = intervalSource,
        neverDone = neverDone,
        provenance = provenance,
        updatedAtMs = parseTs(updatedAt),
        deleted = deletedAt != null,
    )
}

/** The wire shape for [SupabaseFleetBackend.upsertVehicleSpec]. Every column always present, no
 * defaults - same "this is a full REPLACE, not a partial PATCH" reasoning as [ChassisQuirkUpsertDto].
 * `decoded_at` is the one nullable field here: [jsonOrNull]'s sibling [tsOrNull] does the
 * `null`-when-never-decoded translation, sourced from [VehicleSpecUpload.decodedAtMs]. */
@Serializable
private data class VehicleSpecUpsertDto(
    @SerialName("vehicle_id") val vehicleId: String,
    val vin: String,
    @SerialName("engine_cylinders") val engineCylinders: Int?,
    @SerialName("displacement_l") val displacementL: Double?,
    @SerialName("engine_hp") val engineHp: Int?,
    @SerialName("engine_config") val engineConfig: String,
    @SerialName("fuel_type") val fuelType: String,
    @SerialName("transmission_style") val transmissionStyle: String,
    @SerialName("transmission_speeds") val transmissionSpeeds: String,
    @SerialName("drive_type") val driveType: String,
    @SerialName("body_class") val bodyClass: String,
    val doors: Int?,
    val series: String,
    @SerialName("vehicle_type") val vehicleType: String,
    val manufacturer: String,
    @SerialName("plant_city") val plantCity: String,
    @SerialName("plant_country") val plantCountry: String,
    @SerialName("paint_color") val paintColor: String,
    @SerialName("paint_code") val paintCode: String,
    @SerialName("build_notes") val buildNotes: String,
    @SerialName("decoded_at") val decodedAt: String?,
    val provenance: String,
)

/** The wire shape read back off `public.vehicle_specs` for every operation. No `deleted_at` -
 * see [RemoteVehicleSpec]'s own doc comment for why this table has none. */
@Serializable
private data class VehicleSpecRowDto(
    @SerialName("vehicle_id") val vehicleId: String,
    val vin: String = "",
    @SerialName("engine_cylinders") val engineCylinders: Int? = null,
    @SerialName("displacement_l") val displacementL: Double? = null,
    @SerialName("engine_hp") val engineHp: Int? = null,
    @SerialName("engine_config") val engineConfig: String = "",
    @SerialName("fuel_type") val fuelType: String = "",
    @SerialName("transmission_style") val transmissionStyle: String = "",
    @SerialName("transmission_speeds") val transmissionSpeeds: String = "",
    @SerialName("drive_type") val driveType: String = "",
    @SerialName("body_class") val bodyClass: String = "",
    val doors: Int? = null,
    val series: String = "",
    @SerialName("vehicle_type") val vehicleType: String = "",
    val manufacturer: String = "",
    @SerialName("plant_city") val plantCity: String = "",
    @SerialName("plant_country") val plantCountry: String = "",
    @SerialName("paint_color") val paintColor: String = "",
    @SerialName("paint_code") val paintCode: String = "",
    @SerialName("build_notes") val buildNotes: String = "",
    @SerialName("decoded_at") val decodedAt: String? = null,
    val provenance: String,
    @SerialName("updated_at") val updatedAt: String,
) {
    fun toRemoteVehicleSpec() = RemoteVehicleSpec(
        vehicleServerId = vehicleId,
        vin = vin,
        engineCylinders = engineCylinders,
        displacementL = displacementL,
        engineHp = engineHp,
        engineConfig = engineConfig,
        fuelType = fuelType,
        transmissionStyle = transmissionStyle,
        transmissionSpeeds = transmissionSpeeds,
        driveType = driveType,
        bodyClass = bodyClass,
        doors = doors,
        series = series,
        vehicleType = vehicleType,
        manufacturer = manufacturer,
        plantCity = plantCity,
        plantCountry = plantCountry,
        paintColor = paintColor,
        paintCode = paintCode,
        buildNotes = buildNotes,
        decodedAtMs = parseTsOrNull(decodedAt),
        provenance = provenance,
        updatedAtMs = parseTs(updatedAt),
    )
}

/** The wire shape for [SupabaseFleetBackend.upsertBuildEntry]. `cost_cents` is already converted
 * (dollars -> cents) by the time [FleetReconcile] builds a [BuildEntryUpload] - this DTO only
 * carries the already-cents `Long` across the wire, CLAUDE.md section 3. */
@Serializable
private data class BuildEntryUpsertDto(
    @SerialName("sync_id") val syncId: String,
    @SerialName("vehicle_id") val vehicleId: String,
    @SerialName("entry_type") val entryType: String,
    val title: String,
    val vendor: String,
    @SerialName("part_number") val partNumber: String,
    @SerialName("cost_cents") val costCents: Long?,
    @SerialName("logged_at") val loggedAt: String,
    val mileage: Int?,
    val notes: String,
    val provenance: String,
)

/** The wire shape read back off `public.build_entries` for every operation. */
@Serializable
private data class BuildEntryRowDto(
    val id: String,
    @SerialName("sync_id") val syncId: String,
    @SerialName("vehicle_id") val vehicleId: String,
    @SerialName("entry_type") val entryType: String,
    val title: String,
    val vendor: String = "",
    @SerialName("part_number") val partNumber: String = "",
    @SerialName("cost_cents") val costCents: Long? = null,
    @SerialName("logged_at") val loggedAt: String,
    val mileage: Int? = null,
    val notes: String = "",
    val provenance: String,
    @SerialName("updated_at") val updatedAt: String,
    @SerialName("deleted_at") val deletedAt: String? = null,
) {
    fun toRemoteBuildEntry() = RemoteBuildEntry(
        serverId = id,
        syncId = syncId,
        vehicleServerId = vehicleId,
        entryType = entryType,
        title = title,
        vendor = vendor,
        partNumber = partNumber,
        costCents = costCents,
        loggedAtMs = parseTs(loggedAt),
        mileage = mileage,
        notes = notes,
        provenance = provenance,
        updatedAtMs = parseTs(updatedAt),
        deleted = deletedAt != null,
    )
}

/** The wire shape for [SupabaseFleetBackend.upsertDriveReassignment]. Two vehicle references, not
 * one - see [RemoteDriveReassignment]'s own doc comment for why a correction rule needs both the
 * current and the corrected car. */
@Serializable
private data class DriveReassignmentUpsertDto(
    @SerialName("sync_id") val syncId: String,
    @SerialName("vehicle_id") val vehicleId: String,
    @SerialName("new_vehicle_id") val newVehicleId: String,
    @SerialName("from_at") val fromAt: String,
    @SerialName("to_at") val toAt: String,
    val provenance: String,
)

/** The wire shape read back off `public.drive_reassignments` for every operation. */
@Serializable
private data class DriveReassignmentRowDto(
    val id: String,
    @SerialName("sync_id") val syncId: String,
    @SerialName("vehicle_id") val vehicleId: String,
    @SerialName("new_vehicle_id") val newVehicleId: String,
    @SerialName("from_at") val fromAt: String,
    @SerialName("to_at") val toAt: String,
    val provenance: String,
    @SerialName("updated_at") val updatedAt: String,
    @SerialName("deleted_at") val deletedAt: String? = null,
) {
    fun toRemoteDriveReassignment() = RemoteDriveReassignment(
        serverId = id,
        syncId = syncId,
        vehicleServerId = vehicleId,
        newVehicleServerId = newVehicleId,
        fromAtMs = parseTs(fromAt),
        toAtMs = parseTs(toAt),
        provenance = provenance,
        updatedAtMs = parseTs(updatedAt),
        deleted = deletedAt != null,
    )
}

/**
 * [FleetBackend]'s real implementation over Postgrest, against `public.vehicles`,
 * `public.service_history`, `public.drives` (`supabase/migrations/20260825000500_aspect_places_fleet.sql`,
 * `supabase/migrations/20260826000200_fleet_drives.sql`) and `public.code_events`,
 * `public.code_clear_events`, `public.oil_analyses`, `public.chassis_quirks`, `public.vehicle_specs`,
 * `public.build_entries`, `public.drive_reassignments`
 * (`supabase/migrations/20260826000600_fleet_diagnostics_specs_build.sql`). This is the deliberately
 * untested seam in the fleet cutover, same posture as [SupabasePlacesBackend]/[SupabaseEventsBackend] -
 * exercising it for real needs a live project. [FleetBackend] is the fake-friendly interface; every
 * branch here does nothing but translate exceptions and decode DTOs.
 */
class SupabaseFleetBackend(private val client: SupabaseClient) : FleetBackend {

    private suspend inline fun <T> translating(action: String, block: () -> T): Result<T> = try {
        Result.success(block())
    } catch (e: RestException) {
        Result.failure(FleetBackendException("Supabase rejected the request to $action: ${e.error}"))
    } catch (e: IOException) {
        Result.failure(FleetBackendException("Couldn't reach the server to $action."))
    } catch (e: Exception) {
        Result.failure(FleetBackendException("Couldn't $action: ${e.message ?: "unknown error"}"))
    }

    override suspend fun fetchActiveVehicles(): Result<List<RemoteVehicle>> = translating("load your vehicles") {
        client.postgrest.from(VEHICLES_TABLE)
            .select {
                filter { filter("deleted_at", FilterOperator.IS, "null") }
            }
            .decodeList<VehicleRowDto>()
            .map { it.toRemoteVehicle() }
    }

    /** Same "select by origin_guid first" shape as [SupabaseEventsBackend.uploadMigratedEvent] -
     * this is a re-run guard, not a gate-immutability workaround, and `Result.success(false)` on an
     * existing row means exactly "already migrated", never a failure. */
    override suspend fun uploadMigratedVehicle(vehicle: MigratedVehicle): Result<Boolean> =
        translating("upload a migrated vehicle") {
            val existing = client.postgrest.from(VEHICLES_TABLE)
                .select {
                    filter { eq("origin_guid", vehicle.originGuid) }
                }
                .decodeList<VehicleRowDto>()
            if (existing.isNotEmpty()) return@translating false

            client.postgrest.from(VEHICLES_TABLE).insert(
                VehicleInsertDto(
                    name = vehicle.name,
                    make = vehicle.make,
                    model = vehicle.model,
                    year = vehicle.year,
                    trim = vehicle.trim,
                    engine = vehicle.engine,
                    confirmed = vehicle.confirmed,
                    odometerBaseline = vehicle.odometerBaseline,
                    odometerBaselineAt = tsOrNull(vehicle.odometerBaselineAtMs),
                    originGuid = vehicle.originGuid,
                ),
            )
            true
        }

    /** Ticket 26's live write. `null` [VehicleUpload.serverId] means "no row yet" - a plain
     * insert, letting Postgres mint the uuid. A non-null [VehicleUpload.serverId] filters an
     * `UPDATE` to exactly that row - no `onConflict`/`upsert()` call needed, because the caller
     * already knows whether the row exists (that is what [VehicleUpload.serverId] being non-null
     * MEANS), unlike the `syncId`-keyed tables in this file that genuinely don't know until they
     * try. */
    override suspend fun upsertVehicle(vehicle: VehicleUpload): Result<RemoteVehicle> =
        translating("save that vehicle") {
            val dto = VehicleWriteDto(
                name = vehicle.name,
                make = vehicle.make,
                model = vehicle.model,
                year = vehicle.year,
                trim = vehicle.trim,
                engine = vehicle.engine,
                confirmed = vehicle.confirmed,
                odometerBaseline = vehicle.odometerBaseline,
                odometerBaselineAt = tsOrNull(vehicle.odometerBaselineAtMs),
                archived = vehicle.archived,
            )
            if (vehicle.serverId == null) {
                client.postgrest.from(VEHICLES_TABLE)
                    .insert(dto) { select() }
                    .decodeSingle<VehicleRowDto>()
                    .toRemoteVehicle()
            } else {
                client.postgrest.from(VEHICLES_TABLE)
                    .update(dto) {
                        filter { eq("id", vehicle.serverId) }
                        select()
                    }
                    .decodeSingle<VehicleRowDto>()
                    .toRemoteVehicle()
            }
        }

    override suspend fun fetchActiveServiceHistory(): Result<List<RemoteServiceHistory>> =
        translating("load your service history") {
            client.postgrest.from(SERVICE_HISTORY_TABLE)
                .select {
                    filter { filter("deleted_at", FilterOperator.IS, "null") }
                }
                .decodeList<ServiceHistoryRowDto>()
                .map { it.toRemoteServiceHistory() }
        }

    override suspend fun uploadMigratedServiceHistory(history: MigratedServiceHistory): Result<Boolean> =
        translating("upload a migrated service record") {
            val existing = client.postgrest.from(SERVICE_HISTORY_TABLE)
                .select {
                    filter { eq("origin_guid", history.originGuid) }
                }
                .decodeList<ServiceHistoryRowDto>()
            if (existing.isNotEmpty()) return@translating false

            client.postgrest.from(SERVICE_HISTORY_TABLE).insert(
                ServiceHistoryInsertDto(
                    vehicleId = history.vehicleServerId,
                    serviceName = history.serviceName,
                    mileage = history.mileage,
                    serviceDate = dateOrNull(history.serviceDateEpochMs),
                    costCents = history.costCents,
                    kind = history.kind,
                    originGuid = history.originGuid,
                ),
            )
            true
        }

    /** Ticket 26 step 2's live write - same "caller already knows insert-vs-update from whether
     * serverId is null" shape as [upsertVehicle]; see that function's own doc comment. */
    override suspend fun upsertServiceHistory(history: ServiceHistoryUpload): Result<RemoteServiceHistory> =
        translating("save that service record") {
            val dto = ServiceHistoryWriteDto(
                vehicleId = history.vehicleServerId,
                serviceName = history.serviceName,
                mileage = history.mileage,
                serviceDate = dateOrNull(history.serviceDateEpochMs),
                costCents = history.costCents,
                kind = history.kind,
            )
            if (history.serverId == null) {
                client.postgrest.from(SERVICE_HISTORY_TABLE)
                    .insert(dto) { select() }
                    .decodeSingle<ServiceHistoryRowDto>()
                    .toRemoteServiceHistory()
            } else {
                client.postgrest.from(SERVICE_HISTORY_TABLE)
                    .update(dto) {
                        filter { eq("id", history.serverId) }
                        select()
                    }
                    .decodeSingle<ServiceHistoryRowDto>()
                    .toRemoteServiceHistory()
            }
        }

    override suspend fun fetchActiveDrives(): Result<List<RemoteDrive>> = translating("load your drives") {
        client.postgrest.from(DRIVES_TABLE)
            .select {
                filter { filter("deleted_at", FilterOperator.IS, "null") }
            }
            .decodeList<DriveRowDto>()
            .map { it.toRemoteDrive() }
    }

    override suspend fun upsertDrive(drive: DriveUpload): Result<RemoteDrive> = translating("save that drive") {
        client.postgrest.from(DRIVES_TABLE)
            .upsert(
                DriveUpsertDto(
                    syncId = drive.syncId,
                    vehicleId = drive.vehicleServerId,
                    startedAt = Instant.ofEpochMilli(drive.startedAtMs).toString(),
                    endedAt = Instant.ofEpochMilli(drive.endedAtMs).toString(),
                    miles = drive.miles,
                    gallons = drive.gallons,
                    endReason = drive.endReason,
                ),
            ) {
                onConflict = "sync_id"
                select()
            }
            .decodeSingle<DriveRowDto>()
            .toRemoteDrive()
    }

    override suspend fun fetchActiveCodeEvents(): Result<List<RemoteCodeEvent>> =
        translating("load your stored codes") {
            client.postgrest.from(CODE_EVENTS_TABLE)
                .select {
                    filter { filter("deleted_at", FilterOperator.IS, "null") }
                }
                .decodeList<CodeEventRowDto>()
                .map { it.toRemoteCodeEvent() }
        }

    override suspend fun upsertCodeEvent(event: CodeEventUpload): Result<RemoteCodeEvent> =
        translating("save that stored-code reading") {
            client.postgrest.from(CODE_EVENTS_TABLE)
                .upsert(
                    CodeEventUpsertDto(
                        syncId = event.syncId,
                        vehicleId = event.vehicleServerId,
                        occurredAt = Instant.ofEpochMilli(event.occurredAtMs).toString(),
                        mileage = event.mileage,
                        codes = Json.parseToJsonElement(event.codesJson),
                        freezeFrame = jsonOrNull(event.freezeFrameJson),
                        provenance = event.provenance,
                    ),
                ) {
                    onConflict = "sync_id"
                    select()
                }
                .decodeSingle<CodeEventRowDto>()
                .toRemoteCodeEvent()
        }

    override suspend fun fetchActiveCodeClearEvents(): Result<List<RemoteCodeClearEvent>> =
        translating("load your code-clear history") {
            client.postgrest.from(CODE_CLEAR_EVENTS_TABLE)
                .select {
                    filter { filter("deleted_at", FilterOperator.IS, "null") }
                }
                .decodeList<CodeClearEventRowDto>()
                .map { it.toRemoteCodeClearEvent() }
        }

    override suspend fun upsertCodeClearEvent(event: CodeClearEventUpload): Result<RemoteCodeClearEvent> =
        translating("save that code-clear outcome") {
            client.postgrest.from(CODE_CLEAR_EVENTS_TABLE)
                .upsert(
                    CodeClearEventUpsertDto(
                        syncId = event.syncId,
                        vehicleId = event.vehicleServerId,
                        occurredAt = Instant.ofEpochMilli(event.occurredAtMs).toString(),
                        mileage = event.mileage,
                        codesBefore = Json.parseToJsonElement(event.codesBeforeJson),
                        freezeFrame = jsonOrNull(event.freezeFrameJson),
                        codesAfter = jsonOrNull(event.codesAfterJson),
                        outcome = event.outcome,
                        ackRaw = event.ackRaw,
                        provenance = event.provenance,
                    ),
                ) {
                    onConflict = "sync_id"
                    select()
                }
                .decodeSingle<CodeClearEventRowDto>()
                .toRemoteCodeClearEvent()
        }

    override suspend fun fetchActiveOilAnalyses(): Result<List<RemoteOilAnalysis>> =
        translating("load your oil analyses") {
            client.postgrest.from(OIL_ANALYSES_TABLE)
                .select {
                    filter { filter("deleted_at", FilterOperator.IS, "null") }
                }
                .decodeList<OilAnalysisRowDto>()
                .map { it.toRemoteOilAnalysis() }
        }

    override suspend fun upsertOilAnalysis(analysis: OilAnalysisUpload): Result<RemoteOilAnalysis> =
        translating("save that oil analysis") {
            client.postgrest.from(OIL_ANALYSES_TABLE)
                .upsert(
                    OilAnalysisUpsertDto(
                        syncId = analysis.syncId,
                        vehicleId = analysis.vehicleServerId,
                        analyzedAt = Instant.ofEpochMilli(analysis.analyzedAtMs).toString(),
                        mileage = analysis.mileage,
                        oilBrand = analysis.oilBrand,
                        oilGrade = analysis.oilGrade,
                        drainIntervalMiles = analysis.drainIntervalMiles,
                        iron = analysis.iron,
                        copper = analysis.copper,
                        lead = analysis.lead,
                        tin = analysis.tin,
                        aluminum = analysis.aluminum,
                        chromium = analysis.chromium,
                        nickel = analysis.nickel,
                        sodium = analysis.sodium,
                        potassium = analysis.potassium,
                        silicon = analysis.silicon,
                        boron = analysis.boron,
                        magnesium = analysis.magnesium,
                        fuelPercent = analysis.fuelPercent,
                        waterPercent = analysis.waterPercent,
                        tbn = analysis.tbn,
                        viscosityCst = analysis.viscosityCst,
                        labNotes = analysis.labNotes,
                        provenance = analysis.provenance,
                    ),
                ) {
                    onConflict = "sync_id"
                    select()
                }
                .decodeSingle<OilAnalysisRowDto>()
                .toRemoteOilAnalysis()
        }

    override suspend fun fetchChassisQuirks(): Result<List<RemoteChassisQuirk>> =
        translating("load the chassis quirk index") {
            // No deleted_at filter - chassis_quirks has no such column (see RemoteChassisQuirk's
            // own doc comment: it is REPLACE-semantics reference content, not a per-user record).
            client.postgrest.from(CHASSIS_QUIRKS_TABLE)
                .select()
                .decodeList<ChassisQuirkRowDto>()
                .map { it.toRemoteChassisQuirk() }
        }

    override suspend fun upsertChassisQuirk(quirk: ChassisQuirkUpload): Result<RemoteChassisQuirk> =
        translating("save that chassis quirk") {
            client.postgrest.from(CHASSIS_QUIRKS_TABLE)
                .upsert(
                    ChassisQuirkUpsertDto(
                        quirkId = quirk.quirkId,
                        chassis = quirk.chassis,
                        engine = quirk.engine,
                        title = quirk.title,
                        symptom = quirk.symptom,
                        verificationSteps = quirk.verificationSteps,
                        mileageLow = quirk.mileageLow,
                        mileageHigh = quirk.mileageHigh,
                        severity = quirk.severity,
                        costLowCents = quirk.costLowCents,
                        costHighCents = quirk.costHighCents,
                        fixNotes = quirk.fixNotes,
                        sourceUrl = quirk.sourceUrl,
                        provenance = quirk.provenance,
                    ),
                ) {
                    onConflict = "quirk_id"
                    select()
                }
                .decodeSingle<ChassisQuirkRowDto>()
                .toRemoteChassisQuirk()
        }

    override suspend fun fetchVehicleSpecs(): Result<List<RemoteVehicleSpec>> =
        translating("load your vehicle specs") {
            // No deleted_at filter - vehicle_specs has no such column (see RemoteVehicleSpec's own
            // doc comment).
            client.postgrest.from(VEHICLE_SPECS_TABLE)
                .select()
                .decodeList<VehicleSpecRowDto>()
                .map { it.toRemoteVehicleSpec() }
        }

    override suspend fun upsertVehicleSpec(spec: VehicleSpecUpload): Result<RemoteVehicleSpec> =
        translating("save that vehicle's spec sheet") {
            client.postgrest.from(VEHICLE_SPECS_TABLE)
                .upsert(
                    VehicleSpecUpsertDto(
                        vehicleId = spec.vehicleServerId,
                        vin = spec.vin,
                        engineCylinders = spec.engineCylinders,
                        displacementL = spec.displacementL,
                        engineHp = spec.engineHp,
                        engineConfig = spec.engineConfig,
                        fuelType = spec.fuelType,
                        transmissionStyle = spec.transmissionStyle,
                        transmissionSpeeds = spec.transmissionSpeeds,
                        driveType = spec.driveType,
                        bodyClass = spec.bodyClass,
                        doors = spec.doors,
                        series = spec.series,
                        vehicleType = spec.vehicleType,
                        manufacturer = spec.manufacturer,
                        plantCity = spec.plantCity,
                        plantCountry = spec.plantCountry,
                        paintColor = spec.paintColor,
                        paintCode = spec.paintCode,
                        buildNotes = spec.buildNotes,
                        decodedAt = tsOrNull(spec.decodedAtMs),
                        provenance = spec.provenance,
                    ),
                ) {
                    onConflict = "vehicle_id"
                    select()
                }
                .decodeSingle<VehicleSpecRowDto>()
                .toRemoteVehicleSpec()
        }

    override suspend fun fetchActiveMaintenanceSchedules(): Result<List<RemoteMaintenanceSchedule>> =
        translating("load the maintenance schedule") {
            client.postgrest.from(MAINTENANCE_SCHEDULES_TABLE)
                .select {
                    filter { filter("deleted_at", FilterOperator.IS, "null") }
                }
                .decodeList<MaintenanceScheduleRowDto>()
                .map { it.toRemoteMaintenanceSchedule() }
        }

    override suspend fun upsertMaintenanceSchedule(schedule: MaintenanceScheduleUpload): Result<RemoteMaintenanceSchedule> =
        translating("save that maintenance schedule") {
            client.postgrest.from(MAINTENANCE_SCHEDULES_TABLE)
                .upsert(
                    MaintenanceScheduleUpsertDto(
                        vehicleId = schedule.vehicleServerId,
                        serviceName = schedule.serviceName,
                        intervalMiles = schedule.intervalMiles,
                        intervalMonths = schedule.intervalMonths,
                        intervalSource = schedule.intervalSource,
                        neverDone = schedule.neverDone,
                        provenance = schedule.provenance,
                    ),
                ) {
                    onConflict = "vehicle_id,service_name"
                    select()
                }
                .decodeSingle<MaintenanceScheduleRowDto>()
                .toRemoteMaintenanceSchedule()
        }

    override suspend fun fetchActiveBuildEntries(): Result<List<RemoteBuildEntry>> =
        translating("load your build sheet") {
            client.postgrest.from(BUILD_ENTRIES_TABLE)
                .select {
                    filter { filter("deleted_at", FilterOperator.IS, "null") }
                }
                .decodeList<BuildEntryRowDto>()
                .map { it.toRemoteBuildEntry() }
        }

    override suspend fun upsertBuildEntry(entry: BuildEntryUpload): Result<RemoteBuildEntry> =
        translating("save that build-sheet entry") {
            client.postgrest.from(BUILD_ENTRIES_TABLE)
                .upsert(
                    BuildEntryUpsertDto(
                        syncId = entry.syncId,
                        vehicleId = entry.vehicleServerId,
                        entryType = entry.entryType,
                        title = entry.title,
                        vendor = entry.vendor,
                        partNumber = entry.partNumber,
                        costCents = entry.costCents,
                        loggedAt = Instant.ofEpochMilli(entry.loggedAtMs).toString(),
                        mileage = entry.mileage,
                        notes = entry.notes,
                        provenance = entry.provenance,
                    ),
                ) {
                    onConflict = "sync_id"
                    select()
                }
                .decodeSingle<BuildEntryRowDto>()
                .toRemoteBuildEntry()
        }

    override suspend fun fetchActiveDriveReassignments(): Result<List<RemoteDriveReassignment>> =
        translating("load your drive-reassignment corrections") {
            client.postgrest.from(DRIVE_REASSIGNMENTS_TABLE)
                .select {
                    filter { filter("deleted_at", FilterOperator.IS, "null") }
                }
                .decodeList<DriveReassignmentRowDto>()
                .map { it.toRemoteDriveReassignment() }
        }

    override suspend fun upsertDriveReassignment(reassignment: DriveReassignmentUpload): Result<RemoteDriveReassignment> =
        translating("save that drive-reassignment correction") {
            client.postgrest.from(DRIVE_REASSIGNMENTS_TABLE)
                .upsert(
                    DriveReassignmentUpsertDto(
                        syncId = reassignment.syncId,
                        vehicleId = reassignment.vehicleServerId,
                        newVehicleId = reassignment.newVehicleServerId,
                        fromAt = Instant.ofEpochMilli(reassignment.fromAtMs).toString(),
                        toAt = Instant.ofEpochMilli(reassignment.toAtMs).toString(),
                        provenance = reassignment.provenance,
                    ),
                ) {
                    onConflict = "sync_id"
                    select()
                }
                .decodeSingle<DriveReassignmentRowDto>()
                .toRemoteDriveReassignment()
        }

    /**
     * The wire shape for [uploadObdSampleBatch]. `recorded_at` reuses [tsOrNull]'s non-nullable
     * sibling ([Instant.toString], called directly at the call site) rather than a shared helper -
     * every sample in a batch upload genuinely has a timestamp, unlike the many-optional-timestamp
     * DTOs elsewhere in this file.
     */
    @Serializable
    private data class ObdSampleUpsertDto(
        @SerialName("vehicle_id") val vehicleId: String,
        val pid: String,
        val value: Double,
        val unit: String,
        @SerialName("recorded_at") val recordedAt: String,
        val lat: Double?,
        val lng: Double?,
    )

    /**
     * A single Postgrest `upsert` call over the whole batch, `on_conflict` set to the table's own
     * natural key and `ignoreDuplicates = true` (the client-side name for `Prefer:
     * resolution=ignore-duplicates`, which is what makes a re-post of an already-present sample a
     * no-op rather than a merge or an error). No `select()` on the response - [ObdSampleReconcile]
     * does not need the rows Postgres now holds back, only whether the call succeeded, and skipping
     * the round-trip payload matters here specifically because a batch can be hundreds of rows.
     */
    override suspend fun uploadObdSampleBatch(batch: List<ObdSampleUpload>): Result<Unit> =
        translating("upload OBD samples") {
            if (batch.isNotEmpty()) {
                client.postgrest.from(OBD_SAMPLES_TABLE).upsert(
                    batch.map { sample ->
                        ObdSampleUpsertDto(
                            vehicleId = sample.vehicleServerId,
                            pid = sample.pid,
                            value = sample.value,
                            unit = sample.unit,
                            recordedAt = Instant.ofEpochMilli(sample.recordedAtMs).toString(),
                            lat = sample.lat,
                            lng = sample.lng,
                        )
                    },
                ) {
                    onConflict = "vehicle_id,pid,recorded_at"
                    ignoreDuplicates = true
                }
            }
            Unit
        }

    /**
     * `head = true` + `Count.EXACT` - a `HEAD` request whose only payload is the `Content-Range`
     * header [io.github.jan.supabase.postgrest.result.PostgrestResult.countOrNull] reads, never a
     * downloaded row. See [FleetBackend.countObdSamples]'s own doc for why this, not
     * [fetchActiveVehicles]'s full-fetch shape, is what [ObdSampleReconcile.maybeAutoRun] calls.
     * `countOrNull()` returning null (no `Content-Range` header at all) is treated as zero rather
     * than failed - the same "empty, not unreadable" question CLAUDE.md's proactive-raise rule
     * asks elsewhere doesn't apply to a HEAD count the way it does to a permission-gated read, and
     * a genuinely broken request already surfaces as a caught exception before `countOrNull` runs.
     */
    override suspend fun countObdSamples(): Result<Long> =
        translating("count OBD samples") {
            client.postgrest.from(OBD_SAMPLES_TABLE).select {
                head = true
                count(Count.EXACT)
            }.countOrNull() ?: 0L
        }
}
