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
import com.kevin.legion.backend.ServiceHistoryUpload
import com.kevin.legion.backend.VehicleSpecUpload
import com.kevin.legion.backend.VehicleUpload
import com.kevin.legion.data.local.CarDatabase
import com.kevin.legion.data.local.Vehicle
import com.kevin.legion.data.local.VehicleSidecar
import com.kevin.legion.testutil.RoomTestReset
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * The `drives`/`drive_reassignments` step of the fleet cutover (backend-erp ticket 26 step 3,
 * `.scratch/backend-erp/issues/26-the-fleet-cutover-for-real.md`), built together per ticket 06's
 * ruling that a fact and its corrections must not split across two systems. Exercised entirely
 * through [FleetEngineStore.backendOverride] and an in-memory [FakeDrivesBackend], never a real
 * SupabaseClient - same posture as [FleetEngineStoreServiceHistoryCutoverTest].
 *
 * Unlike that test, there is no separate "configured read serves the replica" case here at all -
 * see [FleetEngineStore]'s own class doc for why `drives`/`drive_reassignments` needed no read-side
 * repoint: the legacy table already IS the thing both the live push and
 * [com.kevin.legion.backend.FleetReconcile]'s batch refill write to, so an ordinary DAO read already
 * serves whatever either channel put there.
 */
@RunWith(RobolectricTestRunner::class)
class FleetEngineStoreDrivesCutoverTest {
    private val context = RuntimeEnvironment.getApplication()
    private val db get() = CarDatabase.getDatabase(context)

    /** Fails any call this step must not make - every fleet table besides `vehicles`/`drives`/
     * `drive_reassignments`. */
    private class FakeDrivesBackend : FleetBackend {
        val drives = mutableMapOf<String, RemoteDrive>()
        val reassignments = mutableMapOf<String, RemoteDriveReassignment>()
        private var counter = 0
        var clock = 1_000L
        var nextDrivePushFails = false

        override suspend fun fetchActiveVehicles(): Result<List<RemoteVehicle>> = error("out of scope")
        override suspend fun uploadMigratedVehicle(vehicle: MigratedVehicle): Result<Boolean> = error("out of scope")
        override suspend fun upsertVehicle(vehicle: VehicleUpload): Result<RemoteVehicle> = error("out of scope")
        override suspend fun fetchActiveServiceHistory(): Result<List<RemoteServiceHistory>> = error("out of scope")
        override suspend fun uploadMigratedServiceHistory(history: MigratedServiceHistory): Result<Boolean> = error("out of scope")
        override suspend fun upsertServiceHistory(history: ServiceHistoryUpload): Result<RemoteServiceHistory> = error("out of scope")

        override suspend fun fetchActiveDrives(): Result<List<RemoteDrive>> = Result.success(drives.values.toList())

        override suspend fun upsertDrive(drive: DriveUpload): Result<RemoteDrive> {
            if (nextDrivePushFails) {
                nextDrivePushFails = false
                return Result.failure(FleetBackendException("simulated network failure"))
            }
            val existing = drives[drive.syncId]
            val row = RemoteDrive(
                serverId = existing?.serverId ?: "drive-${++counter}",
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
            drives[row.syncId] = row
            return Result.success(row)
        }

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

        override suspend fun fetchActiveDriveReassignments(): Result<List<RemoteDriveReassignment>> =
            Result.success(reassignments.values.toList())

        override suspend fun upsertDriveReassignment(reassignment: DriveReassignmentUpload): Result<RemoteDriveReassignment> {
            val existing = reassignments[reassignment.syncId]
            val row = RemoteDriveReassignment(
                serverId = existing?.serverId ?: "reassignment-${++counter}",
                syncId = reassignment.syncId,
                vehicleServerId = reassignment.vehicleServerId,
                newVehicleServerId = reassignment.newVehicleServerId,
                fromAtMs = reassignment.fromAtMs,
                toAtMs = reassignment.toAtMs,
                provenance = reassignment.provenance,
                updatedAtMs = ++clock,
                deleted = false,
            )
            reassignments[row.syncId] = row
            return Result.success(row)
        }
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

    private val macA = "AA:BB:CC"
    private val macB = "DD:EE:FF"

    private fun vehicle(mac: String, name: String) = Vehicle(
        obdMac = mac, name = name, make = "Jeep", model = "Cherokee", year = 1998,
        personaPrompt = "buckle up", voiceName = "Kore", personaTraits = "{}", confirmed = true,
        archived = false, onboarded = false, tripMilesSinceBaseline = 0.0, lastOdometerPromptAt = 0L,
    )

    /** Plants a synced car directly, the same way [FleetEngineStoreServiceHistoryCutoverTest.syncedVehicle]
     * does - bypasses [FleetEngineStore.createVehicle]'s own sync push, which is out of scope here. */
    private suspend fun syncedVehicle(mac: String, name: String, serverId: String) {
        db.vehicleDao().upsert(vehicle(mac, name))
        db.vehicleSidecarDao().upsert(VehicleSidecar(serverId = serverId, obdMac = mac))
    }

    @Test
    fun `an unconfigured install never touches the drives push`() = runBlocking {
        db.vehicleDao().upsert(vehicle(macA, "A"))
        val id = FleetEngineStore.recordDrive(context, macA, 1_000L, 2_000L, 12.0, 0.5, "ENGINE_OFF")
        assertNotNull(db.driveDao().getById(id))
        assertNull(db.driveDao().getById(id)!!.serverId)
    }

    @Test
    fun `a first configured drive push records what the server returns`() = runBlocking {
        val backend = FakeDrivesBackend()
        FleetEngineStore.backendOverride = backend
        syncedVehicle(macA, "A", "vehicle-A")

        val id = FleetEngineStore.recordDrive(context, macA, 1_000L, 2_000L, 12.0, 0.5, "ENGINE_OFF")

        assertEquals(1, backend.drives.size)
        val local = db.driveDao().getById(id)!!
        assertNotNull("a synced drive must have its serverId recorded", local.serverId)
        assertEquals(backend.drives.values.single().serverId, local.serverId)
    }

    @Test
    fun `a re-run of the drive push updates rather than duplicating, and never remints the local id`() = runBlocking {
        val backend = FakeDrivesBackend()
        FleetEngineStore.backendOverride = backend
        syncedVehicle(macA, "A", "vehicle-A")

        val id = FleetEngineStore.recordDrive(context, macA, 1_000L, 2_000L, 12.0, 0.5, "ENGINE_OFF")
        val firstServerId = db.driveDao().getById(id)!!.serverId

        // A drive is never edited through any domain call (Drive's own class doc: "no update, no
        // delete") - the only way to exercise a re-run is a direct retry of the SAME local row's
        // push. See FleetEngineStore.syncDriveToServer's own doc for why it is `internal`.
        FleetEngineStore.syncDriveToServer(context, macA, id)
        FleetEngineStore.syncDriveToServer(context, macA, id)

        assertEquals("a repost must never mint a second server row", 1, backend.drives.size)
        assertEquals(firstServerId, backend.drives.values.single().serverId)
        assertEquals("the local row id must never move across a re-push", id, db.driveDao().getById(id)!!.id)
    }

    @Test
    fun `a FAILED drive push leaves the local write intact and is logged, not thrown`() = runBlocking {
        val backend = FakeDrivesBackend()
        backend.nextDrivePushFails = true
        FleetEngineStore.backendOverride = backend
        syncedVehicle(macA, "A", "vehicle-A")

        val id = FleetEngineStore.recordDrive(context, macA, 1_000L, 2_000L, 12.0, 0.5, "ENGINE_OFF")

        assertNotNull("the local write must succeed regardless of the server failure", db.driveDao().getById(id))
        assertNull("a failed push must not fabricate a serverId", db.driveDao().getById(id)!!.serverId)
        assertEquals(0, backend.drives.size)
    }

    @Test
    fun `a drive with no server mapping yet is a no-op push, not a crash`() = runBlocking {
        val backend = FakeDrivesBackend()
        FleetEngineStore.backendOverride = backend
        // Configured, but this car has never itself synced - no VehicleSidecar row.
        db.vehicleDao().upsert(vehicle(macA, "A"))

        val id = FleetEngineStore.recordDrive(context, macA, 1_000L, 2_000L, 12.0, 0.5, "ENGINE_OFF")

        assertNotNull(db.driveDao().getById(id))
        assertEquals(0, backend.drives.size)
    }

    @Test
    fun `reads already serve the table both channels write to - no replica repoint needed`() = runBlocking {
        val backend = FakeDrivesBackend()
        FleetEngineStore.backendOverride = backend
        syncedVehicle(macA, "A", "vehicle-A")

        FleetEngineStore.recordDrive(context, macA, 1_000L, 2_000L, 12.0, 0.5, "ENGINE_OFF")

        val recent = db.driveDao().getRecent(macA, 10)
        assertEquals(1, recent.size)
        assertEquals(12.0, recent.single().miles, 0.0)
    }

    @Test
    fun `a drive and its reassignment stay consistent across a re-run`() = runBlocking {
        val backend = FakeDrivesBackend()
        FleetEngineStore.backendOverride = backend
        syncedVehicle(macA, "A", "vehicle-A")
        syncedVehicle(macB, "B", "vehicle-B")

        val driveId = FleetEngineStore.recordDrive(context, macA, 1_000L, 2_000L, 12.0, 0.5, "ENGINE_OFF")
        val reassignmentId = FleetEngineStore.recordDriveReassignment(context, macA, macB, 1_000L, 2_000L)
        assertNotNull(reassignmentId)

        assertEquals(1, backend.drives.size)
        assertEquals(1, backend.reassignments.size)
        val reassignmentRow = backend.reassignments.values.single()
        assertEquals("vehicle-A", reassignmentRow.vehicleServerId)
        assertEquals("vehicle-B", reassignmentRow.newVehicleServerId)

        // Re-run both pushes - neither the drive row nor the reassignment row should duplicate or
        // remint, and they still agree with each other.
        FleetEngineStore.syncDriveToServer(context, macA, driveId)
        FleetEngineStore.syncDriveReassignmentToServer(context, reassignmentId!!)

        assertEquals(1, backend.drives.size)
        assertEquals(1, backend.reassignments.size)
        assertEquals(driveId, db.driveDao().getById(driveId)!!.id)
        assertEquals(reassignmentId, db.driveReassignmentDao().getById(reassignmentId)!!.id)
    }

    @Test
    fun `reassigning to the same car is a no-op, matching the prior direct-call contract`() = runBlocking {
        val backend = FakeDrivesBackend()
        FleetEngineStore.backendOverride = backend
        syncedVehicle(macA, "A", "vehicle-A")

        val result = FleetEngineStore.recordDriveReassignment(context, macA, macA, 1_000L, 2_000L)

        assertEquals(null, result)
        assertEquals(0, db.driveReassignmentDao().getAll().size)
        assertEquals(0, backend.reassignments.size)
    }

    @Test
    fun `a reassignment naming a car with no server mapping yet is a no-op push, not a crash`() = runBlocking {
        val backend = FakeDrivesBackend()
        FleetEngineStore.backendOverride = backend
        syncedVehicle(macA, "A", "vehicle-A")
        // macB exists locally but has never synced - no VehicleSidecar row.
        db.vehicleDao().upsert(vehicle(macB, "B"))

        val reassignmentId = FleetEngineStore.recordDriveReassignment(context, macA, macB, 1_000L, 2_000L)

        assertNotNull(reassignmentId)
        assertEquals(0, backend.reassignments.size)
    }
}
