package com.kevin.legion.vehicle

import com.kevin.legion.backend.BuildEntryUpload
import com.kevin.legion.backend.ChassisQuirkUpload
import com.kevin.legion.backend.CodeClearEventUpload
import com.kevin.legion.backend.CodeEventUpload
import com.kevin.legion.backend.DriveReassignmentUpload
import com.kevin.legion.backend.DriveUpload
import com.kevin.legion.backend.FleetBackend
import com.kevin.legion.backend.FleetBackendException
import com.kevin.legion.backend.MigratedServiceHistory
import com.kevin.legion.backend.ObdSampleUpload
import com.kevin.legion.backend.MigratedVehicle
import com.kevin.legion.backend.OilAnalysisUpload
import com.kevin.legion.backend.RemoteBuildEntry
import com.kevin.legion.backend.RemoteChassisQuirk
import com.kevin.legion.backend.RemoteCodeClearEvent
import com.kevin.legion.backend.RemoteCodeEvent
import com.kevin.legion.backend.RemoteDrive
import com.kevin.legion.backend.RemoteDriveReassignment
import com.kevin.legion.backend.RemoteOilAnalysis
import com.kevin.legion.backend.RemoteServiceHistory
import com.kevin.legion.backend.RemoteVehicle
import com.kevin.legion.backend.RemoteVehicleSpec
import com.kevin.legion.backend.RemoteMaintenanceSchedule
import com.kevin.legion.backend.ServiceHistoryUpload
import com.kevin.legion.backend.VehicleSpecUpload
import com.kevin.legion.backend.VehicleUpload
import com.kevin.legion.backend.MaintenanceScheduleUpload
import com.kevin.legion.data.local.CarDatabase
import com.kevin.legion.data.local.Vehicle
import com.kevin.legion.data.local.VehicleSidecar
import com.kevin.legion.testutil.RoomTestReset
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * The `service_history` step of the fleet cutover (backend-erp ticket 26 step 2,
 * `.scratch/backend-erp/issues/26-the-fleet-cutover-for-real.md`). Exercised entirely through
 * [FleetEngineStore.backendOverride] and an in-memory [FakeServiceHistoryBackend], never a real
 * SupabaseClient - same posture as [FleetEngineStoreVehicleCutoverTest].
 *
 * Every fleet table besides `vehicles`/`service_history` is stubbed to fail loudly if ever called.
 */
@RunWith(RobolectricTestRunner::class)
class FleetEngineStoreServiceHistoryCutoverTest {
    private val context = RuntimeEnvironment.getApplication()
    private val db get() = CarDatabase.getDatabase(context)

    private class FakeServiceHistoryBackend : FleetBackend {
        val vehicles = mutableMapOf<String, RemoteVehicle>()
        val serviceHistory = mutableMapOf<String, RemoteServiceHistory>()
        private var counter = 0
        var clock = 1_000L
        var nextUpsertFails = false

        override suspend fun fetchActiveVehicles(): Result<List<RemoteVehicle>> = Result.success(vehicles.values.toList())
        override suspend fun uploadMigratedVehicle(vehicle: MigratedVehicle): Result<Boolean> = error("out of scope")

        override suspend fun upsertVehicle(vehicle: VehicleUpload): Result<RemoteVehicle> {
            val row = if (vehicle.serverId == null) {
                RemoteVehicle(
                    serverId = "vehicle-${++counter}",
                    name = vehicle.name, make = vehicle.make, model = vehicle.model, year = vehicle.year,
                    trim = vehicle.trim, engine = vehicle.engine, confirmed = vehicle.confirmed,
                    odometerBaseline = vehicle.odometerBaseline, odometerBaselineAtMs = vehicle.odometerBaselineAtMs,
                    updatedAtMs = ++clock, deleted = false, originGuid = null, archived = vehicle.archived,
                )
            } else {
                val existing = vehicles[vehicle.serverId] ?: return Result.failure(FleetBackendException("no such row"))
                existing.copy(
                    name = vehicle.name, make = vehicle.make, model = vehicle.model, year = vehicle.year,
                    trim = vehicle.trim, engine = vehicle.engine, confirmed = vehicle.confirmed,
                    odometerBaseline = vehicle.odometerBaseline, odometerBaselineAtMs = vehicle.odometerBaselineAtMs,
                    updatedAtMs = ++clock, archived = vehicle.archived,
                )
            }
            vehicles[row.serverId] = row
            return Result.success(row)
        }

        override suspend fun fetchActiveServiceHistory(): Result<List<RemoteServiceHistory>> =
            Result.success(serviceHistory.values.toList())

        override suspend fun uploadMigratedServiceHistory(history: MigratedServiceHistory): Result<Boolean> = error("out of scope")

        override suspend fun upsertServiceHistory(history: ServiceHistoryUpload): Result<RemoteServiceHistory> {
            if (nextUpsertFails) {
                nextUpsertFails = false
                return Result.failure(FleetBackendException("simulated network failure"))
            }
            val row = if (history.serverId == null) {
                RemoteServiceHistory(
                    serverId = "history-${++counter}",
                    vehicleServerId = history.vehicleServerId,
                    serviceName = history.serviceName,
                    mileage = history.mileage,
                    serviceDateEpochMs = history.serviceDateEpochMs,
                    costCents = history.costCents,
                    kind = history.kind,
                    updatedAtMs = ++clock,
                    deleted = false,
                    originGuid = null,
                )
            } else {
                val existing = serviceHistory[history.serverId] ?: return Result.failure(FleetBackendException("no such row"))
                existing.copy(
                    serviceName = history.serviceName, mileage = history.mileage,
                    serviceDateEpochMs = history.serviceDateEpochMs, costCents = history.costCents,
                    kind = history.kind, updatedAtMs = ++clock,
                )
            }
            serviceHistory[row.serverId] = row
            return Result.success(row)
        }

        override suspend fun fetchActiveDrives(): Result<List<RemoteDrive>> = error("out of scope")
        override suspend fun upsertDrive(drive: DriveUpload): Result<RemoteDrive> = error("out of scope")
        override suspend fun fetchActiveCodeEvents(): Result<List<RemoteCodeEvent>> = error("out of scope")
        override suspend fun upsertCodeEvent(event: CodeEventUpload): Result<RemoteCodeEvent> = error("out of scope")
        override suspend fun fetchActiveCodeClearEvents(): Result<List<RemoteCodeClearEvent>> = error("out of scope")
        override suspend fun upsertCodeClearEvent(event: CodeClearEventUpload): Result<RemoteCodeClearEvent> = error("out of scope")
        override suspend fun fetchActiveOilAnalyses(): Result<List<RemoteOilAnalysis>> = error("out of scope")
        override suspend fun upsertOilAnalysis(analysis: OilAnalysisUpload): Result<RemoteOilAnalysis> = error("out of scope")
        override suspend fun fetchChassisQuirks(): Result<List<RemoteChassisQuirk>> = error("out of scope")
        override suspend fun upsertChassisQuirk(quirk: ChassisQuirkUpload): Result<RemoteChassisQuirk> = error("out of scope")
        override suspend fun fetchVehicleSpecs(): Result<List<RemoteVehicleSpec>> = error("out of scope")
        override suspend fun upsertVehicleSpec(spec: VehicleSpecUpload): Result<RemoteVehicleSpec> = error("out of scope")
        override suspend fun fetchActiveBuildEntries(): Result<List<RemoteBuildEntry>> = error("out of scope")
        override suspend fun upsertBuildEntry(entry: BuildEntryUpload): Result<RemoteBuildEntry> = error("out of scope")
        override suspend fun fetchActiveDriveReassignments(): Result<List<RemoteDriveReassignment>> = error("out of scope")
        override suspend fun upsertDriveReassignment(reassignment: DriveReassignmentUpload): Result<RemoteDriveReassignment> = error("out of scope")
        override suspend fun fetchActiveMaintenanceSchedules(): Result<List<RemoteMaintenanceSchedule>> = error("out of scope")
        override suspend fun upsertMaintenanceSchedule(schedule: MaintenanceScheduleUpload): Result<RemoteMaintenanceSchedule> = error("out of scope")
        override suspend fun uploadObdSampleBatch(batch: List<ObdSampleUpload>): Result<Unit> = error("out of scope")
    }

    @Before
    fun clearState() {
        RoomTestReset.resetCarDatabaseSingleton()
    }

    @After
    fun clearOverride() {
        RoomTestReset.drainArchDiskIoPool()
        FleetEngineStore.backendOverride = null
    }

    private val mac = "AA:BB:CC"

    private fun freshVehicle() = Vehicle(
        obdMac = mac, name = "Test Car", make = "Jeep", model = "Cherokee", year = 1998,
        personaPrompt = "buckle up", voiceName = "Kore", personaTraits = "{}", confirmed = true,
        archived = false, onboarded = false, tripMilesSinceBaseline = 0.0, lastOdometerPromptAt = 0L,
    )

    /** Synced car, no dependency on [FleetEngineStore.createVehicle]'s own sync push - directly
     * plants the [VehicleSidecar] row [syncServiceHistoryToServer] resolves `vehicleServerId` from,
     * matching what a real configured `createVehicle` would leave behind. */
    private suspend fun syncedVehicle(backend: FakeServiceHistoryBackend): String {
        db.vehicleDao().upsert(freshVehicle())
        val serverId = "vehicle-preexisting"
        db.vehicleSidecarDao().upsert(VehicleSidecar(serverId = serverId, obdMac = mac))
        return serverId
    }

    @Test
    fun `an unconfigured install never touches the service history replica`() = runBlocking {
        db.vehicleDao().upsert(freshVehicle())
        val result = FleetEngineStore.insertObserved(context, mac, "Oil Change", 50_000, 1_000L, 5_000L)
        assertTrue(result is FleetEngineStore.InsertObservedResult.Success)
        assertEquals(0, db.serviceHistoryReplicaDao().getAll().size)
    }

    @Test
    fun `a first configured insert pushes to the server and records the returned uuid`() = runBlocking {
        val backend = FakeServiceHistoryBackend()
        FleetEngineStore.backendOverride = backend
        syncedVehicle(backend)

        val result = FleetEngineStore.insertObserved(context, mac, "Oil Change", 50_000, 1_000L, 5_000L)
        assertTrue(result is FleetEngineStore.InsertObservedResult.Success)
        val id = (result as FleetEngineStore.InsertObservedResult.Success).recordId

        assertEquals(1, backend.serviceHistory.size)
        val local = db.serviceRecordDao().getById(id)!!
        assertNotNull("a synced record must have its serverId recorded", local.serverId)
        assertEquals(backend.serviceHistory.values.single().serverId, local.serverId)
        val replica = db.serviceHistoryReplicaDao().getByServerId(local.serverId!!)
        assertNotNull(replica)
        assertEquals("Oil Change", replica!!.serviceName)
    }

    @Test
    fun `an upsert with a known serverId updates rather than duplicating`() = runBlocking {
        val backend = FakeServiceHistoryBackend()
        FleetEngineStore.backendOverride = backend
        syncedVehicle(backend)

        val result = FleetEngineStore.insertObserved(context, mac, "Oil Change", 50_000, 1_000L, 5_000L)
        val id = (result as FleetEngineStore.InsertObservedResult.Success).recordId
        assertEquals(1, backend.serviceHistory.size)

        FleetEngineStore.editMileageAndCost(context, id, 51_000, 6_000L)

        assertEquals("an update must never mint a second server row", 1, backend.serviceHistory.size)
        assertEquals(51_000, backend.serviceHistory.values.single().mileage)
        assertEquals(6_000L, backend.serviceHistory.values.single().costCents)
    }

    @Test
    fun `a re-run does not remint the local row id`() = runBlocking {
        val backend = FakeServiceHistoryBackend()
        FleetEngineStore.backendOverride = backend
        syncedVehicle(backend)

        val result = FleetEngineStore.insertObserved(context, mac, "Oil Change", 50_000, 1_000L, 5_000L)
        val id = (result as FleetEngineStore.InsertObservedResult.Success).recordId

        FleetEngineStore.editMileageAndCost(context, id, 51_000, 6_000L)
        FleetEngineStore.editMileageAndCost(context, id, 52_000, 7_000L)

        // The LEGACY row's own id is the identity every id-keyed caller relies on - it must never
        // move across re-syncs (b17bc88's shape, one layer further in than the replica).
        assertEquals(id, db.serviceRecordDao().getById(id)!!.id)
        assertEquals(1, db.serviceHistoryReplicaDao().getAll().size)
    }

    @Test
    fun `a configured read serves the replica for a synced car`() = runBlocking {
        // "Configured" here means the WRITE path is live - see this file's own doc comment for why
        // the READ path is deliberately unchanged (still `service_records`, always). This test pins
        // that: the record inserted above is visible through the ordinary legacy-table read, which
        // is exactly what "configured: read/write the replica and the server" resolves to for this
        // table today.
        val backend = FakeServiceHistoryBackend()
        FleetEngineStore.backendOverride = backend
        syncedVehicle(backend)

        FleetEngineStore.insertObserved(context, mac, "Oil Change", 50_000, 1_000L, 5_000L)

        val history = FleetEngineStore.serviceRecordsForVehicle(context, mac)
        assertEquals(1, history.size)
        assertEquals("Oil Change", history.single().serviceName)
    }

    @Test
    fun `an unconfigured read is untouched and still reads the legacy table`() = runBlocking {
        db.vehicleDao().upsert(freshVehicle())
        FleetEngineStore.insertObserved(context, mac, "Oil Change", 50_000, 1_000L, 5_000L)

        val history = FleetEngineStore.serviceRecordsForVehicle(context, mac)
        assertEquals(1, history.size)
        assertEquals(0, db.serviceHistoryReplicaDao().getAll().size)
    }

    @Test
    fun `a FAILED remote write leaves the legacy write intact and is logged, not thrown`() = runBlocking {
        val backend = FakeServiceHistoryBackend()
        backend.nextUpsertFails = true
        FleetEngineStore.backendOverride = backend
        syncedVehicle(backend)

        val result = FleetEngineStore.insertObserved(context, mac, "Oil Change", 50_000, 1_000L, 5_000L)
        assertTrue("the local write must succeed regardless of the server failure", result is FleetEngineStore.InsertObservedResult.Success)
        val id = (result as FleetEngineStore.InsertObservedResult.Success).recordId
        assertNull("a failed push must not fabricate a serverId", db.serviceRecordDao().getById(id)!!.serverId)
        assertEquals(0, db.serviceHistoryReplicaDao().getAll().size)
    }

    @Test
    fun `a car with no server mapping yet is a no-op push, not a crash`() = runBlocking {
        val backend = FakeServiceHistoryBackend()
        FleetEngineStore.backendOverride = backend
        // Configured, but this car has never itself synced - no VehicleSidecar row.
        db.vehicleDao().upsert(freshVehicle())

        val result = FleetEngineStore.insertObserved(context, mac, "Oil Change", 50_000, 1_000L, 5_000L)
        assertTrue(result is FleetEngineStore.InsertObservedResult.Success)
        assertEquals(0, backend.serviceHistory.size)
    }

    @Test
    fun `an ASSERTED anchor write preserves its serverId across edits`() = runBlocking {
        val backend = FakeServiceHistoryBackend()
        FleetEngineStore.backendOverride = backend
        syncedVehicle(backend)

        db.maintenanceItemDao().upsert(
            com.kevin.legion.data.local.MaintenanceItem(vehicleId = mac, serviceName = "Timing Belt"),
        )
        FleetEngineStore.setAnchor(context, mac, "Timing Belt", 40_000, 900L, System.currentTimeMillis())
        assertEquals(1, backend.serviceHistory.size)
        val firstServerId = backend.serviceHistory.values.single().serverId

        // A second setAnchor call REPLACEs the same legacy row (find-or-create-or-clear) - the
        // serverId must survive that REPLACE, not get wiped back to null and mint a second row.
        FleetEngineStore.setAnchor(context, mac, "Timing Belt", 41_000, 950L, System.currentTimeMillis())

        assertEquals("a REPLACE of the anchor row must not mint a second server row", 1, backend.serviceHistory.size)
        assertEquals(firstServerId, backend.serviceHistory.values.single().serverId)
        assertEquals(41_000, backend.serviceHistory.values.single().mileage)
    }
}
