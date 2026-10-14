package com.kevin.midnightai.vehicle

import com.kevin.midnightai.data.local.MaintenanceItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for [VehicleController]'s pure maintenance-schedule decision logic
 * (`isDue`, `isUnknown`, `computeNextService`). Deliberately does not touch
 * Room, Context, or any `android.*` call - `gradlew testDebugUnitTest` runs on
 * a plain JVM with an unmocked `android.jar`, so anything platform-backed
 * throws at test runtime rather than compile time (see playbook-coding.md's
 * 2026-07-28 testing entry). `VehicleController.isDue`/`isUnknown`/
 * `computeNextService` are `internal` precisely so this file can drive them
 * directly with plain [MaintenanceItem] values.
 */
class MaintenanceScheduleTest {

    private val day = 24L * 60 * 60 * 1000
    private val month = 30L * day

    private fun item(
        name: String = "Oil Change",
        intervalMiles: Int? = null,
        intervalMonths: Int? = null,
        lastDoneMileage: Int? = null,
        lastDoneDate: Long? = null,
        neverDone: Boolean = false,
    ) = MaintenanceItem(
        vehicleId = "car-1",
        serviceName = name,
        intervalMiles = intervalMiles,
        intervalMonths = intervalMonths,
        lastDoneMileage = lastDoneMileage,
        lastDoneDate = lastDoneDate,
        neverDone = neverDone,
    )

    // --- isUnknown / isDue: the unknown-vs-never-done distinction -----------

    @Test
    fun `unknown item (all anchors null) is not due`() {
        val i = item(intervalMiles = 5000)
        assertTrue(VehicleController.isUnknown(i))
        assertTrue(!VehicleController.isDue(i, currentMileage = 50_000, now = 0))
    }

    @Test
    fun `unknown item is excluded from next-service ranking, surfaced separately`() {
        val unknown = item(name = "Spark Plugs", intervalMiles = 30_000)
        val anchored = item(
            name = "Oil Change", intervalMiles = 5000, lastDoneMileage = 40_000,
        )
        val result = VehicleController.computeNextService(
            items = listOf(unknown, anchored),
            currentMileage = 41_000,
            now = 0,
            odometerBaseline = 40_000,
        )
        assertEquals("Oil Change", result?.byMiles?.serviceName)
        assertEquals(1, result?.unknownCount)
        assertEquals(listOf("Spark Plugs"), result?.unknownNames)
    }

    @Test
    fun `neverDone item is always due regardless of anchors`() {
        val i = item(intervalMiles = 5000, neverDone = true)
        assertTrue(VehicleController.isDue(i, currentMileage = 0, now = 0))
    }

    @Test
    fun `neverDone item excluded from unknown`() {
        val i = item(neverDone = true)
        assertTrue(!VehicleController.isUnknown(i))
    }

    // --- mileage-due / time-due boundaries -----------------------------------

    @Test
    fun `mileage boundary just under is not due`() {
        val i = item(intervalMiles = 5000, lastDoneMileage = 40_000)
        assertTrue(!VehicleController.isDue(i, currentMileage = 44_999, now = 0))
    }

    @Test
    fun `mileage boundary exactly at interval is due`() {
        val i = item(intervalMiles = 5000, lastDoneMileage = 40_000)
        assertTrue(VehicleController.isDue(i, currentMileage = 45_000, now = 0))
    }

    @Test
    fun `time boundary just under is not due`() {
        val lastDone = 0L
        val i = item(intervalMonths = 6, lastDoneDate = lastDone)
        assertTrue(!VehicleController.isDue(i, currentMileage = 0, now = 6 * month - 1))
    }

    @Test
    fun `time boundary exactly at interval is due`() {
        val lastDone = 0L
        val i = item(intervalMonths = 6, lastDoneDate = lastDone)
        assertTrue(VehicleController.isDue(i, currentMileage = 0, now = 6 * month))
    }

    // --- next-service ranking (both axes, no forced single winner) -----------

    @Test
    fun `item due in 1200mi ranks ahead of item due in 3100mi on the miles axis`() {
        val currentMileage = 44_600
        val near = item(
            name = "Tire Rotation", intervalMiles = 6000,
            lastDoneMileage = currentMileage - (6000 - 1200), // 1,200 mi remaining
        )
        val far = item(
            name = "Brake Pads", intervalMiles = 30_000,
            lastDoneMileage = currentMileage - (30_000 - 3100), // 3,100 mi remaining
        )
        val result = VehicleController.computeNextService(
            items = listOf(near, far),
            currentMileage = currentMileage,
            now = 0,
            odometerBaseline = 1,
        )
        assertEquals("Tire Rotation", result?.byMiles?.serviceName)
        assertEquals(1200L, result?.byMiles?.remaining)
        assertEquals(VehicleController.ScheduleUnit.MILES, result?.byMiles?.unit)
        assertNull(result?.byTime)
    }

    @Test
    fun `no months-to-miles conversion happens anywhere`() {
        // A months-only item's remaining value must be reported in DAYS, never
        // converted to an equivalent mileage figure.
        val monthsItem = item(name = "Coolant Flush", intervalMonths = 12, lastDoneDate = 0)
        val result = VehicleController.computeNextService(
            items = listOf(monthsItem),
            currentMileage = 999_999, // irrelevant - no mileage interval on this item
            now = 0,
            odometerBaseline = 1,
        )
        assertEquals(VehicleController.ScheduleUnit.DAYS, result?.byTime?.unit)
        assertEquals(12 * 30L, result?.byTime?.remaining) // 12 months * 30 days/month, no mileage math involved
        assertNull(result?.byMiles)
    }

    @Test
    fun `odometerBaseline zero skips mileage axis entirely, time axis still populated`() {
        val milesItem = item(name = "Oil Change", intervalMiles = 5000, lastDoneMileage = 0)
        val monthsItem = item(name = "Coolant Flush", intervalMonths = 6, lastDoneDate = 0)
        val result = VehicleController.computeNextService(
            items = listOf(milesItem, monthsItem),
            currentMileage = 4999,
            now = 5 * month,
            odometerBaseline = 0,
        )
        assertNull(result?.byMiles)
        assertEquals("Coolant Flush", result?.byTime?.serviceName)
        assertEquals(VehicleController.ScheduleUnit.DAYS, result?.byTime?.unit)
        assertTrue(result?.odometerUnset == true)
    }

    @Test
    fun `dual-axis item appears in both byMiles and byTime with its own remaining value on each`() {
        // 3000mi/6mo interval, both anchors at 0. At currentMileage=1000 and
        // now=3 months, it has 2,000 mi remaining and 3 months (90 days) remaining.
        val dual = item(name = "Oil Change", intervalMiles = 3000, intervalMonths = 6, lastDoneMileage = 0, lastDoneDate = 0)
        val result = VehicleController.computeNextService(
            items = listOf(dual),
            currentMileage = 1000,
            now = 3 * month,
            odometerBaseline = 1,
        )
        assertEquals("Oil Change", result?.byMiles?.serviceName)
        assertEquals(2000L, result?.byMiles?.remaining)
        assertEquals("Oil Change", result?.byTime?.serviceName)
        assertEquals(90L, result?.byTime?.remaining)
    }

    @Test
    fun `Nadia's counterexample - dual-axis item is not buried by an unrelated miles-only item`() {
        // Dual-axis A: 3000mi/6mo, anchors at 0. At now=179 days / currentMileage=10:
        //  - daysRemaining = 6*30 - 179 = 1 day remaining
        // Miles-only B: 30000mi, lastDone 0, currentMileage=10 -> 29,990 mi remaining.
        // Under the OLD design, A's dual axis resolved (via the now-deleted
        // fraction comparison) to DAYS ONLY, and the ranking picked ANY
        // miles-tagged candidate over ANY days-tagged one - so B (29,990 mi) beat
        // A's DAYS-resolved axis outright, and the oil change due TOMORROW was
        // never even surfaced. That bug is what this proves is gone: A now shows
        // up as the byTime leader regardless of what exists on the miles axis.
        //
        // NOTE: at currentMileage=10, A's OWN milesRemaining is 3000-10=2,990,
        // which is also less than B's 29,990 - so with no axis-collapsing, A
        // legitimately leads BOTH axes here (exactly the "may win both" case
        // documented on NextService). That is a stronger proof than a split
        // result would have been: A is not merely rescued onto one axis, it is
        // correctly the most urgent item full stop, on every axis it has data for.
        val a = item(name = "Oil Change", intervalMiles = 3000, intervalMonths = 6, lastDoneMileage = 0, lastDoneDate = 0)
        val b = item(name = "Transmission Fluid", intervalMiles = 30_000, lastDoneMileage = 0)
        val result = VehicleController.computeNextService(
            items = listOf(a, b),
            currentMileage = 10,
            now = 179 * day,
            odometerBaseline = 1,
        )
        assertEquals("Oil Change", result?.byTime?.serviceName)
        assertEquals(1L, result?.byTime?.remaining)
        assertEquals("Oil Change", result?.byMiles?.serviceName)
        assertEquals(2_990L, result?.byMiles?.remaining)
    }

    @Test
    fun `no dual-axis collapsing - a miles-only item can still win byMiles over a dual-axis item`() {
        // A (dual-axis): 10,000mi / 24mo, anchors at 0. At currentMileage=1000,
        // now=1 month: 9,000 mi remaining, 690 days remaining.
        // B (miles-only): 5,000mi, anchor at 0. At currentMileage=1000: 4,000 mi
        // remaining - genuinely sooner than A's own miles axis, so B must win
        // byMiles even though A is the dual-axis item. Nothing about being
        // dual-axis gives A priority on an axis it isn't actually winning.
        val a = item(name = "Oil Change", intervalMiles = 10_000, intervalMonths = 24, lastDoneMileage = 0, lastDoneDate = 0)
        val b = item(name = "Tire Rotation", intervalMiles = 5000, lastDoneMileage = 0)
        val result = VehicleController.computeNextService(
            items = listOf(a, b),
            currentMileage = 1000,
            now = month,
            odometerBaseline = 1,
        )
        assertEquals("Tire Rotation", result?.byMiles?.serviceName)
        assertEquals(4000L, result?.byMiles?.remaining)
        assertEquals("Oil Change", result?.byTime?.serviceName)
        assertEquals(690L, result?.byTime?.remaining)
    }

    // --- backfill mileage resolution (logPastServiceDirect's "milesAgo" phrasing) --

    @Test
    fun `milesAgo resolves to currentMileage minus milesAgo`() {
        assertEquals(37_000, VehicleController.resolveBackfillMileage(mileage = null, milesAgo = 3000, currentMileage = 40_000))
    }

    @Test
    fun `milesAgo floors at zero when it overshoots currentMileage`() {
        assertEquals(0, VehicleController.resolveBackfillMileage(mileage = null, milesAgo = 50_000, currentMileage = 40_000))
    }

    @Test
    fun `negative milesAgo is clamped to zero before subtracting, never producing a future anchor`() {
        // A negative milesAgo (a malformed voice-tool argument) must not compute
        // currentMileage - (-x) = currentMileage + x, which would put
        // lastDoneMileage AHEAD of currentMileage - a service dated into the future.
        val resolved = VehicleController.resolveBackfillMileage(mileage = null, milesAgo = -500, currentMileage = 40_000)
        assertEquals(40_000, resolved)
        assertTrue(resolved!! <= 40_000)
    }

    @Test
    fun `explicit mileage wins over milesAgo when both given`() {
        assertEquals(12_345, VehicleController.resolveBackfillMileage(mileage = 12_345, milesAgo = 3000, currentMileage = 40_000))
    }

    @Test
    fun `all items already due returns a non-null result flagged allDue, not null`() {
        // B5: a fully-logged, well-used car where every anchored item has
        // already crossed its threshold is NOT the same state as a fresh
        // install with no schedule at all - it must not collapse onto the
        // same null the caller uses for "register the car or log a service".
        val due = item(intervalMiles = 5000, lastDoneMileage = 40_000)
        val result = VehicleController.computeNextService(
            items = listOf(due),
            currentMileage = 45_500,
            now = 0,
            odometerBaseline = 1,
        )
        assertTrue(result?.allDue == true)
        assertNull(result?.byMiles)
        assertNull(result?.byTime)
        assertEquals(0, result?.unknownCount)
    }

    @Test
    fun `no items at all returns null - the fresh-install state`() {
        val result = VehicleController.computeNextService(
            items = emptyList(),
            currentMileage = 45_500,
            now = 0,
            odometerBaseline = 1,
        )
        assertNull(result)
    }

    @Test
    fun `no anchored not-yet-due item but an unknown item still returns a non-null result to surface it`() {
        // Both axes empty, but there's an unknown to report - the caller still
        // needs the object to say "I don't know about Spark Plugs" rather than
        // getting a bare null that looks like there's nothing to say at all.
        val due = item(intervalMiles = 5000, lastDoneMileage = 40_000)
        val unknown = item(name = "Spark Plugs")
        val result = VehicleController.computeNextService(
            items = listOf(due, unknown),
            currentMileage = 45_500,
            now = 0,
            odometerBaseline = 1,
        )
        assertNull(result?.byMiles)
        assertNull(result?.byTime)
        assertEquals(1, result?.unknownCount)
        assertEquals(listOf("Spark Plugs"), result?.unknownNames)
    }

    // --- formatRemaining (spoken singular/plural/zero-days presentation) -----

    @Test
    fun `formatRemaining pluralizes miles correctly`() {
        assertEquals("50 miles", VehicleController.formatRemaining(60L, VehicleController.ScheduleUnit.MILES))
        assertEquals("1 mile", VehicleController.formatRemaining(1L, VehicleController.ScheduleUnit.MILES))
    }

    // Rounds DOWN to a multiple of 50, not to nearest - see formatRemaining's note.
    @Test
    fun `formatRemaining rounds miles down to a multiple of 50, never showing raw precision`() {
        assertEquals("2900 miles", VehicleController.formatRemaining(2949L, VehicleController.ScheduleUnit.MILES))
        assertEquals("2950 miles", VehicleController.formatRemaining(2976L, VehicleController.ScheduleUnit.MILES))
        assertEquals("50 miles", VehicleController.formatRemaining(99L, VehicleController.ScheduleUnit.MILES))
    }

    // Rounding must never OVERSTATE the remaining margin. An earlier cut floored
    // the rounded value at 50, so 10 miles left was spoken as "50 miles" - five
    // times the real margin, in the one direction this must never be wrong in.
    @Test
    fun `formatRemaining reports a close mileage exactly rather than rounding it up`() {
        assertEquals("10 miles", VehicleController.formatRemaining(10L, VehicleController.ScheduleUnit.MILES))
        assertEquals("49 miles", VehicleController.formatRemaining(49L, VehicleController.ScheduleUnit.MILES))
    }

    // Above the exact-reporting threshold, rounding may only ever understate.
    @Test
    fun `formatRemaining rounding never overstates the remaining margin`() {
        for (n in 50L..5000L) {
            val spoken = VehicleController.formatRemaining(n, VehicleController.ScheduleUnit.MILES)
            val value = spoken.removeSuffix(" miles").toLong()
            assertTrue("$n mi spoken as $spoken overstates the margin", value <= n)
        }
    }

    @Test
    fun `formatRemaining pluralizes days correctly`() {
        assertEquals("3 days", VehicleController.formatRemaining(3L, VehicleController.ScheduleUnit.DAYS))
    }

    @Test
    fun `formatRemaining singularizes exactly 1 day`() {
        assertEquals("1 day", VehicleController.formatRemaining(1L, VehicleController.ScheduleUnit.DAYS))
    }

    @Test
    fun `formatRemaining reports zero days as today, not '0 days'`() {
        assertEquals("today", VehicleController.formatRemaining(0L, VehicleController.ScheduleUnit.DAYS))
    }

    @Test
    fun `formatRemaining reports negative days (already past due-today) as today too`() {
        assertEquals("today", VehicleController.formatRemaining(-1L, VehicleController.ScheduleUnit.DAYS))
    }

    // Regression: isDue checks neverDone FIRST and returns true unconditionally,
    // so an item left flagged never-done after being serviced reads as
    // permanently overdue and is re-injected into the live prompt every turn.
    // logServiceDirect (the "I just did this now" path) used to leave it set,
    // which is the normal sequence: confirm never done, do the work, log it.
    @Test
    fun `an item marked never-done is not still due once it carries an anchor`() {
        val serviced = item(neverDone = false, lastDoneMileage = 45_000, lastDoneDate = 1_000L)
            .copy(intervalMiles = 5_000, intervalMonths = 6)
        assertTrue(
            "a just-serviced item must not read as due",
            !VehicleController.isDue(serviced, currentMileage = 45_100, now = 2_000L),
        )
        assertTrue(
            "the same item still flagged never-done stays due forever",
            VehicleController.isDue(serviced.copy(neverDone = true), currentMileage = 45_100, now = 2_000L),
        )
    }

    // --- canonicalizeServiceName (longest match, not first-in-list) ----------

    // Regression: keywords are matched by substring, so "cabin air filter"
    // CONTAINS "air filter". Under the old first-match-wins, logging the cabin
    // filter stamped the ENGINE air filter's row and said so out loud - no LLM
    // phrasing needed, just list order.
    @Test
    fun `cabin air filter does not collapse into the engine air filter`() {
        assertEquals("Cabin Air Filter", VehicleController.canonicalizeServiceName("cabin air filter"))
        assertEquals("Cabin Air Filter", VehicleController.canonicalizeServiceName("Cabin Air Filter"))
        assertEquals("Air Filter", VehicleController.canonicalizeServiceName("air filter"))
    }

    // Regression: "Brake Pads" carried a bare "brake" keyword, so "Brake Fluid" -
    // a service the severe-schedule lookup asks for BY NAME - canonicalized onto
    // the brake pads row, moving a time-based interval onto a mileage service.
    @Test
    fun `brake fluid does not collapse into brake pads`() {
        assertEquals("Brake Fluid", VehicleController.canonicalizeServiceName("brake fluid"))
        assertEquals("Brake Pads", VehicleController.canonicalizeServiceName("brakes"))
        assertEquals("Brake Pads", VehicleController.canonicalizeServiceName("brake pad"))
    }

    @Test
    fun `an out-of-vocabulary service passes through titlecased`() {
        assertEquals("Timing belt", VehicleController.canonicalizeServiceName("timing belt"))
        assertEquals("Differential fluid", VehicleController.canonicalizeServiceName("differential fluid"))
    }

    // --- mergeBackfillAnchors (logPastServiceDirect's anchor-merge rules) -----

    @Test
    fun `neverDone clears both prior anchors`() {
        val base = item(lastDoneMileage = 40_000, lastDoneDate = 1000)
        val merged = VehicleController.mergeBackfillAnchors(
            base = base, mileage = null, milesAgo = null, date = null, neverDone = true, currentMileage = 45_000,
        )
        assertTrue(merged.neverDone)
        assertNull(merged.lastDoneMileage)
        assertNull(merged.lastDoneDate)
    }

    @Test
    fun `supplying a mileage anchor clears neverDone and the stale date anchor`() {
        val base = item(neverDone = true)
        val merged = VehicleController.mergeBackfillAnchors(
            base = base, mileage = 42_000, milesAgo = null, date = null, neverDone = false, currentMileage = 45_000,
        )
        assertTrue(!merged.neverDone)
        assertEquals(42_000, merged.lastDoneMileage)
        assertNull("a mileage-only update must not pair with a stale prior date", merged.lastDoneDate)
    }

    @Test
    fun `supplying only a date anchor clears the stale mileage anchor`() {
        val base = item(lastDoneMileage = 40_000, lastDoneDate = 1000)
        val merged = VehicleController.mergeBackfillAnchors(
            base = base, mileage = null, milesAgo = null, date = 5000L, neverDone = false, currentMileage = 45_000,
        )
        assertEquals(5000L, merged.lastDoneDate)
        assertNull("a date-only update must not pair with a stale prior mileage", merged.lastDoneMileage)
    }

    @Test
    fun `supplying both mileage and date keeps both, no clearing`() {
        val base = item(lastDoneMileage = 40_000, lastDoneDate = 1000)
        val merged = VehicleController.mergeBackfillAnchors(
            base = base, mileage = 42_000, milesAgo = null, date = 5000L, neverDone = false, currentMileage = 45_000,
        )
        assertEquals(42_000, merged.lastDoneMileage)
        assertEquals(5000L, merged.lastDoneDate)
    }
}
