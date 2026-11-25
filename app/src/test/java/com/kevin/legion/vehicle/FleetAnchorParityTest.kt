package com.kevin.legion.vehicle

import com.kevin.legion.data.local.MaintenanceItem
import com.kevin.legion.data.local.ServiceRecord
import com.kevin.legion.engine.fleet.FleetRecordBridge
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * The phone's half of one parity check: the same service history, the same verdict here and on the
 * engine. The engine's half is `server/tests/test_maintenance_mileageless.py`, which runs the
 * history below - the same names, dates and figures - through `brief.maintenance.schedule_status`
 * and both of its renderers. **The two files are one test in two languages; change one and change
 * the other.**
 *
 * Why it exists: blocker 3 of the RED review on PR #5. The phone took both anchor axes off the
 * single newest row and the engine took `max(mileage)` across every row, so once a service could be
 * logged with no reading (the car plan's slice 0) the engine went on reporting an anchor the phone
 * had already dropped - and called the just-logged service overdue, by name, in the spoken brief
 * and in `next_service`.
 */
class FleetAnchorParityTest {

    private val vehicleId = "test-mac"

    /** The same calendar days the Python fixture uses. */
    private fun day(year: Int, month: Int, dayOfMonth: Int): Long =
        LocalDate.of(year, month, dayOfMonth).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

    private val apr20 = day(2026, 4, 20)
    private val jan10 = day(2025, 1, 10)
    private val today = day(2026, 10, 15)

    /**
     * An OBSERVED row as `FleetEngineStore.insertObserved` writes one: `updatedAt` is stamped with
     * the EVENT date, not the wall clock.
     */
    private fun observed(id: Long, name: String, mileage: Int?, date: Long) = ServiceRecord(
        id = id, vehicleId = vehicleId, serviceName = name, mileage = mileage, date = date,
        kind = "OBSERVED", updatedAt = date,
    )

    /** An ASSERTED anchor as `FleetEngineStore.setAnchor` writes one: `updatedAt` is the wall clock. */
    private fun asserted(id: Long, name: String, mileage: Int?, date: Long?, statedAt: Long) = ServiceRecord(
        id = id, vehicleId = vehicleId, serviceName = name, mileage = mileage, date = date,
        kind = "ASSERTED", updatedAt = statedAt,
    )

    // "oil change": logged Apr 20 at 227,000, logged again today with no reading stated.
    private val oilChange = listOf(
        observed(id = 1, name = "oil change", mileage = 227_000, date = apr20),
        observed(id = 2, name = "oil change", mileage = null, date = today),
    )

    // "spark plugs": logged Jan 2025 at 71,200, logged again today with no reading stated.
    private val sparkPlugs = listOf(
        observed(id = 3, name = "spark plugs", mileage = 71_200, date = jan10),
        observed(id = 4, name = "spark plugs", mileage = null, date = today),
    )

    private fun itemFrom(
        name: String,
        rows: List<ServiceRecord>,
        intervalMiles: Int?,
        intervalMonths: Int?,
    ): MaintenanceItem {
        val (mileage, date) = FleetRecordBridge.projectAnchorLegacy(rows)
        return MaintenanceItem(
            vehicleId = vehicleId, serviceName = name,
            intervalMiles = intervalMiles, intervalMonths = intervalMonths,
            lastDoneMileage = mileage, lastDoneDate = date,
        )
    }

    @Test
    fun `the anchor is both axes of the newest row, so a reading two services old does not stand`() {
        // `last_done_on == TODAY`, `last_done_miles is None` on the engine side.
        assertEquals(null to today, FleetRecordBridge.projectAnchorLegacy(oilChange))
        assertEquals(null to today, FleetRecordBridge.projectAnchorLegacy(sparkPlugs))
    }

    @Test
    fun `the dual-axis item is timed by date on both sides, and is not overdue on either`() {
        // Engine: due_on = today + 6 months, due_at_miles None, needs_mileage False.
        val item = itemFrom("oil change", oilChange, intervalMiles = 5_000, intervalMonths = 6)

        assertFalse("a months interval and a date still time it", needsMileageReading(item))
        assertFalse(
            "227,000 + 5,000 is an anchor this history no longer holds",
            VehicleController.isDue(item, currentMileage = 235_000, odometerUnset = false, now = today + 1),
        )
    }

    @Test
    fun `the miles-only item is reported as needing a reading on both sides, and never as due`() {
        // Engine: needs_mileage == ["spark plugs"], and it is in neither `overdue` nor `by_miles`.
        val item = itemFrom("spark plugs", sparkPlugs, intervalMiles = 30_000, intervalMonths = null)

        assertTrue(needsMileageReading(item))
        assertFalse(VehicleController.isDue(item, currentMileage = 235_000, odometerUnset = false, now = today + 1))
    }

    @Test
    fun `over the whole history neither side times it, and nothing reads as all due`() {
        val items = listOf(
            itemFrom("oil change", oilChange, intervalMiles = 5_000, intervalMonths = 6),
            itemFrom("spark plugs", sparkPlugs, intervalMiles = 30_000, intervalMonths = null),
        )

        val next = VehicleController.computeNextService(
            items = items, currentMileage = 235_000, now = today + 1, odometerBaseline = 235_000,
        )

        assertEquals(listOf("spark plugs"), next?.needsMileage)
        assertEquals("oil change", next?.byTime?.serviceName)
        assertEquals(null, next?.byMiles)
        assertEquals(false, next?.allDue)
    }

    @Test
    fun `a stated reading times the miles-only item again, at the same figure both sides give`() {
        // Engine: next_by_miles.due_at_miles == 265,000.
        val withReading = listOf(
            observed(id = 3, name = "spark plugs", mileage = 71_200, date = jan10),
            observed(id = 4, name = "spark plugs", mileage = 235_000, date = today),
        )
        val item = itemFrom("spark plugs", withReading, intervalMiles = 30_000, intervalMonths = null)

        assertFalse(needsMileageReading(item))
        assertEquals(235_000, item.lastDoneMileage)
        val next = VehicleController.computeNextService(
            items = listOf(item), currentMileage = 235_000, now = today + 1, odometerBaseline = 235_000,
        )
        assertEquals(30_000L, next?.byMiles?.remaining)
    }

    @Test
    fun `a miles-ago backfill leaves the ASSERTED anchor standing, and both sides read it`() {
        // `log_past_service` with "about 8,000 miles ago" plus a date writes BOTH rows: the
        // remembered figure as an ASSERTED anchor, and an OBSERVED event with no mileage (an
        // OBSERVED row may not carry a figure derived from the live estimate). `explainedBy` is
        // false between them - the anchor states a mileage the event does not - so the ASSERTED
        // row survives, and the phone picks it by `updatedAt`. The engine picks it too: `_stated_at`
        // ranks an assertion by when it was written and a backdated observation by its event day.
        val rows = listOf(
            asserted(id = 5, name = "oil change", mileage = 42_000, date = apr20, statedAt = today),
            observed(id = 6, name = "oil change", mileage = null, date = apr20),
        )

        assertFalse(
            "the event does not explain the anchor, so the anchor is not superseded",
            FleetRecordBridge.explainedBy(42_000, apr20, null, apr20),
        )
        assertEquals(42_000 to apr20, FleetRecordBridge.projectAnchorLegacy(rows))
        assertFalse(
            "a driver-stated anchor still times the item",
            needsMileageReading(
                itemFrom("oil change", rows, intervalMiles = 5_000, intervalMonths = null),
            ),
        )
    }

    @Test
    fun `an undated ASSERTED anchor stated today outranks an older dated log, both sides`() {
        // Round 2 blocker A. The mileage-only backfill ("I did the oil change at 232,000") writes
        // an UNDATED ASSERTED row, because `mergeBackfillAnchors`' anti-pairing rule refuses to
        // attach a date nobody stated. `setAnchor` stamps the wall clock, so that row is the newest
        // here. The engine sorted an undated row LAST and kept the 2025 row's 71,200, which made it
        // call the service overdue by name for a history the phone times as fine.
        // Engine: `test_an_undated_asserted_anchor_outranks_an_older_dated_log`.
        val rows = listOf(
            observed(id = 7, name = "oil change", mileage = 71_200, date = jan10),
            asserted(id = 8, name = "oil change", mileage = 232_000, date = null, statedAt = today),
        )

        assertEquals(232_000 to null, FleetRecordBridge.projectAnchorLegacy(rows))
        val item = itemFrom("oil change", rows, intervalMiles = 5_000, intervalMonths = null)
        assertFalse("a stated anchor times it", needsMileageReading(item))
        assertFalse(
            "76,200 is an anchor this history no longer holds",
            VehicleController.isDue(item, currentMileage = 235_000, odometerUnset = false, now = today + 1),
        )
    }

    @Test
    fun `a log carrying a reading outranks an anchor asserted earlier the same day, both sides`() {
        // The other direction, so the rule is an ORDER and not a preference for assertions: a hands
        // "mark done" (ASSERTED, no mileage) and then a voice log that carries one, the same day.
        // Engine: `test_a_dated_log_today_outranks_an_asserted_anchor_stated_earlier_today`.
        val anHourAgo = today - 3_600_000L
        val rows = listOf(
            asserted(id = 9, name = "oil change", mileage = null, date = today, statedAt = anHourAgo),
            observed(id = 10, name = "oil change", mileage = 240_000, date = today),
        )

        assertEquals(240_000 to today, FleetRecordBridge.projectAnchorLegacy(rows))
    }
}
