package com.kevin.legion.backend

import com.kevin.legion.data.local.CarDatabase
import com.kevin.legion.data.local.OdbSample
import com.kevin.legion.data.local.Vehicle
import com.kevin.legion.engine.fleet.FleetRecordBridge
import com.kevin.legion.testutil.RoomTestReset
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * [ObdSampleReconcile] - exercised against an in-memory [FakeObdFleetBackend] and a real
 * (Robolectric) Room, never a network, same posture as [FleetReconcileTest]. Only the two
 * [FleetBackend] methods this reconcile actually calls are functional here
 * ([FleetBackend.fetchActiveVehicles]/[FleetBackend.uploadObdSampleBatch]) - everything else on
 * the interface is `error("out of scope")`, the same convention [FleetReconcileTest]'s own fake
 * uses for methods a given wave never reaches.
 */
@RunWith(RobolectricTestRunner::class)
class ObdSampleReconcileTest {
    private val context = RuntimeEnvironment.getApplication()

    private class FakeObdFleetBackend : FleetBackend {
        val vehicles = mutableMapOf<String, RemoteVehicle>() // keyed by originGuid
        val batches = mutableListOf<List<ObdSampleUpload>>()
        var failNextBatch = false

        override suspend fun fetchActiveVehicles(): Result<List<RemoteVehicle>> = Result.success(vehicles.values.toList())
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
        override suspend fun fetchVehicleSpecs(): Result<List<RemoteVehicleSpec>> = error("out of scope")
        override suspend fun upsertVehicleSpec(spec: VehicleSpecUpload): Result<RemoteVehicleSpec> = error("out of scope")
        override suspend fun fetchActiveBuildEntries(): Result<List<RemoteBuildEntry>> = error("out of scope")
        override suspend fun upsertBuildEntry(entry: BuildEntryUpload): Result<RemoteBuildEntry> = error("out of scope")
        override suspend fun fetchActiveDriveReassignments(): Result<List<RemoteDriveReassignment>> = error("out of scope")
        override suspend fun upsertDriveReassignment(reassignment: DriveReassignmentUpload): Result<RemoteDriveReassignment> = error("out of scope")

        override suspend fun uploadObdSampleBatch(batch: List<ObdSampleUpload>): Result<Unit> {
            if (failNextBatch) {
                failNextBatch = false
                return Result.failure(FleetBackendException("simulated transport failure"))
            }
            batches.add(batch)
            return Result.success(Unit)
        }
    }

    @Before
    fun clearState() {
        RoomTestReset.resetCarDatabaseSingleton()
    }

    private fun remoteVehicleFor(obdMac: String, serverId: String) = RemoteVehicle(
        serverId = serverId,
        name = "Jeep",
        make = "Jeep",
        model = "Cherokee",
        year = 1998,
        trim = null,
        engine = null,
        confirmed = true,
        odometerBaseline = null,
        odometerBaselineAtMs = null,
        updatedAtMs = 1_000L,
        deleted = false,
        originGuid = FleetRecordBridge.vehicleGuid(obdMac),
        archived = false,
    )

    private suspend fun insertLegacyVehicle(obdMac: String) {
        CarDatabase.getDatabase(context).vehicleDao().upsert(
            Vehicle(obdMac = obdMac, name = "Jeep", make = "Jeep", model = "Cherokee", year = 1998, personaPrompt = ""),
        )
    }

    private suspend fun insertSample(obdMac: String, pid: String = "010C", ts: Long = 1_000L): Long {
        val db = CarDatabase.getDatabase(context)
        db.odbSampleDao().insert(OdbSample(vehicleId = obdMac, pid = pid, value = 850.0, unit = "rpm", timestamp = ts))
        return db.odbSampleDao().getAfterId(0, Int.MAX_VALUE).last().id
    }

    @Test
    fun `a sample whose vehicle resolves uploads`() = runBlocking {
        val obdMac = "AA:BB:CC:DD:EE:01"
        insertLegacyVehicle(obdMac)
        val backend = FakeObdFleetBackend().apply { vehicles[obdMac] = remoteVehicleFor(obdMac, "vehicle-1") }
        insertSample(obdMac)

        val report = ObdSampleReconcile.run(context, backend).getOrThrow()

        assertEquals(1, report.sourceCount)
        assertEquals(1, report.uploaded)
        assertTrue(report.skippedUnresolvedVehicle.isEmpty())
        assertEquals(1, backend.batches.sumOf { it.size })
        assertEquals("vehicle-1", backend.batches.single().single().vehicleServerId)
    }

    @Test
    fun `a sample whose vehicle does not resolve is skipped and named, not guessed`() = runBlocking {
        val obdMac = "AA:BB:CC:DD:EE:02"
        insertLegacyVehicle(obdMac)
        val backend = FakeObdFleetBackend() // no matching RemoteVehicle - never migrated server-side
        insertSample(obdMac)

        val report = ObdSampleReconcile.run(context, backend).getOrThrow()

        assertEquals(0, report.uploaded)
        assertTrue(backend.batches.isEmpty())
        assertEquals(1, report.skippedUnresolvedVehicle.size)
        assertTrue(report.skippedUnresolvedVehicle.single().contains(obdMac))
    }

    @Test
    fun `a re-run uploads nothing new`() = runBlocking {
        val obdMac = "AA:BB:CC:DD:EE:03"
        insertLegacyVehicle(obdMac)
        val backend = FakeObdFleetBackend().apply { vehicles[obdMac] = remoteVehicleFor(obdMac, "vehicle-1") }
        insertSample(obdMac)

        val first = ObdSampleReconcile.run(context, backend).getOrThrow()
        assertEquals(1, first.uploaded)

        val second = ObdSampleReconcile.run(context, backend).getOrThrow()
        assertEquals(0, second.uploaded)
        assertEquals(1, second.sourceCount) // the row is still there, just not re-sent
    }

    @Test
    fun `reported counts match what was actually sent`() = runBlocking {
        val obdMac = "AA:BB:CC:DD:EE:04"
        insertLegacyVehicle(obdMac)
        val backend = FakeObdFleetBackend().apply { vehicles[obdMac] = remoteVehicleFor(obdMac, "vehicle-1") }
        repeat(3) { insertSample(obdMac, ts = 1_000L + it) }

        val report = ObdSampleReconcile.run(context, backend).getOrThrow()

        assertEquals(3, report.sourceCount)
        assertEquals(3, report.uploaded)
        assertEquals(3, backend.batches.sumOf { it.size })
    }
}
