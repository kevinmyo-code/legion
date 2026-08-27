package com.kevin.legion.backend

import com.kevin.legion.data.local.CarDatabase
import com.kevin.legion.data.local.Drive
import com.kevin.legion.data.local.RecordProvenance
import com.kevin.legion.data.local.Vehicle
import com.kevin.legion.engine.RecordStore
import com.kevin.legion.engine.fleet.FleetAspectSeeder
import com.kevin.legion.engine.fleet.FleetRecordBridge
import com.kevin.legion.testutil.RoomTestReset
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
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
        var clock = 1_000L
        private var vehicleCounter = 0
        private var serviceHistoryCounter = 0
        private var driveCounter = 0

        /** Set to make the NEXT [uploadMigratedVehicle] call fail - the short-circuit test's hook. */
        var failNextVehicleUpload = false

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
            )
            return Result.success(true)
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
        val obdMac = "FE:ED:FA:CE:00:02"
        val vehicleEngineId = createEngineVehicle(obdMac)
        createEngineServiceHistory(vehicleEngineId)
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
}
