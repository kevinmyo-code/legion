package com.kevin.legion.engine.migration

import com.kevin.legion.data.local.CarDatabase
import com.kevin.legion.data.local.MaintenanceItem
import com.kevin.legion.data.local.RecordProvenance
import com.kevin.legion.data.local.ServiceRecord
import com.kevin.legion.data.local.Vehicle
import com.kevin.legion.engine.RecordStore
import com.kevin.legion.engine.fleet.FleetAspectSeeder
import com.kevin.legion.engine.fleet.FleetRecordBridge
import com.kevin.legion.testutil.RoomTestReset
import com.kevin.legion.vehicle.FleetEngineStore
import kotlinx.coroutines.runBlocking
import org.junit.After
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
 * Robolectric coverage for [EngineFleetServiceHistoryRetirementCopy] - engine retirement step 3
 * (ticket 16). Every test seeds the ENGINE directly (through [RecordStore], the same door
 * [EngineDataMigrationWave4] and the pre-repoint [FleetEngineStore] used to write through) rather
 * than going through today's [FleetEngineStore], because that class no longer touches the engine at
 * all after the repoint - this file's whole job is proving the copier reconciles data that was
 * ALREADY there from before this branch landed.
 */
@RunWith(RobolectricTestRunner::class)
class EngineFleetServiceHistoryRetirementCopyTest {
    private val context = RuntimeEnvironment.getApplication()
    private val db get() = CarDatabase.getDatabase(context)
    private val mac = "AA:BB:CC:DD:EE:FF"

    @Before
    fun clearState() = runBlocking {
        RoomTestReset.resetCarDatabaseSingleton()
        context.getSharedPreferences("engine_fleet_service_history_retirement", android.content.Context.MODE_PRIVATE)
            .edit().clear().commit()
        // Vehicle needs no repoint (ticket 14) - createVehicle already writes both the engine
        // record and the legacy mirror in one call, which is what this copier's own vehicle-mapping
        // walk (macByVehicleEngineId) depends on.
        FleetEngineStore.createVehicle(
            context,
            Vehicle(obdMac = mac, name = "Cherokee", make = "Jeep", model = "Cherokee", year = 1998, personaPrompt = "", confirmed = true),
        )
    }

    @After
    fun drainRoomInvalidationTracker() {
        RoomTestReset.drainArchDiskIoPool()
    }

    private fun store() = RecordStore(db.engineRecordDao(), db.fieldDefDao(), db.recordTypeDao())

    /** Seeds an engine `ServiceHistory` row directly, bypassing [FleetEngineStore] entirely - see
     * this file's own class doc for why. */
    private suspend fun seedEngineServiceHistory(
        vehicleEngineId: Long,
        serviceName: String,
        mileage: Int?,
        date: Long?,
        kind: String,
        guid: String,
        updatedAt: Long,
    ) {
        val schema = FleetAspectSeeder.ensureSeeded(context)
        store().create(
            recordTypeId = schema.serviceHistory.recordTypeId,
            fieldValues = mapOf(
                schema.serviceHistory.fieldIds.getValue(FleetAspectSeeder.FIELD_SH_VEHICLE) to vehicleEngineId,
                schema.serviceHistory.fieldIds.getValue(FleetAspectSeeder.FIELD_SH_SERVICE_NAME) to serviceName,
                schema.serviceHistory.fieldIds.getValue(FleetAspectSeeder.FIELD_SH_MILEAGE) to mileage,
                schema.serviceHistory.fieldIds.getValue(FleetAspectSeeder.FIELD_SH_SERVICE_DATE) to date,
                schema.serviceHistory.fieldIds.getValue(FleetAspectSeeder.FIELD_SH_COST) to null,
                schema.serviceHistory.fieldIds.getValue(FleetAspectSeeder.FIELD_SH_KIND) to kind,
            ),
            provenance = RecordProvenance.USER,
            now = updatedAt,
            guid = guid,
        )
    }

    private suspend fun vehicleEngineId(): Long =
        db.engineRecordDao().getByGuid(FleetRecordBridge.vehicleGuid(mac))!!.id

    // ============================================================================================
    // kind preservation - the assertion this step lives or dies on (OBSERVED vs ASSERTED).
    // ============================================================================================

    @Test
    fun `kind survives the copy exactly - OBSERVED and ASSERTED never swap or collapse`() = runBlocking {
        val vId = vehicleEngineId()
        seedEngineServiceHistory(vId, "Oil Change", mileage = 227_483, date = 1_723_000_000_000L, kind = "OBSERVED", guid = "observed-guid-1", updatedAt = 1_723_000_000_000L)
        seedEngineServiceHistory(vId, "Brake Pads", mileage = 90_000, date = null, kind = "ASSERTED", guid = FleetRecordBridge.assertedAnchorGuid(mac, "Brake Pads"), updatedAt = 1_724_000_000_000L)

        EngineFleetServiceHistoryRetirementCopy.copyIfNeeded(context)

        val observed = db.serviceRecordDao().getBySyncId("observed-guid-1")!!
        val asserted = db.serviceRecordDao().getBySyncId(FleetRecordBridge.assertedAnchorGuid(mac, "Brake Pads"))!!
        assertEquals("OBSERVED", observed.kind)
        assertEquals(227_483, observed.mileage)
        assertEquals("ASSERTED", asserted.kind)
        assertEquals(90_000, asserted.mileage)
        assertNull("the ASSERTED row's own null date must survive, never backfilled", asserted.date)
    }

    // ============================================================================================
    // Idempotent - no duplicate or overwrite on the natural key (syncId for ServiceHistory,
    // composite PK for MaintenanceSchedule).
    // ============================================================================================

    @Test
    fun `a second call copies nothing more and reports alreadyDone`() = runBlocking {
        val vId = vehicleEngineId()
        seedEngineServiceHistory(vId, "Oil Change", 227_483, 1_723_000_000_000L, "OBSERVED", "observed-guid-1", 1_723_000_000_000L)

        val first = EngineFleetServiceHistoryRetirementCopy.copyIfNeeded(context)
        val second = EngineFleetServiceHistoryRetirementCopy.copyIfNeeded(context)

        assertEquals(1, first.serviceHistoryCopied)
        assertFalse(first.alreadyDone)
        assertTrue(second.alreadyDone)
        assertEquals(1, db.serviceRecordDao().getRecentForVehicle(mac, 10).size)
    }

    @Test
    fun `a legacy row already present at the same syncId is never overwritten`() = runBlocking {
        val vId = vehicleEngineId()
        // The engine has one value...
        seedEngineServiceHistory(vId, "Oil Change", 227_483, 1_723_000_000_000L, "OBSERVED", "shared-guid", 1_723_000_000_000L)
        // ...but the legacy table already has a DIFFERENT row at the same syncId (e.g. a prior,
        // interrupted pass, or a genuine pre-existing row) - reconcile, never overwrite (ticket 15).
        db.serviceRecordDao().insert(
            ServiceRecord(vehicleId = mac, serviceName = "Oil Change", mileage = 999_999, date = 1L, syncId = "shared-guid", kind = "OBSERVED", updatedAt = 1L),
        )

        EngineFleetServiceHistoryRetirementCopy.copyIfNeeded(context)

        assertEquals("the pre-existing legacy row wins ties, untouched", 999_999, db.serviceRecordDao().getBySyncId("shared-guid")!!.mileage)
    }

    // ============================================================================================
    // Nothing is deleted from the engine.
    // ============================================================================================

    @Test
    fun `engine records still exist after the copy`() = runBlocking {
        val vId = vehicleEngineId()
        val schema = FleetAspectSeeder.ensureSeeded(context)
        seedEngineServiceHistory(vId, "Oil Change", 227_483, 1_723_000_000_000L, "OBSERVED", "observed-guid-1", 1_723_000_000_000L)

        EngineFleetServiceHistoryRetirementCopy.copyIfNeeded(context)

        assertEquals(1, db.engineRecordDao().activeByRecordType(schema.serviceHistory.recordTypeId).size)
    }

    // ============================================================================================
    // Anchor derivation from the typed tables matches what projectAnchor produced from engine
    // records - the load-bearing cross-check between the OLD derivation and the NEW one.
    // ============================================================================================

    @Test
    fun `the derived anchor from the copied legacy rows matches FleetRecordBridge_projectAnchor's own derivation from the engine`() = runBlocking {
        val vId = vehicleEngineId()
        // An older OBSERVED row, then a newer ASSERTED anchor that disagrees on mileage and omits
        // the date - the exact "most-recently-stated row wins wholesale" case projectAnchor's own
        // doc calls out.
        seedEngineServiceHistory(vId, "Oil Change", 227_000, 1_650_000_000_000L, "OBSERVED", "observed-guid-1", 1_650_000_000_000L)
        seedEngineServiceHistory(vId, "Oil Change", 227_483, null, "ASSERTED", FleetRecordBridge.assertedAnchorGuid(mac, "Oil Change"), 1_723_000_000_000L)
        FleetEngineStore.upsertNewItem(context, MaintenanceItem(vehicleId = mac, serviceName = "Oil Change", intervalMiles = 5000))

        // The OLD derivation, straight off the engine records this test seeded.
        val schema = FleetAspectSeeder.ensureSeeded(context)
        val engineHistory = db.engineRecordDao().activeByRecordType(schema.serviceHistory.recordTypeId)
            .filter { FleetRecordBridge.serviceHistoryVehicleId(it, schema.serviceHistory.fieldIds) == vId }
        val (engineMileage, engineDate) = FleetRecordBridge.projectAnchor(engineHistory, schema.serviceHistory.fieldIds)

        // The NEW derivation, read through FleetEngineStore after the repoint copy.
        val derived = FleetEngineStore.get(context, mac, "Oil Change")!!

        assertEquals(engineMileage, derived.lastDoneMileage)
        assertEquals(engineDate, derived.lastDoneDate)
        assertEquals("the most-recently-stated row's own mileage wins outright", 227_483, derived.lastDoneMileage)
        assertNull("the most-recently-stated row's own null date must stand", derived.lastDoneDate)
    }

    // ============================================================================================
    // MaintenanceSchedule - natural-key gap fill, lastDoneMileage/lastDoneDate land NULL (derived,
    // not stored).
    // ============================================================================================

    @Test
    fun `a MaintenanceSchedule engine record gap-fills into maintenance_items with the anchor left null`() = runBlocking {
        val vId = vehicleEngineId()
        val schema = FleetAspectSeeder.ensureSeeded(context)
        store().create(
            recordTypeId = schema.maintenanceSchedule.recordTypeId,
            fieldValues = mapOf(
                schema.maintenanceSchedule.fieldIds.getValue(FleetAspectSeeder.FIELD_MS_VEHICLE) to vId,
                schema.maintenanceSchedule.fieldIds.getValue(FleetAspectSeeder.FIELD_MS_SERVICE_NAME) to "Tire Rotation",
                schema.maintenanceSchedule.fieldIds.getValue(FleetAspectSeeder.FIELD_MS_INTERVAL_MILES) to 6000,
                schema.maintenanceSchedule.fieldIds.getValue(FleetAspectSeeder.FIELD_MS_INTERVAL_MONTHS) to null,
                schema.maintenanceSchedule.fieldIds.getValue(FleetAspectSeeder.FIELD_MS_INTERVAL_SOURCE) to "CONFIRMED",
                schema.maintenanceSchedule.fieldIds.getValue(FleetAspectSeeder.FIELD_MS_NEVER_DONE) to false,
            ),
            provenance = RecordProvenance.USER,
            guid = FleetRecordBridge.scheduleGuid(mac, "Tire Rotation"),
        )

        EngineFleetServiceHistoryRetirementCopy.copyIfNeeded(context)

        val row = db.maintenanceItemDao().get(mac, "Tire Rotation")!!
        assertEquals(6000, row.intervalMiles)
        assertEquals("CONFIRMED", row.intervalSource)
        assertNull("derived, not stored - see this copier's own class doc", row.lastDoneMileage)
        assertNull(row.lastDoneDate)
    }
}
