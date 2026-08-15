package com.kevin.legion.vehicle

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-function regression for ticket 10's two live defects
 * (`.scratch/fleet-maintenance/issues/10-odometer-truth-and-drift.md`, sourced from ticket 03's
 * research): the `wanted("010D")` permanent latch, and the GPS per-tick acceptance floor admitting
 * phantom miles while idling.
 *
 * No Robolectric needed - [TelemetryRecorder.pidWanted] and [TelemetryRecorder.gpsTickMilesAccepted]
 * are pure, `internal`, and take no Context/Room/Android (same posture as
 * [VehicleControllerServiceNameTest]). [TelemetryRecorder.run] itself is NOT exercised here - it's
 * an infinite loop against live Bluetooth/GPS/Room state, out of reach for a plain unit test.
 */
class TelemetryRecorderTest {

    // --- pidWanted: the 010D latch fix -----------------------------------------------------------

    @Test
    fun `010D is wanted even after 3+ consecutive failures - the latch this ticket removes`() {
        // Before the fix, this exact map (3 fails already recorded) permanently excluded 010D from
        // every future tick of the SAME run() invocation - a car with no GPS fix then accrued no
        // distance at all, with nothing anywhere saying so.
        assertTrue(TelemetryRecorder.pidWanted("010D", mapOf("010D" to 3)))
        assertTrue(TelemetryRecorder.pidWanted("010D", mapOf("010D" to 99)))
    }

    @Test
    fun `010D is wanted with no prior failures recorded at all`() {
        assertTrue(TelemetryRecorder.pidWanted("010D", emptyMap()))
    }

    @Test
    fun `every other PID still latches off after 3 consecutive failures - unchanged discovery behavior`() {
        assertFalse(TelemetryRecorder.pidWanted("0105", mapOf("0105" to 3)))
        assertFalse(TelemetryRecorder.pidWanted("0110", mapOf("0110" to 4)))
    }

    @Test
    fun `every other PID is still wanted under the fail threshold`() {
        assertTrue(TelemetryRecorder.pidWanted("0105", mapOf("0105" to 2)))
        assertTrue(TelemetryRecorder.pidWanted("012F", emptyMap()))
    }

    // --- gpsTickMilesAccepted: the raised floor ----------------------------------------------------

    @Test
    fun `the old floor's boundary value (0-001 mi, 1-61 m) is now REJECTED - the phantom-mile regression`() {
        // This exact value used to sit AT the old MIN_TICK_MILES boundary and was accepted every
        // time - below typical 2-5 m GPS static jitter, so an idling engine-running car accrued
        // "phantom miles" every tick. Pinned as a regression: it must now read false.
        assertFalse(TelemetryRecorder.gpsTickMilesAccepted(0.001))
    }

    @Test
    fun `typical GPS static jitter (2-5 m, about 0-0012-0-0031 mi) is rejected by the new floor`() {
        assertFalse(TelemetryRecorder.gpsTickMilesAccepted(0.0012))
        assertFalse(TelemetryRecorder.gpsTickMilesAccepted(0.0031))
    }

    @Test
    fun `the new floor (0-01 mi, about 16 m) itself is accepted`() {
        assertTrue(TelemetryRecorder.gpsTickMilesAccepted(0.01))
    }

    @Test
    fun `real driving distance well above the floor is accepted`() {
        assertTrue(TelemetryRecorder.gpsTickMilesAccepted(0.25))
        assertTrue(TelemetryRecorder.gpsTickMilesAccepted(5.0))
    }

    @Test
    fun `the teleport ceiling is unchanged - still rejects a jump above 5 miles in one tick`() {
        assertFalse(TelemetryRecorder.gpsTickMilesAccepted(5.1))
    }
}
