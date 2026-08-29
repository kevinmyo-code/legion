package com.kevin.legion.backend

import com.kevin.legion.data.local.BuildEntry
import com.kevin.legion.data.local.CarDatabase
import com.kevin.legion.data.local.ChassisQuirk
import com.kevin.legion.data.local.CodeClearEvent
import com.kevin.legion.data.local.CodeEvent
import com.kevin.legion.data.local.Drive
import com.kevin.legion.data.local.DriveReassignment
import com.kevin.legion.data.local.MaintenanceItem
import com.kevin.legion.data.local.OilAnalysis
import com.kevin.legion.data.local.RecordProvenance
import com.kevin.legion.data.local.Vehicle
import com.kevin.legion.data.local.VehicleSpec
import com.kevin.legion.engine.RecordStore
import com.kevin.legion.engine.fleet.FleetAspectSeeder
import com.kevin.legion.engine.fleet.FleetRecordBridge
import com.kevin.legion.testutil.RoomTestReset
import com.kevin.legion.vehicle.FleetEngineStore
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * FleetReconcile's first wave (`vehicles`/`service_history`/`drives` only -
 * `.scratch/backend-erp/issues/10-fleet-cutover.md`). Exercised entirely against an in-memory
 * [FakeFleetBackend] and a real (Robolectric) engine/Room, never a network - same posture as
 * [PlacesReconcileTest]/[EventsReconcileTest].
 */
@RunWith(RobolectricTestRunner::class)
class FleetReconcileTest {
    private val context = RuntimeEnvironment.getApplication()

    private class FakeFleetBackend : FleetBackend {
        val vehicles = mutableMapOf<String, RemoteVehicle>() // keyed by originGuid
        val serviceHistory = mutableMapOf<String, RemoteServiceHistory>() // keyed by originGuid
        val drives = mutableMapOf<String, RemoteDrive>() // keyed by syncId
        val codeEvents = mutableMapOf<String, RemoteCodeEvent>() // keyed by syncId
        val codeClearEvents = mutableMapOf<String, RemoteCodeClearEvent>() // keyed by syncId
        val oilAnalyses = mutableMapOf<String, RemoteOilAnalysis>() // keyed by syncId
        val chassisQuirks = mutableMapOf<String, RemoteChassisQuirk>() // keyed by quirkId
        val vehicleSpecs = mutableMapOf<String, RemoteVehicleSpec>() // keyed by vehicleServerId
        val buildEntries = mutableMapOf<String, RemoteBuildEntry>() // keyed by syncId
        val driveReassignments = mutableMapOf<String, RemoteDriveReassignment>() // keyed by syncId
        var clock = 1_000L
        private var vehicleCounter = 0
        private var serviceHistoryCounter = 0
        private var driveCounter = 0
        private var codeEventCounter = 0
        private var codeClearEventCounter = 0
        private var oilAnalysisCounter = 0
        private var buildEntryCounter = 0
        private var driveReassignmentCounter = 0

        /** Set to make the NEXT [uploadMigratedVehicle] call fail - the short-circuit test's hook. */
        var failNextVehicleUpload = false

        /** Set to make the NEXT [upsertCodeEvent] call fail - the four-new-tables short-circuit
         * test's hook, mirroring [failNextVehicleUpload]'s shape one section down. */
        var failNextCodeEventUpload = false

        /** Set to make the NEXT [upsertBuildEntry] call fail - this wave's own short-circuit test
         * hook, mirroring [failNextCodeEventUpload]'s shape. */
        var failNextBuildEntryUpload = false

        override suspend fun fetchActiveVehicles(): Result<List<RemoteVehicle>> =
            Result.success(vehicles.values.filterNot { it.deleted })

        override suspend fun uploadMigratedVehicle(vehicle: MigratedVehicle): Result<Boolean> {
            if (failNextVehicleUpload) {
                failNextVehicleUpload = false
                return Result.failure(FleetBackendException("simulated transport failure"))
            }
            if (vehicles.containsKey(vehicle.originGuid)) return Result.success(false)
            vehicles[vehicle.originGuid] = RemoteVehicle(
                serverId = "vehicle-${++vehicleCounter}",
                name = vehicle.name,
                make = vehicle.make,
                model = vehicle.model,
                year = vehicle.year,
                trim = vehicle.trim,
                engine = vehicle.engine,
                confirmed = vehicle.confirmed,
                odometerBaseline = vehicle.odometerBaseline,
                odometerBaselineAtMs = vehicle.odometerBaselineAtMs,
                updatedAtMs = ++clock,
                deleted = false,
                originGuid = vehicle.originGuid,
                archived = false,
            )
            return Result.success(true)
        }

        /** Ticket 26's live write - a separate map, keyed by [RemoteVehicle.serverId] rather than
         * [vehicles]' `originGuid` keying, mirroring [SupabaseFleetBackend.upsertVehicle]'s own
         * "insert when null, update by id otherwise" contract without pretending origin_guid is
         * involved at all. */
        val liveVehicles = mutableMapOf<String, RemoteVehicle>()

        override suspend fun upsertVehicle(vehicle: VehicleUpload): Result<RemoteVehicle> {
            val row = if (vehicle.serverId == null) {
                RemoteVehicle(
                    serverId = "live-vehicle-${++vehicleCounter}",
                    name = vehicle.name,
                    make = vehicle.make,
                    model = vehicle.model,
                    year = vehicle.year,
                    trim = vehicle.trim,
                    engine = vehicle.engine,
                    confirmed = vehicle.confirmed,
                    odometerBaseline = vehicle.odometerBaseline,
                    odometerBaselineAtMs = vehicle.odometerBaselineAtMs,
                    updatedAtMs = ++clock,
                    deleted = false,
                    originGuid = null,
                    archived = vehicle.archived,
                )
            } else {
                val existing = liveVehicles[vehicle.serverId]
                    ?: return Result.failure(FleetBackendException("no row for ${vehicle.serverId}"))
                existing.copy(
                    name = vehicle.name,
                    make = vehicle.make,
                    model = vehicle.model,
                    year = vehicle.year,
                    trim = vehicle.trim,
                    engine = vehicle.engine,
                    confirmed = vehicle.confirmed,
                    odometerBaseline = vehicle.odometerBaseline,
                    odometerBaselineAtMs = vehicle.odometerBaselineAtMs,
                    updatedAtMs = ++clock,
                    archived = vehicle.archived,
                )
            }
            liveVehicles[row.serverId] = row
            return Result.success(row)
        }

        override suspend fun fetchActiveServiceHistory(): Result<List<RemoteServiceHistory>> =
            Result.success(serviceHistory.values.filterNot { it.deleted })

        override suspend fun uploadMigratedServiceHistory(history: MigratedServiceHistory): Result<Boolean> {
            if (serviceHistory.containsKey(history.originGuid)) return Result.success(false)
            serviceHistory[history.originGuid] = RemoteServiceHistory(
                serverId = "service_history-${++serviceHistoryCounter}",
                vehicleServerId = history.vehicleServerId,
                serviceName = history.serviceName,
                mileage = history.mileage,
                serviceDateEpochMs = history.serviceDateEpochMs,
                costCents = history.costCents,
                kind = history.kind,
                updatedAtMs = ++clock,
                deleted = false,
                originGuid = history.originGuid,
            )
            return Result.success(true)
        }

        override suspend fun upsertServiceHistory(history: ServiceHistoryUpload): Result<RemoteServiceHistory> = error("out of scope")

        override suspend fun fetchActiveDrives(): Result<List<RemoteDrive>> =
            Result.success(drives.values.filterNot { it.deleted })

        override suspend fun upsertDrive(drive: DriveUpload): Result<RemoteDrive> {
            val existing = drives[drive.syncId]
            val row = RemoteDrive(
                serverId = existing?.serverId ?: "drive-${++driveCounter}",
                syncId = drive.syncId,
                vehicleServerId = drive.vehicleServerId,
                startedAtMs = drive.startedAtMs,
                endedAtMs = drive.endedAtMs,
                miles = drive.miles,
                gallons = drive.gallons,
                endReason = drive.endReason,
                updatedAtMs = ++clock,
                deleted = false,
            )
            drives[drive.syncId] = row
            return Result.success(row)
        }

        override suspend fun fetchActiveCodeEvents(): Result<List<RemoteCodeEvent>> =
            Result.success(codeEvents.values.filterNot { it.deleted })

        override suspend fun upsertCodeEvent(event: CodeEventUpload): Result<RemoteCodeEvent> {
            if (failNextCodeEventUpload) {
                failNextCodeEventUpload = false
                return Result.failure(FleetBackendException("simulated transport failure"))
            }
            val existing = codeEvents[event.syncId]
            val row = RemoteCodeEvent(
                serverId = existing?.serverId ?: "code_event-${++codeEventCounter}",
                syncId = event.syncId,
                vehicleServerId = event.vehicleServerId,
                occurredAtMs = event.occurredAtMs,
                mileage = event.mileage,
                codesJson = event.codesJson,
                freezeFrameJson = event.freezeFrameJson,
                // Records what the reconcile actually sent, never a value the fake invents - a
                // reconcile that stopped asserting provenance would show up here as whatever it
                // sent (or a missing-field compile error), not as a value this fake papers over.
                provenance = event.provenance,
                updatedAtMs = ++clock,
                deleted = false,
            )
            codeEvents[event.syncId] = row
            return Result.success(row)
        }

        override suspend fun fetchActiveCodeClearEvents(): Result<List<RemoteCodeClearEvent>> =
            Result.success(codeClearEvents.values.filterNot { it.deleted })

        override suspend fun upsertCodeClearEvent(event: CodeClearEventUpload): Result<RemoteCodeClearEvent> {
            val existing = codeClearEvents[event.syncId]
            val row = RemoteCodeClearEvent(
                serverId = existing?.serverId ?: "code_clear_event-${++codeClearEventCounter}",
                syncId = event.syncId,
                vehicleServerId = event.vehicleServerId,
                occurredAtMs = event.occurredAtMs,
                mileage = event.mileage,
                codesBeforeJson = event.codesBeforeJson,
                freezeFrameJson = event.freezeFrameJson,
                codesAfterJson = event.codesAfterJson,
                outcome = event.outcome,
                ackRaw = event.ackRaw,
                provenance = event.provenance,
                updatedAtMs = ++clock,
                deleted = false,
            )
            codeClearEvents[event.syncId] = row
            return Result.success(row)
        }

        override suspend fun fetchActiveOilAnalyses(): Result<List<RemoteOilAnalysis>> =
            Result.success(oilAnalyses.values.filterNot { it.deleted })

        override suspend fun upsertOilAnalysis(analysis: OilAnalysisUpload): Result<RemoteOilAnalysis> {
            val existing = oilAnalyses[analysis.syncId]
            val row = RemoteOilAnalysis(
                serverId = existing?.serverId ?: "oil_analysis-${++oilAnalysisCounter}",
                syncId = analysis.syncId,
                vehicleServerId = analysis.vehicleServerId,
                analyzedAtMs = analysis.analyzedAtMs,
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
                // Records what the reconcile actually sent - see the code_events fake's own
                // comment above for why this must not be a literal.
                provenance = analysis.provenance,
                updatedAtMs = ++clock,
                deleted = false,
            )
            oilAnalyses[analysis.syncId] = row
            return Result.success(row)
        }

        override suspend fun fetchChassisQuirks(): Result<List<RemoteChassisQuirk>> =
            Result.success(chassisQuirks.values.toList())

        override suspend fun upsertChassisQuirk(quirk: ChassisQuirkUpload): Result<RemoteChassisQuirk> {
            val row = RemoteChassisQuirk(
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
                updatedAtMs = ++clock,
            )
            // A genuine REPLACE-on-conflict, matching SupabaseFleetBackend.upsertChassisQuirk's
            // real ON CONFLICT(quirk_id) DO UPDATE - always overwrites, never checks for "already
            // there" first, per ChassisQuirkUpload's own doc comment.
            chassisQuirks[quirk.quirkId] = row
            return Result.success(row)
        }

        override suspend fun fetchVehicleSpecs(): Result<List<RemoteVehicleSpec>> =
            Result.success(vehicleSpecs.values.toList())

        override suspend fun upsertVehicleSpec(spec: VehicleSpecUpload): Result<RemoteVehicleSpec> {
            val row = RemoteVehicleSpec(
                vehicleServerId = spec.vehicleServerId,
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
                decodedAtMs = spec.decodedAtMs,
                // Records what the reconcile actually sent - same posture as the code_events fake's
                // own comment above.
                provenance = spec.provenance,
                updatedAtMs = ++clock,
            )
            // A genuine REPLACE-on-conflict, matching SupabaseFleetBackend.upsertVehicleSpec's real
            // ON CONFLICT(vehicle_id) DO UPDATE - always overwrites, same shape as chassis quirks.
            vehicleSpecs[spec.vehicleServerId] = row
            return Result.success(row)
        }

        override suspend fun fetchActiveBuildEntries(): Result<List<RemoteBuildEntry>> =
            Result.success(buildEntries.values.filterNot { it.deleted })

        override suspend fun upsertBuildEntry(entry: BuildEntryUpload): Result<RemoteBuildEntry> {
            if (failNextBuildEntryUpload) {
                failNextBuildEntryUpload = false
                return Result.failure(FleetBackendException("simulated transport failure"))
            }
            val existing = buildEntries[entry.syncId]
            val row = RemoteBuildEntry(
                serverId = existing?.serverId ?: "build_entry-${++buildEntryCounter}",
                syncId = entry.syncId,
                vehicleServerId = entry.vehicleServerId,
                entryType = entry.entryType,
                title = entry.title,
                vendor = entry.vendor,
                partNumber = entry.partNumber,
                costCents = entry.costCents,
                loggedAtMs = entry.loggedAtMs,
                mileage = entry.mileage,
                notes = entry.notes,
                provenance = entry.provenance,
                updatedAtMs = ++clock,
                deleted = false,
            )
            buildEntries[entry.syncId] = row
            return Result.success(row)
        }

        override suspend fun fetchActiveDriveReassignments(): Result<List<RemoteDriveReassignment>> =
            Result.success(driveReassignments.values.filterNot { it.deleted })

        override suspend fun upsertDriveReassignment(reassignment: DriveReassignmentUpload): Result<RemoteDriveReassignment> {
            val existing = driveReassignments[reassignment.syncId]
            val row = RemoteDriveReassignment(
                serverId = existing?.serverId ?: "drive_reassignment-${++driveReassignmentCounter}",
                syncId = reassignment.syncId,
                vehicleServerId = reassignment.vehicleServerId,
                newVehicleServerId = reassignment.newVehicleServerId,
                fromAtMs = reassignment.fromAtMs,
                toAtMs = reassignment.toAtMs,
                provenance = reassignment.provenance,
                updatedAtMs = ++clock,
                deleted = false,
            )
            driveReassignments[reassignment.syncId] = row
            return Result.success(row)
        }

        /** [ObdSampleReconcile]'s upload target - see [ObdSampleReconcileTest] for the suite that
         *  actually exercises this, kept here rather than a second fake so both reconciles share
         *  one [FleetBackend] fake, matching production's own single-interface shape. */
        val obdSampleBatches = mutableListOf<List<ObdSampleUpload>>()

        /** Set to make the NEXT [uploadObdSampleBatch] call fail - same hook shape as
         *  [failNextVehicleUpload]. */
        var failNextObdSampleBatch = false

        override suspend fun uploadObdSampleBatch(batch: List<ObdSampleUpload>): Result<Unit> {
            if (failNextObdSampleBatch) {
                failNextObdSampleBatch = false
                return Result.failure(FleetBackendException("simulated transport failure"))
            }
            obdSampleBatches.add(batch)
            return Result.success(Unit)
        }
    }

    @Before
    fun clearState() {
        RoomTestReset.resetCarDatabaseSingleton()
    }

    /** Creates an active engine `Vehicle` record with [FleetRecordBridge.vehicleGuid] as its guid -
     * the exact derivation `vehicle/FleetEngineStore.kt` uses in production - so this suite's fixture
     * matches the real write path rather than inventing its own guid scheme. */
    private suspend fun createEngineVehicle(
        obdMac: String,
        name: String = "Jeep",
        make: String = "Jeep",
        model: String = "Cherokee",
        year: Int = 1998,
        trim: String? = "Sport",
        engine: String? = "4.0L I6",
        confirmed: Boolean = true,
        odometerBaseline: Int? = 142_000,
        odometerBaselineAtMs: Long? = 10_000L,
    ): Long {
        val db = CarDatabase.getDatabase(context)
        val sch = FleetAspectSeeder.ensureSeeded(context)
        val store = RecordStore(db.engineRecordDao(), db.fieldDefDao(), db.recordTypeDao())
        val result = store.create(
            sch.vehicle.recordTypeId,
            mapOf(
                sch.vehicle.fieldIds.getValue(FleetAspectSeeder.FIELD_NAME) to name,
                sch.vehicle.fieldIds.getValue(FleetAspectSeeder.FIELD_MAKE) to make,
                sch.vehicle.fieldIds.getValue(FleetAspectSeeder.FIELD_MODEL) to model,
                sch.vehicle.fieldIds.getValue(FleetAspectSeeder.FIELD_YEAR) to year,
                sch.vehicle.fieldIds.getValue(FleetAspectSeeder.FIELD_TRIM) to trim,
                sch.vehicle.fieldIds.getValue(FleetAspectSeeder.FIELD_ENGINE) to engine,
                sch.vehicle.fieldIds.getValue(FleetAspectSeeder.FIELD_CONFIRMED) to confirmed,
                sch.vehicle.fieldIds.getValue(FleetAspectSeeder.FIELD_ODOMETER_BASELINE) to odometerBaseline,
                sch.vehicle.fieldIds.getValue(FleetAspectSeeder.FIELD_ODOMETER_BASELINE_AT) to odometerBaselineAtMs,
            ),
            RecordProvenance.USER,
            guid = FleetRecordBridge.vehicleGuid(obdMac),
        )
        return (result as RecordStore.WriteResult.Success).recordId
    }

    private suspend fun createEngineServiceHistory(
        vehicleEngineId: Long,
        serviceName: String = "Oil change",
        mileage: Int? = 143_000,
        serviceDateEpochMs: Long? = 20_000L,
        costCents: Long? = 5_999L,
        kind: String = FleetAspectSeeder.KIND_OBSERVED,
    ): Long {
        val db = CarDatabase.getDatabase(context)
        val sch = FleetAspectSeeder.ensureSeeded(context)
        val store = RecordStore(db.engineRecordDao(), db.fieldDefDao(), db.recordTypeDao())
        val result = store.create(
            sch.serviceHistory.recordTypeId,
            mapOf(
                sch.serviceHistory.fieldIds.getValue(FleetAspectSeeder.FIELD_SH_VEHICLE) to vehicleEngineId,
                sch.serviceHistory.fieldIds.getValue(FleetAspectSeeder.FIELD_SH_SERVICE_NAME) to serviceName,
                sch.serviceHistory.fieldIds.getValue(FleetAspectSeeder.FIELD_SH_MILEAGE) to mileage,
                sch.serviceHistory.fieldIds.getValue(FleetAspectSeeder.FIELD_SH_SERVICE_DATE) to serviceDateEpochMs,
                sch.serviceHistory.fieldIds.getValue(FleetAspectSeeder.FIELD_SH_COST) to costCents,
                sch.serviceHistory.fieldIds.getValue(FleetAspectSeeder.FIELD_SH_KIND) to kind,
            ),
            RecordProvenance.USER,
        )
        return (result as RecordStore.WriteResult.Success).recordId
    }

    /** The legacy Room `Vehicle` row a real dongle registration would have created - needed so
     * [FleetReconcile] can resolve a [Drive.vehicleId] (obdMac) back to the engine vehicle's guid. */
    private suspend fun createLegacyVehicle(obdMac: String) {
        CarDatabase.getDatabase(context).vehicleDao().upsert(
            Vehicle(obdMac = obdMac, name = "Jeep", make = "Jeep", model = "Cherokee", year = 1998, personaPrompt = ""),
        )
    }

    private suspend fun createDrive(
        vehicleId: String,
        syncId: String,
        startedAt: Long = 1_000L,
        endedAt: Long = 2_000L,
        miles: Double = 12.5,
        gallons: Double? = 0.6,
        endReason: String = "ENGINE_OFF",
    ) {
        CarDatabase.getDatabase(context).driveDao().insert(
            Drive(vehicleId = vehicleId, startedAt = startedAt, endedAt = endedAt, miles = miles, gallons = gallons, endReason = endReason, syncId = syncId),
        )
    }

    private suspend fun createCodeEvent(
        vehicleId: String,
        syncId: String,
        timestamp: Long = 1_000L,
        mileage: Int? = 100_000,
        codesJson: String = """["P0420"]""",
        freezeFrameJson: String = "",
    ) {
        CarDatabase.getDatabase(context).codeEventDao().insert(
            CodeEvent(
                vehicleId = vehicleId,
                timestamp = timestamp,
                mileage = mileage,
                codesJson = codesJson,
                freezeFrameJson = freezeFrameJson,
                syncId = syncId,
            ),
        )
    }

    private suspend fun createCodeClearEvent(
        vehicleId: String,
        syncId: String,
        timestamp: Long = 1_000L,
        codesBeforeJson: String = """["P0420"]""",
        freezeFrameJson: String = "",
        codesAfterJson: String = "",
        outcome: String = "UNVERIFIED",
    ) {
        CarDatabase.getDatabase(context).codeClearEventDao().insert(
            CodeClearEvent(
                vehicleId = vehicleId,
                timestamp = timestamp,
                codesBeforeJson = codesBeforeJson,
                freezeFrameJson = freezeFrameJson,
                codesAfterJson = codesAfterJson,
                outcome = outcome,
                syncId = syncId,
            ),
        )
    }

    private suspend fun createOilAnalysis(
        vehicleId: String,
        syncId: String,
        date: Long = 1_000L,
        iron: Int? = 12,
    ) {
        CarDatabase.getDatabase(context).oilAnalysisDao().insert(
            OilAnalysis(vehicleId = vehicleId, date = date, iron = iron, syncId = syncId),
        )
    }

    private suspend fun createChassisQuirk(
        quirkId: String,
        chassis: String = "E46",
        mileageLow: Int = -1,
        mileageHigh: Int = -1,
        costLow: Int = -1,
        costHigh: Int = -1,
    ) {
        CarDatabase.getDatabase(context).chassisQuirkDao().upsertAll(
            listOf(
                ChassisQuirk(
                    quirkId = quirkId,
                    chassis = chassis,
                    title = "Subframe crack",
                    symptom = "clunk",
                    verificationSteps = "inspect",
                    mileageLow = mileageLow,
                    mileageHigh = mileageHigh,
                    severity = "MONITOR",
                    costLow = costLow,
                    costHigh = costHigh,
                ),
            ),
        )
    }

    private suspend fun createVehicleSpec(
        vehicleId: String,
        vin: String = "1J4FF68S6WL123456",
        engineCylinders: Int? = 6,
        decodedAt: Long = 0L,
    ) {
        CarDatabase.getDatabase(context).vehicleSpecDao().upsertStamped(
            VehicleSpec(vehicleId = vehicleId, vin = vin, engineCylinders = engineCylinders, decodedAt = decodedAt),
        )
    }

    private suspend fun createBuildEntry(
        vehicleId: String,
        syncId: String,
        type: String = "mod",
        title: String = "Lift kit",
        cost: Double? = 19.995,
        date: Long = 1_000L,
    ) {
        CarDatabase.getDatabase(context).buildEntryDao().insert(
            BuildEntry(vehicleId = vehicleId, type = type, title = title, cost = cost, date = date, syncId = syncId),
        )
    }

    private suspend fun createDriveReassignment(
        vehicleId: String,
        newVehicleId: String,
        syncId: String,
        fromMs: Long = 1_000L,
        toMs: Long = 2_000L,
    ) {
        CarDatabase.getDatabase(context).driveReassignmentDao().insert(
            DriveReassignment(syncId = syncId, vehicleId = vehicleId, fromMs = fromMs, toMs = toMs, newVehicleId = newVehicleId),
        )
    }

    @After
    fun drainRoomInvalidationTracker() {
        // A DAO write anywhere in this test can schedule a Room InvalidationTracker refresh
        // on ArchTaskExecutor's disk-IO pool. If that refresh is still queued or running when
        // this test method returns, it races Robolectric's own per-@Test-METHOD native SQLite
        // reset and throws "Illegal connection pointer" on a background thread - uncaught, and
        // blamed by kotlinx-coroutines-test on whatever runTest starts next, not on this class.
        // Draining here, before Robolectric ever gets a chance to reset, is the fix - see
        // RoomTestReset's own class doc comment
        // (.scratch/hardening/issues/13-the-suite-is-green-by-luck.md) for the full account.
        RoomTestReset.drainArchDiskIoPool()
    }


    @Test
    fun `each of the three types maps field-for-field`() = runBlocking {
        val obdMac = "AA:BB:CC:DD:EE:FF"
        val vehicleEngineId = createEngineVehicle(obdMac)
        createEngineServiceHistory(vehicleEngineId)
        createLegacyVehicle(obdMac)
        createDrive(vehicleId = obdMac, syncId = "drive-sync-1")
        val backend = FakeFleetBackend()

        val report = FleetReconcile.run(context, backend).getOrThrow()

        assertEquals(1, report.vehicle.engineCount)
        assertEquals(1, report.vehicle.uploaded)
        val vehicleRow = backend.vehicles.values.single()
        assertEquals("Jeep", vehicleRow.name)
        assertEquals("Jeep", vehicleRow.make)
        assertEquals("Cherokee", vehicleRow.model)
        assertEquals(1998, vehicleRow.year)
        assertEquals("Sport", vehicleRow.trim)
        assertEquals("4.0L I6", vehicleRow.engine)
        assertTrue(vehicleRow.confirmed)
        assertEquals(142_000, vehicleRow.odometerBaseline)
        assertEquals(10_000L, vehicleRow.odometerBaselineAtMs)

        assertEquals(1, report.serviceHistory.engineCount)
        assertEquals(1, report.serviceHistory.uploaded)
        assertTrue(report.serviceHistory.skippedUnresolvedVehicle.isEmpty())
        val shRow = backend.serviceHistory.values.single()
        assertEquals(vehicleRow.serverId, shRow.vehicleServerId)
        assertEquals("Oil change", shRow.serviceName)
        assertEquals(143_000, shRow.mileage)
        assertEquals(20_000L, shRow.serviceDateEpochMs)
        assertEquals(5_999L, shRow.costCents)
        assertEquals(FleetAspectSeeder.KIND_OBSERVED, shRow.kind)

        assertEquals(1, report.drive.sourceCount)
        assertEquals(1, report.drive.uploaded)
        assertTrue(report.drive.skippedUnresolvedVehicle.isEmpty())
        val driveRow = backend.drives.values.single()
        assertEquals(vehicleRow.serverId, driveRow.vehicleServerId)
        assertEquals(1_000L, driveRow.startedAtMs)
        assertEquals(2_000L, driveRow.endedAtMs)
        assertEquals(12.5, driveRow.miles, 0.0)
        assertEquals(0.6, driveRow.gallons!!, 0.0)
        assertEquals("ENGINE_OFF", driveRow.endReason)

        assertTrue(report.isClean)

        // Both new replicas (wave 2) refilled from the same server rows the reports above assert on.
        assertEquals(1, report.vehicle.replicaCountAfter)
        assertEquals(1, report.serviceHistory.replicaCountAfter)
        val db = CarDatabase.getDatabase(context)
        val replicaVehicle = db.vehicleReplicaDao().getAllActive().single()
        assertEquals(vehicleRow.serverId, replicaVehicle.serverId)
        assertEquals("Jeep", replicaVehicle.name)
        assertEquals(142_000, replicaVehicle.odometerBaseline)
        val replicaServiceHistory = db.serviceHistoryReplicaDao().getAllActive().single()
        assertEquals(shRow.serverId, replicaServiceHistory.serverId)
        assertEquals(vehicleRow.serverId, replicaServiceHistory.vehicleServerId)
        assertEquals("Oil change", replicaServiceHistory.serviceName)
    }

    @Test
    fun `a vehicle whose year falls outside the server's check constraint is skipped and named, never uploaded`() = runBlocking {
        // Reproduces the real 2026-08-28 on-device failure: a legacy placeholder vehicle
        // (year = 0) was uploaded and Supabase rejected it with `vehicles_year_check`, aborting the
        // whole run. The fix is a pre-check, not a retry - the bad vehicle is named and held back,
        // and every OTHER vehicle in the same run still uploads.
        val badObdMac = "00:00:00:00:00:00"
        createEngineVehicle(badObdMac, name = "", make = "", model = "", year = 0)
        val goodObdMac = "AA:BB:CC:DD:EE:FF"
        createEngineVehicle(goodObdMac, name = "Jeep", year = 1998)
        val backend = FakeFleetBackend()

        val report = FleetReconcile.run(context, backend).getOrThrow()

        assertEquals(2, report.vehicle.engineCount)
        assertEquals(1, report.vehicle.uploaded)
        assertEquals(1, backend.vehicles.size)
        assertEquals(1, report.vehicle.skippedUnexportable.size)
        assertTrue(report.vehicle.skippedUnexportable.single().contains("year"))
        // A skipped-unexportable vehicle is a known, named state, not a real one-sided diff.
        assertTrue(report.vehicle.onlyOnEngine.isEmpty())
        assertFalse(report.vehicle.isClean)
        assertFalse(report.isClean)
    }

    @Test
    fun `a vehicle rejected on its own shape does not stop the run - later waves still execute`() = runBlocking {
        val badObdMac = "00:00:00:00:00:00"
        val badVehicleEngineId = createEngineVehicle(badObdMac, year = 0)
        createLegacyVehicle(badObdMac)
        createEngineServiceHistory(badVehicleEngineId)
        val goodObdMac = "AA:BB:CC:DD:EE:FF"
        createEngineVehicle(goodObdMac, year = 1998)
        createLegacyVehicle(goodObdMac)
        val backend = FakeFleetBackend()

        val report = FleetReconcile.run(context, backend).getOrThrow()

        // The bad vehicle's own service-history row has no server parent to resolve through
        // (its vehicle was never uploaded) so it lands in the existing skipped-unresolved-vehicle
        // bucket, composing correctly with no extra plumbing - it is neither uploaded nor dropped.
        assertEquals(1, report.serviceHistory.skippedUnresolvedVehicle.size)
        assertTrue(backend.serviceHistory.isEmpty())
        // Later waves ran to completion rather than the whole reconcile aborting.
        assertEquals(1, report.vehicle.uploaded)
        assertEquals(0, report.drive.sourceCount)
        assertTrue(report.drive.isClean)
    }

    @Test
    fun `gallons null survives as null rather than becoming 0`() = runBlocking {
        val obdMac = "11:22:33:44:55:66"
        createEngineVehicle(obdMac)
        createLegacyVehicle(obdMac)
        createDrive(vehicleId = obdMac, syncId = "drive-no-fuel", gallons = null)
        val backend = FakeFleetBackend()

        FleetReconcile.run(context, backend).getOrThrow()

        val uploaded = backend.drives.values.single()
        assertNull(uploaded.gallons)

        // And the local replica (this table refills itself) must not have silently turned the
        // upload's own null into a zero either.
        val local = CarDatabase.getDatabase(context).driveDao().getBySyncId("drive-no-fuel")
        assertNull(local?.gallons)
    }

    @Test
    fun `a re-run is idempotent - identity is stable, not just counts`() = runBlocking {
        val obdMac = "77:88:99:AA:BB:CC"
        val vehicleEngineId = createEngineVehicle(obdMac)
        createEngineServiceHistory(vehicleEngineId)
        createLegacyVehicle(obdMac)
        createDrive(vehicleId = obdMac, syncId = "drive-idempotent")
        val backend = FakeFleetBackend()

        val first = FleetReconcile.run(context, backend).getOrThrow()
        val vehicleServerIdAfterFirst = backend.vehicles.values.single().serverId
        val serviceHistoryServerIdAfterFirst = backend.serviceHistory.values.single().serverId
        val db = CarDatabase.getDatabase(context)
        val replicaVehicleIdAfterFirst = db.vehicleReplicaDao().getAllActive().single().serverId
        val replicaServiceHistoryIdAfterFirst = db.serviceHistoryReplicaDao().getAllActive().single().serverId

        val second = FleetReconcile.run(context, backend).getOrThrow()

        // Identity, not just counts (lessons.md L-shape "assert on identity, NOT only on counts") -
        // a wholesale-refresh defect would still report matching counts while quietly reminting a
        // fresh server row for an already-migrated vehicle or service-history entry.
        assertEquals(vehicleServerIdAfterFirst, backend.vehicles.values.single().serverId)
        assertEquals(serviceHistoryServerIdAfterFirst, backend.serviceHistory.values.single().serverId)
        assertEquals(1, backend.vehicles.size)
        assertEquals(1, backend.serviceHistory.size)
        assertEquals(1, backend.drives.size)

        // Same identity check for the two NEW replicas: a re-run wipes and refills both tables, and
        // this is the regression test for that refill quietly reminting the wrong server row (it
        // cannot remint a LOCAL id here the way b17bc88 did for EventReplica - see VehicleReplica's
        // own doc comment for why that mechanism is not needed - but a refill bug could still lose
        // or duplicate the SERVER identity the replica is supposed to mirror).
        assertEquals(replicaVehicleIdAfterFirst, db.vehicleReplicaDao().getAllActive().single().serverId)
        assertEquals(replicaServiceHistoryIdAfterFirst, db.serviceHistoryReplicaDao().getAllActive().single().serverId)
        assertEquals(1, db.vehicleReplicaDao().getAllActive().size)
        assertEquals(1, db.serviceHistoryReplicaDao().getAllActive().size)

        assertEquals(0, second.vehicle.uploaded)
        assertEquals(0, second.serviceHistory.uploaded)
        assertEquals(first.vehicle.serverCountAfter, second.vehicle.serverCountAfter)
        assertEquals(first.serviceHistory.serverCountAfter, second.serviceHistory.serverCountAfter)
        assertEquals(first.vehicle.replicaCountAfter, second.vehicle.replicaCountAfter)
        assertEquals(first.serviceHistory.replicaCountAfter, second.serviceHistory.replicaCountAfter)
        assertEquals(first.drive.replicaCountAfter, second.drive.replicaCountAfter)
        assertTrue(second.isClean)
    }

    @Test
    fun `a failed upload short-circuits and touches nothing further`() = runBlocking {
        createEngineVehicle("DE:AD:BE:EF:00:01")
        createEngineVehicle("DE:AD:BE:EF:00:02")
        val backend = FakeFleetBackend()
        backend.failNextVehicleUpload = true

        val result = FleetReconcile.run(context, backend)

        assertTrue(result.isFailure)
        // The failure happened on the FIRST vehicle - nothing landed server-side, and the second
        // vehicle's upload was never attempted, matching PantryReconcile/EventsReconcile's own
        // "a partial upload must never be reported as a low count" posture (there is no report at
        // all on failure, which is the strongest version of that guarantee).
        assertTrue(backend.vehicles.isEmpty())
        assertTrue(backend.serviceHistory.isEmpty())
        assertTrue(backend.drives.isEmpty())
    }

    @Test
    fun `never deletes or trashes a source row - engine records and drives all survive a run`() = runBlocking {
        val obdMac = "CA:FE:BA:BE:00:01"
        val vehicleEngineId = createEngineVehicle(obdMac)
        createEngineServiceHistory(vehicleEngineId)
        createLegacyVehicle(obdMac)
        createDrive(vehicleId = obdMac, syncId = "drive-survives")
        val backend = FakeFleetBackend()
        val db = CarDatabase.getDatabase(context)
        val sch = FleetAspectSeeder.ensureSeeded(context)

        FleetReconcile.run(context, backend).getOrThrow()

        assertEquals(1, db.engineRecordDao().activeByRecordType(sch.vehicle.recordTypeId).size)
        assertEquals(1, db.engineRecordDao().activeByRecordType(sch.serviceHistory.recordTypeId).size)
        assertEquals(1, db.driveDao().getAll().size)
        // The replicas are a byproduct of the same run, not a source of truth - confirming they
        // landed alongside the engine/drive rows above, never a substitute check for them.
        assertEquals(1, db.vehicleReplicaDao().getAllActive().size)
        assertEquals(1, db.serviceHistoryReplicaDao().getAllActive().size)
    }

    @Test
    fun `a service-history row referencing a vehicle with no server counterpart is skipped, never uploaded with a wrong parent`() = runBlocking {
        // The vehicle engine record is trashed (bypassing RecordStore.delete's own BLOCK-policy
        // check via a raw DAO call, purely to construct the fixture) so it drops out of
        // activeByRecordType entirely - the closest reproducible stand-in for "the vehicle has not
        // migrated yet" without relying on a partial upload, which this reconcile's own
        // short-circuit rule makes impossible to construct (see the test above).
        //
        // Engine retirement step 3 (ticket 16): createLegacyVehicle is now REQUIRED here too, not
        // just createEngineVehicle - EngineFleetServiceHistoryRetirementCopy (which FleetReconcile
        // now runs before reading ServiceHistory) can only translate an engine Vehicle record's
        // guid back to its obdMac via the LEGACY `vehicles` table (FleetRecordBridge.vehicleGuid is
        // a one-way hash - see FleetEngineStore's own class doc), and ticket 14 established that a
        // real install always co-writes both. Without the legacy row, the copier cannot resolve
        // which car the ServiceHistory engine record belongs to and skips copying it entirely,
        // which would make this test fail for the wrong reason (no local row) rather than the one
        // it exists to prove (a local row whose vehicle has not reached the server yet).
        val obdMac = "FE:ED:FA:CE:00:02"
        val vehicleEngineId = createEngineVehicle(obdMac)
        createEngineServiceHistory(vehicleEngineId)
        createLegacyVehicle(obdMac)
        CarDatabase.getDatabase(context).engineRecordDao().trash(vehicleEngineId, System.currentTimeMillis())
        val backend = FakeFleetBackend()

        val report = FleetReconcile.run(context, backend).getOrThrow()

        assertEquals(1, report.serviceHistory.engineCount)
        assertEquals(0, report.serviceHistory.uploaded)
        assertEquals(1, report.serviceHistory.skippedUnresolvedVehicle.size)
        assertTrue(backend.serviceHistory.isEmpty())
        // Skipped, not counted as a diff failure - see FleetReconcile.Report's own doc on why an
        // unresolved parent is excluded from the engine side of the comparison.
        assertTrue(report.serviceHistory.isClean)
    }

    @Test
    fun `a drive referencing a vehicle with no server counterpart is skipped, never uploaded with a wrong parent`() = runBlocking {
        createDrive(vehicleId = "unknown-obd-mac", syncId = "drive-orphan")
        val backend = FakeFleetBackend()

        val report = FleetReconcile.run(context, backend).getOrThrow()

        assertEquals(1, report.drive.sourceCount)
        assertEquals(0, report.drive.uploaded)
        assertEquals(1, report.drive.skippedUnresolvedVehicle.size)
        assertTrue(backend.drives.isEmpty())
    }

    // ---- Wave 3: code_events / code_clear_events / oil_analyses / chassis_quirks -----------------

    @Test
    fun `the four new tables map field-for-field, including oil_analyses' provenance divergence`() = runBlocking {
        val obdMac = "10:20:30:40:50:60"
        createEngineVehicle(obdMac)
        createLegacyVehicle(obdMac)
        createCodeEvent(vehicleId = obdMac, syncId = "code-1", codesJson = """["P0420","P0128"]""")
        createCodeClearEvent(
            vehicleId = obdMac, syncId = "clear-1",
            codesAfterJson = """["P0128"]""", outcome = "RETURNED",
        )
        createOilAnalysis(vehicleId = obdMac, syncId = "oil-1", iron = 17)
        createChassisQuirk(quirkId = "e46_subframe_crack", mileageLow = 80_000, mileageHigh = 150_000, costLow = 200, costHigh = 600)
        val backend = FakeFleetBackend()

        val report = FleetReconcile.run(context, backend).getOrThrow()

        assertEquals(1, report.codeEvent.uploaded)
        val codeEventRow = backend.codeEvents.values.single()
        assertEquals("""["P0420","P0128"]""", codeEventRow.codesJson)
        assertEquals("DETERMINISTIC", codeEventRow.provenance)

        assertEquals(1, report.codeClearEvent.uploaded)
        val clearRow = backend.codeClearEvents.values.single()
        assertEquals("RETURNED", clearRow.outcome)
        assertEquals("""["P0128"]""", clearRow.codesAfterJson)
        assertEquals("DETERMINISTIC", clearRow.provenance)

        assertEquals(1, report.oilAnalysis.uploaded)
        val oilRow = backend.oilAnalyses.values.single()
        assertEquals(17, oilRow.iron)
        // The one place this wave's provenance diverges from its DETERMINISTIC siblings -
        // OilAnalysis.kt's own doc comment: a person transcribed a lab report, code did not
        // derive it.
        assertEquals("USER", oilRow.provenance)

        assertEquals(1, report.chassisQuirk.uploaded)
        val quirkRow = backend.chassisQuirks.values.single()
        assertEquals(80_000, quirkRow.mileageLow)
        assertEquals(150_000, quirkRow.mileageHigh)
        // USD Int -> cents Long, CLAUDE.md section 3.
        assertEquals(20_000L, quirkRow.costLowCents)
        assertEquals(60_000L, quirkRow.costHighCents)
        assertEquals("DETERMINISTIC", quirkRow.provenance)

        assertTrue(report.isClean)
    }

    @Test
    fun `the -1 sentinel becomes a real NULL on the wire, never travelling as -1`() = runBlocking {
        val obdMac = "AA:AA:AA:AA:AA:AA"
        createEngineVehicle(obdMac)
        createLegacyVehicle(obdMac)
        // Defaults are all -1 ("no bound"/"unknown") - see ChassisQuirk's own doc comment.
        createChassisQuirk(quirkId = "unbounded_quirk")
        val backend = FakeFleetBackend()

        FleetReconcile.run(context, backend).getOrThrow()

        val uploaded = backend.chassisQuirks.getValue("unbounded_quirk")
        assertNull(uploaded.mileageLow)
        assertNull(uploaded.mileageHigh)
        assertNull(uploaded.costLowCents)
        assertNull(uploaded.costHighCents)

        // And the round trip back into the local replica must restore -1, not leave a real null
        // sitting in a column the phone-side entity declares non-nullable with a -1 default.
        val local = CarDatabase.getDatabase(context).chassisQuirkDao().getAll().single { it.quirkId == "unbounded_quirk" }
        assertEquals(-1, local.mileageLow)
        assertEquals(-1, local.mileageHigh)
        assertEquals(-1, local.costLow)
        assertEquals(-1, local.costHigh)
    }

    @Test
    fun `the empty-string freeze-frame and codes-after conventions become NULL, not travelling as empty strings`() = runBlocking {
        val obdMac = "BB:BB:BB:BB:BB:BB"
        createEngineVehicle(obdMac)
        createLegacyVehicle(obdMac)
        createCodeEvent(vehicleId = obdMac, syncId = "code-no-freeze", freezeFrameJson = "")
        createCodeClearEvent(vehicleId = obdMac, syncId = "clear-unverified", codesAfterJson = "", outcome = "UNVERIFIED")
        val backend = FakeFleetBackend()

        FleetReconcile.run(context, backend).getOrThrow()

        assertNull(backend.codeEvents.getValue("code-no-freeze").freezeFrameJson)
        assertNull(backend.codeClearEvents.getValue("clear-unverified").codesAfterJson)

        // And the round trip back restores the phone's own "" convention rather than leaving a
        // literal null sitting in a column the entity declares non-nullable with a "" default.
        val db = CarDatabase.getDatabase(context)
        assertEquals("", db.codeEventDao().getBySyncId("code-no-freeze")!!.freezeFrameJson)
        assertEquals("", db.codeClearEventDao().getBySyncId("clear-unverified")!!.codesAfterJson)
    }

    @Test
    fun `a re-run of the four new tables is idempotent - identity is stable, not just counts`() = runBlocking {
        val obdMac = "CC:CC:CC:CC:CC:CC"
        createEngineVehicle(obdMac)
        createLegacyVehicle(obdMac)
        createCodeEvent(vehicleId = obdMac, syncId = "code-idempotent")
        createCodeClearEvent(vehicleId = obdMac, syncId = "clear-idempotent")
        createOilAnalysis(vehicleId = obdMac, syncId = "oil-idempotent")
        createChassisQuirk(quirkId = "idempotent_quirk")
        val backend = FakeFleetBackend()

        FleetReconcile.run(context, backend).getOrThrow()
        val codeEventServerIdAfterFirst = backend.codeEvents.getValue("code-idempotent").serverId
        val clearServerIdAfterFirst = backend.codeClearEvents.getValue("clear-idempotent").serverId
        val oilServerIdAfterFirst = backend.oilAnalyses.getValue("oil-idempotent").serverId

        val second = FleetReconcile.run(context, backend).getOrThrow()

        // Identity, not just counts - a re-run that quietly reminted a fresh server row for an
        // already-uploaded reading would still report matching counts (lessons.md's "assert on
        // identity, NOT only on counts").
        assertEquals(codeEventServerIdAfterFirst, backend.codeEvents.getValue("code-idempotent").serverId)
        assertEquals(clearServerIdAfterFirst, backend.codeClearEvents.getValue("clear-idempotent").serverId)
        assertEquals(oilServerIdAfterFirst, backend.oilAnalyses.getValue("oil-idempotent").serverId)
        assertEquals(1, backend.codeEvents.size)
        assertEquals(1, backend.codeClearEvents.size)
        assertEquals(1, backend.oilAnalyses.size)
        assertEquals(1, backend.chassisQuirks.size)

        // Unlike vehicles/service_history's check-then-insert migration shape, these four are
        // genuine upserts by natural key - same "no already-there branch, a repost still counts"
        // posture DriveReport.uploaded's own doc comment states, so a re-run's `uploaded` count
        // does NOT drop to 0. Identity (asserted above) is the thing that must stay stable, not
        // this count.
        assertEquals(1, second.codeEvent.uploaded)
        assertEquals(1, second.codeClearEvent.uploaded)
        assertEquals(1, second.oilAnalysis.uploaded)
        assertEquals(1, second.chassisQuirk.uploaded)
        assertTrue(second.isClean)

        val db = CarDatabase.getDatabase(context)
        assertEquals(1, db.codeEventDao().getAllForUpload().size)
        assertEquals(1, db.codeClearEventDao().getAllForUpload().size)
        assertEquals(1, db.oilAnalysisDao().getAllForUpload().size)
        assertEquals(1, db.chassisQuirkDao().count())
    }

    @Test
    fun `a failed code-event upload short-circuits before oil analyses or chassis quirks are attempted`() = runBlocking {
        val obdMac = "DD:DD:DD:DD:DD:DD"
        createEngineVehicle(obdMac)
        createLegacyVehicle(obdMac)
        createCodeEvent(vehicleId = obdMac, syncId = "code-fails")
        createOilAnalysis(vehicleId = obdMac, syncId = "oil-never-attempted")
        createChassisQuirk(quirkId = "quirk-never-attempted")
        val backend = FakeFleetBackend()
        backend.failNextCodeEventUpload = true

        val result = FleetReconcile.run(context, backend)

        assertTrue(result.isFailure)
        assertTrue(backend.codeEvents.isEmpty())
        // Neither ran, because the failure happened on code_events, which this reconcile visits
        // before oil_analyses and chassis_quirks - a partial upload must never be reported as a
        // low count, matching the vehicle-upload short-circuit test's own posture.
        assertTrue(backend.oilAnalyses.isEmpty())
        assertTrue(backend.chassisQuirks.isEmpty())
    }

    @Test
    fun `the reconcile never deletes or trashes a code_events, code_clear_events, oil_analyses or chassis_quirks source row`() = runBlocking {
        val obdMac = "EE:EE:EE:EE:EE:EE"
        createEngineVehicle(obdMac)
        createLegacyVehicle(obdMac)
        createCodeEvent(vehicleId = obdMac, syncId = "code-survives")
        createCodeClearEvent(vehicleId = obdMac, syncId = "clear-survives")
        createOilAnalysis(vehicleId = obdMac, syncId = "oil-survives")
        createChassisQuirk(quirkId = "quirk-survives")
        val backend = FakeFleetBackend()

        FleetReconcile.run(context, backend).getOrThrow()

        val db = CarDatabase.getDatabase(context)
        assertEquals(1, db.codeEventDao().getAllForUpload().size)
        assertEquals(1, db.codeClearEventDao().getAllForUpload().size)
        assertEquals(1, db.oilAnalysisDao().getAllForUpload().size)
        assertEquals(1, db.chassisQuirkDao().count())
    }

    // ---- This wave: vehicle_specs / build_entries / drive_reassignments ---------------------------

    @Test
    fun `the last three tables map field-for-field, with the correct provenance per table`() = runBlocking {
        val obdMac = "AA:11:BB:22:CC:33"
        val newObdMac = "DD:44:EE:55:FF:66"
        createEngineVehicle(obdMac)
        createEngineVehicle(newObdMac, name = "Wagoneer", make = "Jeep", model = "Wagoneer", year = 2001)
        createLegacyVehicle(obdMac)
        createLegacyVehicle(newObdMac)
        createVehicleSpec(vehicleId = obdMac, vin = "1J4FF68S6WL654321", engineCylinders = 6, decodedAt = 5_000L)
        createBuildEntry(vehicleId = obdMac, syncId = "build-1", type = "mod", title = "Lift kit", cost = 249.5)
        createDriveReassignment(vehicleId = obdMac, newVehicleId = newObdMac, syncId = "reassign-1", fromMs = 1_000L, toMs = 2_000L)
        val backend = FakeFleetBackend()

        val report = FleetReconcile.run(context, backend).getOrThrow()

        assertEquals(1, report.vehicleSpec.uploaded)
        val specRow = backend.vehicleSpecs.values.single()
        assertEquals("1J4FF68S6WL654321", specRow.vin)
        assertEquals(6, specRow.engineCylinders)
        assertEquals(5_000L, specRow.decodedAtMs)
        assertEquals("DETERMINISTIC", specRow.provenance)

        assertEquals(1, report.buildEntry.uploaded)
        val buildRow = backend.buildEntries.values.single()
        assertEquals("mod", buildRow.entryType)
        assertEquals("Lift kit", buildRow.title)
        assertEquals(24_950L, buildRow.costCents)
        assertEquals("USER", buildRow.provenance)

        assertEquals(1, report.driveReassignment.uploaded)
        val reassignRow = backend.driveReassignments.values.single()
        val fromVehicleServerId = backend.vehicles.values.single { it.name == "Jeep" }.serverId
        val toVehicleServerId = backend.vehicles.values.single { it.name == "Wagoneer" }.serverId
        assertEquals(fromVehicleServerId, reassignRow.vehicleServerId)
        assertEquals(toVehicleServerId, reassignRow.newVehicleServerId)
        assertEquals(1_000L, reassignRow.fromAtMs)
        assertEquals(2_000L, reassignRow.toAtMs)
        assertEquals("USER", reassignRow.provenance)

        assertTrue(report.isClean)
    }

    @Test
    fun `the 0L decodedAt sentinel becomes a real NULL on the wire, and comes back as 0L`() = runBlocking {
        val obdMac = "00:11:22:33:44:55"
        createEngineVehicle(obdMac)
        createLegacyVehicle(obdMac)
        createVehicleSpec(vehicleId = obdMac, decodedAt = 0L)
        val backend = FakeFleetBackend()

        FleetReconcile.run(context, backend).getOrThrow()

        assertNull(backend.vehicleSpecs.getValue(backend.vehicles.values.single().serverId).decodedAtMs)

        // And the round trip back into the local replica must restore 0L, not leave a real null
        // sitting in a column VehicleSpec.decodedAt declares non-nullable with a 0L default.
        val local = CarDatabase.getDatabase(context).vehicleSpecDao().get(obdMac)
        assertEquals(0L, local?.decodedAt)
    }

    @Test
    fun `dollars round to the nearest cent rather than truncating`() = runBlocking {
        val obdMac = "66:77:88:99:AA:BB"
        createEngineVehicle(obdMac)
        createLegacyVehicle(obdMac)
        // 19.995 dollars: float multiplication alone leaves 100 * 19.995 = 1999.9999999999998,
        // which a plain truncating (it * 100).toLong() would floor to 1999 cents - a full cent
        // short of the real value. Math.round must land on 2000.
        createBuildEntry(vehicleId = obdMac, syncId = "build-rounding", cost = 19.995)
        val backend = FakeFleetBackend()

        FleetReconcile.run(context, backend).getOrThrow()

        assertEquals(2_000L, backend.buildEntries.getValue("build-rounding").costCents)
    }

    @Test
    fun `cents come back as dollars for a build entry this device only ever saw server-side`() = runBlocking {
        // Unlike the round-trip tests above (which never overwrite an already-present local row -
        // insert-if-absent, per this object's own class doc), this fixture puts the row on the FAKE
        // BACKEND directly, never locally, so the reconcile's download branch is what has to create
        // it - exercising the cents -> dollars conversion for real rather than trivially agreeing
        // with a local value nothing touched.
        val obdMac = "13:57:9B:DF:24:68"
        createEngineVehicle(obdMac)
        createLegacyVehicle(obdMac)
        val backend = FakeFleetBackend()
        // Prime the vehicle mapping first so the fixture below can name a real server vehicle id.
        FleetReconcile.run(context, backend).getOrThrow()
        val vehicleServerId = backend.vehicles.values.single().serverId
        backend.upsertBuildEntry(
            BuildEntryUpload(
                syncId = "build-server-only",
                vehicleServerId = vehicleServerId,
                entryType = "part",
                title = "Oil filter",
                vendor = "",
                partNumber = "",
                costCents = 2_000L,
                loggedAtMs = 1_000L,
                mileage = null,
                notes = "",
                provenance = "USER",
            ),
        )

        FleetReconcile.run(context, backend).getOrThrow()

        val local = CarDatabase.getDatabase(context).buildEntryDao().getBySyncId("build-server-only")
        assertEquals(20.0, local?.cost!!, 0.0001)
    }

    @Test
    fun `a null cost survives as null rather than becoming 0 cents`() = runBlocking {
        val obdMac = "CC:DD:EE:FF:00:11"
        createEngineVehicle(obdMac)
        createLegacyVehicle(obdMac)
        createBuildEntry(vehicleId = obdMac, syncId = "build-no-cost", cost = null)
        val backend = FakeFleetBackend()

        FleetReconcile.run(context, backend).getOrThrow()

        assertNull(backend.buildEntries.getValue("build-no-cost").costCents)
        val local = CarDatabase.getDatabase(context).buildEntryDao().getBySyncId("build-no-cost")
        assertNull(local?.cost)
    }

    @Test
    fun `a drive reassignment naming an unresolved NEW vehicle is skipped entirely, never uploaded with one leg guessed`() = runBlocking {
        val obdMac = "12:34:56:78:9A:BC"
        createEngineVehicle(obdMac)
        createLegacyVehicle(obdMac)
        // newVehicleId names a car with no engine record at all - the "not yet migrated" case.
        createDriveReassignment(vehicleId = obdMac, newVehicleId = "unregistered-obd-mac", syncId = "reassign-orphan-new")
        val backend = FakeFleetBackend()

        val report = FleetReconcile.run(context, backend).getOrThrow()

        assertEquals(1, report.driveReassignment.sourceCount)
        assertEquals(0, report.driveReassignment.uploaded)
        assertEquals(1, report.driveReassignment.skippedUnresolvedVehicle.size)
        assertTrue(backend.driveReassignments.isEmpty())
    }

    @Test
    fun `a re-run of the last three tables is idempotent - identity is stable, not just counts`() = runBlocking {
        val obdMac = "FE:DC:BA:98:76:54"
        val newObdMac = "12:34:AB:CD:EF:01"
        createEngineVehicle(obdMac)
        createEngineVehicle(newObdMac, name = "Wagoneer", make = "Jeep", model = "Wagoneer", year = 2001)
        createLegacyVehicle(obdMac)
        createLegacyVehicle(newObdMac)
        createVehicleSpec(vehicleId = obdMac)
        createBuildEntry(vehicleId = obdMac, syncId = "build-idempotent")
        createDriveReassignment(vehicleId = obdMac, newVehicleId = newObdMac, syncId = "reassign-idempotent")
        val backend = FakeFleetBackend()

        FleetReconcile.run(context, backend).getOrThrow()
        val vehicleServerId = backend.vehicles.values.single { it.name == "Jeep" }.serverId
        val specServerIdAfterFirst = backend.vehicleSpecs.getValue(vehicleServerId).vehicleServerId
        val buildServerIdAfterFirst = backend.buildEntries.getValue("build-idempotent").serverId
        val reassignServerIdAfterFirst = backend.driveReassignments.getValue("reassign-idempotent").serverId

        val second = FleetReconcile.run(context, backend).getOrThrow()

        assertEquals(specServerIdAfterFirst, backend.vehicleSpecs.getValue(vehicleServerId).vehicleServerId)
        assertEquals(buildServerIdAfterFirst, backend.buildEntries.getValue("build-idempotent").serverId)
        assertEquals(reassignServerIdAfterFirst, backend.driveReassignments.getValue("reassign-idempotent").serverId)
        assertEquals(1, backend.vehicleSpecs.size)
        assertEquals(1, backend.buildEntries.size)
        assertEquals(1, backend.driveReassignments.size)

        assertEquals(1, second.vehicleSpec.uploaded) // REPLACE upsert, same "count never drops to 0" posture as chassis quirks
        assertEquals(1, second.buildEntry.uploaded)
        assertEquals(1, second.driveReassignment.uploaded)
        assertTrue(second.isClean)

        val db = CarDatabase.getDatabase(context)
        assertEquals(1, db.vehicleSpecDao().getAll().size)
        assertEquals(1, db.buildEntryDao().getAllForUpload().size)
        assertEquals(1, db.driveReassignmentDao().getAll().size)
    }

    @Test
    fun `a failed build-entry upload short-circuits before drive reassignments are attempted`() = runBlocking {
        val obdMac = "AB:CD:EF:01:23:45"
        val newObdMac = "45:23:01:EF:CD:AB"
        createEngineVehicle(obdMac)
        createEngineVehicle(newObdMac, name = "Wagoneer", make = "Jeep", model = "Wagoneer", year = 2001)
        createLegacyVehicle(obdMac)
        createLegacyVehicle(newObdMac)
        createBuildEntry(vehicleId = obdMac, syncId = "build-fails")
        createDriveReassignment(vehicleId = obdMac, newVehicleId = newObdMac, syncId = "reassign-never-attempted")
        val backend = FakeFleetBackend()
        backend.failNextBuildEntryUpload = true

        val result = FleetReconcile.run(context, backend)

        assertTrue(result.isFailure)
        assertTrue(backend.buildEntries.isEmpty())
        // Never ran, because the failure happened on build_entries, which this reconcile visits
        // before drive_reassignments - a partial upload must never be reported as a low count.
        assertTrue(backend.driveReassignments.isEmpty())
    }

    @Test
    fun `the reconcile never deletes or trashes a vehicle_specs, build_entries or drive_reassignments source row`() = runBlocking {
        val obdMac = "98:76:54:32:10:AA"
        val newObdMac = "AA:10:32:54:76:98"
        createEngineVehicle(obdMac)
        createEngineVehicle(newObdMac, name = "Wagoneer", make = "Jeep", model = "Wagoneer", year = 2001)
        createLegacyVehicle(obdMac)
        createLegacyVehicle(newObdMac)
        createVehicleSpec(vehicleId = obdMac)
        createBuildEntry(vehicleId = obdMac, syncId = "build-survives")
        createDriveReassignment(vehicleId = obdMac, newVehicleId = newObdMac, syncId = "reassign-survives")
        val backend = FakeFleetBackend()

        FleetReconcile.run(context, backend).getOrThrow()

        val db = CarDatabase.getDatabase(context)
        assertEquals(1, db.vehicleSpecDao().getAll().size)
        assertEquals(1, db.buildEntryDao().getAllForUpload().size)
        assertEquals(1, db.driveReassignmentDao().getAll().size)
    }

    // ================================================================================================
    // Engine retirement step 3 (ticket 16): fleet has no configured write path, so FleetReconcile is
    // the ONLY route service history ever reaches the server - these pin the repoint that keeps that
    // true after ServiceHistory moved off the engine and onto `service_records`.
    // ================================================================================================

    @Test
    fun `a service record written through TODAY's real write path (never touching the engine) is uploaded`() = runBlocking {
        val obdMac = "10:20:30:40:50:60"
        FleetEngineStore.createVehicle(
            context,
            Vehicle(obdMac = obdMac, name = "Cherokee", make = "Jeep", model = "Cherokee", year = 1998, personaPrompt = "", confirmed = true),
        )
        // FleetEngineStore.insertObserved writes ONLY service_records post-repoint (FleetEngineStore's
        // own class doc) - this is the regression this whole fix exists to close: before it, a row
        // written this way would never appear in an upload at all.
        val inserted = FleetEngineStore.insertObserved(context, obdMac, "Oil Change", mileage = 231_500, date = 1_723_500_000_000L, costCents = 4599)
        assertTrue(inserted is FleetEngineStore.InsertObservedResult.Success)
        val backend = FakeFleetBackend()

        val report = FleetReconcile.run(context, backend).getOrThrow()

        assertEquals("the post-repoint write must be visible to the reconcile, not just the engine snapshot", 1, report.serviceHistory.uploaded)
        val uploaded = backend.serviceHistory.values.single()
        assertEquals("Oil Change", uploaded.serviceName)
        assertEquals(231_500, uploaded.mileage)
        assertEquals(4599L, uploaded.costCents)
        assertEquals(FleetAspectSeeder.KIND_OBSERVED, uploaded.kind)
        assertTrue(report.serviceHistory.isClean)
    }

    @Test
    fun `kind survives to the server for both OBSERVED and ASSERTED rows`() = runBlocking {
        val obdMac = "20:30:40:50:60:70"
        FleetEngineStore.createVehicle(
            context,
            Vehicle(obdMac = obdMac, name = "Cherokee", make = "Jeep", model = "Cherokee", year = 1998, personaPrompt = "", confirmed = true),
        )
        FleetEngineStore.insertObserved(context, obdMac, "Oil Change", mileage = 100_000, date = 1_000L, costCents = null)
        // A driver-stated anchor with no backing event - writeAssertedAnchorLegacy's ASSERTED row,
        // also written only to service_records post-repoint.
        FleetEngineStore.upsertNewItem(context, MaintenanceItem(vehicleId = obdMac, serviceName = "Brake Pads"))
        FleetEngineStore.setAnchor(context, obdMac, "Brake Pads", mileage = 90_000, date = null, now = 2_000L)
        val backend = FakeFleetBackend()

        FleetReconcile.run(context, backend).getOrThrow()

        val kinds = backend.serviceHistory.values.map { it.serviceName to it.kind }.toMap()
        assertEquals(FleetAspectSeeder.KIND_OBSERVED, kinds.getValue("Oil Change"))
        assertEquals(FleetAspectSeeder.KIND_ASSERTED, kinds.getValue("Brake Pads"))
    }

    @Test
    fun `the reconcile never deletes or trashes a service_records row it just uploaded`() = runBlocking {
        val obdMac = "30:40:50:60:70:80"
        FleetEngineStore.createVehicle(
            context,
            Vehicle(obdMac = obdMac, name = "Cherokee", make = "Jeep", model = "Cherokee", year = 1998, personaPrompt = "", confirmed = true),
        )
        FleetEngineStore.insertObserved(context, obdMac, "Oil Change", mileage = 100_000, date = 1_000L, costCents = null)
        val backend = FakeFleetBackend()

        FleetReconcile.run(context, backend).getOrThrow()

        val db = CarDatabase.getDatabase(context)
        val row = db.serviceRecordDao().getRecordsForVehicleOnce(obdMac).singleOrNull()
        assertTrue("the source row must survive a reconcile run, present and not tombstoned", row != null)
        assertFalse(row!!.deleted)
    }

}