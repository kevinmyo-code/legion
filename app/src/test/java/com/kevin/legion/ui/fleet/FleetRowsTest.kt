package com.kevin.legion.ui.fleet

import com.kevin.legion.data.local.CodeClearEvent
import com.kevin.legion.data.local.CodeEvent
import com.kevin.legion.data.local.DailyDriveLog
import com.kevin.legion.data.local.MaintenanceItem
import com.kevin.legion.data.local.MonthlyRecap
import com.kevin.legion.data.local.OdbSample
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Pure-logic coverage for [buildDueRows] / [distinctFaultsByFirstSeen] /
 * [groupThousands] - ticket 09's FLEET DUE and FAULTS blocks. No Room, no
 * Android dependency, plain JVM test (same posture as [com.kevin.legion.ui.ledger.LedgerEmptyStateResolverTest]).
 */
class FleetRowsTest {
    private val vehicleId = "test-mac"
    private val now = 1_700_000_000_000L
    private val monthMs = 30L * 24 * 60 * 60 * 1000

    @Test
    fun `buildLiveRows formats each present gauge and stamps its age`() {
        val coolant = OdbSample(vehicleId = vehicleId, pid = "0105", value = 88.0, unit = "C", timestamp = now - 3 * 24 * 60 * 60_000)
        val samples = mapOf("0105" to coolant, "ATRV" to null, "0107" to null)
        val rows = buildLiveRows(samples, now)
        assertEquals(1, rows.size)
        assertEquals("Coolant", rows[0].label)
        assertEquals("88 C", rows[0].value)
        assertEquals("3 days ago", rows[0].sub)
    }

    @Test
    fun `buildLiveRows omits a gauge this install has never recorded, rather than faking a value`() {
        val rows = buildLiveRows(mapOf("0105" to null, "ATRV" to null, "0107" to null), now)
        assertEquals(emptyList<LiveRowView>(), rows)
    }

    @Test
    fun `groupThousands inserts commas at the thousands mark`() {
        assertEquals("0", groupThousands(0))
        assertEquals("400", groupThousands(400))
        assertEquals("5,000", groupThousands(5000))
        assertEquals("132,400", groupThousands(132_400))
    }

    @Test
    fun `an overdue item reports OVERDUE with the interval-words subtitle`() {
        val item = MaintenanceItem(
            vehicleId = vehicleId, serviceName = "Oil Change",
            intervalMiles = 5000, lastDoneMileage = 132_400,
        )
        val rows = buildDueRows(listOf(item), currentMileage = 138_000, odometerUnset = false, now = now)
        assertEquals(1, rows.size)
        assertEquals("OVERDUE", rows[0].value)
        assertEquals(true, rows[0].overdue)
        assertEquals("every 5,000 mi - overdue", rows[0].sub)
    }

    @Test
    fun `a not-yet-due mileage item reports remaining miles, floored to the nearest 50`() {
        val item = MaintenanceItem(
            vehicleId = vehicleId, serviceName = "Front Brake Pads",
            intervalMiles = 25_000, lastDoneMileage = 115_000,
        )
        // remaining = 25,000 - (138,200 - 115,000) = 1,800, already a multiple
        // of 50 - see VehicleController.formatRemaining's doc for why it
        // floors rather than rounds to nearest.
        // Grouped as "1,800" since 2026-08-07: this string sits beside the
        // groupThousands()-formatted sub-line in the same row, and 1800 next to
        // 25,000 read as a formatting bug on device.
        val rows = buildDueRows(listOf(item), currentMileage = 138_200, odometerUnset = false, now = now)
        assertEquals("in 1,800 miles", rows[0].value)
        assertEquals(false, rows[0].overdue)
    }

    @Test
    fun `a not-yet-due time item reports remaining days, never the miles axis`() {
        val item = MaintenanceItem(
            vehicleId = vehicleId, serviceName = "Cabin Air Filter",
            intervalMonths = 12, lastDoneDate = now - monthMs,
        )
        val rows = buildDueRows(listOf(item), currentMileage = 100_000, odometerUnset = false, now = now)
        assertEquals(1, rows.size)
        assertEquals(false, rows[0].overdue)
        assert(rows[0].value.startsWith("in ")) { "expected a remaining-days value, got ${rows[0].value}" }
        assert(rows[0].value.endsWith("days") || rows[0].value.endsWith("day")) {
            "expected a days unit, got ${rows[0].value}"
        }
    }

    @Test
    fun `unknown items - no anchor at all - are excluded entirely`() {
        val known = MaintenanceItem(vehicleId = vehicleId, serviceName = "Oil Change", intervalMiles = 5000, lastDoneMileage = 1000)
        val unknown = MaintenanceItem(vehicleId = vehicleId, serviceName = "Spark Plugs")
        val rows = buildDueRows(listOf(known, unknown), currentMileage = 2000, odometerUnset = false, now = now)
        assertEquals(1, rows.size)
        assertEquals("Oil Change", rows[0].label)
    }

    @Test
    fun `overdue items sort ahead of not-yet-due items, without reordering within either group`() {
        val overdueA = MaintenanceItem(vehicleId = vehicleId, serviceName = "A", intervalMiles = 100, lastDoneMileage = 0)
        val upcoming = MaintenanceItem(vehicleId = vehicleId, serviceName = "B", intervalMiles = 100, lastDoneMileage = 990)
        val overdueC = MaintenanceItem(vehicleId = vehicleId, serviceName = "C", intervalMiles = 100, lastDoneMileage = 0)
        val rows = buildDueRows(listOf(overdueA, upcoming, overdueC), currentMileage = 1000, odometerUnset = false, now = now)
        assertEquals(listOf("A", "C", "B"), rows.map { it.label })
    }

    @Test
    fun `an anchored item with no interval on file reports honestly rather than a bogus remaining value`() {
        val item = MaintenanceItem(vehicleId = vehicleId, serviceName = "Something Logged Once", lastDoneMileage = 50_000)
        val rows = buildDueRows(listOf(item), currentMileage = 60_000, odometerUnset = false, now = now)
        assertEquals("-", rows[0].value)
        assertEquals("no interval on file", rows[0].sub)
    }

    @Test
    fun `distinctFaultsByFirstSeen keeps the EARLIEST timestamp a code appears under, not the latest`() {
        val earlier = CodeEvent(vehicleId = vehicleId, timestamp = now - 2 * monthMs, codesJson = """["P0442"]""")
        val later = CodeEvent(vehicleId = vehicleId, timestamp = now, codesJson = """["P0442","P0128"]""")
        val rows = distinctFaultsByFirstSeen(listOf(later, earlier))
        val p0442 = rows.first { it.code == "P0442" }
        assertEquals(now - 2 * monthMs, p0442.firstSeenMs)
    }

    @Test
    fun `distinctFaultsByFirstSeen flattens multiple codes from one event into separate rows`() {
        val event = CodeEvent(vehicleId = vehicleId, timestamp = now, codesJson = """["P0420","P0128"]""")
        val rows = distinctFaultsByFirstSeen(listOf(event))
        assertEquals(setOf("P0420", "P0128"), rows.map { it.code }.toSet())
    }

    @Test
    fun `distinctFaultsByFirstSeen tolerates a malformed codesJson row rather than throwing`() {
        val bad = CodeEvent(vehicleId = vehicleId, timestamp = now, codesJson = "not json")
        val rows = distinctFaultsByFirstSeen(listOf(bad))
        assertEquals(emptyList<FaultRow>(), rows)
    }

    // ------------------------------------------------------- dueFraction (ticket 05 part C)

    @Test
    fun `dueFraction is always 1f for an overdue item, regardless of how far past the interval`() {
        val item = MaintenanceItem(vehicleId = vehicleId, serviceName = "Oil Change", intervalMiles = 5000, lastDoneMileage = 100_000)
        assertEquals(1f, dueFraction(item, currentMileage = 200_000, odometerUnset = false, now = now, overdue = true))
    }

    @Test
    fun `dueFraction reports 0_5f for a mileage item exactly half elapsed`() {
        val item = MaintenanceItem(vehicleId = vehicleId, serviceName = "Oil Change", intervalMiles = 5000, lastDoneMileage = 100_000)
        val fraction = dueFraction(item, currentMileage = 102_500, odometerUnset = false, now = now, overdue = false)
        assertEquals(0.5f, fraction)
    }

    @Test
    fun `dueFraction is null for an item with no interval on file`() {
        val item = MaintenanceItem(vehicleId = vehicleId, serviceName = "Something Logged Once", lastDoneMileage = 50_000)
        assertNull(dueFraction(item, currentMileage = 60_000, odometerUnset = false, now = now, overdue = false))
    }

    @Test
    fun `dueFraction resolves the time axis when the item has no mileage anchor`() {
        val item = MaintenanceItem(vehicleId = vehicleId, serviceName = "Cabin Air Filter", intervalMonths = 12, lastDoneDate = now - monthMs)
        val fraction = dueFraction(item, currentMileage = 100_000, odometerUnset = false, now = now, overdue = false)
        // 1 month elapsed of a 12-month (360-day) interval - about 0.083.
        assert(fraction != null && fraction > 0.05f && fraction < 0.12f) { "expected roughly 1/12, got $fraction" }
    }

    // ------------------------------------------------ calendar months (ticket 09)

    private fun localMidnight(year: Int, month: Int, day: Int): Long =
        java.time.LocalDate.of(year, month, day).atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()

    @Test
    fun `addCalendarMonths lands on the real calendar date, not 30 days later`() {
        // January has 31 days - the old months*30-day approximation would have landed one day
        // short of February 1st.
        val jan1 = localMidnight(2026, 1, 1)
        assertEquals(localMidnight(2026, 2, 1), addCalendarMonths(jan1, 1))
    }

    @Test
    fun `dueFraction does not prematurely saturate to 1f across a 31-day month`() {
        // 30 days elapsed of a 1-month interval starting Jan 1 - the real due date is Feb 1 (31
        // days out), so this is NOT yet fully elapsed. The old months*30-day approximation treated
        // one month as exactly 30 days, which would have read this as fraction == 1f (exactly
        // saturated) a full day early.
        val item = MaintenanceItem(vehicleId = vehicleId, serviceName = "Coolant Flush", intervalMonths = 1, lastDoneDate = localMidnight(2026, 1, 1))
        // odometerUnset = true here matches currentMileage = 0's own real-world reading (no odometer
        // confirmed yet); the item has no miles axis at all, so it has no bearing on this test's
        // time-axis saturation math either way.
        val fraction = dueFraction(item, currentMileage = 0, odometerUnset = true, now = localMidnight(2026, 1, 31), overdue = false)
        assert(fraction != null && fraction < 1f) { "expected a fraction short of full saturation, got $fraction" }
    }

    // --------------------------------------------------- axis-closer-to-due (ticket 09)

    @Test
    fun `chooseDueAxis picks miles when the miles clock is further along than the time clock`() {
        val item = MaintenanceItem(
            vehicleId = vehicleId, serviceName = "Oil Change",
            intervalMiles = 5000, lastDoneMileage = 100_000,
            intervalMonths = 6, lastDoneDate = now,
        )
        // Miles: 4,500/5,000 = 0.90 elapsed. Time: 0 elapsed (lastDoneDate == now). Miles is closer.
        val axis = chooseDueAxis(item, currentMileage = 104_500, odometerUnset = false, now = now)
        assertEquals(DueAxis.MILES, axis)
    }

    @Test
    fun `chooseDueAxis picks time when the time clock is further along than the miles clock`() {
        val item = MaintenanceItem(
            vehicleId = vehicleId, serviceName = "Oil Change",
            intervalMiles = 5000, lastDoneMileage = 100_000,
            intervalMonths = 1, lastDoneDate = localMidnight(2026, 1, 1),
        )
        // Miles: 0 elapsed (currentMileage == lastDoneMileage). Time: ~29/31 elapsed. Time is closer.
        val axis = chooseDueAxis(item, currentMileage = 100_000, odometerUnset = false, now = localMidnight(2026, 1, 30))
        assertEquals(DueAxis.TIME, axis)
    }

    @Test
    fun `a row with both axes names both intervals and the due figure from the closer one`() {
        val item = MaintenanceItem(
            vehicleId = vehicleId, serviceName = "Oil Change",
            intervalMiles = 7500, lastDoneMileage = 100_000,
            intervalMonths = 6, lastDoneDate = now,
        )
        // Miles is the closer axis here (6,400/7,500 elapsed vs. 0 elapsed on time), so the due
        // figure in the sub-line comes from miles - matching ticket 09's own worked example shape
        // ("every 7,500 mi or 6 mo - due in 1,100 mi"). The due figure itself is spelled out via
        // VehicleController.formatRemaining, the SAME phrase-builder every other due-in figure in
        // the app uses ("1,100 miles", full word) rather than the ticket's illustrative "mi" - read
        // as an example of the STRUCTURE (both intervals, then the due figure), not a literal string
        // to reproduce against this app's own established wording convention.
        val rows = buildDueRows(listOf(item), currentMileage = 106_400, odometerUnset = false, now = now)
        assertEquals("every 7,500 mi or 6 mo - due in 1,100 miles", rows[0].sub)
    }

    // --------------------------------------------------- odometer unset (ticket 09 constraints)

    @Test
    fun `chooseDueAxis refuses the miles axis when odometerUnset is true, even with currentMileage 0`() {
        val item = MaintenanceItem(vehicleId = vehicleId, serviceName = "Oil Change", intervalMiles = 5000, lastDoneMileage = 132_400)
        assertNull(chooseDueAxis(item, currentMileage = 0, odometerUnset = true, now = now))
    }

    @Test
    fun `chooseDueAxis falls back to time when the odometer is unset but a time axis exists`() {
        val item = MaintenanceItem(
            vehicleId = vehicleId, serviceName = "Oil Change",
            intervalMiles = 5000, lastDoneMileage = 132_400,
            intervalMonths = 6, lastDoneDate = now,
        )
        assertEquals(DueAxis.TIME, chooseDueAxis(item, currentMileage = 0, odometerUnset = true, now = now))
    }

    @Test
    fun `a miles-only item says odometer not set instead of an absurd remaining figure when currentMileage is 0`() {
        // The exact shape of Kevin's real bug (ticket 09 constraints): "the drilldown reports the
        // oil due 'in 121,450 miles'" because 0 - lastDoneMileage produced a deeply negative
        // "elapsed", inflating remaining past the interval itself.
        val item = MaintenanceItem(vehicleId = vehicleId, serviceName = "Oil Change", intervalMiles = 5000, lastDoneMileage = 132_400)
        val rows = buildDueRows(listOf(item), currentMileage = 0, odometerUnset = true, now = now)
        assertEquals(1, rows.size)
        assertEquals("-", rows[0].value)
        assertEquals("every 5,000 mi - odometer not set", rows[0].sub)
        assertNull(rows[0].fraction)
    }

    @Test
    fun `the miles axis stays refused even once accumulated trip miles make currentMileage positive, as long as the odometer itself is unset`() {
        // Senior-dev review fix (mission-control ticket 09 follow-up): currentMileage is
        // odometerBaseline + tripMilesSinceBaseline.roundToInt(), and TelemetryRecorder's trip-mile
        // accumulation loop runs unconditionally on odometerBaseline (gated only on connected + rpm>0
        // + not-in-conversation) - so a car can genuinely reach odometerBaseline == 0,
        // tripMilesSinceBaseline == 40.0, i.e. currentMileage == 40 > 0, while the driver has never
        // confirmed an odometer reading at all. The OLD guard (`currentMileage > 0`) would have opened
        // the miles axis right here - the exact smaller-magnitude reproduction of the "due in 121,450
        // miles" absurdity this whole guard exists to close. The real signal (odometerUnset, threaded
        // from vehicle.odometerBaseline == 0) must refuse it regardless of what currentMileage reads.
        val item = MaintenanceItem(vehicleId = vehicleId, serviceName = "Oil Change", intervalMiles = 5000, lastDoneMileage = 132_400)
        assertNull(chooseDueAxis(item, currentMileage = 40, odometerUnset = true, now = now))

        val rows = buildDueRows(listOf(item), currentMileage = 40, odometerUnset = true, now = now)
        assertEquals(1, rows.size)
        assertEquals("-", rows[0].value)
        assertEquals("every 5,000 mi - odometer not set", rows[0].sub)
        assertNull(rows[0].fraction)
    }

    @Test
    fun `a row never reads OVERDUE off an unconfirmed odometer, even when accumulated trip miles alone would cross the interval`() {
        // Ticket 15 gap 1 (`.scratch/fleet-maintenance/issues/15-isdue-and-the-digest-inherit-the-
        // same-two-gaps.md`): VehicleController.isDue used to compute mileageDue with no regard for
        // odometerUnset, so buildDueRows' own partition (which sorts overdue vs upcoming via isDue,
        // separately from chooseDueAxis's already-guarded render math) could sort an item into
        // OVERDUE off an odometer nobody confirmed - the exact "currentMileage > 0 while
        // odometerBaseline == 0" case ticket 09's own senior-dev review caught for chooseDueAxis, now
        // closed for isDue too. lastDoneMileage = 0 and currentMileage = 6,000 against a 5,000-mile
        // interval crosses it by raw arithmetic alone; odometerUnset = true must refuse it anyway.
        val item = MaintenanceItem(vehicleId = vehicleId, serviceName = "Oil Change", intervalMiles = 5000, lastDoneMileage = 0)
        val rows = buildDueRows(listOf(item), currentMileage = 6000, odometerUnset = true, now = now)
        assertEquals(1, rows.size)
        assertEquals(false, rows[0].overdue)
        assertEquals("-", rows[0].value)
        assertEquals("every 5,000 mi - odometer not set", rows[0].sub)
    }

    // -------------------------------------------------------- [GUESS] tag (ticket 06/09)

    @Test
    fun `isGuessTag is true for any non-CONFIRMED item that actually carries an interval`() {
        val seededWithInterval = MaintenanceItem(vehicleId = vehicleId, serviceName = "Oil Change", intervalMiles = 5000, intervalSource = "SEEDED")
        val seededNoInterval = MaintenanceItem(vehicleId = vehicleId, serviceName = "Brake Fluid", intervalSource = "SEEDED")
        val confirmedWithInterval = MaintenanceItem(vehicleId = vehicleId, serviceName = "Tire Rotation", intervalMiles = 7500, intervalSource = "CONFIRMED")
        assertEquals(true, isGuessTag(seededWithInterval))
        assertEquals(false, isGuessTag(seededNoInterval))
        assertEquals(false, isGuessTag(confirmedWithInterval))
    }

    @Test
    fun `isGuessTag - ticket 18 - a LOOKUP item with an interval is a guess, a LOOKUP item with none is not, CONFIRMED never is`() {
        // A LOOKUP row (a factory-schedule proposal the driver reviewed and accepted via populate,
        // never a figure the driver typed) must disclose exactly like SEEDED - the ticket 18 bug was
        // a LOOKUP row rendering with NO disclosure at all, indistinguishable from a driver-typed
        // CONFIRMED row, off a lookup shown to disagree with itself roughly every other run.
        val lookupWithInterval = MaintenanceItem(vehicleId = vehicleId, serviceName = "Spark Plug Replacement", intervalMiles = 30_000, intervalSource = "LOOKUP")
        val lookupNoInterval = MaintenanceItem(vehicleId = vehicleId, serviceName = "Brake Fluid", intervalSource = "LOOKUP")
        val confirmedWithInterval = MaintenanceItem(vehicleId = vehicleId, serviceName = "Tire Rotation", intervalMiles = 7500, intervalSource = "CONFIRMED")
        assertEquals(true, isGuessTag(lookupWithInterval))
        assertEquals(false, isGuessTag(lookupNoInterval))
        assertEquals(false, isGuessTag(confirmedWithInterval))
    }

    @Test
    fun `provenanceWords names each provenance in words, CONFIRMED has nothing to disclose`() {
        assertEquals("LEGION's guess", provenanceWords(MaintenanceItem(vehicleId = vehicleId, serviceName = "x", intervalSource = "SEEDED")))
        assertEquals("from a factory lookup", provenanceWords(MaintenanceItem(vehicleId = vehicleId, serviceName = "x", intervalSource = "LOOKUP")))
        assertNull(provenanceWords(MaintenanceItem(vehicleId = vehicleId, serviceName = "x", intervalSource = "CONFIRMED")))
    }

    @Test
    fun `a DUE row carries isGuess only when the underlying item is an unconfirmed SEEDED guess`() {
        val guess = MaintenanceItem(vehicleId = vehicleId, serviceName = "Oil Change", intervalMiles = 5000, lastDoneMileage = 100_000, intervalSource = "SEEDED")
        val confirmed = MaintenanceItem(vehicleId = vehicleId, serviceName = "Tire Rotation", intervalMiles = 5000, lastDoneMileage = 100_000, intervalSource = "CONFIRMED")
        val rows = buildDueRows(listOf(guess, confirmed), currentMileage = 100_100, odometerUnset = false, now = now)
        assertEquals(true, rows.first { it.label == "Oil Change" }.isGuess)
        assertEquals(false, rows.first { it.label == "Tire Rotation" }.isGuess)
    }

    // ------------------------------------------------- FULL SCHEDULE (ticket 09)

    @Test
    fun `buildScheduleRows puts every non-deleted item into exactly one of three groups`() {
        val overdue = MaintenanceItem(vehicleId = vehicleId, serviceName = "Oil Change", intervalMiles = 100, lastDoneMileage = 0)
        val upcoming = MaintenanceItem(vehicleId = vehicleId, serviceName = "Brake Pads", intervalMiles = 100, lastDoneMileage = 990)
        val unknown = MaintenanceItem(vehicleId = vehicleId, serviceName = "Spark Plugs", intervalMiles = 30_000)
        val rows = buildScheduleRows(listOf(overdue, upcoming, unknown), currentMileage = 1000, odometerUnset = false, now = now)
        assertEquals(ScheduleGroup.OVERDUE, rows.first { it.serviceName == "Oil Change" }.group)
        assertEquals(ScheduleGroup.UPCOMING, rows.first { it.serviceName == "Brake Pads" }.group)
        assertEquals(ScheduleGroup.UNKNOWN, rows.first { it.serviceName == "Spark Plugs" }.group)
    }

    @Test
    fun `an unknown-anchor row in FULL SCHEDULE has no due figure or meter but still names its interval`() {
        val unknown = MaintenanceItem(vehicleId = vehicleId, serviceName = "Spark Plugs", intervalMiles = 30_000, intervalSource = "SEEDED")
        val rows = buildScheduleRows(listOf(unknown), currentMileage = 1000, odometerUnset = false, now = now)
        assertEquals("-", rows[0].value)
        assertNull(rows[0].fraction)
        assertEquals("every 30,000 mi", rows[0].sub)
        assertEquals(true, rows[0].isGuess)
    }

    @Test
    fun `buildScheduleRows also never sorts a row into OVERDUE off an unconfirmed odometer`() {
        // Same ticket 15 gap 1 fix as buildDueRows' own regression above, exercised through the
        // FULL SCHEDULE grouping function instead - it partitions via the same VehicleController
        // .isDue call, so it inherited the identical bug.
        val item = MaintenanceItem(vehicleId = vehicleId, serviceName = "Oil Change", intervalMiles = 5000, lastDoneMileage = 0)
        val rows = buildScheduleRows(listOf(item), currentMileage = 6000, odometerUnset = true, now = now)
        assertEquals(ScheduleGroup.UPCOMING, rows[0].group)
    }

    @Test
    fun `confirmableItems excludes a SEEDED item with no interval to confirm`() {
        val guess = MaintenanceItem(vehicleId = vehicleId, serviceName = "Oil Change", intervalMiles = 5000, intervalSource = "SEEDED")
        val orphan = MaintenanceItem(vehicleId = vehicleId, serviceName = "Brake Fluid", intervalSource = "SEEDED")
        val confirmed = MaintenanceItem(vehicleId = vehicleId, serviceName = "Tire Rotation", intervalMiles = 7500, intervalSource = "CONFIRMED")
        assertEquals(listOf("Oil Change"), confirmableItems(listOf(guess, orphan, confirmed)).map { it.serviceName })
    }

    /**
     * Ticket 18: confirming is how a factory-lookup value becomes one the driver actually vouches
     * for, so a `LOOKUP` row MUST be offered - excluding it would strand every populate-accepted
     * interval as permanently unconfirmable. What must not happen is the row losing its provenance
     * on the way; the dialog renders [provenanceWords] per row precisely because a bulk confirm is
     * where an unstable lookup value would otherwise pass for a driver-stated one.
     */
    @Test
    fun `confirmableItems includes a LOOKUP row, and it is still distinguishable from a seeded guess`() {
        val lookup = MaintenanceItem(vehicleId = vehicleId, serviceName = "Spark Plugs", intervalMiles = 30_000, intervalSource = "LOOKUP")
        val seeded = MaintenanceItem(vehicleId = vehicleId, serviceName = "Oil Change", intervalMiles = 5_000, intervalSource = "SEEDED")
        val confirmed = MaintenanceItem(vehicleId = vehicleId, serviceName = "Tire Rotation", intervalMiles = 7_500, intervalSource = "CONFIRMED")

        assertEquals(
            listOf("Spark Plugs", "Oil Change"),
            confirmableItems(listOf(lookup, seeded, confirmed)).map { it.serviceName },
        )
        // Same list, different words - the distinction survives into what the driver reads.
        assertEquals("from a factory lookup", provenanceWords(lookup))
        assertEquals("LEGION's guess", provenanceWords(seeded))
    }

    /** A LOOKUP row with nothing on either axis has no number to confirm, same as a seeded orphan. */
    @Test
    fun `confirmableItems excludes a LOOKUP row with no interval`() {
        val orphan = MaintenanceItem(vehicleId = vehicleId, serviceName = "Brake Pads", intervalSource = "LOOKUP")
        assertEquals(emptyList<String>(), confirmableItems(listOf(orphan)).map { it.serviceName })
    }

    // --------------------------------------------------- RECAPS month slots (ticket 05 part A)

    private fun recap(year: Int, month: Int, miles: Double = 100.0, mpg: Double? = 25.0) = MonthlyRecap(
        vehicleId = vehicleId, year = year, month = month, generatedAt = now,
        milesDriven = miles, avgMpg = mpg, driveCount = 1, longestDriveMiles = null,
        codeEventCount = 0, serviceCount = 0, narrative = "n",
        coverImagePath = null,
    )

    @Test
    fun `buildRecapMonthSlots fills a missing month in the middle as a null gap, not skipped or zeroed`() {
        val slots = buildRecapMonthSlots(listOf(recap(2026, 8), recap(2026, 6)))
        assertEquals(3, slots.size)
        assertEquals(listOf(6, 7, 8), slots.map { it.month })
        assertNull(slots[1].milesDriven)
        assertNull(slots[1].avgMpg)
    }

    @Test
    fun `buildRecapMonthSlots spans a year boundary by calendar-month key, not by month number alone`() {
        val slots = buildRecapMonthSlots(listOf(recap(2027, 1), recap(2026, 12)))
        assertEquals(2, slots.size)
        assertEquals(listOf(2026 to 12, 2027 to 1), slots.map { it.year to it.month })
    }

    @Test
    fun `buildRecapMonthSlots returns an empty list for no recaps`() {
        assertEquals(emptyList<RecapMonthSlot>(), buildRecapMonthSlots(emptyList()))
    }

    @Test
    fun `recapMonthPoints maps a null-valued slot to a null DeckPoint, never a zero`() {
        val slots = buildRecapMonthSlots(listOf(recap(2026, 8, miles = 500.0), recap(2026, 6, miles = 300.0)))
        val points = recapMonthPoints(slots) { it.milesDriven }
        assertEquals(3, points.size)
        assertNull(points[1])
        assertEquals(300f, points[0]!!.y)
        assertEquals(500f, points[2]!!.y)
    }

    @Test
    fun `recapMonthXLabels thins to January and July only`() {
        // Months 6,7,8,9 - only July gets a label among these four.
        val slots = buildRecapMonthSlots(listOf(recap(2026, 9), recap(2026, 6)))
        val labels = recapMonthXLabels(slots)
        assertEquals(listOf("", "JUL 2026", "", ""), labels)
    }

    // ------------------------------------------------- DRIVES sparklines (ticket 12)

    private fun driveLog(day: Int, miles: Double, mpg: Double?) = DailyDriveLog(
        vehicleId = vehicleId, year = 2026, month = 8, day = day, generatedAt = now,
        milesDriven = miles, avgMpg = mpg, driveCount = if (miles > 0) 1 else 0, codeEventCount = 0,
        narrative = "n",
    )

    @Test
    fun `buildMpgSparkline reverses to oldest-first and reports a null gap for a trip that never finished`() {
        // newest-first input: day 3, day 2, day 1 - buildMpgSparkline must
        // reverse it to oldest-first for DeckSparkline's index-ordered contract.
        val logs = listOf(driveLog(3, 40.0, 28.0), driveLog(2, 5.0, null), driveLog(1, 20.0, 26.5))
        val points = buildMpgSparkline(logs)
        assertEquals(listOf(26.5f, null, 28.0f), points)
    }

    @Test
    fun `buildMilesSparkline reverses to oldest-first and carries a genuine zero for an undriven day, never a gap`() {
        val logs = listOf(driveLog(3, 40.0, 28.0), driveLog(2, 0.0, null), driveLog(1, 20.0, 26.5))
        val points = buildMilesSparkline(logs)
        // day 2's 0.0 stays a real 0f, unlike buildMpgSparkline's null for the
        // same day - milesDriven is non-null by construction (see the
        // function's own doc), so nothing here is a gap.
        assertEquals(listOf(20f, 0f, 40f), points)
    }

    // ------------------------------------------------------- visibleFaultCodes (D7 union rule)

    private fun clearEvent(
        timestamp: Long,
        outcome: String,
        codesAfterJson: String = "",
    ) = CodeClearEvent(
        vehicleId = vehicleId,
        timestamp = timestamp,
        codesBeforeJson = "[]",
        codesAfterJson = codesAfterJson,
        outcome = outcome,
    )

    @Test
    fun `visibleFaultCodes shows every code and reports no date when nothing was ever cleared`() {
        val events = listOf(CodeEvent(vehicleId = vehicleId, timestamp = now, codesJson = """["P0420","P0128"]"""))
        val (codes, clearedAt) = visibleFaultCodes(events, emptyList())
        assertEquals(setOf("P0420", "P0128"), codes)
        assertNull(clearedAt)
    }

    @Test
    fun `visibleFaultCodes hides every code timestamp-filtered by a CLEARED anchor with nothing since`() {
        val events = listOf(CodeEvent(vehicleId = vehicleId, timestamp = now - monthMs, codesJson = """["P0420","P0128"]"""))
        val clear = clearEvent(timestamp = now, outcome = "CLEARED", codesAfterJson = "[]")
        val (codes, clearedAt) = visibleFaultCodes(events, listOf(clear))
        assertEquals(emptySet<String>(), codes)
        assertEquals(now, clearedAt)
    }

    @Test
    fun `visibleFaultCodes shows a code_event newer than the CLEARED anchor without needing the union`() {
        val cleared = clearEvent(timestamp = now, outcome = "CLEARED", codesAfterJson = "[]")
        val freshEvent = CodeEvent(vehicleId = vehicleId, timestamp = now + 1000, codesJson = """["P0442"]""")
        val (codes, clearedAt) = visibleFaultCodes(listOf(freshEvent), listOf(cleared))
        assertEquals(setOf("P0442"), codes)
        assertEquals(now, clearedAt)
    }

    /**
     * The ticket's own required scenario: "shows a RETURNED code and hides a CLEARED one."
     *
     * Two codes trip together (P0420, P0128), both get CLEARED at T1 (the anchor). A LATER
     * RETURNED clear attempt at T2 proves P0420 is live again, but - because the health-monitor
     * poll's own baseline never learned about the T1 clear (see [visibleFaultCodes]'s own doc) -
     * no fresh [CodeEvent] row exists for that return. The plain timestamp filter alone would show
     * nothing (both code_events predate T1); the union of T2's `codesAfterJson` must rescue P0420
     * while P0128, which really was cleared and never returned, stays hidden.
     */
    @Test
    fun `visibleFaultCodes union rule shows a RETURNED code and hides a CLEARED one`() {
        val t0 = now - 2 * monthMs
        val t1 = now - monthMs
        val t2 = now
        val events = listOf(
            CodeEvent(vehicleId = vehicleId, timestamp = t0, codesJson = """["P0420","P0128"]"""),
        )
        val clearedEvent = clearEvent(timestamp = t1, outcome = "CLEARED", codesAfterJson = "[]")
        val returnedEvent = clearEvent(timestamp = t2, outcome = "RETURNED", codesAfterJson = """["P0420"]""")

        val (codes, clearedAt) = visibleFaultCodes(events, listOf(clearedEvent, returnedEvent))

        assertEquals("P0420 (RETURNED) must show, P0128 (CLEARED, never returned) must not", setOf("P0420"), codes)
        assertEquals("only the CLEARED event ever moves the anchor/date line", t1, clearedAt)
    }

    @Test
    fun `visibleFaultCodes never anchors on a RETURNED or UNVERIFIED clear-event alone`() {
        // D7's own text: "RETURNED and UNVERIFIED clears do NOT filter anything." With no CLEARED
        // event on file at all, a RETURNED/UNVERIFIED history must never hide or date-stamp anything.
        val events = listOf(CodeEvent(vehicleId = vehicleId, timestamp = now - monthMs, codesJson = """["P0420"]"""))
        val onlyReturned = clearEvent(timestamp = now, outcome = "RETURNED", codesAfterJson = """["P0420"]""")
        val (codes, clearedAt) = visibleFaultCodes(events, listOf(onlyReturned))
        assertEquals(setOf("P0420"), codes)
        assertNull(clearedAt)
    }

    // -------------------------------------- senior-review fixes, 2026-08-16 (D7 union rule)

    /**
     * FIX (a): the old implementation early-returned `distinctFaultsByFirstSeen(events) to null`
     * the instant no `CLEARED` clear-event existed, skipping the union half of D7's rule entirely -
     * a `RETURNED` survivor named only in a clear-event's own `codesAfterJson` was silently hidden
     * whenever this vehicle had never had a `CLEARED` clear. `events` is empty here on purpose: this
     * is also the (b)-adjacent shape where the survivor has no `code_events` row at all, so a bug
     * that special-cased "fall back to `code_events`" alone could not accidentally pass it.
     */
    @Test
    fun `visibleFaultCodes shows a RETURNED survivor when no CLEARED event has ever existed`() {
        val returned = clearEvent(timestamp = now, outcome = "RETURNED", codesAfterJson = """["P0442"]""")
        val (codes, clearedAt) = visibleFaultCodes(emptyList(), listOf(returned))
        assertEquals(setOf("P0442"), codes)
        assertNull(clearedAt)
    }

    /**
     * FIX (b): [withSynthesizedSurvivors] is the replacement for the old call site's
     * `visibleCodes.mapNotNull { allFirstSeen[code] }`, which silently DROPPED any survivor code
     * with no `code_events` row instead of rendering it - reachable because
     * `AriaForegroundService.startHealthMonitor` only writes its first row on the first 5-minute
     * poll, and a code cleared and returned inside that window has no fresh `code_events` row yet.
     * [FaultRow.firstSeenMs] must backdate to the clear-event's OWN timestamp (the earliest LEGION
     * can honestly claim to have known the code was live), never drop the row and never invent "now".
     */
    @Test
    fun `withSynthesizedSurvivors renders a survivor with no code_events row, backdated to the clear-event's own timestamp`() {
        val returned = clearEvent(timestamp = now, outcome = "RETURNED", codesAfterJson = """["P0442"]""")
        val rows = withSynthesizedSurvivors(
            visibleCodes = setOf("P0442"),
            allFirstSeen = emptyMap(),
            clearEvents = listOf(returned),
        )
        assertEquals(listOf(FaultRow("P0442", now)), rows)
    }

    /** [withSynthesizedSurvivors] must NOT synthesize a row for a code [allFirstSeen] already has - the real, observed `firstSeenMs` wins, never the clear-event's backdated fallback. */
    @Test
    fun `withSynthesizedSurvivors prefers the real code_events first-seen over the synthesized fallback`() {
        val realFirstSeen = now - monthMs
        val returned = clearEvent(timestamp = now, outcome = "RETURNED", codesAfterJson = """["P0420"]""")
        val rows = withSynthesizedSurvivors(
            visibleCodes = setOf("P0420"),
            allFirstSeen = mapOf("P0420" to FaultRow("P0420", realFirstSeen)),
            clearEvents = listOf(returned),
        )
        assertEquals(listOf(FaultRow("P0420", realFirstSeen)), rows)
    }

    /**
     * Hiding still requires a `CLEARED` anchor specifically - an `UNVERIFIED`-only history (the
     * sibling case to the existing `RETURNED`-only test above) must not hide or date-stamp anything
     * either. `UNVERIFIED`'s `codesAfterJson` is always empty (D2: the post-send re-read never came
     * back), so this also confirms the union half contributes nothing when there is nothing to add.
     */
    @Test
    fun `visibleFaultCodes never hides on an UNVERIFIED clear-event alone`() {
        val events = listOf(CodeEvent(vehicleId = vehicleId, timestamp = now - monthMs, codesJson = """["P0420"]"""))
        val onlyUnverified = clearEvent(timestamp = now, outcome = "UNVERIFIED", codesAfterJson = "")
        val (codes, clearedAt) = visibleFaultCodes(events, listOf(onlyUnverified))
        assertEquals(setOf("P0420"), codes)
        assertNull(clearedAt)
    }
}
