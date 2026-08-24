package com.kevin.legion.engine.fleet

import com.kevin.legion.data.local.CarDatabase
import com.kevin.legion.data.local.DeletePolicy
import com.kevin.legion.engine.FieldConfig
import com.kevin.legion.testutil.RoomTestReset
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * Robolectric coverage for [FleetAspectSeeder] - idempotence at every granularity (same shape as
 * [com.kevin.legion.engine.pantry.PantryAspectSeederTest]/
 * [com.kevin.legion.engine.ledger.LedgerAspectSeederTest]) plus the schema-specific things this
 * carve's own design depends on: `ServiceHistory.vehicle`/`MaintenanceSchedule.vehicle` are both
 * `DeletePolicy.BLOCK` references, and `costCents`/`kind` land where the carve doc says.
 */
@RunWith(RobolectricTestRunner::class)
class FleetAspectSeederTest {
    private val context = RuntimeEnvironment.getApplication()
    private val db get() = CarDatabase.getDatabase(context)

    @Before
    fun clearState() {
        RoomTestReset.resetCarDatabaseSingleton()
    }

    @Test
    fun `ensureSeeded is idempotent - a second call returns the same ids and creates no duplicate rows`() = runBlocking {
        val first = FleetAspectSeeder.ensureSeeded(context)
        val second = FleetAspectSeeder.ensureSeeded(context)

        assertEquals(first.aspectId, second.aspectId)
        assertEquals(first.vehicle.recordTypeId, second.vehicle.recordTypeId)
        assertEquals(first.serviceHistory.recordTypeId, second.serviceHistory.recordTypeId)
        assertEquals(first.maintenanceSchedule.recordTypeId, second.maintenanceSchedule.recordTypeId)
        assertEquals(first.vehicle.fieldIds, second.vehicle.fieldIds)
        assertEquals(first.serviceHistory.fieldIds, second.serviceHistory.fieldIds)
        assertEquals(first.maintenanceSchedule.fieldIds, second.maintenanceSchedule.fieldIds)

        assertEquals(1, db.aspectDao().listActive().count { it.name == FleetAspectSeeder.ASPECT_NAME })
        assertEquals(3, db.recordTypeDao().listByAspect(first.aspectId).size)
    }

    @Test
    fun `Vehicle record type carries every declared field, locked exactly where required`() = runBlocking {
        val schema = FleetAspectSeeder.ensureSeeded(context)
        val fields = db.fieldDefDao().forRecordType(schema.vehicle.recordTypeId).associateBy { it.name }

        assertEquals(9, fields.size)
        assertTrue(fields.getValue(FleetAspectSeeder.FIELD_NAME).locked)
        assertTrue(fields.getValue(FleetAspectSeeder.FIELD_MAKE).locked)
        assertTrue(fields.getValue(FleetAspectSeeder.FIELD_MODEL).locked)
        assertTrue(fields.getValue(FleetAspectSeeder.FIELD_YEAR).locked)
        assertTrue(fields.getValue(FleetAspectSeeder.FIELD_CONFIRMED).locked)
        assertTrue("trim is optional, not locked", !fields.getValue(FleetAspectSeeder.FIELD_TRIM).locked)
        assertTrue("odometerBaseline is optional, not locked", !fields.getValue(FleetAspectSeeder.FIELD_ODOMETER_BASELINE).locked)
    }

    @Test
    fun `ServiceHistory vehicle field is a BLOCK-policy REFERENCE at Vehicle, and costCents is the primaryAmountFieldId`() = runBlocking {
        val schema = FleetAspectSeeder.ensureSeeded(context)
        val vehicleField = db.fieldDefDao().forRecordType(schema.serviceHistory.recordTypeId)
            .single { it.id == schema.serviceHistory.fieldIds.getValue(FleetAspectSeeder.FIELD_SH_VEHICLE) }

        val refConfig = FieldConfig.referenceConfig(vehicleField.config)
        assertNotNull(refConfig)
        assertEquals(schema.vehicle.recordTypeId, refConfig!!.targetRecordTypeId)
        assertEquals(DeletePolicy.BLOCK, refConfig.deletePolicy)

        val recordType = db.recordTypeDao().getById(schema.serviceHistory.recordTypeId)!!
        assertEquals(schema.serviceHistory.fieldIds.getValue(FleetAspectSeeder.FIELD_SH_COST), recordType.primaryAmountFieldId)

        val kindOptions = FieldConfig.choiceOptions(
            db.fieldDefDao().forRecordType(schema.serviceHistory.recordTypeId)
                .single { it.id == schema.serviceHistory.fieldIds.getValue(FleetAspectSeeder.FIELD_SH_KIND) }.config,
        )
        assertEquals(listOf("OBSERVED", "ASSERTED"), kindOptions)
    }

    @Test
    fun `MaintenanceSchedule vehicle field is also a BLOCK-policy REFERENCE, and carries no last-done field`() = runBlocking {
        val schema = FleetAspectSeeder.ensureSeeded(context)
        val vehicleField = db.fieldDefDao().forRecordType(schema.maintenanceSchedule.recordTypeId)
            .single { it.id == schema.maintenanceSchedule.fieldIds.getValue(FleetAspectSeeder.FIELD_MS_VEHICLE) }

        val refConfig = FieldConfig.referenceConfig(vehicleField.config)
        assertNotNull(refConfig)
        assertEquals(schema.vehicle.recordTypeId, refConfig!!.targetRecordTypeId)
        assertEquals(DeletePolicy.BLOCK, refConfig.deletePolicy)

        val fieldNames = db.fieldDefDao().forRecordType(schema.maintenanceSchedule.recordTypeId).map { it.name }
        assertTrue("lastDoneMileage/lastDoneDate must never appear here - the whole point of the carve", "lastDoneMileage" !in fieldNames)
        assertTrue("lastDoneMileage/lastDoneDate must never appear here - the whole point of the carve", "lastDoneDate" !in fieldNames)
        assertEquals(6, fieldNames.size)
    }
}
