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
import com.kevin.legion.data.local.BuildEntry
import com.kevin.legion.data.local.CarDatabase
import com.kevin.legion.data.local.Vehicle
import com.kevin.legion.data.local.VehicleSidecar
import com.kevin.legion.data.local.VehicleSpec
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
 * The `vehicle_specs`/`build_entries` step of the fleet cutover (backend-erp ticket 26 step 5, the
 * last one, `.scratch/backend-erp/issues/26-the-fleet-cutover-for-real.md`). `chassis_quirks`, the
 * third table this step named, is deliberately absent here for the identical reason `oil_analyses`
 * was absent from [FleetEngineStoreDiagnosticsCutoverTest] - it has no live local producer to
 * exercise (see [FleetEngineStore]'s own class doc).
 *
 * Exercised entirely through [FleetEngineStore.backendOverride] and an in-memory [FakeSpecsBackend],
 * never a real SupabaseClient - same posture as every prior cutover-step test in this file.
 */
@RunWith(RobolectricTestRunner::class)
class FleetEngineStoreSpecsCutoverTest {
    private val context = RuntimeEnvironment.getApplication()
    private val db get() = CarDatabase.getDatabase(context)

    /** Fails any call this step must not make - every fleet table besides `vehicles`/
     * `vehicle_specs`/`build_entries`. */
    private class FakeSpecsBackend : FleetBackend {
        val vehicleSpecs = mutableMapOf<String, RemoteVehicleSpec>()
        val buildEntries = mutableMapOf<String, RemoteBuildEntry>()
        private var counter = 0
        var clock = 1_000L
        var nextVehicleSpecPushFails = false
        var nextBuildEntryPushFails = false

        override suspend fun fetchActiveVehicles(): Result<List<RemoteVehicle>> = error("out of scope")
        override suspend fun uploadMigratedVehicle(vehicle: MigratedVehicle): Result<Boolean> = error("out of scope")
        override suspend fun upsertVehicle(vehicle: VehicleUpload): Result<RemoteVehicle> = error("out of scope")
        override suspend fun fetchActiveServiceHistory(): Result<List<RemoteServiceHistory>> = error("out of scope")
        override suspend fun uploadMigratedServiceHistory(history: MigratedServiceHistory): Result<Boolean> = error("out of scope")
        override suspend fun upsertServiceHistory(history: ServiceHistoryUpload): Result<RemoteServiceHistory> = error("out of scope")
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

        override suspend fun fetchVehicleSpecs(): Result<List<RemoteVehicleSpec>> = Result.success(vehicleSpecs.values.toList())

        override suspend fun upsertVehicleSpec(spec: VehicleSpecUpload): Result<RemoteVehicleSpec> {
            if (nextVehicleSpecPushFails) {
                nextVehicleSpecPushFails = false
                return Result.failure(FleetBackendException("simulated network failure"))
            }
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
                provenance = spec.provenance,
                updatedAtMs = ++clock,
            )
            vehicleSpecs[row.vehicleServerId] = row
            return Result.success(row)
        }

        override suspend fun fetchActiveBuildEntries(): Result<List<RemoteBuildEntry>> = Result.success(buildEntries.values.toList())

        override suspend fun upsertBuildEntry(entry: BuildEntryUpload): Result<RemoteBuildEntry> {
            if (nextBuildEntryPushFails) {
                nextBuildEntryPushFails = false
                return Result.failure(FleetBackendException("simulated network failure"))
            }
            val existing = buildEntries[entry.syncId]
            val row = RemoteBuildEntry(
                serverId = existing?.serverId ?: "build-entry-${++counter}",
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
            buildEntries[row.syncId] = row
            return Result.success(row)
        }

        override suspend fun fetchActiveDriveReassignments(): Result<List<RemoteDriveReassignment>> = error("out of scope")
        override suspend fun upsertDriveReassignment(reassignment: DriveReassignmentUpload): Result<RemoteDriveReassignment> = error("out of scope")
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

    private fun vehicle(mac: String, name: String) = Vehicle(
        obdMac = mac, name = name, make = "Jeep", model = "Cherokee", year = 1998,
        personaPrompt = "buckle up", voiceName = "Kore", personaTraits = "{}", confirmed = true,
        archived = false, onboarded = false, tripMilesSinceBaseline = 0.0, lastOdometerPromptAt = 0L,
    )

    /** Plants a synced car directly, the same way [FleetEngineStoreDrivesCutoverTest.syncedVehicle]
     * does - bypasses [FleetEngineStore.createVehicle]'s own sync push, which is out of scope here. */
    private suspend fun syncedVehicle(mac: String, name: String, serverId: String) {
        db.vehicleDao().upsert(vehicle(mac, name))
        db.vehicleSidecarDao().upsert(VehicleSidecar(serverId = serverId, obdMac = mac))
    }

    private fun spec(mac: String, vin: String = "1J4FF48S3TL199999") = VehicleSpec(
        vehicleId = mac,
        vin = vin,
        engineCylinders = 6,
        displacementL = 4.0,
    )

    // ------------------------------------------------------------------------------- VehicleSpec

    @Test
    fun `an unconfigured install never touches the vehicle-spec push`() = runBlocking {
        db.vehicleDao().upsert(vehicle(macA, "A"))
        FleetEngineStore.upsertVehicleSpec(context, spec(macA))
        assertNotNull(db.vehicleSpecDao().get(macA))
    }

    @Test
    fun `a first configured vehicle-spec push lands on the server keyed by the vehicle's own uuid`() = runBlocking {
        val backend = FakeSpecsBackend()
        FleetEngineStore.backendOverride = backend
        syncedVehicle(macA, "A", "vehicle-A")

        FleetEngineStore.upsertVehicleSpec(context, spec(macA))

        assertEquals(1, backend.vehicleSpecs.size)
        assertEquals("vehicle-A", backend.vehicleSpecs.values.single().vehicleServerId)
        assertNotNull(db.vehicleSpecDao().get(macA))
    }

    @Test
    fun `a re-run of the vehicle-spec push overwrites in place rather than duplicating`() = runBlocking {
        val backend = FakeSpecsBackend()
        FleetEngineStore.backendOverride = backend
        syncedVehicle(macA, "A", "vehicle-A")

        FleetEngineStore.upsertVehicleSpec(context, spec(macA, vin = "OLDVIN"))
        FleetEngineStore.syncVehicleSpecToServer(context, macA)
        FleetEngineStore.syncVehicleSpecToServer(context, macA)

        assertEquals("a repost must never mint a second server row", 1, backend.vehicleSpecs.size)
        assertEquals("OLDVIN", backend.vehicleSpecs.values.single().vin)
        assertEquals("the local row's own key must never move across a re-push", macA, db.vehicleSpecDao().get(macA)!!.vehicleId)
    }

    @Test
    fun `a FAILED vehicle-spec push leaves the local write intact and is logged, not thrown`() = runBlocking {
        val backend = FakeSpecsBackend()
        backend.nextVehicleSpecPushFails = true
        FleetEngineStore.backendOverride = backend
        syncedVehicle(macA, "A", "vehicle-A")

        FleetEngineStore.upsertVehicleSpec(context, spec(macA))

        assertNotNull("the local write must succeed regardless of the server failure", db.vehicleSpecDao().get(macA))
        assertEquals(0, backend.vehicleSpecs.size)
    }

    @Test
    fun `a vehicle spec with no server mapping yet is a no-op push, not a crash`() = runBlocking {
        val backend = FakeSpecsBackend()
        FleetEngineStore.backendOverride = backend
        // Configured, but this car has never itself synced - no VehicleSidecar row.
        db.vehicleDao().upsert(vehicle(macA, "A"))

        FleetEngineStore.upsertVehicleSpec(context, spec(macA))

        assertNotNull(db.vehicleSpecDao().get(macA))
        assertEquals(0, backend.vehicleSpecs.size)
    }

    // -------------------------------------------------------------------------------- BuildEntry

    @Test
    fun `an unconfigured install never touches the build-entry push`() = runBlocking {
        db.vehicleDao().upsert(vehicle(macA, "A"))
        val id = FleetEngineStore.recordBuildEntry(
            context, macA, "mod", "Intake", "", "", 199.99, 1_000L, 50_000, "",
        )
        assertNotNull(db.buildEntryDao().getById(id))
        assertNull(db.buildEntryDao().getById(id)!!.serverId)
    }

    @Test
    fun `a first configured build-entry push records what the server returns, cost converted to cents`() = runBlocking {
        val backend = FakeSpecsBackend()
        FleetEngineStore.backendOverride = backend
        syncedVehicle(macA, "A", "vehicle-A")

        val id = FleetEngineStore.recordBuildEntry(
            context, macA, "mod", "Intake", "Vendor Co", "PN-1", 199.99, 1_000L, 50_000, "loud",
        )

        assertEquals(1, backend.buildEntries.size)
        assertEquals(19999L, backend.buildEntries.values.single().costCents)
        val local = db.buildEntryDao().getById(id)!!
        assertNotNull("a synced build entry must have its serverId recorded", local.serverId)
        assertEquals(backend.buildEntries.values.single().serverId, local.serverId)
    }

    @Test
    fun `a null-cost build entry never fabricates a cost`() = runBlocking {
        val backend = FakeSpecsBackend()
        FleetEngineStore.backendOverride = backend
        syncedVehicle(macA, "A", "vehicle-A")

        FleetEngineStore.recordBuildEntry(context, macA, "part", "Filter", "", "", null, 1_000L, null, "")

        assertNull(backend.buildEntries.values.single().costCents)
    }

    @Test
    fun `a re-run of the build-entry push updates rather than duplicating, and never remints the local id`() = runBlocking {
        val backend = FakeSpecsBackend()
        FleetEngineStore.backendOverride = backend
        syncedVehicle(macA, "A", "vehicle-A")

        val id = FleetEngineStore.recordBuildEntry(
            context, macA, "mod", "Intake", "", "", 199.99, 1_000L, 50_000, "",
        )
        val firstServerId = db.buildEntryDao().getById(id)!!.serverId

        // A build entry has no domain-level edit call (BuildEntryDao's own doc on why `delete` is
        // dormant) - the only way to exercise a re-run is a direct retry of the SAME local row's
        // push. See FleetEngineStore.syncBuildEntryToServer's own doc for why it is `internal`.
        FleetEngineStore.syncBuildEntryToServer(context, macA, id)
        FleetEngineStore.syncBuildEntryToServer(context, macA, id)

        assertEquals("a repost must never mint a second server row", 1, backend.buildEntries.size)
        assertEquals(firstServerId, backend.buildEntries.values.single().serverId)
        assertEquals("the local row id must never move across a re-push", id, db.buildEntryDao().getById(id)!!.id)
    }

    @Test
    fun `a FAILED build-entry push leaves the local write intact and is logged, not thrown`() = runBlocking {
        val backend = FakeSpecsBackend()
        backend.nextBuildEntryPushFails = true
        FleetEngineStore.backendOverride = backend
        syncedVehicle(macA, "A", "vehicle-A")

        val id = FleetEngineStore.recordBuildEntry(
            context, macA, "mod", "Intake", "", "", 199.99, 1_000L, 50_000, "",
        )

        assertNotNull("the local write must succeed regardless of the server failure", db.buildEntryDao().getById(id))
        assertNull("a failed push must not fabricate a serverId", db.buildEntryDao().getById(id)!!.serverId)
        assertEquals(0, backend.buildEntries.size)
    }

    @Test
    fun `a build entry with no server mapping yet is a no-op push, not a crash`() = runBlocking {
        val backend = FakeSpecsBackend()
        FleetEngineStore.backendOverride = backend
        // Configured, but this car has never itself synced - no VehicleSidecar row.
        db.vehicleDao().upsert(vehicle(macA, "A"))

        val id = FleetEngineStore.recordBuildEntry(
            context, macA, "mod", "Intake", "", "", 199.99, 1_000L, 50_000, "",
        )

        assertNotNull(db.buildEntryDao().getById(id))
        assertEquals(0, backend.buildEntries.size)
    }

    @Test
    fun `reads already serve the tables both channels write to - no replica repoint needed`() = runBlocking {
        val backend = FakeSpecsBackend()
        FleetEngineStore.backendOverride = backend
        syncedVehicle(macA, "A", "vehicle-A")

        FleetEngineStore.upsertVehicleSpec(context, spec(macA))
        FleetEngineStore.recordBuildEntry(context, macA, "mod", "Intake", "", "", 199.99, 1_000L, 50_000, "")

        assertNotNull(db.vehicleSpecDao().get(macA))
        assertEquals(1, db.buildEntryDao().getForVehicle(macA).size)
    }
}
