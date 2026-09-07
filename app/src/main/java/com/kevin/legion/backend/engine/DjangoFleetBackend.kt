package com.kevin.legion.backend.engine

import com.kevin.legion.backend.BuildEntryUpload
import com.kevin.legion.backend.ChassisQuirkUpload
import com.kevin.legion.backend.CodeClearEventUpload
import com.kevin.legion.backend.CodeEventUpload
import com.kevin.legion.backend.DriveReassignmentUpload
import com.kevin.legion.backend.DriveUpload
import com.kevin.legion.backend.FleetBackend
import com.kevin.legion.backend.MaintenanceScheduleUpload
import com.kevin.legion.backend.MigratedServiceHistory
import com.kevin.legion.backend.MigratedVehicle
import com.kevin.legion.backend.ObdSampleUpload
import com.kevin.legion.backend.OilAnalysisUpload
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
import com.kevin.legion.backend.ServiceHistoryUpload
import com.kevin.legion.backend.VehicleSpecUpload
import com.kevin.legion.backend.VehicleUpload
import io.ktor.http.encodeURLPathPart
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull

private const val FLEET_ROOT = "/api/fleet/"
private const val MAINTENANCE_PATH = FLEET_ROOT + "maintenance_schedules/"
private const val OBD_PATH = FLEET_ROOT + "obd_samples/"
private const val OBD_BATCH_PATH = OBD_PATH + "batch/"
private const val OBD_COUNT_PATH = OBD_PATH + "count/"

/**
 * `api/fleet.py`'s own `OBD_BATCH_MAX`, mirrored so an oversized batch is refused before it is
 * sent rather than after a round trip that cannot succeed.
 *
 * **The server is still the authority and refuses independently** (a 400 naming the cap), so this
 * is a pre-flight, not a second copy of the rule in the sense ADR 0044 forbids - it decides
 * nothing the server does not also decide. The number is read off `api/fleet.py`, where it is
 * derived: the batch insert is ONE statement with eight placeholders per row and Postgres caps a
 * statement at 65535 bind parameters. It is twice `ObdSampleReconcile.BATCH_SIZE`, so no caller in
 * this app can reach it today. If the two ever drift, this refuses batches the engine would take -
 * the safe direction, and visible in words rather than as a silent truncation.
 */
private const val OBD_BATCH_MAX = 1000

private fun fleetDate(ms: Long): String =
    Instant.ofEpochMilli(ms).atZone(ZoneOffset.UTC).toLocalDate().toString()

/** `codes`/`freeze_frame`/`codes_after` cross the [FleetBackend] seam as raw JSON TEXT and are
 * `jsonb` on both servers, so they are re-parsed here on the way out. A null stays absent as a
 * genuine JSON null rather than as an omitted key: [engineSyncedJson] sets `explicitNulls`, and a
 * `PUT` that omitted the key would leave the stored value in place. */
private fun fleetJson(text: String): JsonElement = Json.parseToJsonElement(text)

private fun fleetJsonOrNull(text: String?): JsonElement = text?.let { fleetJson(it) } ?: JsonNull

/**
 * [FleetBackend] over the household Django engine (`server/api/fleet.py`, eleven synced tables plus
 * `obd_samples`' three hand-written routes). Interchangeable with
 * [com.kevin.legion.backend.SupabaseFleetBackend] by construction: same interface, same `Remote*`
 * types out.
 *
 * **All six identity shapes, and the server has one route table per shape** - the four
 * [FleetBackend]'s own class doc enumerates, plus the pair and plus `obd_samples`:
 *
 * | Shape | Tables | Addressed by |
 * |---|---|---|
 * | 1 | `vehicles`, `service_history` | `origin_guid` |
 * | 2 | `drives`, `code_events`, `code_clear_events`, `oil_analyses`, `build_entries`, and
 *   `drive_reassignments` | `sync_id` |
 * | 3 | `chassis_quirks` | `quirk_id`, no `deleted_at` |
 * | 4 | `vehicle_specs` | `vehicle_id`, no `deleted_at` |
 * | 5 | `maintenance_schedules` | the PAIR `(vehicle_id, service_name)`, two path segments |
 * | 6 | `obd_samples` | nothing - no detail route at all |
 *
 * **`chassis_quirks` and `vehicle_specs` are read with a `since`-less `?since=` feed, never
 * `?active=1`.** Both set `has_tombstones = False` because neither table has a `deleted_at` column,
 * so the unnarrowed feed already IS the live set and `?active=1` is a parameter those views do not
 * advertise. `fetchChassisQuirks`/`fetchVehicleSpecs` are unconditional full fetches in the
 * interface too, so nothing is lost.
 *
 * **THE ONE GAP, stated here rather than buried: [upsertVehicle] and [upsertServiceHistory] cannot
 * be served by this transport and refuse in words.** Those two interface functions are keyed on
 * [VehicleUpload.serverId] / [ServiceHistoryUpload.serverId] - a `vehicles.id` uuid, null meaning
 * "insert" - which is the identity `SupabaseFleetBackend` writes against. The engine's routes for
 * both tables are keyed on `origin_guid` and there is no POST on either collection, so there is no
 * request this class could send that addresses the row the caller means. Resolving one identity to
 * the other would mean either minting an `origin_guid` for a live-created car (the column means
 * "migration provenance" - see [com.kevin.legion.backend.RemoteVehicle.originGuid] - and is null on
 * every server-created row) or looking one up and finding null. **That is a decision about what
 * `origin_guid` means, not a transport detail, so it is refused rather than guessed at.** Fleet
 * still defaults to [Transport.SUPABASE], so nothing changes for any install today; the two
 * migration uploads ([uploadMigratedVehicle], [uploadMigratedServiceHistory]) DO carry an
 * `origin_guid` and work.
 */
// FleetBackend declares 37 functions - eleven tables x (fetch active, upsert, fetch changed) plus
// obd_samples' three - so any class implementing it has 37 too, and detekt's TooManyFunctions
// ceiling of 11 cannot be met without leaving part of the interface unimplemented.
// SupabaseFleetBackend carries the identical count and sits in config/detekt/baseline.xml for the
// identical reason; this ticket's brief forbids adding to that baseline, so the same fact is
// stated here instead, next to the code it describes. Same decision, same words, as
// DjangoBodyBackend's own suppression.
@Suppress("TooManyFunctions") // 37 = FleetBackend's own function count; see the comment above.
class DjangoFleetBackend(private val http: EngineHttp) : FleetBackend {

    private val vehicles = table("vehicles", DjangoVehicleRow.serializer()) { it.id }
    private val serviceHistory = table("service_history", DjangoServiceHistoryRow.serializer()) { it.id }
    private val drives = table("drives", DjangoDriveRow.serializer()) { it.id }
    private val codeEvents = table("code_events", DjangoCodeEventRow.serializer()) { it.id }
    private val codeClearEvents = table("code_clear_events", DjangoCodeClearEventRow.serializer()) { it.id }
    private val oilAnalyses = table("oil_analyses", DjangoOilAnalysisRow.serializer()) { it.id }
    private val buildEntries = table("build_entries", DjangoBuildEntryRow.serializer()) { it.id }
    private val driveReassignments =
        table("drive_reassignments", DjangoDriveReassignmentRow.serializer()) { it.id }
    private val chassisQuirks = table("chassis_quirks", DjangoChassisQuirkRow.serializer()) { it.quirkId }
    private val vehicleSpecs = table("vehicle_specs", DjangoVehicleSpecRow.serializer()) { it.vehicleId }
    private val maintenance =
        table("maintenance_schedules", DjangoMaintenanceScheduleRow.serializer()) { it.id }
    private val obdSamples = table("obd_samples", DjangoObdSampleRow.serializer()) { it.dedupeKey() }

    // --- Shape 1: origin_guid ------------------------------------------------------------------

    override suspend fun fetchActiveVehicles(): Result<List<RemoteVehicle>> =
        translatingEngineCall("load your vehicles") { vehicles.fetchActive().map { it.toRemote() } }

    override suspend fun fetchChangedVehiclesSince(sinceMs: Long): Result<List<RemoteVehicle>> =
        translatingEngineCall("load changed vehicles") {
            vehicles.fetchChangedSince(fleetTs(sinceMs)).map { it.toRemote() }
        }

    /**
     * `PUT /api/fleet/vehicles/<origin_guid>/`.
     *
     * **`Result.success(true)` here means "the row now holds these values", never "this call is what
     * created it".** The interface's contract is `false` for "already present, nothing written", and
     * this transport cannot honour that half: the route answers 200 whether it inserted or updated,
     * deliberately ("which of the two happened is not something a retrying client can act on"), and
     * there is no detail GET to pre-flight against. That is the same narrowing
     * [DjangoPlacesBackend.softDelete]'s own doc comment records for its own `false`, and it is
     * narrow in the safe direction: `true` always means the server holds this vehicle now, which is
     * the only thing [com.kevin.legion.backend.FleetReconcile] acts on. Its `uploaded` count reads
     * as "rows sent" rather than "rows newly created" on this transport - the same convention
     * `ObdSampleReconcile.Report.uploaded` already states for `obd_samples`.
     */
    override suspend fun uploadMigratedVehicle(vehicle: MigratedVehicle): Result<Boolean> =
        translatingEngineCall("upload a migrated vehicle") {
            val body = engineSyncedJson.encodeToString(
                DjangoVehicleWrite.serializer(),
                DjangoVehicleWrite(
                    name = vehicle.name,
                    make = vehicle.make,
                    model = vehicle.model,
                    year = vehicle.year,
                    trim = vehicle.trim,
                    engine = vehicle.engine,
                    confirmed = vehicle.confirmed,
                    odometerBaseline = vehicle.odometerBaseline,
                    odometerBaselineAt = vehicle.odometerBaselineAtMs?.let { fleetTs(it) },
                    // Neither field exists on MigratedVehicle - the one-time replay predates both
                    // (tickets 27 and the 2026-09-03 pull) - and PUT replaces the whole row, so
                    // they go out at their column defaults rather than being omitted.
                    archived = false,
                    lastObdMac = null,
                ),
            )
            vehicles.put(vehicle.originGuid, body)
            true
        }

    /** Refused without sending anything - see this class's own doc comment, "THE ONE GAP". */
    override suspend fun upsertVehicle(vehicle: VehicleUpload): Result<RemoteVehicle> =
        Result.failure(EngineHttpException(EngineFailure.Refused(HTTP_NOT_FOUND, LIVE_VEHICLE_REFUSAL)))

    override suspend fun fetchActiveServiceHistory(): Result<List<RemoteServiceHistory>> =
        translatingEngineCall("load your service history") {
            serviceHistory.fetchActive().map { it.toRemote() }
        }

    override suspend fun fetchChangedServiceHistorySince(sinceMs: Long): Result<List<RemoteServiceHistory>> =
        translatingEngineCall("load changed service history") {
            serviceHistory.fetchChangedSince(fleetTs(sinceMs)).map { it.toRemote() }
        }

    /** Same `true`-means-"the row holds this now" narrowing as [uploadMigratedVehicle]. */
    override suspend fun uploadMigratedServiceHistory(history: MigratedServiceHistory): Result<Boolean> =
        translatingEngineCall("upload a migrated service record") {
            val body = engineSyncedJson.encodeToString(
                DjangoServiceHistoryWrite.serializer(),
                DjangoServiceHistoryWrite(
                    vehicleId = history.vehicleServerId,
                    serviceName = history.serviceName,
                    mileage = history.mileage,
                    serviceDate = history.serviceDateEpochMs?.let { fleetDate(it) },
                    costCents = history.costCents,
                    kind = history.kind,
                ),
            )
            serviceHistory.put(history.originGuid, body)
            true
        }

    /** Refused without sending anything - see this class's own doc comment, "THE ONE GAP". */
    override suspend fun upsertServiceHistory(history: ServiceHistoryUpload): Result<RemoteServiceHistory> =
        Result.failure(EngineHttpException(EngineFailure.Refused(HTTP_NOT_FOUND, LIVE_SERVICE_REFUSAL)))

    // --- Shape 2: sync_id ----------------------------------------------------------------------

    override suspend fun fetchActiveDrives(): Result<List<RemoteDrive>> =
        translatingEngineCall("load your drives") { drives.fetchActive().map { it.toRemote() } }

    override suspend fun fetchChangedDrivesSince(sinceMs: Long): Result<List<RemoteDrive>> =
        translatingEngineCall("load changed drives") {
            drives.fetchChangedSince(fleetTs(sinceMs)).map { it.toRemote() }
        }

    override suspend fun upsertDrive(drive: DriveUpload): Result<RemoteDrive> =
        translatingEngineCall("save that drive") {
            val body = engineSyncedJson.encodeToString(
                DjangoDriveWrite.serializer(),
                DjangoDriveWrite(
                    vehicleId = drive.vehicleServerId,
                    startedAt = fleetTs(drive.startedAtMs),
                    endedAt = fleetTs(drive.endedAtMs),
                    miles = drive.miles,
                    gallons = drive.gallons,
                    endReason = drive.endReason,
                ),
            )
            drives.put(drive.syncId, body).toRemote()
        }

    override suspend fun fetchActiveCodeEvents(): Result<List<RemoteCodeEvent>> =
        translatingEngineCall("load your stored codes") { codeEvents.fetchActive().map { it.toRemote() } }

    override suspend fun fetchChangedCodeEventsSince(sinceMs: Long): Result<List<RemoteCodeEvent>> =
        translatingEngineCall("load changed stored codes") {
            codeEvents.fetchChangedSince(fleetTs(sinceMs)).map { it.toRemote() }
        }

    override suspend fun upsertCodeEvent(event: CodeEventUpload): Result<RemoteCodeEvent> =
        translatingEngineCall("save that code event") {
            val body = engineSyncedJson.encodeToString(
                DjangoCodeEventWrite.serializer(),
                DjangoCodeEventWrite(
                    vehicleId = event.vehicleServerId,
                    occurredAt = fleetTs(event.occurredAtMs),
                    mileage = event.mileage,
                    codes = fleetJson(event.codesJson),
                    freezeFrame = fleetJsonOrNull(event.freezeFrameJson),
                ),
            )
            codeEvents.put(event.syncId, body).toRemote()
        }

    override suspend fun fetchActiveCodeClearEvents(): Result<List<RemoteCodeClearEvent>> =
        translatingEngineCall("load your code clears") {
            codeClearEvents.fetchActive().map { it.toRemote() }
        }

    override suspend fun fetchChangedCodeClearEventsSince(sinceMs: Long): Result<List<RemoteCodeClearEvent>> =
        translatingEngineCall("load changed code clears") {
            codeClearEvents.fetchChangedSince(fleetTs(sinceMs)).map { it.toRemote() }
        }

    override suspend fun upsertCodeClearEvent(event: CodeClearEventUpload): Result<RemoteCodeClearEvent> =
        translatingEngineCall("save that code clear") {
            val body = engineSyncedJson.encodeToString(
                DjangoCodeClearEventWrite.serializer(),
                DjangoCodeClearEventWrite(
                    vehicleId = event.vehicleServerId,
                    occurredAt = fleetTs(event.occurredAtMs),
                    mileage = event.mileage,
                    codesBefore = fleetJson(event.codesBeforeJson),
                    freezeFrame = fleetJsonOrNull(event.freezeFrameJson),
                    codesAfter = fleetJsonOrNull(event.codesAfterJson),
                    outcome = event.outcome,
                    ackRaw = event.ackRaw,
                ),
            )
            codeClearEvents.put(event.syncId, body).toRemote()
        }

    override suspend fun fetchActiveOilAnalyses(): Result<List<RemoteOilAnalysis>> =
        translatingEngineCall("load your oil analyses") { oilAnalyses.fetchActive().map { it.toRemote() } }

    override suspend fun fetchChangedOilAnalysesSince(sinceMs: Long): Result<List<RemoteOilAnalysis>> =
        translatingEngineCall("load changed oil analyses") {
            oilAnalyses.fetchChangedSince(fleetTs(sinceMs)).map { it.toRemote() }
        }

    override suspend fun upsertOilAnalysis(analysis: OilAnalysisUpload): Result<RemoteOilAnalysis> =
        translatingEngineCall("save that oil analysis") {
            oilAnalyses.put(analysis.syncId, oilAnalysisBody(analysis)).toRemote()
        }

    override suspend fun fetchActiveBuildEntries(): Result<List<RemoteBuildEntry>> =
        translatingEngineCall("load your build sheet") { buildEntries.fetchActive().map { it.toRemote() } }

    override suspend fun fetchChangedBuildEntriesSince(sinceMs: Long): Result<List<RemoteBuildEntry>> =
        translatingEngineCall("load changed build entries") {
            buildEntries.fetchChangedSince(fleetTs(sinceMs)).map { it.toRemote() }
        }

    override suspend fun upsertBuildEntry(entry: BuildEntryUpload): Result<RemoteBuildEntry> =
        translatingEngineCall("save that build entry") {
            val body = engineSyncedJson.encodeToString(
                DjangoBuildEntryWrite.serializer(),
                DjangoBuildEntryWrite(
                    vehicleId = entry.vehicleServerId,
                    entryType = entry.entryType,
                    title = entry.title,
                    vendor = entry.vendor,
                    partNumber = entry.partNumber,
                    costCents = entry.costCents,
                    loggedAt = fleetTs(entry.loggedAtMs),
                    mileage = entry.mileage,
                    notes = entry.notes,
                ),
            )
            buildEntries.put(entry.syncId, body).toRemote()
        }

    override suspend fun fetchActiveDriveReassignments(): Result<List<RemoteDriveReassignment>> =
        translatingEngineCall("load your drive corrections") {
            driveReassignments.fetchActive().map { it.toRemote() }
        }

    override suspend fun fetchChangedDriveReassignmentsSince(
        sinceMs: Long,
    ): Result<List<RemoteDriveReassignment>> =
        translatingEngineCall("load changed drive corrections") {
            driveReassignments.fetchChangedSince(fleetTs(sinceMs)).map { it.toRemote() }
        }

    override suspend fun upsertDriveReassignment(
        reassignment: DriveReassignmentUpload,
    ): Result<RemoteDriveReassignment> =
        translatingEngineCall("save that drive correction") {
            val body = engineSyncedJson.encodeToString(
                DjangoDriveReassignmentWrite.serializer(),
                DjangoDriveReassignmentWrite(
                    vehicleId = reassignment.vehicleServerId,
                    newVehicleId = reassignment.newVehicleServerId,
                    fromAt = fleetTs(reassignment.fromAtMs),
                    toAt = fleetTs(reassignment.toAtMs),
                ),
            )
            driveReassignments.put(reassignment.syncId, body).toRemote()
        }

    // --- Shapes 3 and 4: quirk_id, vehicle_id (neither table has a tombstone column) ------------

    override suspend fun fetchChassisQuirks(): Result<List<RemoteChassisQuirk>> =
        translatingEngineCall("load the chassis quirks") {
            // `?since=` with no value, NOT `?active=1` - see this class's own doc comment.
            chassisQuirks.fetchChangedSince(null).map { it.toRemote() }
        }

    override suspend fun upsertChassisQuirk(quirk: ChassisQuirkUpload): Result<RemoteChassisQuirk> =
        translatingEngineCall("save that chassis quirk") {
            val body = engineSyncedJson.encodeToString(
                DjangoChassisQuirkWrite.serializer(),
                DjangoChassisQuirkWrite(
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
                ),
            )
            chassisQuirks.put(quirk.quirkId, body).toRemote()
        }

    override suspend fun fetchVehicleSpecs(): Result<List<RemoteVehicleSpec>> =
        translatingEngineCall("load the vehicle specs") {
            vehicleSpecs.fetchChangedSince(null).map { it.toRemote() }
        }

    override suspend fun fetchChangedVehicleSpecsSince(sinceMs: Long): Result<List<RemoteVehicleSpec>> =
        translatingEngineCall("load changed vehicle specs") {
            vehicleSpecs.fetchChangedSince(fleetTs(sinceMs)).map { it.toRemote() }
        }

    override suspend fun upsertVehicleSpec(spec: VehicleSpecUpload): Result<RemoteVehicleSpec> =
        translatingEngineCall("save that vehicle spec") {
            vehicleSpecs.put(spec.vehicleServerId, vehicleSpecBody(spec)).toRemote()
        }

    // --- Shape 5: the pair (vehicle_id, service_name) ------------------------------------------

    override suspend fun fetchActiveMaintenanceSchedules(): Result<List<RemoteMaintenanceSchedule>> =
        translatingEngineCall("load your maintenance schedules") {
            // The LIST action is the generic one, inherited untouched - only the detail route
            // departs (`MaintenanceScheduleViewSet`'s own docstring), so `?active=1` is right here
            // even though the two functions below cannot use EngineSyncedTable at all.
            maintenance.fetchActive().map { it.toRemote() }
        }

    override suspend fun fetchChangedMaintenanceSchedulesSince(
        sinceMs: Long,
    ): Result<List<RemoteMaintenanceSchedule>> =
        translatingEngineCall("load changed maintenance schedules") {
            maintenance.fetchChangedSince(fleetTs(sinceMs)).map { it.toRemote() }
        }

    /**
     * `PUT /api/fleet/maintenance_schedules/<vehicle_id>/<service_name>/`.
     *
     * **Built here rather than through [EngineSyncedTable], because the identity is TWO path
     * segments and that class encodes exactly one.** Handing it `"$vehicleId/$serviceName"` would
     * percent-encode the separator into `%2F` and address a row that cannot exist. Each half is
     * encoded on its own, so a service name with a space ("oil change" - the common case) survives
     * as `%20`; a service name containing a literal `/` cannot be addressed at all, which is a
     * limit `MaintenanceScheduleViewSet.detail_path_suffix` states for itself ("Django's `str`
     * converter matches any non-empty string without a `/`") and has never occurred.
     */
    override suspend fun upsertMaintenanceSchedule(
        schedule: MaintenanceScheduleUpload,
    ): Result<RemoteMaintenanceSchedule> =
        translatingEngineCall("save that maintenance schedule") {
            val body = engineSyncedJson.encodeToString(
                DjangoMaintenanceScheduleWrite.serializer(),
                DjangoMaintenanceScheduleWrite(
                    intervalMiles = schedule.intervalMiles,
                    intervalMonths = schedule.intervalMonths,
                    intervalSource = schedule.intervalSource,
                    neverDone = schedule.neverDone,
                ),
            )
            val response = http.put(
                maintenancePath(schedule.vehicleServerId, schedule.serviceName),
                body,
            ).getOrThrow()
            engineSyncedJson
                .decodeFromString(DjangoMaintenanceScheduleRow.serializer(), response.body)
                .toRemote()
        }

    // --- Shape 6: obd_samples ------------------------------------------------------------------

    /**
     * `POST /api/fleet/obd_samples/batch/` with a JSON ARRAY body.
     *
     * **Idempotent by construction rather than by checking**: `obd_samples_natural_key_idx` is
     * unique on `(vehicle_id, pid, recorded_at)` and the insert names it as its `on conflict`
     * target, so a re-post of a batch that already landed writes nothing and answers 200 with
     * `inserted: 0`. That is the normal case, not the exceptional one -
     * [com.kevin.legion.backend.ObdSampleReconcile] resumes from a cursor it may have failed to
     * advance.
     *
     * An empty batch is a no-op that sends nothing: the server would answer 200 with `inserted: 0`,
     * so the round trip buys nothing. An oversized one is refused in words - see [OBD_BATCH_MAX].
     */
    // A `when` rather than two guard clauses and a return, purely to keep to detekt's ReturnCount
    // ceiling of two: the three branches are the three answers, and none of them is an early exit
    // from a body that continues.
    override suspend fun uploadObdSampleBatch(batch: List<ObdSampleUpload>): Result<Unit> = when {
        batch.isEmpty() -> Result.success(Unit)
        batch.size > OBD_BATCH_MAX -> oversizedBatchRefusal(batch.size)
        else -> translatingEngineCall("upload those OBD samples") {
            val body = engineSyncedJson.encodeToString(
                ListSerializer(DjangoObdSampleWrite.serializer()),
                batch.map { obdSampleWrite(it) },
            )
            val response = http.post(OBD_BATCH_PATH, body).getOrThrow()
            // Decoded although the result is discarded - see DjangoObdBatchResult's own doc comment.
            engineSyncedJson.decodeFromString(DjangoObdBatchResult.serializer(), response.body)
        }
    }

    /** `GET /api/fleet/obd_samples/count/` - one integer, no rows, the same bargain the Supabase
     * transport struck with a `HEAD` plus `Prefer: count=exact`. No `?vehicle=` here: the interface
     * asks for the whole table, and the count route makes that parameter optional precisely because
     * an aggregate is one row whatever the table's size. */
    override suspend fun countObdSamples(): Result<Long> =
        translatingEngineCall("count the stored OBD samples") {
            val response = http.get(OBD_COUNT_PATH).getOrThrow()
            engineSyncedJson.decodeFromString(DjangoObdSampleCount.serializer(), response.body).count
        }

    /**
     * `GET /api/fleet/obd_samples/?vehicle=<uuid>&since=<iso>` - the windowed, per-vehicle pull.
     *
     * **`vehicle` is REQUIRED and is always sent.** This is the one list route in the API with a
     * mandatory filter (`api/fleet.py`: 20,796 rows on 2026-09-07, "an unbounded feed over it is a
     * download, not a feed"), and an absent one is a 400 naming the parameter rather than a page of
     * everything. `since` filters `recorded_at` - when the sample was READ FROM THE CAR, not when it
     * reached the server - which is the column [FleetBackend.fetchObdSamplesSince] means.
     *
     * No `?active=1`: this table has no `deleted_at`, so there are no tombstones to include or
     * exclude.
     */
    override suspend fun fetchObdSamplesSince(
        vehicleServerId: String,
        sinceMs: Long,
    ): Result<List<RemoteObdSample>> =
        translatingEngineCall("load that car's OBD samples") {
            obdSamples
                .fetchChangedSince(fleetTs(sinceMs), mapOf("vehicle" to vehicleServerId))
                .map { it.toRemote() }
        }

    // --- Plumbing ------------------------------------------------------------------------------

    private fun obdSampleWrite(sample: ObdSampleUpload) = DjangoObdSampleWrite(
        vehicleId = sample.vehicleServerId,
        pid = sample.pid,
        value = sample.value,
        unit = sample.unit,
        recordedAt = fleetTs(sample.recordedAtMs),
        lat = sample.lat,
        lng = sample.lng,
    )

    /** [uploadObdSampleBatch]'s over-cap answer, split out so the refusal wording lives beside
     * [OBD_BATCH_MAX]'s reasoning rather than inside a `when` branch. */
    private fun oversizedBatchRefusal(size: Int): Result<Unit> = Result.failure(
        EngineHttpException(
            EngineFailure.Refused(
                status = HTTP_NOT_FOUND,
                body = "That batch of $size OBD samples is over the engine's cap of " +
                    "$OBD_BATCH_MAX - nothing was sent, and none of them were stored. Send them " +
                    "in smaller batches.",
            ),
        ),
    )

    private fun <ROW> table(
        name: String,
        rowSerializer: KSerializer<ROW>,
        idOf: (ROW) -> String,
    ) = EngineSyncedTable(
        http = http,
        path = "$FLEET_ROOT$name/",
        rowSerializer = rowSerializer,
        idOf = idOf,
    )

    /** Split out only because [upsertOilAnalysis] would otherwise be a 30-line expression body;
     * there is no logic here, one named argument per column. */
    private fun oilAnalysisBody(analysis: OilAnalysisUpload): String =
        engineSyncedJson.encodeToString(
            DjangoOilAnalysisWrite.serializer(),
            DjangoOilAnalysisWrite(
                vehicleId = analysis.vehicleServerId,
                analyzedAt = fleetTs(analysis.analyzedAtMs),
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
            ),
        )

    /** Split out for the same reason as [oilAnalysisBody]. */
    private fun vehicleSpecBody(spec: VehicleSpecUpload): String =
        engineSyncedJson.encodeToString(
            DjangoVehicleSpecWrite.serializer(),
            DjangoVehicleSpecWrite(
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
                decodedAt = spec.decodedAtMs?.let { fleetTs(it) },
            ),
        )

    internal companion object {
        /** Each half percent-encoded on its own so the separator survives - see
         * [upsertMaintenanceSchedule]'s doc comment. `internal` so a test can assert the exact path
         * both key halves produce rather than re-deriving it. */
        internal fun maintenancePath(vehicleServerId: String, serviceName: String): String =
            MAINTENANCE_PATH + vehicleServerId.encodeURLPathPart() + "/" +
                serviceName.encodeURLPathPart() + "/"

        internal const val LIVE_VEHICLE_REFUSAL =
            "The engine addresses a vehicle by its origin_guid and this write carries only a " +
                "server id, so there is no row it could name - nothing was sent and no vehicle " +
                "was saved."

        internal const val LIVE_SERVICE_REFUSAL =
            "The engine addresses a service record by its origin_guid and this write carries only " +
                "a server id, so there is no row it could name - nothing was sent and no service " +
                "record was saved."
    }
}
