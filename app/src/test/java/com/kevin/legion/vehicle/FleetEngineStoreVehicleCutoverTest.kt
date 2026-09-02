package com.kevin.legion.vehicle

import com.kevin.legion.backend.FleetBackend
import com.kevin.legion.backend.FleetBackendException
import com.kevin.legion.backend.MigratedVehicle
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
import com.kevin.legion.backend.BuildEntryUpload
import com.kevin.legion.backend.ChassisQuirkUpload
import com.kevin.legion.backend.CodeClearEventUpload
import com.kevin.legion.backend.CodeEventUpload
import com.kevin.legion.backend.DriveReassignmentUpload
import com.kevin.legion.backend.DriveUpload
import com.kevin.legion.backend.MigratedServiceHistory
import com.kevin.legion.backend.ObdSampleUpload
import com.kevin.legion.backend.OilAnalysisUpload
import com.kevin.legion.backend.VehicleSpecUpload
import com.kevin.legion.backend.VehicleUpload
import com.kevin.legion.backend.MaintenanceScheduleUpload
import com.kevin.legion.data.local.CarDatabase
import com.kevin.legion.data.local.Vehicle
import com.kevin.legion.testutil.RoomTestReset
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * The vehicles-only step of the fleet cutover (backend-erp ticket 26,
 * `.scratch/backend-erp/issues/26-the-fleet-cutover-for-real.md`). Exercised entirely through
 * [FleetEngineStore.backendOverride] and an in-memory [FakeVehicleBackend] - never a real
 * SupabaseClient, same posture as [com.kevin.legion.location.PlaceControllerBackendTest].
 *
 * Every other fleet table ([FleetBackend]'s remaining methods) is stubbed to fail loudly if ever
 * called - this ticket is vehicles ONLY, and a stray call into one of those would mean a later
 * step's write path leaked into this one.
 */
@RunWith(RobolectricTestRunner::class)
class FleetEngineStoreVehicleCutoverTest {
    private val context = RuntimeEnvironment.getApplication()
    private val db get() = CarDatabase.getDatabase(context)

    /** Fails any call this ticket must not make - every fleet table besides `vehicles`. */
    private class FakeVehicleBackend : FleetBackend {
        val vehicles = mutableMapOf<String, RemoteVehicle>()
        private var counter = 0
        var clock = 1_000L
        var nextUpsertFails = false

        override suspend fun fetchActiveVehicles(): Result<List<RemoteVehicle>> = Result.success(vehicles.values.toList())
        override suspend fun uploadMigratedVehicle(vehicle: MigratedVehicle): Result<Boolean> =
            error("out of scope for this ticket")

        override suspend fun upsertVehicle(vehicle: VehicleUpload): Result<RemoteVehicle> {
            if (nextUpsertFails) {
                nextUpsertFails = false
                return Result.failure(FleetBackendException("simulated network failure"))
            }
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

        override suspend fun fetchActiveServiceHistory(): Result<List<RemoteServiceHistory>> = error("out of scope")
        override suspend fun uploadMigratedServiceHistory(history: MigratedServiceHistory): Result<Boolean> = error("out of scope")
        override suspend fun upsertServiceHistory(history: com.kevin.legion.backend.ServiceHistoryUpload): Result<RemoteServiceHistory> = error("out of scope")
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

    private fun freshVehicle(mac: String = "AA:BB:CC") = Vehicle(
        obdMac = mac, name = "Test Car", make = "Jeep", model = "Cherokee", year = 1998,
        personaPrompt = "buckle up", voiceName = "Kore", personaTraits = "{}", confirmed = true,
        archived = false, onboarded = false, tripMilesSinceBaseline = 0.0, lastOdometerPromptAt = 0L,
    )

    @Test
    fun `an unconfigured install never touches the sidecar or replica`() = runBlocking {
        // No backendOverride set - FleetEngineStore.backend(context) resolves null exactly as it
        // does on a real, un-configured clone-and-run install (SupabaseClientProvider.get returns
        // null when no project is saved).
        FleetEngineStore.createVehicle(context, freshVehicle())

        assertEquals(0, db.vehicleReplicaDao().getAll().size)
        assertEquals(null, db.vehicleSidecarDao().getByMac("AA:BB:CC"))
        val legacy = FleetEngineStore.getByMac(context, "AA:BB:CC")
        assertNotNull(legacy)
        assertEquals("Test Car", legacy!!.name)
    }

    @Test
    fun `a first configured create inserts and records the returned uuid`() = runBlocking {
        val backend = FakeVehicleBackend()
        FleetEngineStore.backendOverride = backend

        FleetEngineStore.createVehicle(context, freshVehicle())

        assertEquals(1, backend.vehicles.size)
        val sidecar = db.vehicleSidecarDao().getByMac("AA:BB:CC")
        assertNotNull("a synced car must have a sidecar row recording its serverId", sidecar)
        assertEquals(backend.vehicles.values.single().serverId, sidecar!!.serverId)
        val replica = db.vehicleReplicaDao().getByServerId(sidecar.serverId)
        assertNotNull(replica)
        assertEquals("Test Car", replica!!.name)
    }

    @Test
    fun `an upsert with a known serverId updates rather than duplicating`() = runBlocking {
        val backend = FakeVehicleBackend()
        FleetEngineStore.backendOverride = backend
        FleetEngineStore.createVehicle(context, freshVehicle())
        assertEquals(1, backend.vehicles.size)

        FleetEngineStore.setEngine(context, "AA:BB:CC", "4.0L I6", System.currentTimeMillis())

        assertEquals("an update must never mint a second server row", 1, backend.vehicles.size)
        assertEquals("4.0L I6", backend.vehicles.values.single().engine)
    }

    @Test
    fun `a re-run does not remint the local replica row id`() = runBlocking {
        val backend = FakeVehicleBackend()
        FleetEngineStore.backendOverride = backend
        FleetEngineStore.createVehicle(context, freshVehicle())
        val sidecar = db.vehicleSidecarDao().getByMac("AA:BB:CC")!!
        val firstReplicaId = db.vehicleReplicaDao().getByServerId(sidecar.serverId)!!.id

        FleetEngineStore.setIdentity(context, "AA:BB:CC", 1999, "Jeep", "Cherokee", "Sport", "Test Car", System.currentTimeMillis())
        FleetEngineStore.setIdentity(context, "AA:BB:CC", 1999, "Jeep", "Cherokee", "Sport", "Test Car", System.currentTimeMillis())

        val secondReplicaId = db.vehicleReplicaDao().getByServerId(sidecar.serverId)!!.id
        assertEquals(
            "the replica's LOCAL surrogate id must never move across re-syncs - the exact b17bc88 shape",
            firstReplicaId,
            secondReplicaId,
        )
        assertEquals(1, db.vehicleReplicaDao().getAll().size)
    }

    @Test
    fun `a configured read composes replica and sidecar - archived from the replica, persona blank`() = runBlocking {
        // CORRECTED 2026-08-29, ticket 27: archived moved OFF the sidecar onto VehicleReplica
        // (it is USER state, not device state), and personaPrompt/voiceName/personaTraits left the
        // sidecar entirely - nothing reads them, so a configured read supplies the same blank
        // default an unconfigured row would, rather than carrying them through a new store.
        val backend = FakeVehicleBackend()
        FleetEngineStore.backendOverride = backend
        FleetEngineStore.createVehicle(context, freshVehicle())

        FleetEngineStore.setArchived(context, "AA:BB:CC", true, System.currentTimeMillis())
        FleetEngineStore.markOdometerPrompted(context, "AA:BB:CC", 5_000L, System.currentTimeMillis())
        FleetEngineStore.addTripMiles(context, "AA:BB:CC", 12.5, System.currentTimeMillis())
        FleetEngineStore.setIdentity(context, "AA:BB:CC", 2001, "Jeep", "Cherokee", "Sport", "Renamed", System.currentTimeMillis())

        val composed = FleetEngineStore.getByMac(context, "AA:BB:CC")
        assertNotNull(composed)
        // Server-owned columns come from the replica (post the setIdentity edit).
        assertEquals("Renamed", composed!!.name)
        assertEquals(2001, composed.year)
        // archived is server-owned now too - it must survive a configured read via the replica.
        assertTrue("archived must survive a configured read", composed.archived)
        // Phone-only columns still come from the sidecar.
        assertEquals(5_000L, composed.lastOdometerPromptAt)
        assertEquals(12.5, composed.tripMilesSinceBaseline, 0.0001)
        // Vestigial - never carried through a configured read.
        assertEquals("", composed.personaPrompt)
        assertEquals("", composed.voiceName)
        assertEquals("", composed.personaTraits)
    }

    @Test
    fun `a FAILED remote write leaves the legacy write intact and is logged, not thrown`() = runBlocking {
        val backend = FakeVehicleBackend()
        backend.nextUpsertFails = true
        FleetEngineStore.backendOverride = backend

        // Must not throw - a network failure on the third (best-effort) write is swallowed, per
        // syncVehicleToServer's own doc comment.
        FleetEngineStore.createVehicle(context, freshVehicle())

        assertFalse("a failed push must not fabricate a sidecar row", db.vehicleSidecarDao().getByMac("AA:BB:CC") != null)
        val legacy = FleetEngineStore.getByMac(context, "AA:BB:CC")
        assertNotNull("the local write must have landed regardless of the server failure", legacy)
        assertEquals("Test Car", legacy!!.name)
    }

    @Test
    fun `the unconfigured path is untouched and still reads the legacy table`() = runBlocking {
        FleetEngineStore.createVehicle(context, freshVehicle())
        FleetEngineStore.setArchived(context, "AA:BB:CC", true, System.currentTimeMillis())

        val vehicle = FleetEngineStore.getByMac(context, "AA:BB:CC")
        assertNotNull(vehicle)
        assertTrue(vehicle!!.archived)
        assertEquals(0, db.vehicleReplicaDao().getAll().size)
    }
}
