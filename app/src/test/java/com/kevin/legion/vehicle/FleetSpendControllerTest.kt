package com.kevin.legion.vehicle

import com.kevin.legion.data.local.CarDatabase
import com.kevin.legion.data.local.ServiceRecord
import com.kevin.legion.data.local.Vehicle
import com.kevin.legion.testutil.RoomTestReset
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.time.ZoneId

/**
 * Regression for [FleetSpendController] (ticket 11 §4,
 * `.scratch/fleet-maintenance/issues/11-service-history-cost-and-fleet-spend.md`) - the coverage
 * count on the total figure, the cost-per-mile REFUSAL while the odometer is unconfirmed (Kevin's
 * real Jeep is `odometerBaseline == 0` today), and the canonicalised grouping for spend-by-type.
 *
 * Robolectric through the real [CarDatabase.getDatabase] path, same shape as
 * [VehicleControllerServiceWritesTest].
 */
@RunWith(RobolectricTestRunner::class)
class FleetSpendControllerTest {
    private val context = RuntimeEnvironment.getApplication()
    private val db get() = CarDatabase.getDatabase(context)

    @Before
    fun clearState() = runBlocking {
        RoomTestReset.resetCarDatabaseSingleton()
    }

    private suspend fun seedVehicle(odometerBaseline: Int) {
        db.vehicleDao().upsert(
            Vehicle(
                obdMac = "V1", name = "Cherokee", make = "Jeep", model = "Cherokee", year = 1998,
                personaPrompt = "", odometerBaseline = odometerBaseline, confirmed = true,
            )
        )
    }

    // --- totalSpent: the coverage figure (CLAUDE.md §4 rule 6) ------------------------------

    @Test
    fun `totalSpent reports zero cost-bearing records when nothing has a cost yet`() = runBlocking {
        seedVehicle(227_000)
        db.serviceRecordDao().insert(ServiceRecord(vehicleId = "V1", serviceName = "Oil Change", mileage = 118_374, date = 1_000L, costCents = null))
        db.serviceRecordDao().insert(ServiceRecord(vehicleId = "V1", serviceName = "Tire Rotation", mileage = 220_000, date = 2_000L, costCents = null))

        val total = FleetSpendController.totalSpent(context, "V1")

        // Kevin's exact real shape - two records, both cost-less.
        assertEquals(0L, total.totalCents)
        assertEquals(0, total.recordsWithCost)
        assertEquals(2, total.totalRecords)
    }

    @Test
    fun `totalSpent sums only the records that carry a cost, and counts both figures separately`() = runBlocking {
        seedVehicle(227_000)
        db.serviceRecordDao().insert(ServiceRecord(vehicleId = "V1", serviceName = "Oil Change", mileage = 118_374, date = 1_000L, costCents = 4599))
        db.serviceRecordDao().insert(ServiceRecord(vehicleId = "V1", serviceName = "Tire Rotation", mileage = 220_000, date = 2_000L, costCents = null))
        db.serviceRecordDao().insert(ServiceRecord(vehicleId = "V1", serviceName = "Air Filter", mileage = 200_000, date = 3_000L, costCents = 2200))

        val total = FleetSpendController.totalSpent(context, "V1")

        assertEquals(6799L, total.totalCents)
        assertEquals(2, total.recordsWithCost)
        assertEquals(3, total.totalRecords)
    }

    @Test
    fun `totalSpent excludes a soft-deleted record entirely`() = runBlocking {
        seedVehicle(227_000)
        db.serviceRecordDao().insert(ServiceRecord(vehicleId = "V1", serviceName = "Oil Change", mileage = 118_374, date = 1_000L, costCents = 4599))
        val id = db.serviceRecordDao().getRecentForVehicle("V1", 1).single().id
        db.serviceRecordDao().softDelete(id)

        val total = FleetSpendController.totalSpent(context, "V1")

        assertEquals(0L, total.totalCents)
        assertEquals(0, total.recordsWithCost)
        assertEquals(0, total.totalRecords)
    }

    // --- costPerMile: the refusal (ticket 11 §4's headline case) ----------------------------

    @Test
    fun `costPerMile refuses when the odometer has never been confirmed, exactly Kevin's real Jeep`() = runBlocking {
        // Kevin's real shape: odometerBaseline == 0.
        seedVehicle(0)
        db.serviceRecordDao().insert(ServiceRecord(vehicleId = "V1", serviceName = "Oil Change", mileage = 118_374, date = 1_000L, costCents = 4599))

        val result = FleetSpendController.costPerMile(context, "V1")

        assertTrue("must REFUSE, not divide by a zero/near-zero odometer", result is FleetSpendController.CostPerMile.Refused)
        val reason = (result as FleetSpendController.CostPerMile.Refused).reason
        assertTrue("the refusal must be a real sentence, not blank", reason.isNotBlank())
    }

    @Test
    fun `costPerMile refuses when the odometer is confirmed but nothing has a cost yet`() = runBlocking {
        seedVehicle(227_000)
        db.serviceRecordDao().insert(ServiceRecord(vehicleId = "V1", serviceName = "Oil Change", mileage = 118_374, date = 1_000L, costCents = null))

        val result = FleetSpendController.costPerMile(context, "V1")

        assertTrue(result is FleetSpendController.CostPerMile.Refused)
    }

    @Test
    fun `costPerMile computes a real figure once the odometer is confirmed and something has a cost`() = runBlocking {
        seedVehicle(200_000)
        db.serviceRecordDao().insert(ServiceRecord(vehicleId = "V1", serviceName = "Oil Change", mileage = 199_000, date = 1_000L, costCents = 10_000))

        val result = FleetSpendController.costPerMile(context, "V1")

        assertTrue(result is FleetSpendController.CostPerMile.Value)
        val centsPerMile = (result as FleetSpendController.CostPerMile.Value).centsPerMile
        // totalCents(10_000) / currentMileage(200_000) = 0.05 cents/mi.
        assertEquals(0.05, centsPerMile, 0.0001)
    }

    // --- spendByServiceType: canonicalised grouping (ticket 07's duplicate problem) ---------

    @Test
    fun `spendByServiceType groups near-duplicate names onto one canonicalised bucket`() = runBlocking {
        seedVehicle(227_000)
        // Same real duplicate-concept pair ticket 01 counted on Kevin's phone.
        db.serviceRecordDao().insert(ServiceRecord(vehicleId = "V1", serviceName = "Air Filter", mileage = 200_000, date = 1_000L, costCents = 1500))
        db.serviceRecordDao().insert(ServiceRecord(vehicleId = "V1", serviceName = "Air Filter Replacement", mileage = 210_000, date = 2_000L, costCents = 2000))
        db.serviceRecordDao().insert(ServiceRecord(vehicleId = "V1", serviceName = "Oil Change", mileage = 220_000, date = 3_000L, costCents = 4599))

        val byType = FleetSpendController.spendByServiceType(context, "V1")

        assertEquals("exactly two buckets, not three - Air Filter and Air Filter Replacement collapse", 2, byType.size)
        // Sorted descending by spend: Oil Change (4599) first, the merged Air Filter bucket (3500) second.
        assertEquals("Oil Change", byType[0].first)
        assertEquals(4599L, byType[0].second)
        assertEquals(3500L, byType[1].second)
    }

    @Test
    fun `spendByServiceType excludes records with no cost from every bucket`() = runBlocking {
        seedVehicle(227_000)
        db.serviceRecordDao().insert(ServiceRecord(vehicleId = "V1", serviceName = "Oil Change", mileage = 118_374, date = 1_000L, costCents = null))

        val byType = FleetSpendController.spendByServiceType(context, "V1")

        assertTrue(byType.isEmpty())
    }

    // --- spendByYear ---------------------------------------------------------------------

    @Test
    fun `spendByYear groups by calendar year and excludes cost-less records`() = runBlocking {
        seedVehicle(227_000)
        val year2025 = java.time.LocalDate.of(2025, 6, 1).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val year2026a = java.time.LocalDate.of(2026, 2, 1).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val year2026b = java.time.LocalDate.of(2026, 8, 1).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        db.serviceRecordDao().insert(ServiceRecord(vehicleId = "V1", serviceName = "Oil Change", mileage = 200_000, date = year2025, costCents = 3000))
        db.serviceRecordDao().insert(ServiceRecord(vehicleId = "V1", serviceName = "Tire Rotation", mileage = 210_000, date = year2026a, costCents = 2000))
        db.serviceRecordDao().insert(ServiceRecord(vehicleId = "V1", serviceName = "Air Filter", mileage = 215_000, date = year2026b, costCents = 1500))
        db.serviceRecordDao().insert(ServiceRecord(vehicleId = "V1", serviceName = "Brake Fluid", mileage = 216_000, date = year2026b, costCents = null))

        val byYear = FleetSpendController.spendByYear(context, "V1")

        assertEquals(2, byYear.size)
        assertEquals(2025 to 3000L, byYear[0])
        assertEquals(2026 to 3500L, byYear[1])
    }
}
