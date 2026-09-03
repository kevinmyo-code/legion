package com.kevin.legion.backend

import com.kevin.legion.data.local.CarDatabase
import com.kevin.legion.data.local.OdbSample
import com.kevin.legion.data.local.RecordProvenance
import com.kevin.legion.data.local.Vehicle
import com.kevin.legion.engine.RecordStore
import com.kevin.legion.engine.fleet.FleetAspectSeeder
import com.kevin.legion.engine.fleet.FleetRecordBridge
import com.kevin.legion.testutil.RoomTestReset
import kotlinx.coroutines.runBlocking
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

        /** Settable so a [maybeAutoRun][ObdSampleReconcile.maybeAutoRun]-adjacent test can assert
         *  the reported [ObdSampleReconcile.Report.serverCountAfter] without a live HEAD count. */
        var serverCount = 0L

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

        override suspend fun fetchActiveMaintenanceSchedules(): Result<List<RemoteMaintenanceSchedule>> = error("out of scope")
        override suspend fun upsertMaintenanceSchedule(schedule: MaintenanceScheduleUpload): Result<RemoteMaintenanceSchedule> = error("out of scope")
        override suspend fun uploadObdSampleBatch(batch: List<ObdSampleUpload>): Result<Unit> {
            if (failNextBatch) {
                failNextBatch = false
                return Result.failure(FleetBackendException("simulated transport failure"))
            }
            batches.add(batch)
            return Result.success(Unit)
        }

        override suspend fun countObdSamples(): Result<Long> = Result.success(serverCount)
    }

    @Before
    fun clearState() {
        RoomTestReset.resetCarDatabaseSingleton()
        SupabaseConfig.clear(context)
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

    /** Same fixture shape as [FleetReconcileTest]'s own `createEngineVehicle` - a real engine
     * `Vehicle` record keyed by [FleetRecordBridge.vehicleGuid], so [FleetReconcile]'s own
     * rejection predicate has something real to evaluate rather than an invented shortcut. `year =
     * 0` reproduces ticket 30's actual on-device placeholder vehicles exactly. */
    private suspend fun createRejectableEngineVehicle(obdMac: String, year: Int = 0) {
        val db = CarDatabase.getDatabase(context)
        val sch = FleetAspectSeeder.ensureSeeded(context)
        val store = RecordStore(db.engineRecordDao(), db.fieldDefDao(), db.recordTypeDao())
        store.create(
            sch.vehicle.recordTypeId,
            mapOf(
                sch.vehicle.fieldIds.getValue(FleetAspectSeeder.FIELD_NAME) to "",
                sch.vehicle.fieldIds.getValue(FleetAspectSeeder.FIELD_MAKE) to "",
                sch.vehicle.fieldIds.getValue(FleetAspectSeeder.FIELD_MODEL) to "",
                sch.vehicle.fieldIds.getValue(FleetAspectSeeder.FIELD_YEAR) to year,
                sch.vehicle.fieldIds.getValue(FleetAspectSeeder.FIELD_TRIM) to null,
                sch.vehicle.fieldIds.getValue(FleetAspectSeeder.FIELD_ENGINE) to null,
                sch.vehicle.fieldIds.getValue(FleetAspectSeeder.FIELD_CONFIRMED) to false,
                sch.vehicle.fieldIds.getValue(FleetAspectSeeder.FIELD_ODOMETER_BASELINE) to null,
                sch.vehicle.fieldIds.getValue(FleetAspectSeeder.FIELD_ODOMETER_BASELINE_AT) to null,
            ),
            RecordProvenance.USER,
            guid = FleetRecordBridge.vehicleGuid(obdMac),
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

    @Test
    fun `a sample whose vehicle is permanently unexportable is skipped, counted, named, and the cursor advances past it`() = runBlocking {
        val obdMac = "66:1E:11:0E:82:0E" // ticket 30's real year-0 placeholder
        insertLegacyVehicle(obdMac)
        createRejectableEngineVehicle(obdMac, year = 0)
        val backend = FakeObdFleetBackend() // never migrated, and never WILL be - year 0 is rejected
        val lastId = insertSample(obdMac)

        val report = ObdSampleReconcile.run(context, backend).getOrThrow()

        assertTrue(backend.batches.isEmpty())
        assertTrue(report.skippedUnresolvedVehicle.isEmpty()) // this is NOT the "run Fleet first" bucket
        assertEquals(1, report.skippedPermanentlyUnexportableSampleCount)
        assertEquals(1, report.skippedPermanentlyUnexportableVehicles.size)
        assertTrue(report.skippedPermanentlyUnexportableVehicles.single().contains(obdMac))
        assertTrue(report.skippedPermanentlyUnexportableVehicles.single().contains("1885"))
        // The cursor must move past the dead sample, or every future run re-discovers the same
        // permanent dead end forever.
        assertEquals(lastId, report.cursorAt)
    }

    @Test
    fun `a sample whose vehicle is merely not yet migrated still halts with the existing wording`() = runBlocking {
        val obdMac = "AA:BB:CC:DD:EE:05"
        insertLegacyVehicle(obdMac)
        // An engine Vehicle record that WOULD pass the rejection check - just not uploaded yet.
        createRejectableEngineVehicle(obdMac, year = 1998)
        val backend = FakeObdFleetBackend() // no matching RemoteVehicle
        insertSample(obdMac)

        val report = ObdSampleReconcile.run(context, backend).getOrThrow()

        assertTrue(backend.batches.isEmpty())
        assertTrue(report.skippedPermanentlyUnexportableVehicles.isEmpty())
        assertEquals(0, report.skippedPermanentlyUnexportableSampleCount)
        assertEquals(1, report.skippedUnresolvedVehicle.size)
        assertTrue(report.skippedUnresolvedVehicle.single().contains("not yet migrated"))
    }

    @Test
    fun `a run with both a dead vehicle and a temporary one skips the dead one and halts on the temporary one`() = runBlocking {
        val deadObdMac = "66:1E:11:0E:82:0E"
        val tempObdMac = "AA:BB:CC:DD:EE:06"
        insertLegacyVehicle(deadObdMac)
        insertLegacyVehicle(tempObdMac)
        createRejectableEngineVehicle(deadObdMac, year = 0)
        createRejectableEngineVehicle(tempObdMac, year = 1998)
        val backend = FakeObdFleetBackend() // neither vehicle is on the server
        insertSample(deadObdMac, ts = 1_000L)
        insertSample(tempObdMac, ts = 2_000L)

        val report = ObdSampleReconcile.run(context, backend).getOrThrow()

        assertEquals(1, report.skippedPermanentlyUnexportableSampleCount)
        assertTrue(report.skippedPermanentlyUnexportableVehicles.single().contains(deadObdMac))
        assertEquals(1, report.skippedUnresolvedVehicle.size)
        assertTrue(report.skippedUnresolvedVehicle.single().contains(tempObdMac))
    }

    @Test
    fun `a re-run after a halt resumes rather than restarting`() = runBlocking {
        val resolvedObdMac = "AA:BB:CC:DD:EE:07"
        val stuckObdMac = "AA:BB:CC:DD:EE:08"
        insertLegacyVehicle(resolvedObdMac)
        insertLegacyVehicle(stuckObdMac)
        createRejectableEngineVehicle(stuckObdMac, year = 1998)
        val backend = FakeObdFleetBackend().apply { vehicles[resolvedObdMac] = remoteVehicleFor(resolvedObdMac, "vehicle-1") }
        insertSample(resolvedObdMac, ts = 1_000L)
        insertSample(stuckObdMac, ts = 2_000L)

        val first = ObdSampleReconcile.run(context, backend).getOrThrow()
        assertEquals(1, first.uploaded)
        assertEquals(1, first.skippedUnresolvedVehicle.size)

        // Nothing changes about the stuck vehicle server-side, so a second run must not re-upload
        // the resolved sample and must report the same halt, not restart from zero.
        val second = ObdSampleReconcile.run(context, backend).getOrThrow()
        assertEquals(0, second.uploaded)
        assertEquals(1, second.skippedUnresolvedVehicle.size)
        assertEquals(first.cursorAt, second.cursorAt)
    }

    // --- ObdSampleReconcile.maybeAutoRun's two guard halves ----------------------------------

    @Test
    fun `autoRunGate returns null when Supabase is not configured`() {
        // clearState's SupabaseConfig.clear(context) already left this unconfigured.
        assertNull(ObdSampleReconcile.autoRunGate(context))
    }

    /**
     * Exercises [ObdSampleReconcile.isThrottled] directly rather than [ObdSampleReconcile.autoRunGate]
     * as a whole: driving [autoRunGate] with a CONFIGURED Supabase project throws
     * `IllegalStateException` under Robolectric (`SettingsSessionManager` needs a platform Settings
     * implementation this JVM sandbox does not provide - confirmed empirically, not assumed), the
     * same reason [com.kevin.legion.backend.BodyOutboxDrainTest] only ever calls
     * `SupabaseClientProvider.get` unconfigured. [isThrottled]'s own doc explains why it is pulled
     * out as a pure predicate for exactly this reason. This still proves the property the ticket
     * asks for - a reservation at time T throttles a call shortly after T and releases one once
     * the floor elapses - just via [ObdSampleReconcile.setLastAutoRunAtForTest] rather than a live
     * [ObdSampleReconcile.maybeAutoRun] round trip.
     */
    @Test
    fun `isThrottled is true immediately after a reservation, false once the floor elapses`() {
        val reservedAt = 10_000_000L
        ObdSampleReconcile.setLastAutoRunAtForTest(reservedAt)

        assertTrue(ObdSampleReconcile.isThrottled(reservedAt + 1_000L)) // 1s later - inside the 5-minute floor
        assertTrue(ObdSampleReconcile.isThrottled(reservedAt)) // the same instant
        assertFalse(ObdSampleReconcile.isThrottled(reservedAt + 6 * 60_000L)) // 6 minutes later - past the floor
    }

    private fun fakeGateway(userId: String?) = object : SupabaseAuthGateway {
        override suspend fun signInWithPassword(email: String, password: String) = Unit
        override suspend fun signOut() = Unit
        override fun currentUserId(): String? = userId
        override suspend fun awaitSessionReady(timeoutMillis: Long): Boolean = true
        override suspend fun householdRosterSize(): Int = 0
    }

    @Test
    fun `runIfSignedIn no-ops for a genuinely signed-out session, never attempting an upload`() = runBlocking {
        val obdMac = "AA:BB:CC:DD:EE:09"
        insertLegacyVehicle(obdMac)
        val backend = FakeObdFleetBackend().apply { vehicles[obdMac] = remoteVehicleFor(obdMac, "vehicle-1") }
        insertSample(obdMac)
        val auth = SupabaseAuth(context, gatewayProvider = { fakeGateway(null) })

        ObdSampleReconcile.runIfSignedIn(context, backend, auth)

        assertTrue(backend.batches.isEmpty())
        assertEquals(0L, ObdSampleUploadCursor.lastUploadedId(context))
    }

    @Test
    fun `runIfSignedIn runs once when signed in`() = runBlocking {
        val obdMac = "AA:BB:CC:DD:EE:10"
        insertLegacyVehicle(obdMac)
        val backend = FakeObdFleetBackend().apply { vehicles[obdMac] = remoteVehicleFor(obdMac, "vehicle-1") }
        insertSample(obdMac)
        val auth = SupabaseAuth(context, gatewayProvider = { fakeGateway("user-1") })

        ObdSampleReconcile.runIfSignedIn(context, backend, auth)

        assertEquals(1, backend.batches.sumOf { it.size })
    }
}
