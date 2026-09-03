package com.kevin.legion.backend

import com.kevin.legion.data.local.CarDatabase
import com.kevin.legion.data.local.MaintenanceItem
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
 * [MaintenanceScheduleReconcile] - exercised against an in-memory [FakeMaintenanceFleetBackend]
 * and a real (Robolectric) Room, never a network. Same "only the methods this reconcile actually
 * calls are functional, everything else on [FleetBackend] is `error(\"out of scope\")`" convention
 * [ObdSampleReconcileTest]'s own class doc describes.
 */
@RunWith(RobolectricTestRunner::class)
class MaintenanceScheduleReconcileTest {
    private val context = RuntimeEnvironment.getApplication()

    private class FakeMaintenanceFleetBackend : FleetBackend {
        val vehicles = mutableMapOf<String, RemoteVehicle>() // keyed by obdMac (test-local convenience)
        val schedules = mutableMapOf<String, RemoteMaintenanceSchedule>() // keyed by "vehicleServerId|serviceName" (exact)
        var clock = 1_000L
        private var counter = 0
        var failNextUpsert = false

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
        override suspend fun uploadObdSampleBatch(batch: List<ObdSampleUpload>): Result<Unit> = error("out of scope")
        override suspend fun countObdSamples(): Result<Long> = error("out of scope")

        override suspend fun fetchActiveMaintenanceSchedules(): Result<List<RemoteMaintenanceSchedule>> =
            Result.success(schedules.values.toList())

        override suspend fun upsertMaintenanceSchedule(schedule: MaintenanceScheduleUpload): Result<RemoteMaintenanceSchedule> {
            if (failNextUpsert) {
                failNextUpsert = false
                return Result.failure(FleetBackendException("simulated transport failure"))
            }
            val key = "${schedule.vehicleServerId}|${schedule.serviceName}"
            val row = RemoteMaintenanceSchedule(
                serverId = schedules[key]?.serverId ?: "ms-${++counter}",
                vehicleServerId = schedule.vehicleServerId,
                serviceName = schedule.serviceName,
                intervalMiles = schedule.intervalMiles,
                intervalMonths = schedule.intervalMonths,
                intervalSource = schedule.intervalSource,
                neverDone = schedule.neverDone,
                provenance = schedule.provenance,
                updatedAtMs = ++clock,
                deleted = false,
            )
            schedules[key] = row
            return Result.success(row)
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

    private suspend fun insertItem(
        obdMac: String,
        serviceName: String,
        intervalMiles: Int? = 5_000,
        intervalMonths: Int? = null,
        intervalSource: String = "SEEDED",
        neverDone: Boolean = false,
    ) {
        CarDatabase.getDatabase(context).maintenanceItemDao().upsertStamped(
            MaintenanceItem(
                vehicleId = obdMac,
                serviceName = serviceName,
                intervalMiles = intervalMiles,
                intervalMonths = intervalMonths,
                intervalSource = intervalSource,
                neverDone = neverDone,
            ),
        )
    }

    @Test
    fun `an item whose vehicle resolves uploads with USER provenance`() = runBlocking {
        val obdMac = "AA:BB:CC:DD:EE:01"
        insertLegacyVehicle(obdMac)
        val backend = FakeMaintenanceFleetBackend().apply { vehicles[obdMac] = remoteVehicleFor(obdMac, "vehicle-1") }
        insertItem(obdMac, "Oil Change", intervalMiles = 5_000)

        val report = MaintenanceScheduleReconcile.run(context, backend).getOrThrow()

        assertEquals(1, report.sourceCount)
        assertEquals(1, report.uploaded)
        assertTrue(report.skippedUnresolvedVehicle.isEmpty())
        assertTrue(report.skippedNoInterval.isEmpty())
        assertTrue(report.isClean)
        val row = backend.schedules.values.single()
        assertEquals("vehicle-1", row.vehicleServerId)
        assertEquals("Oil Change", row.serviceName)
        assertEquals(5_000, row.intervalMiles)
        assertEquals("USER", row.provenance)
    }

    @Test
    fun `an item whose vehicle does not resolve is skipped and named, not guessed`() = runBlocking {
        val obdMac = "AA:BB:CC:DD:EE:02"
        insertLegacyVehicle(obdMac)
        val backend = FakeMaintenanceFleetBackend() // no server vehicle seeded
        insertItem(obdMac, "Oil Change")

        val report = MaintenanceScheduleReconcile.run(context, backend).getOrThrow()

        assertEquals(0, report.uploaded)
        assertEquals(1, report.skippedUnresolvedVehicle.size)
        assertTrue(report.skippedUnresolvedVehicle.single().contains("vehicle not yet migrated"))
        assertTrue(backend.schedules.isEmpty())
    }

    @Test
    fun `an item with neither interval is skipped and named, never uploaded`() = runBlocking {
        val obdMac = "AA:BB:CC:DD:EE:03"
        insertLegacyVehicle(obdMac)
        val backend = FakeMaintenanceFleetBackend().apply { vehicles[obdMac] = remoteVehicleFor(obdMac, "vehicle-1") }
        insertItem(obdMac, "Brake Fluid", intervalMiles = null, intervalMonths = null)

        val report = MaintenanceScheduleReconcile.run(context, backend).getOrThrow()

        assertEquals(0, report.uploaded)
        assertEquals(1, report.skippedNoInterval.size)
        assertTrue(report.skippedNoInterval.single().contains("no interval on file"))
        assertTrue(backend.schedules.isEmpty())
    }

    @Test
    fun `a re-run is idempotent - no duplicate server row, identity is stable`() = runBlocking {
        val obdMac = "AA:BB:CC:DD:EE:04"
        insertLegacyVehicle(obdMac)
        val backend = FakeMaintenanceFleetBackend().apply { vehicles[obdMac] = remoteVehicleFor(obdMac, "vehicle-1") }
        insertItem(obdMac, "Oil Change", intervalMiles = 5_000)

        val first = MaintenanceScheduleReconcile.run(context, backend).getOrThrow()
        val serverIdAfterFirst = backend.schedules.values.single().serverId
        val second = MaintenanceScheduleReconcile.run(context, backend).getOrThrow()

        assertEquals(1, first.serverCountAfter)
        assertEquals(1, second.serverCountAfter)
        assertEquals(1, backend.schedules.size)
        assertTrue(second.isClean)
        // Same server row (same serverId), not a second insert - the REPLACE-on-conflict contract.
        assertEquals(serverIdAfterFirst, backend.schedules.values.single().serverId)
    }

    @Test
    fun `an interval edit between runs overwrites the server row rather than duplicating it`() = runBlocking {
        val obdMac = "AA:BB:CC:DD:EE:05"
        insertLegacyVehicle(obdMac)
        val backend = FakeMaintenanceFleetBackend().apply { vehicles[obdMac] = remoteVehicleFor(obdMac, "vehicle-1") }
        insertItem(obdMac, "Oil Change", intervalMiles = 5_000)
        MaintenanceScheduleReconcile.run(context, backend).getOrThrow()
        val serverIdBefore = backend.schedules.values.single().serverId

        // Driver confirms a new interval - same (vehicleId, serviceName), different value.
        insertItem(obdMac, "Oil Change", intervalMiles = 7_500, intervalSource = "CONFIRMED")
        val report = MaintenanceScheduleReconcile.run(context, backend).getOrThrow()

        assertEquals(1, report.serverCountAfter)
        assertEquals(1, backend.schedules.size)
        val row = backend.schedules.values.single()
        assertEquals(serverIdBefore, row.serverId)
        assertEquals(7_500, row.intervalMiles)
        assertEquals("CONFIRMED", row.intervalSource)
    }

    @Test
    fun `a service name differing only by casing reuses the server's existing casing, never creating a second row`() = runBlocking {
        val obdMac = "AA:BB:CC:DD:EE:06"
        insertLegacyVehicle(obdMac)
        val backend = FakeMaintenanceFleetBackend().apply {
            vehicles[obdMac] = remoteVehicleFor(obdMac, "vehicle-1")
            // A row already on file under one casing - as if uploaded earlier, or by the other phone.
            schedules["vehicle-1|Oil Change"] = RemoteMaintenanceSchedule(
                serverId = "ms-existing",
                vehicleServerId = "vehicle-1",
                serviceName = "Oil Change",
                intervalMiles = 5_000,
                intervalMonths = null,
                intervalSource = "SEEDED",
                neverDone = false,
                provenance = "USER",
                updatedAtMs = 1_000L,
                deleted = false,
            )
        }
        // The LOCAL row differs only by case.
        insertItem(obdMac, "oil change", intervalMiles = 6_000)

        val report = MaintenanceScheduleReconcile.run(context, backend).getOrThrow()

        assertEquals(1, report.uploaded)
        // Still exactly one server row - the upload reused the server's own "Oil Change" casing as
        // its conflict target rather than upserting under "oil change", which would have inserted a
        // second row under the real (vehicle_id, service_name) unique constraint.
        assertEquals(1, backend.schedules.size)
        val row = backend.schedules.values.single()
        assertEquals("ms-existing", row.serverId)
        assertEquals("Oil Change", row.serviceName)
        assertEquals(6_000, row.intervalMiles)
        assertTrue(report.isClean)
    }

    @Test
    fun `a failed upsert short-circuits and touches nothing further`() = runBlocking {
        val obdMac = "AA:BB:CC:DD:EE:07"
        insertLegacyVehicle(obdMac)
        val backend = FakeMaintenanceFleetBackend().apply {
            vehicles[obdMac] = remoteVehicleFor(obdMac, "vehicle-1")
            failNextUpsert = true
        }
        insertItem(obdMac, "Oil Change")

        val result = MaintenanceScheduleReconcile.run(context, backend)

        assertTrue(result.isFailure)
        assertTrue(backend.schedules.isEmpty())
    }

    @Test
    fun `never deletes or trashes a source row`() = runBlocking {
        val obdMac = "AA:BB:CC:DD:EE:08"
        insertLegacyVehicle(obdMac)
        val backend = FakeMaintenanceFleetBackend().apply { vehicles[obdMac] = remoteVehicleFor(obdMac, "vehicle-1") }
        insertItem(obdMac, "Oil Change")

        MaintenanceScheduleReconcile.run(context, backend).getOrThrow()

        assertEquals(1, CarDatabase.getDatabase(context).maintenanceItemDao().getAllActive().size)
    }
}
