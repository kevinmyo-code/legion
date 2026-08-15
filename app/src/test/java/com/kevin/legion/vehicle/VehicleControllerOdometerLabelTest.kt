package com.kevin.legion.vehicle

import com.kevin.legion.data.local.Vehicle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-function regression for ticket 10
 * (`.scratch/fleet-maintenance/issues/10-odometer-truth-and-drift.md`): the estimate-labelling
 * split ([VehicleController.mileageCaveat] / [mileageValueText] / [mileageLabel]) and the
 * below-current-reading "questioned, not refused" rule ([VehicleController.odometerQuestionNote]).
 *
 * No Robolectric needed - every function under test is pure, `internal`, and takes no Context/DB
 * (per CLAUDE.md sec 11's testing convention, same posture as [VehicleControllerServiceNameTest]).
 */
class VehicleControllerOdometerLabelTest {

    private fun vehicle(
        odometerBaseline: Int = 0,
        odometerBaselineAt: Long = 0L,
        tripMilesSinceBaseline: Double = 0.0,
    ) = Vehicle(
        obdMac = "AA:BB:CC:DD:EE:FF",
        name = "Test Car",
        make = "Jeep",
        model = "Cherokee",
        year = 1998,
        personaPrompt = "",
        odometerBaseline = odometerBaseline,
        odometerBaselineAt = odometerBaselineAt,
        tripMilesSinceBaseline = tripMilesSinceBaseline,
    )

    // --- mileageCaveat: the bare/estimate split ------------------------------------------------

    @Test
    fun `mileageCaveat is null the instant a reading is confirmed - nothing has accrued since`() {
        val v = vehicle(odometerBaseline = 227_900, odometerBaselineAt = 1_000L, tripMilesSinceBaseline = 0.0)
        assertNull(VehicleController.mileageCaveat(v, now = 2_000L))
    }

    @Test
    fun `a baseline never set, with trip miles accrued, is the MOST caveated case - not the least`() {
        // Regression, review 2026-08-15. This used to assert null, and the production guard used to
        // return null, so this figure rendered BARE - pure dead reckoning against an anchor that has
        // never existed, the least confirmed number the app can produce, and the one case escaping
        // the caveat entirely.
        //
        // Reachable by ordinary use: AddCarDialog never asks for a reading, and TelemetryRecorder's
        // accumulation is keyed only on the row existing, not on any baseline being set.
        //
        // Ticket 10's rule is about whether the number IS the driver's stated reading, never about
        // whether a drift delta happens to be computable.
        val v = vehicle(odometerBaseline = 0, odometerBaselineAt = 0L, tripMilesSinceBaseline = 42.0)
        assertEquals("estimated, never confirmed", VehicleController.mileageCaveat(v))
    }

    @Test
    fun `mileageValueText carries the about prefix for a never-confirmed baseline`() {
        val v = vehicle(odometerBaseline = 0, odometerBaselineAt = 0L, tripMilesSinceBaseline = 42.0)
        assertEquals("about 42 mi", VehicleController.mileageValueText(v))
    }

    @Test
    fun `mileageCaveat is still null when there is no mileage to show at all`() {
        val v = vehicle(odometerBaseline = 0, odometerBaselineAt = 0L, tripMilesSinceBaseline = 0.0)
        assertNull(VehicleController.mileageCaveat(v))
        assertEquals("", VehicleController.mileageValueText(v))
    }

    @Test
    fun `mileageCaveat names how long ago the baseline was confirmed once anything has accrued`() {
        val now = 4L * 24 * 60 * 60 * 1000 // 4 days, epoch-ish
        val confirmedAt = now - 3L * 24 * 60 * 60 * 1000 // 3 days before now
        val v = vehicle(odometerBaseline = 227_900, odometerBaselineAt = confirmedAt, tripMilesSinceBaseline = 12.4)
        assertEquals("estimated, last confirmed 3 days ago", VehicleController.mileageCaveat(v, now))
    }

    @Test
    fun `mileageCaveat says never confirmed when the baseline was seeded rather than driver-set`() {
        // odometerBaselineAt == 0 with a positive baseline is the seam a hand-written test row can
        // hit even though production code always stamps `at` alongside a real setOdometer write.
        val v = vehicle(odometerBaseline = 100_000, odometerBaselineAt = 0L, tripMilesSinceBaseline = 5.0)
        assertEquals("estimated, never confirmed", VehicleController.mileageCaveat(v))
    }

    // --- mileageValueText: the number half ------------------------------------------------------

    @Test
    fun `mileageValueText is blank when there is no reading at all`() {
        assertEquals("", VehicleController.mileageValueText(vehicle(odometerBaseline = 0, tripMilesSinceBaseline = 0.0)))
    }

    @Test
    fun `mileageValueText renders bare for a confirmed reading`() {
        val v = vehicle(odometerBaseline = 227_900, odometerBaselineAt = 1_000L, tripMilesSinceBaseline = 0.0)
        assertEquals("227,900 mi", VehicleController.mileageValueText(v))
    }

    @Test
    fun `mileageValueText carries the about- prefix once anything has accrued`() {
        val v = vehicle(odometerBaseline = 227_900, odometerBaselineAt = 1_000L, tripMilesSinceBaseline = 12.0)
        assertEquals("about 227,912 mi", VehicleController.mileageValueText(v))
    }

    // --- mileageLabel: the combined spoken sentence ---------------------------------------------

    @Test
    fun `mileageLabel is the ticket's own worked example`() {
        val now = 4L * 24 * 60 * 60 * 1000
        val confirmedAt = now - 3L * 24 * 60 * 60 * 1000
        val v = vehicle(odometerBaseline = 227_900, odometerBaselineAt = confirmedAt, tripMilesSinceBaseline = 0.4)
        // 227,900 + round(0.4) = 227,900 exactly (rounds to 0) - bump trip miles so the number moves
        // visibly and still matches "about 227,900 mi" at low trip mileage.
        assertEquals(
            "about 227,900 mi - estimated, last confirmed 3 days ago",
            VehicleController.mileageLabel(v, now),
        )
    }

    @Test
    fun `mileageLabel renders bare with no dash and no caveat for a confirmed reading`() {
        val v = vehicle(odometerBaseline = 138_204, odometerBaselineAt = 1_000L, tripMilesSinceBaseline = 0.0)
        assertEquals("138,204 mi", VehicleController.mileageLabel(v, now = 2_000L))
    }

    @Test
    fun `mileageLabel is blank when there is nothing to show`() {
        assertEquals("", VehicleController.mileageLabel(vehicle(odometerBaseline = 0, tripMilesSinceBaseline = 0.0)))
    }

    // --- odometerQuestionNote: ticket 10 §7's "questioned, not refused" rule --------------------

    @Test
    fun `a reading below the prior estimate is questioned when a real baseline was already on file`() {
        val note = VehicleController.odometerQuestionNote(miles = 227_800, priorEstimate = 227_900, baselineSet = true)
        assertTrue("expected a note explaining the lower reading", note != null)
        assertTrue(note!!.contains("lower"))
        assertTrue(note.contains("227,900") || note.contains("227900"))
    }

    @Test
    fun `a reading at or above the prior estimate is never questioned`() {
        assertNull(VehicleController.odometerQuestionNote(miles = 227_900, priorEstimate = 227_900, baselineSet = true))
        assertNull(VehicleController.odometerQuestionNote(miles = 228_000, priorEstimate = 227_900, baselineSet = true))
    }

    @Test
    fun `the very first reading on a car is never questioned even though it reads below leftover trip miles`() {
        // baselineSet = false is exactly the "odometerBaseline == 0" case - there is no real prior
        // reading to have drifted from, so a first-ever entry must never be second-guessed against
        // stale accumulator noise.
        assertNull(VehicleController.odometerQuestionNote(miles = 50_000, priorEstimate = 60_000, baselineSet = false))
    }
}
