package com.kevin.legion.backend.engine

import com.kevin.legion.backend.RemoteBuildEntry
import com.kevin.legion.backend.RemoteChassisQuirk
import com.kevin.legion.backend.RemoteCodeClearEvent
import com.kevin.legion.backend.RemoteCodeEvent
import com.kevin.legion.backend.RemoteDrive
import com.kevin.legion.backend.RemoteDriveReassignment
import com.kevin.legion.backend.RemoteMaintenanceSchedule
import com.kevin.legion.backend.RemoteObdSample
import com.kevin.legion.backend.RemoteOilAnalysis
import com.kevin.legion.backend.RemoteServiceHistory
import com.kevin.legion.backend.RemoteVehicle
import com.kevin.legion.backend.RemoteVehicleSpec
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/*
 * Every wire shape [DjangoFleetBackend] encodes or decodes, in one file so that class is the
 * routes and nothing else.
 *
 * **`internal`, not `private`, purely because they live in a different file from their one user.**
 * `DjangoBodyBackend` keeps its DTOs `private` in the same file; fleet has eleven tables and five
 * identity shapes, and one file carrying both would be past the 1000-line ceiling CLAUDE.md sets.
 * Nothing outside this package may see them either way.
 *
 * **Read off `server/openapi.yaml`, not off the Python and not off the live engine.** The contract
 * is the authority this ticket was told to use and it carries a staleness test; a deploy can lag
 * `dev`, so the live engine is the weaker source where they disagree.
 *
 * **Three rules hold across every write DTO here, and each one is a decision rather than a
 * style:**
 *
 * 1. **The identity column is absent from the body.** `SyncedModelViewSet.upsert` does
 *    `data[self.identity_field] = identity` before validating - the URL is the authority - so
 *    `origin_guid` / `sync_id` / `quirk_id` / `vehicle_id` never ride along. `DjangoPlaceWrite`
 *    makes the opposite call for `label` and says why; a guid is not human text a percent-encoding
 *    bug can mangle.
 * 2. **`provenance` is absent, and the value the caller supplied is therefore IGNORED.** It is
 *    read-only on every `SyncedSerializer`, and the server sets it from `default_provenance`:
 *    `DETERMINISTIC` for `drives`, `code_events`, `code_clear_events`, `chassis_quirks` and
 *    `vehicle_specs`, `USER` for the rest. `SupabaseFleetBackend` sends the caller's value and the
 *    engine will not, so a row's provenance can differ by transport - stated here rather than
 *    discovered, because it is exactly the kind of silent divergence a cutover is supposed to
 *    surface. It is not a loss of meaning: every one of these tables is authored data, and section
 *    4's gate governs `ledger_transactions`/`receipts`, which have no write route at all.
 * 3. **Every writable column is sent on every write, nulls included** ([engineSyncedJson] sets
 *    `explicitNulls = true`), because `PUT` replaces the whole row. A nullable field left off the
 *    wire is a field DRF never sees, and the value already stored would survive a caller asking for
 *    it to be cleared.
 */

internal fun fleetTs(ms: Long): String = ledgerTs(ms)

internal fun fleetParseTs(s: String): Long = ledgerParseTs(s)

// ---------------------------------------------------------------------------------------------
// SHAPE 1: origin_guid (vehicles, service_history)
// ---------------------------------------------------------------------------------------------

/** One `public.vehicles` row - `openapi.yaml`'s `Vehicle` schema. [odometerBaselineAt] and
 * [odometerBaseline] are paired-or-neither at the database level
 * (`vehicles_odometer_baseline_paired`); that invariant is the SERVER's to enforce, exactly as
 * [RemoteVehicle]'s own doc comment says, so nothing here checks it. */
@Serializable
internal data class DjangoVehicleRow(
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
    @SerialName("updated_at") val updatedAt: String,
    @SerialName("deleted_at") val deletedAt: String? = null,
    @SerialName("origin_guid") val originGuid: String? = null,
    val archived: Boolean = false,
    @SerialName("last_obd_mac") val lastObdMac: String? = null,
) {
    fun toRemote() = RemoteVehicle(
        serverId = id,
        name = name,
        make = make,
        model = model,
        year = year,
        trim = trim,
        engine = engine,
        confirmed = confirmed,
        odometerBaseline = odometerBaseline,
        odometerBaselineAtMs = odometerBaselineAt?.let { fleetParseTs(it) },
        updatedAtMs = fleetParseTs(updatedAt),
        deleted = deletedAt != null,
        originGuid = originGuid,
        archived = archived,
        lastObdMac = lastObdMac,
    )
}

@Serializable
internal data class DjangoVehicleWrite(
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
    @SerialName("last_obd_mac") val lastObdMac: String?,
)

/** One `public.service_history` row. [serviceDate] is a bare DATE, converted at UTC midnight in
 * both directions - the same convention every other date column in this package uses. */
@Serializable
internal data class DjangoServiceHistoryRow(
    val id: String,
    @SerialName("vehicle_id") val vehicleId: String,
    @SerialName("service_name") val serviceName: String,
    val mileage: Int? = null,
    @SerialName("service_date") val serviceDate: String? = null,
    @SerialName("cost_cents") val costCents: Long? = null,
    val kind: String,
    @SerialName("updated_at") val updatedAt: String,
    @SerialName("deleted_at") val deletedAt: String? = null,
    @SerialName("origin_guid") val originGuid: String? = null,
) {
    fun toRemote() = RemoteServiceHistory(
        serverId = id,
        vehicleServerId = vehicleId,
        serviceName = serviceName,
        mileage = mileage,
        serviceDateEpochMs = serviceDate?.let { ledgerParseDate(it) },
        costCents = costCents,
        kind = kind,
        updatedAtMs = fleetParseTs(updatedAt),
        deleted = deletedAt != null,
        originGuid = originGuid,
    )
}

@Serializable
internal data class DjangoServiceHistoryWrite(
    @SerialName("vehicle_id") val vehicleId: String,
    @SerialName("service_name") val serviceName: String,
    val mileage: Int?,
    @SerialName("service_date") val serviceDate: String?,
    @SerialName("cost_cents") val costCents: Long?,
    val kind: String,
)

// ---------------------------------------------------------------------------------------------
// SHAPE 2: sync_id (drives, code_events, code_clear_events, oil_analyses, build_entries,
// drive_reassignments)
// ---------------------------------------------------------------------------------------------

@Serializable
internal data class DjangoDriveRow(
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
    fun toRemote() = RemoteDrive(
        serverId = id,
        syncId = syncId,
        vehicleServerId = vehicleId,
        startedAtMs = fleetParseTs(startedAt),
        endedAtMs = fleetParseTs(endedAt),
        miles = miles,
        gallons = gallons,
        endReason = endReason,
        updatedAtMs = fleetParseTs(updatedAt),
        deleted = deletedAt != null,
    )
}

@Serializable
internal data class DjangoDriveWrite(
    @SerialName("vehicle_id") val vehicleId: String,
    @SerialName("started_at") val startedAt: String,
    @SerialName("ended_at") val endedAt: String,
    val miles: Double,
    val gallons: Double?,
    @SerialName("end_reason") val endReason: String,
)

/** One `public.code_events` row. `codes`/`freeze_frame` are `jsonb` server-side and raw JSON TEXT
 * at the [com.kevin.legion.backend.FleetBackend] seam, so they are [JsonElement] here and
 * `.toString()`d across - the identical treatment `SupabaseFleetBackend` gives them, for the
 * identical reason. */
@Serializable
internal data class DjangoCodeEventRow(
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
    fun toRemote() = RemoteCodeEvent(
        serverId = id,
        syncId = syncId,
        vehicleServerId = vehicleId,
        occurredAtMs = fleetParseTs(occurredAt),
        mileage = mileage,
        codesJson = codes.toString(),
        freezeFrameJson = freezeFrame?.toString(),
        provenance = provenance,
        updatedAtMs = fleetParseTs(updatedAt),
        deleted = deletedAt != null,
    )
}

@Serializable
internal data class DjangoCodeEventWrite(
    @SerialName("vehicle_id") val vehicleId: String,
    @SerialName("occurred_at") val occurredAt: String,
    val mileage: Int?,
    val codes: JsonElement,
    @SerialName("freeze_frame") val freezeFrame: JsonElement?,
)

@Serializable
internal data class DjangoCodeClearEventRow(
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
    fun toRemote() = RemoteCodeClearEvent(
        serverId = id,
        syncId = syncId,
        vehicleServerId = vehicleId,
        occurredAtMs = fleetParseTs(occurredAt),
        mileage = mileage,
        codesBeforeJson = codesBefore.toString(),
        freezeFrameJson = freezeFrame?.toString(),
        codesAfterJson = codesAfter?.toString(),
        outcome = outcome,
        ackRaw = ackRaw,
        provenance = provenance,
        updatedAtMs = fleetParseTs(updatedAt),
        deleted = deletedAt != null,
    )
}

@Serializable
internal data class DjangoCodeClearEventWrite(
    @SerialName("vehicle_id") val vehicleId: String,
    @SerialName("occurred_at") val occurredAt: String,
    val mileage: Int?,
    @SerialName("codes_before") val codesBefore: JsonElement,
    @SerialName("freeze_frame") val freezeFrame: JsonElement?,
    @SerialName("codes_after") val codesAfter: JsonElement?,
    val outcome: String,
    @SerialName("ack_raw") val ackRaw: String,
)

@Serializable
internal data class DjangoOilAnalysisRow(
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
    fun toRemote() = RemoteOilAnalysis(
        serverId = id,
        syncId = syncId,
        vehicleServerId = vehicleId,
        analyzedAtMs = fleetParseTs(analyzedAt),
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
        updatedAtMs = fleetParseTs(updatedAt),
        deleted = deletedAt != null,
    )
}

@Serializable
internal data class DjangoOilAnalysisWrite(
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
)

@Serializable
internal data class DjangoBuildEntryRow(
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
    fun toRemote() = RemoteBuildEntry(
        serverId = id,
        syncId = syncId,
        vehicleServerId = vehicleId,
        entryType = entryType,
        title = title,
        vendor = vendor,
        partNumber = partNumber,
        costCents = costCents,
        loggedAtMs = fleetParseTs(loggedAt),
        mileage = mileage,
        notes = notes,
        provenance = provenance,
        updatedAtMs = fleetParseTs(updatedAt),
        deleted = deletedAt != null,
    )
}

@Serializable
internal data class DjangoBuildEntryWrite(
    @SerialName("vehicle_id") val vehicleId: String,
    @SerialName("entry_type") val entryType: String,
    val title: String,
    val vendor: String,
    @SerialName("part_number") val partNumber: String,
    @SerialName("cost_cents") val costCents: Long?,
    @SerialName("logged_at") val loggedAt: String,
    val mileage: Int?,
    val notes: String,
)

@Serializable
internal data class DjangoDriveReassignmentRow(
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
    fun toRemote() = RemoteDriveReassignment(
        serverId = id,
        syncId = syncId,
        vehicleServerId = vehicleId,
        newVehicleServerId = newVehicleId,
        fromAtMs = fleetParseTs(fromAt),
        toAtMs = fleetParseTs(toAt),
        provenance = provenance,
        updatedAtMs = fleetParseTs(updatedAt),
        deleted = deletedAt != null,
    )
}

@Serializable
internal data class DjangoDriveReassignmentWrite(
    @SerialName("vehicle_id") val vehicleId: String,
    @SerialName("new_vehicle_id") val newVehicleId: String,
    @SerialName("from_at") val fromAt: String,
    @SerialName("to_at") val toAt: String,
)

// ---------------------------------------------------------------------------------------------
// SHAPE 3: quirk_id. SHAPE 4: vehicle_id. Neither table has a `deleted_at` column, so neither
// `Remote*` shape carries `deleted` and neither feed takes `?active=1`.
// ---------------------------------------------------------------------------------------------

@Serializable
internal data class DjangoChassisQuirkRow(
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
    fun toRemote() = RemoteChassisQuirk(
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
        updatedAtMs = fleetParseTs(updatedAt),
    )
}

@Serializable
internal data class DjangoChassisQuirkWrite(
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
)

@Serializable
internal data class DjangoVehicleSpecRow(
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
    fun toRemote() = RemoteVehicleSpec(
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
        decodedAtMs = decodedAt?.let { fleetParseTs(it) },
        provenance = provenance,
        updatedAtMs = fleetParseTs(updatedAt),
    )
}

@Serializable
internal data class DjangoVehicleSpecWrite(
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
)

// ---------------------------------------------------------------------------------------------
// SHAPE 5: the pair (vehicle_id, service_name). SHAPE 6: obd_samples, which is like nothing else.
// ---------------------------------------------------------------------------------------------

@Serializable
internal data class DjangoMaintenanceScheduleRow(
    val id: String,
    @SerialName("vehicle_id") val vehicleId: String,
    @SerialName("service_name") val serviceName: String,
    @SerialName("interval_miles") val intervalMiles: Int? = null,
    @SerialName("interval_months") val intervalMonths: Int? = null,
    @SerialName("interval_source") val intervalSource: String,
    @SerialName("never_done") val neverDone: Boolean = false,
    val provenance: String,
    @SerialName("updated_at") val updatedAt: String,
    @SerialName("deleted_at") val deletedAt: String? = null,
) {
    fun toRemote() = RemoteMaintenanceSchedule(
        serverId = id,
        vehicleServerId = vehicleId,
        serviceName = serviceName,
        intervalMiles = intervalMiles,
        intervalMonths = intervalMonths,
        intervalSource = intervalSource,
        neverDone = neverDone,
        provenance = provenance,
        updatedAtMs = fleetParseTs(updatedAt),
        deleted = deletedAt != null,
    )
}

/**
 * The `PUT /api/fleet/maintenance_schedules/<vehicle_id>/<service_name>/` body.
 *
 * **Both halves of the identity are in the URL, and NEITHER is repeated here** - the same rule
 * every other write DTO in this file follows, applied to a two-column key.
 * `MaintenanceScheduleViewSet.upsert` sets `data["vehicle_id"]` AND `data["service_name"]` from the
 * path before validating ("the URL is the authority on identity, exactly as in the generic
 * `upsert` - both halves of it").
 */
@Serializable
internal data class DjangoMaintenanceScheduleWrite(
    @SerialName("interval_miles") val intervalMiles: Int?,
    @SerialName("interval_months") val intervalMonths: Int?,
    @SerialName("interval_source") val intervalSource: String,
    @SerialName("never_done") val neverDone: Boolean,
)

/** One `public.obd_samples` row off the windowed feed. **No `id` on the wire and none here** -
 * `ObdSampleSerializer` publishes none, because there is no detail route to address a sample by
 * one; the natural key `(vehicle_id, pid, recorded_at)` IS the identity, which is also what
 * [RemoteObdSample] carries. `created_at` is on the wire and dropped by `ignoreUnknownKeys`. */
@Serializable
internal data class DjangoObdSampleRow(
    @SerialName("vehicle_id") val vehicleId: String,
    val pid: String,
    val value: Double,
    val unit: String,
    @SerialName("recorded_at") val recordedAt: String,
    val lat: Double? = null,
    val lng: Double? = null,
) {
    /** The natural key, rendered for [EngineSyncedTable]'s page-overlap de-duplication only.
     * Never sent anywhere - see that class's `idOf` parameter doc. */
    fun dedupeKey(): String = "$vehicleId|$pid|$recordedAt"

    fun toRemote() = RemoteObdSample(
        vehicleServerId = vehicleId,
        pid = pid,
        value = value,
        unit = unit,
        recordedAtMs = fleetParseTs(recordedAt),
        lat = lat,
        lng = lng,
    )
}

/** One element of `POST /api/fleet/obd_samples/batch/`'s JSON ARRAY body - `openapi.yaml`'s
 * `ObdSampleUpload`. Unlike every other write DTO here this one DOES carry its `vehicle_id`: the
 * batch route has no identity in the URL at all, so the body is the only place it can be. */
@Serializable
internal data class DjangoObdSampleWrite(
    @SerialName("vehicle_id") val vehicleId: String,
    val pid: String,
    val value: Double,
    val unit: String,
    @SerialName("recorded_at") val recordedAt: String,
    val lat: Double?,
    val lng: Double?,
)

/** `POST /api/fleet/obd_samples/batch/`'s response - `openapi.yaml`'s `ObdBatchResult`. Decoded
 * even though [com.kevin.legion.backend.FleetBackend.uploadObdSampleBatch] returns
 * `Result<Unit>`, so a 2xx carrying a body this client cannot understand fails as `Malformed`
 * rather than being reported as a successful upload of something nobody checked - the same reason
 * [DjangoEventsBackend.uploadMigratedEvent] decodes a row it then ignores. */
@Serializable
internal data class DjangoObdBatchResult(
    val received: Int,
    val inserted: Int,
    @SerialName("already_present") val alreadyPresent: Int,
    @SerialName("duplicates_in_batch") val duplicatesInBatch: Int,
)

/** `GET /api/fleet/obd_samples/count/`'s response - one integer and nothing else. */
@Serializable
internal data class DjangoObdSampleCount(val count: Long)
