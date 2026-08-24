package com.kevin.legion.engine.migration

import com.kevin.legion.data.local.CarDatabase
import com.kevin.legion.data.local.DeletePolicy
import com.kevin.legion.data.local.MaintenanceItem
import com.kevin.legion.data.local.RecordProvenance
import com.kevin.legion.data.local.ServiceRecord
import com.kevin.legion.data.local.Vehicle
import com.kevin.legion.engine.FieldConfig
import com.kevin.legion.engine.PayloadCodec
import com.kevin.legion.engine.fleet.FleetAspectSeeder
import com.kevin.legion.testutil.RoomTestReset
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
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
 * Robolectric coverage for [EngineDataMigrationWave4] - Wave 4's own owed tests
 * (`.scratch/aspect-engine/issues/21-migration-waves.md` point 5: idempotent, count-exact,
 * content-faithful incl. cents, reference integrity vehicle->children, BLOCK delete policy
 * exercised against a real [com.kevin.legion.engine.RecordStore], partial-failure flag). Same
 * shape as [EngineDataMigrationWave2Test]/[EngineDataMigrationWave3Test], plus coverage specific
 * to this wave's own headline move: the `ServiceHistory` `OBSERVED`/`ASSERTED` unification.
 */
@RunWith(RobolectricTestRunner::class)
class EngineDataMigrationWave4Test {
    private val context = RuntimeEnvironment.getApplication()
    private val db get() = CarDatabase.getDatabase(context)

    @Before
    fun clearState() {
        RoomTestReset.resetCarDatabaseSingleton()
        context.getSharedPreferences("engine_migration_wave4", android.content.Context.MODE_PRIVATE)
            .edit().clear().commit()
    }

    private suspend fun seedVehicle(
        mac: String = "AA:BB:CC:DD:EE:FF",
        name: String = "The Jeep",
        make: String = "Jeep",
        model: String = "Cherokee",
        year: Int = 1998,
    ) {
        db.vehicleDao().upsertStamped(Vehicle(obdMac = mac, name = name, make = make, model = model, year = year, personaPrompt = ""))
    }

    // --------------------------------------------------------------------------------- count-exact

    @Test
    fun `count-exact - one Vehicle, one OBSERVED ServiceHistory, one MaintenanceSchedule`() = runBlocking {
        seedVehicle()
        db.serviceRecordDao().insert(ServiceRecord(vehicleId = "AA:BB:CC:DD:EE:FF", serviceName = "Oil Change", mileage = 100_000, date = 1L, costCents = 4500L))
        db.maintenanceItemDao().upsertStamped(MaintenanceItem(vehicleId = "AA:BB:CC:DD:EE:FF", serviceName = "Tire Rotation", intervalMiles = 6000))

        val result = EngineDataMigrationWave4.copyFleetIfNeeded(context)

        assertEquals(1, result.vehiclesCopied)
        assertEquals(1, result.serviceHistoryCopied)
        assertEquals(1, result.maintenanceScheduleCopied)

        val schema = FleetAspectSeeder.ensureSeeded(context)
        assertEquals(1, db.engineRecordDao().activeByRecordType(schema.vehicle.recordTypeId).size)
        assertEquals(1, db.engineRecordDao().activeByRecordType(schema.serviceHistory.recordTypeId).size)
        assertEquals(1, db.engineRecordDao().activeByRecordType(schema.maintenanceSchedule.recordTypeId).size)
    }

    // ------------------------------------------------------------------------------ content-faithful

    @Test
    fun `content-faithful - Vehicle fields, odometer, and provenance are USER`() = runBlocking {
        db.vehicleDao().upsertStamped(
            Vehicle(
                obdMac = "AA:BB:CC:DD:EE:FF", name = "The Jeep", make = "Jeep", model = "Cherokee", year = 1998,
                personaPrompt = "", odometerBaseline = 227_483, odometerBaselineAt = 555L, trim = "Sport", engine = "4.0L I6", confirmed = true,
            ),
        )

        EngineDataMigrationWave4.copyFleetIfNeeded(context)

        val schema = FleetAspectSeeder.ensureSeeded(context)
        val record = db.engineRecordDao().activeByRecordType(schema.vehicle.recordTypeId).single()
        val payload = JSONObject(record.payload)

        assertEquals("The Jeep", PayloadCodec.readString(payload, schema.vehicle.fieldIds.getValue(FleetAspectSeeder.FIELD_NAME)))
        assertEquals("Jeep", PayloadCodec.readString(payload, schema.vehicle.fieldIds.getValue(FleetAspectSeeder.FIELD_MAKE)))
        assertEquals("Cherokee", PayloadCodec.readString(payload, schema.vehicle.fieldIds.getValue(FleetAspectSeeder.FIELD_MODEL)))
        assertEquals(1998.0, payload.getDouble(PayloadCodec.key(schema.vehicle.fieldIds.getValue(FleetAspectSeeder.FIELD_YEAR))), 0.0001)
        assertEquals("Sport", PayloadCodec.readString(payload, schema.vehicle.fieldIds.getValue(FleetAspectSeeder.FIELD_TRIM)))
        assertEquals("4.0L I6", PayloadCodec.readString(payload, schema.vehicle.fieldIds.getValue(FleetAspectSeeder.FIELD_ENGINE)))
        assertTrue(payload.getBoolean(PayloadCodec.key(schema.vehicle.fieldIds.getValue(FleetAspectSeeder.FIELD_CONFIRMED))))
        assertEquals(227_483.0, payload.getDouble(PayloadCodec.key(schema.vehicle.fieldIds.getValue(FleetAspectSeeder.FIELD_ODOMETER_BASELINE))), 0.0001)
        assertEquals(555L, PayloadCodec.readLong(payload, schema.vehicle.fieldIds.getValue(FleetAspectSeeder.FIELD_ODOMETER_BASELINE_AT)))
        assertEquals(RecordProvenance.USER, record.provenance)
    }

    @Test
    fun `content-faithful - OBSERVED ServiceHistory carries cost cents exactly, and costCents is the promoted amount`() = runBlocking {
        seedVehicle()
        db.serviceRecordDao().insert(
            ServiceRecord(vehicleId = "AA:BB:CC:DD:EE:FF", serviceName = "Oil Change", mileage = 227_374, date = 12L, costCents = 128_86L),
        )

        EngineDataMigrationWave4.copyFleetIfNeeded(context)

        val schema = FleetAspectSeeder.ensureSeeded(context)
        val record = db.engineRecordDao().activeByRecordType(schema.serviceHistory.recordTypeId).single()
        val payload = JSONObject(record.payload)

        assertEquals("Oil Change", PayloadCodec.readString(payload, schema.serviceHistory.fieldIds.getValue(FleetAspectSeeder.FIELD_SH_SERVICE_NAME)))
        assertEquals(227_374.0, payload.getDouble(PayloadCodec.key(schema.serviceHistory.fieldIds.getValue(FleetAspectSeeder.FIELD_SH_MILEAGE))), 0.0001)
        assertEquals(12L, PayloadCodec.readLong(payload, schema.serviceHistory.fieldIds.getValue(FleetAspectSeeder.FIELD_SH_SERVICE_DATE)))
        assertEquals(128_86L, PayloadCodec.readLong(payload, schema.serviceHistory.fieldIds.getValue(FleetAspectSeeder.FIELD_SH_COST)))
        assertEquals(128_86L, record.amountCents)
        assertEquals("OBSERVED", PayloadCodec.readString(payload, schema.serviceHistory.fieldIds.getValue(FleetAspectSeeder.FIELD_SH_KIND)))
    }

    @Test
    fun `reference integrity - ServiceHistory and MaintenanceSchedule vehicle fields point at the real new Vehicle record id`() = runBlocking {
        seedVehicle()
        db.serviceRecordDao().insert(ServiceRecord(vehicleId = "AA:BB:CC:DD:EE:FF", serviceName = "Oil Change", mileage = 1000, date = 1L))
        db.maintenanceItemDao().upsertStamped(MaintenanceItem(vehicleId = "AA:BB:CC:DD:EE:FF", serviceName = "Oil Change", intervalMiles = 5000))

        EngineDataMigrationWave4.copyFleetIfNeeded(context)

        val schema = FleetAspectSeeder.ensureSeeded(context)
        val vehicleRecord = db.engineRecordDao().activeByRecordType(schema.vehicle.recordTypeId).single()
        val shRecord = db.engineRecordDao().activeByRecordType(schema.serviceHistory.recordTypeId).single()
        val msRecord = db.engineRecordDao().activeByRecordType(schema.maintenanceSchedule.recordTypeId).single()

        assertEquals(vehicleRecord.id, PayloadCodec.readReferenceId(JSONObject(shRecord.payload), schema.serviceHistory.fieldIds.getValue(FleetAspectSeeder.FIELD_SH_VEHICLE)))
        assertEquals(vehicleRecord.id, PayloadCodec.readReferenceId(JSONObject(msRecord.payload), schema.maintenanceSchedule.fieldIds.getValue(FleetAspectSeeder.FIELD_MS_VEHICLE)))
    }

    // -------------------------------------------------------------------- ServiceHistory unification

    @Test
    fun `an anchor exactly explained by an OBSERVED row produces no ASSERTED row - no duplicate`() = runBlocking {
        seedVehicle()
        db.serviceRecordDao().insert(ServiceRecord(vehicleId = "AA:BB:CC:DD:EE:FF", serviceName = "Oil Change", mileage = 118_374, date = 999L))
        db.maintenanceItemDao().upsertStamped(
            MaintenanceItem(vehicleId = "AA:BB:CC:DD:EE:FF", serviceName = "Oil Change", intervalMiles = 5000, lastDoneMileage = 118_374, lastDoneDate = 999L),
        )

        val result = EngineDataMigrationWave4.copyFleetIfNeeded(context)

        assertEquals(1, result.serviceHistoryCopied) // only the OBSERVED row - the anchor is explained
        val schema = FleetAspectSeeder.ensureSeeded(context)
        val rows = db.engineRecordDao().activeByRecordType(schema.serviceHistory.recordTypeId)
        assertEquals(1, rows.size)
        assertEquals("OBSERVED", PayloadCodec.readString(JSONObject(rows.single().payload), schema.serviceHistory.fieldIds.getValue(FleetAspectSeeder.FIELD_SH_KIND)))
    }

    @Test
    fun `the Jeep case - a disagreeing anchor with no matching OBSERVED row survives as its own ASSERTED row`() = runBlocking {
        // Kevin's real drift bug: clock says 227,483 mi with no date; the record says 227,374 mi on
        // a real date. Neither axis matches - both facts must survive.
        seedVehicle()
        db.serviceRecordDao().insert(ServiceRecord(vehicleId = "AA:BB:CC:DD:EE:FF", serviceName = "Oil Change", mileage = 227_374, date = 12L))
        db.maintenanceItemDao().upsertStamped(
            MaintenanceItem(vehicleId = "AA:BB:CC:DD:EE:FF", serviceName = "Oil Change", intervalMiles = 5000, lastDoneMileage = 227_483, lastDoneDate = null),
        )

        val result = EngineDataMigrationWave4.copyFleetIfNeeded(context)

        assertEquals(2, result.serviceHistoryCopied) // OBSERVED + ASSERTED, both preserved
        val schema = FleetAspectSeeder.ensureSeeded(context)
        val rows = db.engineRecordDao().activeByRecordType(schema.serviceHistory.recordTypeId)
        assertEquals(2, rows.size)

        val kinds = rows.mapNotNull { PayloadCodec.readString(JSONObject(it.payload), schema.serviceHistory.fieldIds.getValue(FleetAspectSeeder.FIELD_SH_KIND)) }.sorted()
        assertEquals(listOf("ASSERTED", "OBSERVED"), kinds)

        val assertedRow = rows.single { PayloadCodec.readString(JSONObject(it.payload), schema.serviceHistory.fieldIds.getValue(FleetAspectSeeder.FIELD_SH_KIND)) == "ASSERTED" }
        val assertedPayload = JSONObject(assertedRow.payload)
        assertEquals(227_483.0, assertedPayload.getDouble(PayloadCodec.key(schema.serviceHistory.fieldIds.getValue(FleetAspectSeeder.FIELD_SH_MILEAGE))), 0.0001)
        assertNull(PayloadCodec.readLong(assertedPayload, schema.serviceHistory.fieldIds.getValue(FleetAspectSeeder.FIELD_SH_SERVICE_DATE)))
        assertNull("an ASSERTED row never carries a cost", PayloadCodec.readLong(assertedPayload, schema.serviceHistory.fieldIds.getValue(FleetAspectSeeder.FIELD_SH_COST)))
    }

    @Test
    fun `a coincidental mileage match with a disagreeing date does NOT explain the anchor - both must match the same row`() = runBlocking {
        // Senior review regression (2026-08-24): the first cut of the dedup rule OR'd the two axes,
        // so a single OBSERVED row whose mileage happened to match the anchor's mileage - while its
        // date genuinely disagreed - was wrongly treated as "explained", silently dropping the
        // date the driver stated. Both axes must now match the SAME row for it to count.
        seedVehicle()
        db.serviceRecordDao().insert(
            ServiceRecord(vehicleId = "AA:BB:CC:DD:EE:FF", serviceName = "Oil Change", mileage = 118_374, date = 100L),
        )
        db.maintenanceItemDao().upsertStamped(
            MaintenanceItem(
                vehicleId = "AA:BB:CC:DD:EE:FF", serviceName = "Oil Change", intervalMiles = 5000,
                lastDoneMileage = 118_374, // matches the OBSERVED row exactly
                lastDoneDate = 999L, // but the date genuinely disagrees with that same row's date (100L)
            ),
        )

        val result = EngineDataMigrationWave4.copyFleetIfNeeded(context)

        assertEquals(2, result.serviceHistoryCopied) // OBSERVED + ASSERTED - the anchor must still land
        val schema = FleetAspectSeeder.ensureSeeded(context)
        val rows = db.engineRecordDao().activeByRecordType(schema.serviceHistory.recordTypeId)
        assertEquals(2, rows.size)

        val assertedRow = rows.single {
            PayloadCodec.readString(JSONObject(it.payload), schema.serviceHistory.fieldIds.getValue(FleetAspectSeeder.FIELD_SH_KIND)) == "ASSERTED"
        }
        val assertedPayload = JSONObject(assertedRow.payload)
        assertEquals(118_374.0, assertedPayload.getDouble(PayloadCodec.key(schema.serviceHistory.fieldIds.getValue(FleetAspectSeeder.FIELD_SH_MILEAGE))), 0.0001)
        assertEquals(
            "the driver-stated date must survive - it must not be silently dropped just because a DIFFERENT axis coincidentally matched",
            999L,
            PayloadCodec.readLong(assertedPayload, schema.serviceHistory.fieldIds.getValue(FleetAspectSeeder.FIELD_SH_SERVICE_DATE)),
        )
    }

    @Test
    fun `neverDone produces no ServiceHistory row at all`() = runBlocking {
        seedVehicle()
        db.maintenanceItemDao().upsertStamped(
            MaintenanceItem(vehicleId = "AA:BB:CC:DD:EE:FF", serviceName = "Timing Belt", neverDone = true, lastDoneMileage = null, lastDoneDate = null),
        )

        val result = EngineDataMigrationWave4.copyFleetIfNeeded(context)

        assertEquals(0, result.serviceHistoryCopied)
        assertEquals(1, result.maintenanceScheduleCopied)
        val schema = FleetAspectSeeder.ensureSeeded(context)
        val msRecord = db.engineRecordDao().activeByRecordType(schema.maintenanceSchedule.recordTypeId).single()
        assertTrue(JSONObject(msRecord.payload).getBoolean(PayloadCodec.key(schema.maintenanceSchedule.fieldIds.getValue(FleetAspectSeeder.FIELD_MS_NEVER_DONE))))
    }

    @Test
    fun `a tombstoned ServiceRecord and a tombstoned MaintenanceItem are not migrated`() = runBlocking {
        seedVehicle()
        db.serviceRecordDao().insert(ServiceRecord(vehicleId = "AA:BB:CC:DD:EE:FF", serviceName = "Oil Change", mileage = 1, date = 1L))
        val id = db.serviceRecordDao().getMostRecentForVehicle("AA:BB:CC:DD:EE:FF")!!.id
        db.serviceRecordDao().softDelete(id)
        db.maintenanceItemDao().upsertStamped(MaintenanceItem(vehicleId = "AA:BB:CC:DD:EE:FF", serviceName = "Tire Rotation", intervalMiles = 6000))
        db.maintenanceItemDao().softDelete("AA:BB:CC:DD:EE:FF", "Tire Rotation", System.currentTimeMillis())

        val result = EngineDataMigrationWave4.copyFleetIfNeeded(context)

        assertEquals(0, result.serviceHistoryCopied)
        assertEquals(0, result.maintenanceScheduleCopied)
    }

    // ---------------------------------------------------------------------------------- BLOCK policy

    @Test
    fun `deleting a Vehicle with migrated ServiceHistory is BLOCKed, and nothing is written`() = runBlocking {
        seedVehicle()
        db.serviceRecordDao().insert(ServiceRecord(vehicleId = "AA:BB:CC:DD:EE:FF", serviceName = "Oil Change", mileage = 1, date = 1L))
        EngineDataMigrationWave4.copyFleetIfNeeded(context)

        val schema = FleetAspectSeeder.ensureSeeded(context)
        val vehicleRecord = db.engineRecordDao().activeByRecordType(schema.vehicle.recordTypeId).single()
        val recordStore = com.kevin.legion.engine.RecordStore(db.engineRecordDao(), db.fieldDefDao(), db.recordTypeDao())

        val result = recordStore.delete(vehicleRecord.id)

        assertTrue(result is com.kevin.legion.engine.RecordStore.DeleteResult.Blocked)
        assertEquals(1, db.engineRecordDao().activeByRecordType(schema.vehicle.recordTypeId).size)
        assertEquals(1, db.engineRecordDao().activeByRecordType(schema.serviceHistory.recordTypeId).size)
    }

    @Test
    fun `deleting a Vehicle with a migrated MaintenanceSchedule is also BLOCKed`() = runBlocking {
        seedVehicle()
        db.maintenanceItemDao().upsertStamped(MaintenanceItem(vehicleId = "AA:BB:CC:DD:EE:FF", serviceName = "Tire Rotation", intervalMiles = 6000))
        EngineDataMigrationWave4.copyFleetIfNeeded(context)

        val schema = FleetAspectSeeder.ensureSeeded(context)
        val vehicleRecord = db.engineRecordDao().activeByRecordType(schema.vehicle.recordTypeId).single()
        val recordStore = com.kevin.legion.engine.RecordStore(db.engineRecordDao(), db.fieldDefDao(), db.recordTypeDao())

        val result = recordStore.delete(vehicleRecord.id)

        assertTrue(result is com.kevin.legion.engine.RecordStore.DeleteResult.Blocked)
    }

    @Test
    fun `a vehicle with no history at all deletes cleanly`() = runBlocking {
        seedVehicle()
        EngineDataMigrationWave4.copyFleetIfNeeded(context)

        val schema = FleetAspectSeeder.ensureSeeded(context)
        val vehicleRecord = db.engineRecordDao().activeByRecordType(schema.vehicle.recordTypeId).single()
        val recordStore = com.kevin.legion.engine.RecordStore(db.engineRecordDao(), db.fieldDefDao(), db.recordTypeDao())

        val result = recordStore.delete(vehicleRecord.id)

        assertTrue(result is com.kevin.legion.engine.RecordStore.DeleteResult.Trashed)
    }

    // ----------------------------------------------------------------------------------- idempotence

    @Test
    fun `idempotent - a second run copies nothing and leaves counts unchanged`() = runBlocking {
        seedVehicle()
        db.serviceRecordDao().insert(ServiceRecord(vehicleId = "AA:BB:CC:DD:EE:FF", serviceName = "Oil Change", mileage = 1, date = 1L))
        db.maintenanceItemDao().upsertStamped(MaintenanceItem(vehicleId = "AA:BB:CC:DD:EE:FF", serviceName = "Tire Rotation", intervalMiles = 6000))

        val first = EngineDataMigrationWave4.copyFleetIfNeeded(context)
        val second = EngineDataMigrationWave4.copyFleetIfNeeded(context)

        assertFalse(first.alreadyDone)
        assertTrue(second.alreadyDone)
        assertEquals(0, second.vehiclesCopied)
        assertEquals(0, second.serviceHistoryCopied)
        assertEquals(0, second.maintenanceScheduleCopied)

        val schema = FleetAspectSeeder.ensureSeeded(context)
        assertEquals(1, db.engineRecordDao().activeByRecordType(schema.vehicle.recordTypeId).size)
        assertEquals(1, db.engineRecordDao().activeByRecordType(schema.serviceHistory.recordTypeId).size)
        assertEquals(1, db.engineRecordDao().activeByRecordType(schema.maintenanceSchedule.recordTypeId).size)
    }

    @Test
    fun `per-row guid check is also idempotent even without the completion flag`() = runBlocking {
        seedVehicle()
        db.serviceRecordDao().insert(ServiceRecord(vehicleId = "AA:BB:CC:DD:EE:FF", serviceName = "Oil Change", mileage = 1, date = 1L))

        EngineDataMigrationWave4.copyFleetIfNeeded(context)
        context.getSharedPreferences("engine_migration_wave4", android.content.Context.MODE_PRIVATE)
            .edit().putBoolean("fleet_completed_v1", false).commit()
        val retry = EngineDataMigrationWave4.copyFleetIfNeeded(context)

        assertEquals(0, retry.vehiclesCopied)
        assertEquals(0, retry.serviceHistoryCopied)
        val schema = FleetAspectSeeder.ensureSeeded(context)
        assertEquals(1, db.engineRecordDao().activeByRecordType(schema.vehicle.recordTypeId).size)
        assertEquals(1, db.engineRecordDao().activeByRecordType(schema.serviceHistory.recordTypeId).size)
    }

    // ------------------------------------------------------------------------------ failure paths

    /** Corrupts the already-seeded `vehicle` REFERENCE field on `ServiceHistory` so
     * [com.kevin.legion.engine.RecordStore.create]'s reference validation rejects every
     * `ServiceHistory` write this pass ("wrong record type" - pointed at `ServiceHistory` itself
     * instead of `Vehicle`) - same test-seam shape [EngineDataMigrationWave2Test]'s
     * `corruptReceiptReferenceField` already establishes. */
    private suspend fun corruptServiceHistoryVehicleField(schema: FleetAspectSeeder.Schema) {
        val field = db.fieldDefDao().forRecordType(schema.serviceHistory.recordTypeId)
            .single { it.id == schema.serviceHistory.fieldIds.getValue(FleetAspectSeeder.FIELD_SH_VEHICLE) }
        db.fieldDefDao().update(field.copy(config = FieldConfig.serializeReference(schema.serviceHistory.recordTypeId, DeletePolicy.BLOCK)))
    }

    private suspend fun restoreServiceHistoryVehicleField(schema: FleetAspectSeeder.Schema) {
        val field = db.fieldDefDao().forRecordType(schema.serviceHistory.recordTypeId)
            .single { it.id == schema.serviceHistory.fieldIds.getValue(FleetAspectSeeder.FIELD_SH_VEHICLE) }
        db.fieldDefDao().update(field.copy(config = FieldConfig.serializeReference(schema.vehicle.recordTypeId, DeletePolicy.BLOCK)))
    }

    @Test
    fun `a forced ServiceHistory create failure leaves the completion flag UNSET, and the row is retried on the next run`() = runBlocking {
        seedVehicle()
        db.serviceRecordDao().insert(ServiceRecord(vehicleId = "AA:BB:CC:DD:EE:FF", serviceName = "Oil Change", mileage = 1, date = 1L))
        val schema = FleetAspectSeeder.ensureSeeded(context)
        corruptServiceHistoryVehicleField(schema)

        val first = EngineDataMigrationWave4.copyFleetIfNeeded(context)

        assertEquals(1, first.vehiclesCopied) // Vehicle itself has no reference field - unaffected
        assertEquals(0, first.serviceHistoryCopied) // reference validation rejected it
        assertFalse(
            "the completion flag must stay clear when a ServiceHistory create failed this pass",
            context.getSharedPreferences("engine_migration_wave4", android.content.Context.MODE_PRIVATE)
                .getBoolean("fleet_completed_v1", false),
        )

        restoreServiceHistoryVehicleField(schema)
        val retry = EngineDataMigrationWave4.copyFleetIfNeeded(context)

        assertEquals(0, retry.vehiclesCopied) // already copied, guid recognized
        assertEquals(1, retry.serviceHistoryCopied) // the previously-failed row is retried and now lands
    }
}
