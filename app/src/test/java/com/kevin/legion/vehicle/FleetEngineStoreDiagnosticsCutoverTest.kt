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
 * The `code_events`/`code_clear_events` step of the fleet cutover (backend-erp ticket 26 step 4,
 * `.scratch/backend-erp/issues/26-the-fleet-cutover-for-real.md`), built together because they
 * share both a shape (append-only, no engine-record counterpart, `syncId` identity) and a producer
 * pattern (one live call site each). `oil_analyses`, the third of the trio, is deliberately absent
 * from this test - it has no live write entry point to cut over at all (see [FleetEngineStore]'s
 * own class doc for the full reasoning), so there is nothing here to exercise.
 *
 * Exercised entirely through [FleetEngineStore.backendOverride] and an in-memory
 * [FakeDiagnosticsBackend], never a real SupabaseClient - same posture as
 * [FleetEngineStoreDrivesCutoverTest].
 */
@RunWith(RobolectricTestRunner::class)
class FleetEngineStoreDiagnosticsCutoverTest {
    private val context = RuntimeEnvironment.getApplication()
    private val db get() = CarDatabase.getDatabase(context)

    /** Fails any call this step must not make - every fleet table besides `vehicles`/`code_events`/
     * `code_clear_events`. */
    private class FakeDiagnosticsBackend : FleetBackend {
        val codeEvents = mutableMapOf<String, RemoteCodeEvent>()
        val codeClearEvents = mutableMapOf<String, RemoteCodeClearEvent>()
        private var counter = 0
        var clock = 1_000L
        var nextCodeEventPushFails = false
        var nextCodeClearEventPushFails = false

        override suspend fun fetchActiveVehicles(): Result<List<RemoteVehicle>> = error("out of scope")
        override suspend fun uploadMigratedVehicle(vehicle: MigratedVehicle): Result<Boolean> = error("out of scope")
        override suspend fun upsertVehicle(vehicle: VehicleUpload): Result<RemoteVehicle> = error("out of scope")
        override suspend fun fetchActiveServiceHistory(): Result<List<RemoteServiceHistory>> = error("out of scope")
        override suspend fun uploadMigratedServiceHistory(history: MigratedServiceHistory): Result<Boolean> = error("out of scope")
        override suspend fun upsertServiceHistory(history: ServiceHistoryUpload): Result<RemoteServiceHistory> = error("out of scope")

        override suspend fun fetchActiveDrives(): Result<List<RemoteDrive>> = error("out of scope")
        override suspend fun upsertDrive(drive: DriveUpload): Result<RemoteDrive> = error("out of scope")

        override suspend fun fetchActiveCodeEvents(): Result<List<RemoteCodeEvent>> = Result.success(codeEvents.values.toList())

        override suspend fun upsertCodeEvent(event: CodeEventUpload): Result<RemoteCodeEvent> {
            if (nextCodeEventPushFails) {
                nextCodeEventPushFails = false
                return Result.failure(FleetBackendException("simulated network failure"))
            }
            val existing = codeEvents[event.syncId]
            val row = RemoteCodeEvent(
                serverId = existing?.serverId ?: "code-event-${++counter}",
                syncId = event.syncId,
                vehicleServerId = event.vehicleServerId,
                occurredAtMs = event.occurredAtMs,
                mileage = event.mileage,
                codesJson = event.codesJson,
                freezeFrameJson = event.freezeFrameJson,
                provenance = event.provenance,
                updatedAtMs = ++clock,
                deleted = false,
            )
            codeEvents[row.syncId] = row
            return Result.success(row)
        }

        override suspend fun fetchActiveCodeClearEvents(): Result<List<RemoteCodeClearEvent>> =
            Result.success(codeClearEvents.values.toList())

        override suspend fun upsertCodeClearEvent(event: CodeClearEventUpload): Result<RemoteCodeClearEvent> {
            if (nextCodeClearEventPushFails) {
                nextCodeClearEventPushFails = false
                return Result.failure(FleetBackendException("simulated network failure"))
            }
            val existing = codeClearEvents[event.syncId]
            val row = RemoteCodeClearEvent(
                serverId = existing?.serverId ?: "code-clear-event-${++counter}",
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
            codeClearEvents[row.syncId] = row
            return Result.success(row)
        }

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

    // ---------------------------------------------------------------------------------- CodeEvent

    @Test
    fun `an unconfigured install never touches the code-event push`() = runBlocking {
        db.vehicleDao().upsert(vehicle(macA, "A"))
        val id = FleetEngineStore.recordCodeEvent(context, macA, 1_000L, 120_000, "[\"P0420\"]", "")
        assertNotNull(db.codeEventDao().getById(id))
        assertNull(db.codeEventDao().getById(id)!!.serverId)
    }

    @Test
    fun `a first configured code-event push records what the server returns`() = runBlocking {
        val backend = FakeDiagnosticsBackend()
        FleetEngineStore.backendOverride = backend
        syncedVehicle(macA, "A", "vehicle-A")

        val id = FleetEngineStore.recordCodeEvent(context, macA, 1_000L, 120_000, "[\"P0420\"]", "")

        assertEquals(1, backend.codeEvents.size)
        val local = db.codeEventDao().getById(id)!!
        assertNotNull("a synced code event must have its serverId recorded", local.serverId)
        assertEquals(backend.codeEvents.values.single().serverId, local.serverId)
    }

    @Test
    fun `a re-run of the code-event push updates rather than duplicating, and never remints the local id`() = runBlocking {
        val backend = FakeDiagnosticsBackend()
        FleetEngineStore.backendOverride = backend
        syncedVehicle(macA, "A", "vehicle-A")

        val id = FleetEngineStore.recordCodeEvent(context, macA, 1_000L, 120_000, "[\"P0420\"]", "")
        val firstServerId = db.codeEventDao().getById(id)!!.serverId

        // A code event has no domain-level edit call (this table has no update path at all) - the
        // only way to exercise a re-run is a direct retry of the SAME local row's push. See
        // FleetEngineStore.syncCodeEventToServer's own doc for why it is `internal`.
        FleetEngineStore.syncCodeEventToServer(context, macA, id)
        FleetEngineStore.syncCodeEventToServer(context, macA, id)

        assertEquals("a repost must never mint a second server row", 1, backend.codeEvents.size)
        assertEquals(firstServerId, backend.codeEvents.values.single().serverId)
        assertEquals("the local row id must never move across a re-push", id, db.codeEventDao().getById(id)!!.id)
    }

    @Test
    fun `a FAILED code-event push leaves the local write intact and is logged, not thrown`() = runBlocking {
        val backend = FakeDiagnosticsBackend()
        backend.nextCodeEventPushFails = true
        FleetEngineStore.backendOverride = backend
        syncedVehicle(macA, "A", "vehicle-A")

        val id = FleetEngineStore.recordCodeEvent(context, macA, 1_000L, 120_000, "[\"P0420\"]", "")

        assertNotNull("the local write must succeed regardless of the server failure", db.codeEventDao().getById(id))
        assertNull("a failed push must not fabricate a serverId", db.codeEventDao().getById(id)!!.serverId)
        assertEquals(0, backend.codeEvents.size)
    }

    @Test
    fun `a code event with no server mapping yet is a no-op push, not a crash`() = runBlocking {
        val backend = FakeDiagnosticsBackend()
        FleetEngineStore.backendOverride = backend
        // Configured, but this car has never itself synced - no VehicleSidecar row.
        db.vehicleDao().upsert(vehicle(macA, "A"))

        val id = FleetEngineStore.recordCodeEvent(context, macA, 1_000L, 120_000, "[\"P0420\"]", "")

        assertNotNull(db.codeEventDao().getById(id))
        assertEquals(0, backend.codeEvents.size)
    }

    // ----------------------------------------------------------------------------- CodeClearEvent

    @Test
    fun `an unconfigured install never touches the code-clear-event push`() = runBlocking {
        db.vehicleDao().upsert(vehicle(macA, "A"))
        val id = FleetEngineStore.recordCodeClearEvent(
            context, macA, 1_000L, 120_000, "[\"P0420\"]", "", "[]", "CLEARED", "44",
        )
        assertNotNull(db.codeClearEventDao().getById(id))
        assertNull(db.codeClearEventDao().getById(id)!!.serverId)
    }

    @Test
    fun `a first configured code-clear-event push records what the server returns`() = runBlocking {
        val backend = FakeDiagnosticsBackend()
        FleetEngineStore.backendOverride = backend
        syncedVehicle(macA, "A", "vehicle-A")

        val id = FleetEngineStore.recordCodeClearEvent(
            context, macA, 1_000L, 120_000, "[\"P0420\"]", "", "[]", "CLEARED", "44",
        )

        assertEquals(1, backend.codeClearEvents.size)
        val local = db.codeClearEventDao().getById(id)!!
        assertNotNull("a synced code-clear event must have its serverId recorded", local.serverId)
        assertEquals(backend.codeClearEvents.values.single().serverId, local.serverId)
    }

    @Test
    fun `a re-run of the code-clear-event push updates rather than duplicating, and never remints the local id`() = runBlocking {
        val backend = FakeDiagnosticsBackend()
        FleetEngineStore.backendOverride = backend
        syncedVehicle(macA, "A", "vehicle-A")

        val id = FleetEngineStore.recordCodeClearEvent(
            context, macA, 1_000L, 120_000, "[\"P0420\"]", "", "[]", "CLEARED", "44",
        )
        val firstServerId = db.codeClearEventDao().getById(id)!!.serverId

        // Same "no domain-level edit call" shape as CodeEvent - a direct retry of the same local
        // row is the only way to exercise a re-run. See FleetEngineStore.syncCodeClearEventToServer's
        // own doc for why it is `internal`.
        FleetEngineStore.syncCodeClearEventToServer(context, macA, id)
        FleetEngineStore.syncCodeClearEventToServer(context, macA, id)

        assertEquals("a repost must never mint a second server row", 1, backend.codeClearEvents.size)
        assertEquals(firstServerId, backend.codeClearEvents.values.single().serverId)
        assertEquals("the local row id must never move across a re-push", id, db.codeClearEventDao().getById(id)!!.id)
    }

    @Test
    fun `a FAILED code-clear-event push leaves the local write intact and is logged, not thrown`() = runBlocking {
        val backend = FakeDiagnosticsBackend()
        backend.nextCodeClearEventPushFails = true
        FleetEngineStore.backendOverride = backend
        syncedVehicle(macA, "A", "vehicle-A")

        val id = FleetEngineStore.recordCodeClearEvent(
            context, macA, 1_000L, 120_000, "[\"P0420\"]", "", "[]", "CLEARED", "44",
        )

        assertNotNull("the local write must succeed regardless of the server failure", db.codeClearEventDao().getById(id))
        assertNull("a failed push must not fabricate a serverId", db.codeClearEventDao().getById(id)!!.serverId)
        assertEquals(0, backend.codeClearEvents.size)
    }

    @Test
    fun `a code-clear event with no server mapping yet is a no-op push, not a crash`() = runBlocking {
        val backend = FakeDiagnosticsBackend()
        FleetEngineStore.backendOverride = backend
        // Configured, but this car has never itself synced - no VehicleSidecar row.
        db.vehicleDao().upsert(vehicle(macA, "A"))

        val id = FleetEngineStore.recordCodeClearEvent(
            context, macA, 1_000L, 120_000, "[\"P0420\"]", "", "[]", "CLEARED", "44",
        )

        assertNotNull(db.codeClearEventDao().getById(id))
        assertEquals(0, backend.codeClearEvents.size)
    }

    @Test
    fun `reads already serve the table both channels write to - no replica repoint needed`() = runBlocking {
        val backend = FakeDiagnosticsBackend()
        FleetEngineStore.backendOverride = backend
        syncedVehicle(macA, "A", "vehicle-A")

        FleetEngineStore.recordCodeEvent(context, macA, 1_000L, 120_000, "[\"P0420\"]", "")
        FleetEngineStore.recordCodeClearEvent(
            context, macA, 2_000L, 120_500, "[\"P0420\"]", "", "[]", "CLEARED", "44",
        )

        assertEquals(1, db.codeEventDao().getAll(macA).size)
        assertEquals(1, db.codeClearEventDao().getAll(macA).size)
    }
}
