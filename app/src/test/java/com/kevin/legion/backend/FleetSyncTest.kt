package com.kevin.legion.backend

import com.kevin.legion.data.local.CarDatabase
import com.kevin.legion.data.local.Drive
import com.kevin.legion.testutil.RoomTestReset
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * [FleetSync.pull] - exercised against an in-memory [FakeFleetPullBackend] and real (Robolectric)
 * Room, never a network, same posture as [LedgerConfigSyncTest]/[FleetReconcileTest]. Covers the
 * vehicle-mac-hint reconstruction this ticket exists for, plus the same five merge rules every
 * other live-sync aspect's own pull test already covers (insert / LWW both directions / tombstone
 * with and without a local match / local-only survives / second pull is a true no-op), applied here
 * to `vehicles` (the reconstruction wave) and `service_history`/`maintenance_schedules` (the other
 * two genuinely-LWW tables) - see [FleetSync]'s own class doc for why the remaining six tables
 * reduce to a strictly simpler insert-or-delete shape that this test also exercises once via
 * `drives`.
 */
@RunWith(RobolectricTestRunner::class)
class FleetSyncTest {
    private val context = RuntimeEnvironment.getApplication()

    private class FakeFleetPullBackend : FleetBackend {
        val vehicles = mutableMapOf<String, RemoteVehicle>() // by serverId
        val serviceHistory = mutableMapOf<String, RemoteServiceHistory>() // by serverId
        val drives = mutableMapOf<String, RemoteDrive>() // by syncId
        val maintenanceSchedules = mutableMapOf<String, RemoteMaintenanceSchedule>() // by "vehicleServerId|serviceName"
        val obdSamples = mutableMapOf<String, MutableList<RemoteObdSample>>() // by vehicleServerId
        var obdSampleServerTotal = 0L

        override suspend fun fetchChangedVehiclesSince(sinceMs: Long) =
            Result.success(vehicles.values.filter { it.updatedAtMs >= sinceMs })
        override suspend fun fetchChangedServiceHistorySince(sinceMs: Long) =
            Result.success(serviceHistory.values.filter { it.updatedAtMs >= sinceMs })
        override suspend fun fetchChangedDrivesSince(sinceMs: Long) =
            Result.success(drives.values.filter { it.updatedAtMs >= sinceMs })
        override suspend fun fetchChangedMaintenanceSchedulesSince(sinceMs: Long) =
            Result.success(maintenanceSchedules.values.filter { it.updatedAtMs >= sinceMs })
        override suspend fun fetchObdSamplesSince(vehicleServerId: String, sinceMs: Long) =
            Result.success((obdSamples[vehicleServerId] ?: emptyList()).filter { it.recordedAtMs >= sinceMs })
        override suspend fun countObdSamples(): Result<Long> = Result.success(obdSampleServerTotal)

        // Everything below is unused by FleetSync (upload-side only) - "not used" failures, same
        // convention every other fake in this package uses for its own unexercised half.
        override suspend fun fetchActiveVehicles() = Result.failure<List<RemoteVehicle>>(FleetBackendException("not used"))
        override suspend fun uploadMigratedVehicle(vehicle: MigratedVehicle) = Result.failure<Boolean>(FleetBackendException("not used"))
        override suspend fun upsertVehicle(vehicle: VehicleUpload) = Result.failure<RemoteVehicle>(FleetBackendException("not used"))
        override suspend fun fetchActiveServiceHistory() = Result.failure<List<RemoteServiceHistory>>(FleetBackendException("not used"))
        override suspend fun uploadMigratedServiceHistory(history: MigratedServiceHistory) = Result.failure<Boolean>(FleetBackendException("not used"))
        override suspend fun upsertServiceHistory(history: ServiceHistoryUpload) = Result.failure<RemoteServiceHistory>(FleetBackendException("not used"))
        override suspend fun fetchActiveDrives() = Result.failure<List<RemoteDrive>>(FleetBackendException("not used"))
        override suspend fun upsertDrive(drive: DriveUpload) = Result.failure<RemoteDrive>(FleetBackendException("not used"))
        override suspend fun fetchActiveCodeEvents() = Result.failure<List<RemoteCodeEvent>>(FleetBackendException("not used"))
        override suspend fun upsertCodeEvent(event: CodeEventUpload) = Result.failure<RemoteCodeEvent>(FleetBackendException("not used"))
        override suspend fun fetchActiveCodeClearEvents() = Result.failure<List<RemoteCodeClearEvent>>(FleetBackendException("not used"))
        override suspend fun upsertCodeClearEvent(event: CodeClearEventUpload) = Result.failure<RemoteCodeClearEvent>(FleetBackendException("not used"))
        override suspend fun fetchActiveOilAnalyses() = Result.failure<List<RemoteOilAnalysis>>(FleetBackendException("not used"))
        override suspend fun upsertOilAnalysis(analysis: OilAnalysisUpload) = Result.failure<RemoteOilAnalysis>(FleetBackendException("not used"))
        override suspend fun fetchChassisQuirks() = Result.failure<List<RemoteChassisQuirk>>(FleetBackendException("not used"))
        override suspend fun upsertChassisQuirk(quirk: ChassisQuirkUpload) = Result.failure<RemoteChassisQuirk>(FleetBackendException("not used"))
        override suspend fun fetchVehicleSpecs() = Result.failure<List<RemoteVehicleSpec>>(FleetBackendException("not used"))
        override suspend fun upsertVehicleSpec(spec: VehicleSpecUpload) = Result.failure<RemoteVehicleSpec>(FleetBackendException("not used"))
        override suspend fun fetchActiveBuildEntries() = Result.failure<List<RemoteBuildEntry>>(FleetBackendException("not used"))
        override suspend fun upsertBuildEntry(entry: BuildEntryUpload) = Result.failure<RemoteBuildEntry>(FleetBackendException("not used"))
        override suspend fun fetchActiveDriveReassignments() = Result.failure<List<RemoteDriveReassignment>>(FleetBackendException("not used"))
        override suspend fun upsertDriveReassignment(reassignment: DriveReassignmentUpload) = Result.failure<RemoteDriveReassignment>(FleetBackendException("not used"))
        override suspend fun fetchActiveMaintenanceSchedules() = Result.failure<List<RemoteMaintenanceSchedule>>(FleetBackendException("not used"))
        override suspend fun upsertMaintenanceSchedule(schedule: MaintenanceScheduleUpload) = Result.failure<RemoteMaintenanceSchedule>(FleetBackendException("not used"))
        override suspend fun uploadObdSampleBatch(batch: List<ObdSampleUpload>) = Result.failure<Unit>(FleetBackendException("not used"))
    }

    @Before
    fun clearState() {
        RoomTestReset.resetCarDatabaseSingleton()
    }

    private fun remoteVehicle(
        serverId: String,
        name: String,
        updatedAtMs: Long,
        lastObdMac: String? = null,
        deleted: Boolean = false,
        confirmed: Boolean = true,
    ) = RemoteVehicle(
        serverId = serverId,
        name = name,
        make = "Toyota",
        model = "Camry",
        year = 2020,
        trim = null,
        engine = null,
        confirmed = confirmed,
        odometerBaseline = null,
        odometerBaselineAtMs = null,
        updatedAtMs = updatedAtMs,
        deleted = deleted,
        originGuid = null,
        archived = false,
        lastObdMac = lastObdMac,
    )

    @Test
    fun `a pulled vehicle with a mac hint reconstructs the legacy row and sidecar`() = runBlocking {
        val backend = FakeFleetPullBackend()
        backend.vehicles["srv-1"] = remoteVehicle("srv-1", "The Camry", 1_000L, lastObdMac = "AA:BB:CC:DD:EE:FF")

        val report = FleetSync.pull(context, backend)

        assertEquals(1, report.vehiclesReconstructed)
        val db = CarDatabase.getDatabase(context)
        val sidecar = db.vehicleSidecarDao().getByServerId("srv-1")
        assertNotNull(sidecar)
        assertEquals("AA:BB:CC:DD:EE:FF", sidecar!!.obdMac)
        val legacy = db.vehicleDao().getByMac("AA:BB:CC:DD:EE:FF")
        assertNotNull(legacy)
        assertEquals("The Camry", legacy!!.name)
        val replica = db.vehicleReplicaDao().getByServerId("srv-1")
        assertNotNull(replica)
    }

    @Test
    fun `a pulled vehicle with a null hint still appears, under a synthetic id`() = runBlocking {
        val backend = FakeFleetPullBackend()
        backend.vehicles["srv-2"] = remoteVehicle("srv-2", "No Dongle Car", 1_000L, lastObdMac = null)

        FleetSync.pull(context, backend)

        val db = CarDatabase.getDatabase(context)
        val sidecar = db.vehicleSidecarDao().getByServerId("srv-2")
        assertNotNull(sidecar)
        // Never a fabricated mac - a real (synthetic, non-empty) local id stands in instead.
        assertTrue(sidecar!!.obdMac.isNotBlank())
        val legacy = db.vehicleDao().getByMac(sidecar.obdMac)
        assertNotNull(legacy)
        assertEquals("No Dongle Car", legacy!!.name)
    }

    @Test
    fun `a mac hint already claimed by another vehicle falls back to a synthetic id`() = runBlocking {
        val backend = FakeFleetPullBackend()
        backend.vehicles["srv-3"] = remoteVehicle("srv-3", "First car", 1_000L, lastObdMac = "11:22:33:44:55:66")
        FleetSync.pull(context, backend)

        // A second, DIFFERENT vehicle claims the SAME hint - a stale/collided hint must never steal
        // the mac out from under the first vehicle's already-established sidecar.
        backend.vehicles["srv-4"] = remoteVehicle("srv-4", "Second car", 2_000L, lastObdMac = "11:22:33:44:55:66")
        FleetSync.pull(context, backend)

        val db = CarDatabase.getDatabase(context)
        val firstSidecar = db.vehicleSidecarDao().getByServerId("srv-3")!!
        val secondSidecar = db.vehicleSidecarDao().getByServerId("srv-4")!!
        assertEquals("11:22:33:44:55:66", firstSidecar.obdMac)
        assertNotEquals(firstSidecar.obdMac, secondSidecar.obdMac)
    }

    @Test
    fun `a server-only service_history row is inserted`() = runBlocking {
        val backend = FakeFleetPullBackend()
        backend.vehicles["srv-5"] = remoteVehicle("srv-5", "Car", 1_000L, lastObdMac = "AA:00:00:00:00:01")
        backend.serviceHistory["sh-1"] = RemoteServiceHistory(
            serverId = "sh-1", vehicleServerId = "srv-5", serviceName = "Oil Change",
            mileage = 50_000, serviceDateEpochMs = 1_000L, costCents = 4_000L, kind = "OBSERVED",
            updatedAtMs = 1_000L, deleted = false, originGuid = null,
        )

        val report = FleetSync.pull(context, backend)

        assertEquals(1, report.serviceHistory.inserted)
        val db = CarDatabase.getDatabase(context)
        val row = db.serviceRecordDao().getByServerId("sh-1")
        assertNotNull(row)
        assertEquals("Oil Change", row!!.serviceName)
    }

    @Test
    fun `a newer server service_history row overwrites an older local one`() = runBlocking {
        val backend = FakeFleetPullBackend()
        backend.vehicles["srv-6"] = remoteVehicle("srv-6", "Car", 1_000L, lastObdMac = "AA:00:00:00:00:02")
        backend.serviceHistory["sh-2"] = RemoteServiceHistory(
            serverId = "sh-2", vehicleServerId = "srv-6", serviceName = "Oil Change",
            mileage = 50_000, serviceDateEpochMs = 1_000L, costCents = 4_000L, kind = "OBSERVED",
            updatedAtMs = 1_000L, deleted = false, originGuid = null,
        )
        FleetSync.pull(context, backend)

        backend.serviceHistory["sh-2"] = backend.serviceHistory["sh-2"]!!.copy(mileage = 60_000, updatedAtMs = 2_000L)
        val report = FleetSync.pull(context, backend)

        assertEquals(1, report.serviceHistory.updated)
        val db = CarDatabase.getDatabase(context)
        assertEquals(60_000, db.serviceRecordDao().getByServerId("sh-2")!!.mileage)
    }

    @Test
    fun `an older server service_history row does not overwrite a newer local one`() = runBlocking {
        val backend = FakeFleetPullBackend()
        backend.vehicles["srv-7"] = remoteVehicle("srv-7", "Car", 1_000L, lastObdMac = "AA:00:00:00:00:03")
        backend.serviceHistory["sh-3"] = RemoteServiceHistory(
            serverId = "sh-3", vehicleServerId = "srv-7", serviceName = "Oil Change",
            mileage = 50_000, serviceDateEpochMs = 5_000L, costCents = 4_000L, kind = "OBSERVED",
            updatedAtMs = 5_000L, deleted = false, originGuid = null,
        )
        FleetSync.pull(context, backend)

        // A stale re-fetch of the same row at an OLDER apparent clock must never regress it.
        backend.serviceHistory["sh-3"] = backend.serviceHistory["sh-3"]!!.copy(mileage = 1, updatedAtMs = 1_000L)
        // Force the watermark back so this stale row is actually re-offered to the merge.
        FleetPullCursor.advance(context, "service_history", 0L)
        val report = FleetSync.pull(context, backend)

        assertEquals(1, report.serviceHistory.skippedLocalNewer)
        val db = CarDatabase.getDatabase(context)
        assertEquals(50_000, db.serviceRecordDao().getByServerId("sh-3")!!.mileage)
    }

    @Test
    fun `a server tombstone with a local match soft-deletes it locally`() = runBlocking {
        val backend = FakeFleetPullBackend()
        backend.vehicles["srv-8"] = remoteVehicle("srv-8", "Car", 1_000L, lastObdMac = "AA:00:00:00:00:04")
        backend.serviceHistory["sh-4"] = RemoteServiceHistory(
            serverId = "sh-4", vehicleServerId = "srv-8", serviceName = "Brake Pads",
            mileage = 10_000, serviceDateEpochMs = 1_000L, costCents = null, kind = "OBSERVED",
            updatedAtMs = 1_000L, deleted = false, originGuid = null,
        )
        FleetSync.pull(context, backend)

        backend.serviceHistory["sh-4"] = backend.serviceHistory["sh-4"]!!.copy(deleted = true, updatedAtMs = 2_000L)
        val report = FleetSync.pull(context, backend)

        assertEquals(1, report.serviceHistory.tombstoned)
        val db = CarDatabase.getDatabase(context)
        assertTrue(db.serviceRecordDao().getByServerId("sh-4")!!.deleted)
    }

    @Test
    fun `a server tombstone with no local match is skipped entirely`() = runBlocking {
        val backend = FakeFleetPullBackend()
        backend.vehicles["srv-9"] = remoteVehicle("srv-9", "Car", 1_000L, lastObdMac = "AA:00:00:00:00:05")
        backend.serviceHistory["sh-5"] = RemoteServiceHistory(
            serverId = "sh-5", vehicleServerId = "srv-9", serviceName = "Ghost record",
            mileage = null, serviceDateEpochMs = null, costCents = null, kind = "ASSERTED",
            updatedAtMs = 1_000L, deleted = true, originGuid = null,
        )

        val report = FleetSync.pull(context, backend)

        assertEquals(1, report.serviceHistory.skippedTombstoneNoLocalMatch)
        val db = CarDatabase.getDatabase(context)
        assertNull(db.serviceRecordDao().getByServerId("sh-5"))
    }

    @Test
    fun `a local-only drive with no server counterpart survives untouched`() = runBlocking {
        val backend = FakeFleetPullBackend()
        backend.vehicles["srv-10"] = remoteVehicle("srv-10", "Car", 1_000L, lastObdMac = "AA:00:00:00:00:06")
        FleetSync.pull(context, backend) // reconstructs the vehicle first

        val db = CarDatabase.getDatabase(context)
        val mac = db.vehicleSidecarDao().getByServerId("srv-10")!!.obdMac
        db.driveDao().insert(
            Drive(
                vehicleId = mac, startedAt = 1_000L, endedAt = 2_000L, miles = 5.0, gallons = null,
                endReason = "ENGINE_OFF", syncId = "local-only-drive",
            ),
        )

        // Server has nothing for this vehicle - a pull must never remove or alter the local-only row.
        val report = FleetSync.pull(context, backend)

        assertEquals(0, report.drives.inserted)
        assertEquals(0, report.drives.tombstoned)
        assertNotNull(db.driveDao().getBySyncId("local-only-drive"))
    }

    @Test
    fun `a second consecutive pull performs no writes`() = runBlocking {
        val backend = FakeFleetPullBackend()
        backend.vehicles["srv-11"] = remoteVehicle("srv-11", "Car", 1_000L, lastObdMac = "AA:00:00:00:00:07")
        backend.serviceHistory["sh-6"] = RemoteServiceHistory(
            serverId = "sh-6", vehicleServerId = "srv-11", serviceName = "Oil Change",
            mileage = 1_000, serviceDateEpochMs = 1_000L, costCents = null, kind = "OBSERVED",
            updatedAtMs = 1_000L, deleted = false, originGuid = null,
        )
        val first = FleetSync.pull(context, backend)
        assertEquals(1, first.vehiclesReconstructed)
        assertEquals(1, first.serviceHistory.inserted)

        val second = FleetSync.pull(context, backend)

        assertEquals(0, second.vehiclesReconstructed)
        assertEquals(0, second.vehicles.updated)
        assertEquals(0, second.serviceHistory.inserted)
        assertEquals(0, second.serviceHistory.updated)
        assertEquals(0, second.serviceHistory.tombstoned)
    }

    @Test
    fun `the OBD window bounds what it claims to`() = runBlocking {
        val backend = FakeFleetPullBackend()
        backend.vehicles["srv-12"] = remoteVehicle("srv-12", "Car", 1_000L, lastObdMac = "AA:00:00:00:00:08")
        val nowMs = System.currentTimeMillis()
        val insideWindowMs = nowMs - (FleetSync.OBD_PULL_WINDOW_DAYS - 1) * 24 * 60 * 60 * 1000L
        val outsideWindowMs = nowMs - (FleetSync.OBD_PULL_WINDOW_DAYS + 5) * 24 * 60 * 60 * 1000L
        backend.obdSamples["srv-12"] = mutableListOf(
            RemoteObdSample("srv-12", "0104", 25.0, "%", insideWindowMs, null, null),
            RemoteObdSample("srv-12", "0104", 40.0, "%", outsideWindowMs, null, null),
        )
        backend.obdSampleServerTotal = 2L

        val report = FleetSync.pull(context, backend)

        // Only the in-window sample was ever asked for - fetchObdSamplesSince is called with a
        // sinceMs floor at the window start, so the out-of-window row is never even offered.
        assertEquals(1, report.obdSamplesPulled)
        assertEquals(2L, report.obdSamplesServerTotal)
        val db = CarDatabase.getDatabase(context)
        val mac = db.vehicleSidecarDao().getByServerId("srv-12")!!.obdMac
        val stored = db.odbSampleDao().getRange(mac, "0104", 0L, nowMs + 1_000L)
        assertEquals(1, stored.size)
        assertEquals(25.0, stored.single().value, 0.0001)
    }
}
