package com.kevin.legion.advisor.digest

import com.kevin.legion.data.local.CodeEvent
import com.kevin.legion.data.local.MaintenanceItem
import com.kevin.legion.data.local.ServiceRecord
import com.kevin.legion.data.local.Vehicle
import com.kevin.legion.vehicle.VehicleController
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-logic coverage for [FleetDigestBuilder.buildDigestText] and its private classifiers, exposed
 * through the `internal` seams the same way [com.kevin.legion.ui.fleet.FleetRowsTest] tests
 * [com.kevin.legion.ui.fleet.buildDueRows]. No Room, no `Context`.
 */
class FleetDigestBuilderTest {

    private val vehicleId = "test-mac"
    private val now = 1_700_000_000_000L
    private val monthMs = 30L * 24 * 60 * 60 * 1000L

    private fun vehicle(make: String = "Honda", model: String = "Civic", year: Int = 2018, odometerBaseline: Int = 0) =
        Vehicle(obdMac = vehicleId, name = "Car", make = make, model = model, year = year, personaPrompt = "", odometerBaseline = odometerBaseline)

    // ------------------------------------------------------------------------------ empty domain

    @Test
    fun `no maintenance schedule at all reads not logged, never zero`() {
        val text = FleetDigestBuilder.buildDigestText(
            vehicle = vehicle(),
            currentMileage = 50_000,
            items = emptyList(),
            unknownNames = emptyList(),
            nextService = null,
            codeEvents = emptyList(),
            recentServices = emptyList(),
            now = now,
        )
        assertTrue(text.contains("MAINTENANCE not logged"))
        assertTrue(text.contains("NEXT not logged"))
        assertTrue(text.contains("DTC not logged"))
        assertTrue(text.contains("ODOMETER TREND not logged"))
        assertTrue(text.contains("LAST SERVICE not logged"))
        assertFalse("must never render a bare 0 count for an absent record", text.contains(" 0 "))
    }

    // ---------------------------------------------------------------------------- due-axis logic

    @Test
    fun `a neverDone item reads overdue-now, never logged - not a computed axis`() {
        val item = MaintenanceItem(vehicleId = vehicleId, serviceName = "Tire Rotation", neverDone = true)
        val text = FleetDigestBuilder.buildDigestText(
            vehicle = vehicle(), currentMileage = 10_000, items = listOf(item), unknownNames = emptyList(),
            nextService = null, codeEvents = emptyList(), recentServices = emptyList(), now = now,
        )
        assertTrue(text.contains("DUE Tire Rotation overdue-now (never logged)"))
    }

    @Test
    fun `a mileage-overdue item names the miles axis, whichever comes first`() {
        val item = MaintenanceItem(
            vehicleId = vehicleId, serviceName = "Oil Change",
            intervalMiles = 5000, lastDoneMileage = 100_000,
        )
        // odometerBaseline must be non-zero here - the miles axis this test exercises is refused
        // outright when the vehicle's own odometer is unset (senior-dev review fix, mission-control
        // ticket 09 follow-up: chooseDueAxis gates on vehicle.odometerBaseline == 0, not on
        // currentMileage, so a default-zero-baseline vehicle() would read "odometer not set" instead
        // of the overdue-miles phrase this test asserts).
        val text = FleetDigestBuilder.buildDigestText(
            vehicle = vehicle(odometerBaseline = 100_000), currentMileage = 106_000, items = listOf(item), unknownNames = emptyList(),
            nextService = null, codeEvents = emptyList(), recentServices = emptyList(), now = now,
        )
        // ticket 09 rewrote DueRowView.sub to name the axis(es) AND the due-ness in one phrase -
        // FleetDigestBuilder now uses that phrase bare rather than wrapping it in a second "overdue (...)".
        assertTrue(text.contains("DUE Oil Change every 5,000 mi - overdue"))
    }

    @Test
    fun `an unknown item is surfaced separately from due, never called overdue`() {
        val text = FleetDigestBuilder.buildDigestText(
            vehicle = vehicle(), currentMileage = 10_000,
            items = listOf(MaintenanceItem(vehicleId = vehicleId, serviceName = "Coolant Flush")),
            unknownNames = listOf("Coolant Flush"),
            nextService = null, codeEvents = emptyList(), recentServices = emptyList(), now = now,
        )
        assertTrue(text.contains("UNKNOWN 1 items no anchor: Coolant Flush"))
        assertFalse(text.contains("DUE Coolant Flush"))
    }

    @Test
    fun `an overdue SEEDED item's DUE line carries the guess disclosure in words - ticket 15 gap 2`() {
        // Ticket 06 required this on six surfaces and missed FleetDigestBuilder - it consumes
        // DueRowView.sub (already-formatted), so the audit's `intervalMiles|intervalMonths` grep
        // could never have matched it. row.isGuess reuses ui.fleet.isGuessTag's exact rule
        // (SEEDED AND an interval exists) rather than reinventing it.
        val item = MaintenanceItem(
            vehicleId = vehicleId, serviceName = "Oil Change",
            intervalMiles = 5000, lastDoneMileage = 100_000, intervalSource = "SEEDED",
        )
        val text = FleetDigestBuilder.buildDigestText(
            vehicle = vehicle(odometerBaseline = 100_000), currentMileage = 106_000, items = listOf(item), unknownNames = emptyList(),
            nextService = null, codeEvents = emptyList(), recentServices = emptyList(), now = now,
        )
        assertTrue(text.contains("DUE Oil Change every 5,000 mi - overdue - guess, unconfirmed"))
    }

    @Test
    fun `a CONFIRMED item's overdue DUE line carries no guess disclosure`() {
        val item = MaintenanceItem(
            vehicleId = vehicleId, serviceName = "Oil Change",
            intervalMiles = 5000, lastDoneMileage = 100_000, intervalSource = "CONFIRMED",
        )
        val text = FleetDigestBuilder.buildDigestText(
            vehicle = vehicle(odometerBaseline = 100_000), currentMileage = 106_000, items = listOf(item), unknownNames = emptyList(),
            nextService = null, codeEvents = emptyList(), recentServices = emptyList(), now = now,
        )
        assertTrue(text.contains("DUE Oil Change every 5,000 mi - overdue [reported]"))
        assertFalse("a driver-confirmed interval must never carry the guess disclosure", text.contains("guess"))
    }

    @Test
    fun `a neverDone SEEDED item's overdue-now line still carries the guess disclosure when it has an interval`() {
        // isGuessTag's own rule: an interval must exist to doubt. A neverDone item that DOES carry a
        // seeded interval is still a guess about that interval, even though its phrase bypasses
        // DueRowView.sub for the "overdue-now (never logged)" wording.
        val item = MaintenanceItem(
            vehicleId = vehicleId, serviceName = "Timing Belt", neverDone = true,
            intervalMiles = 90_000, intervalSource = "SEEDED",
        )
        val text = FleetDigestBuilder.buildDigestText(
            vehicle = vehicle(), currentMileage = 10_000, items = listOf(item), unknownNames = emptyList(),
            nextService = null, codeEvents = emptyList(), recentServices = emptyList(), now = now,
        )
        assertTrue(text.contains("DUE Timing Belt overdue-now (never logged) - guess, unconfirmed"))
    }

    @Test
    fun `a schedule with nothing overdue reads MAINTENANCE DUE none`() {
        val item = MaintenanceItem(
            vehicleId = vehicleId, serviceName = "Oil Change",
            intervalMiles = 5000, lastDoneMileage = 100_000,
        )
        val text = FleetDigestBuilder.buildDigestText(
            vehicle = vehicle(), currentMileage = 101_000, items = listOf(item), unknownNames = emptyList(),
            nextService = null, codeEvents = emptyList(), recentServices = emptyList(), now = now,
        )
        assertTrue(text.contains("MAINTENANCE DUE none"))
    }

    // -------------------------------------------------------------------------------- NEXT axis

    @Test
    fun `next service names both axes with whichever-comes-first phrasing when one item leads both`() {
        val next = VehicleController.NextService(
            byMiles = VehicleController.ServiceCandidate("Oil Change", 400, VehicleController.ScheduleUnit.MILES),
            byTime = VehicleController.ServiceCandidate("Oil Change", 10, VehicleController.ScheduleUnit.DAYS),
            unknownCount = 0, unknownNames = emptyList(), odometerUnset = false, allDue = false,
        )
        val text = FleetDigestBuilder.buildDigestText(
            vehicle = vehicle(), currentMileage = 10_000, items = emptyList(), unknownNames = emptyList(),
            nextService = next, codeEvents = emptyList(), recentServices = emptyList(), now = now,
        )
        assertTrue(text.contains("NEXT Oil Change in"))
        assertTrue(text.contains("whichever comes first"))
    }

    @Test
    fun `allDue reads as its own distinct state, never collapsed into not logged`() {
        val next = VehicleController.NextService(
            byMiles = null, byTime = null, unknownCount = 0, unknownNames = emptyList(),
            odometerUnset = false, allDue = true,
        )
        val text = FleetDigestBuilder.buildDigestText(
            vehicle = vehicle(), currentMileage = 10_000, items = emptyList(), unknownNames = emptyList(),
            nextService = next, codeEvents = emptyList(), recentServices = emptyList(), now = now,
        )
        assertTrue(text.contains("NEXT everything anchored is already due"))
    }

    // ----------------------------------------------------------------------------- DTC severity

    @Test
    fun `classifyDtcSeverity matches the FleetPlaybook's own three named tiers`() {
        assertEquals("stop-now", FleetDigestBuilder.classifyDtcSeverity("P0300"))
        assertEquals("stop-now", FleetDigestBuilder.classifyDtcSeverity("P0308"))
        assertEquals("check-soon", FleetDigestBuilder.classifyDtcSeverity("P0171"))
        assertEquals("check-soon", FleetDigestBuilder.classifyDtcSeverity("P0420"))
        assertEquals("check-soon", FleetDigestBuilder.classifyDtcSeverity("P0128"))
        assertEquals("check-soon", FleetDigestBuilder.classifyDtcSeverity("P0135")) // P01xx sensor-circuit
        assertEquals("drive-on", FleetDigestBuilder.classifyDtcSeverity("P0442"))
        assertEquals("unclassified", FleetDigestBuilder.classifyDtcSeverity("P0700"))
    }

    @Test
    fun `dtc lines carry the code and its severity tag, newest first`() {
        val events = listOf(
            CodeEvent(vehicleId = vehicleId, timestamp = now - 1000, codesJson = "[\"P0300\"]"),
            CodeEvent(vehicleId = vehicleId, timestamp = now - 5000, codesJson = "[\"P0442\"]"),
        )
        val text = FleetDigestBuilder.buildDigestText(
            vehicle = vehicle(), currentMileage = 10_000, items = emptyList(), unknownNames = emptyList(),
            nextService = null, codeEvents = events, recentServices = emptyList(), now = now,
        )
        assertTrue(text.contains("DTC P0300 [stop-now]"))
        assertTrue(text.contains("DTC P0442 [drive-on]"))
    }

    // --------------------------------------------------------------------------- odometer/service

    @Test
    fun `odometer trend buckets recent service mileage by month, missing months read not logged`() {
        val records = listOf(
            ServiceRecord(vehicleId = vehicleId, serviceName = "Oil Change", mileage = 50_000, date = now - 5 * 24 * 60 * 60 * 1000L),
        )
        val text = FleetDigestBuilder.buildDigestText(
            vehicle = vehicle(), currentMileage = 50_200, items = emptyList(), unknownNames = emptyList(),
            nextService = null, codeEvents = emptyList(), recentServices = records, now = now,
        )
        assertTrue(text.contains("ODOMETER TREND"))
        assertTrue(text.contains("current 50,000"))
        // Only the current bucket has a record - the -1mo/-2mo/-3mo buckets are genuinely absent.
        assertTrue(text.contains("not logged"))
    }

    // ------------------------------------------------------------------------- odometer labelling

    @Test
    fun `a confirmed reading renders the odometer bare, an estimate carries its caveat`() {
        // Ticket 10: FleetDigestBuilder no longer formats currentMileage bare itself (the "VEHICLE
        // ... odometer" and "ODOMETER TREND (current ...)" lines) - it renders whatever
        // VehicleController.mileageLabel decided, so this test exercises the real function rather
        // than re-deriving the bare/estimate split a second time.
        val confirmed = vehicle(odometerBaseline = 50_000)
        val confirmedText = FleetDigestBuilder.buildDigestText(
            vehicle = confirmed, currentMileage = 50_000, items = emptyList(), unknownNames = emptyList(),
            nextService = null, codeEvents = emptyList(), recentServices = emptyList(), now = now,
            mileageLabel = VehicleController.mileageLabel(confirmed, now),
        )
        assertTrue(confirmedText.contains("odometer 50,000 mi"))
        assertFalse("a confirmed reading must never carry an estimate caveat", confirmedText.contains("estimated"))

        val estimated = confirmed.copy(tripMilesSinceBaseline = 42.0, odometerBaselineAt = now - 3 * DAY)
        val estimatedText = FleetDigestBuilder.buildDigestText(
            vehicle = estimated, currentMileage = 50_042, items = emptyList(), unknownNames = emptyList(),
            nextService = null, codeEvents = emptyList(), recentServices = emptyList(), now = now,
            mileageLabel = VehicleController.mileageLabel(estimated, now),
        )
        assertTrue(estimatedText.contains("odometer about 50,042 mi - estimated, last confirmed 3 days ago"))
    }

    @Test
    fun `an unset odometer reads not logged, never a blank label`() {
        val text = FleetDigestBuilder.buildDigestText(
            vehicle = vehicle(), currentMileage = 0, items = emptyList(), unknownNames = emptyList(),
            nextService = null, codeEvents = emptyList(), recentServices = emptyList(), now = now,
            // mileageLabel intentionally omitted (defaults to "") - the odometer was never set.
        )
        assertTrue(text.contains("VEHICLE 2018 Honda Civic odometer not logged"))
    }

    @Test
    fun `last service names up to three most recent, newest first as given`() {
        val records = listOf(
            ServiceRecord(vehicleId = vehicleId, serviceName = "Oil Change", mileage = 50_000, date = now - DAY),
            ServiceRecord(vehicleId = vehicleId, serviceName = "Tire Rotation", mileage = 49_000, date = now - 2 * DAY),
        )
        val text = FleetDigestBuilder.buildDigestText(
            vehicle = vehicle(), currentMileage = 50_200, items = emptyList(), unknownNames = emptyList(),
            nextService = null, codeEvents = emptyList(), recentServices = records, now = now,
        )
        assertTrue(text.contains("LAST SERVICE Oil Change"))
        assertTrue(text.contains("LAST SERVICE Tire Rotation"))
    }

    private val DAY = 24L * 60 * 60 * 1000
}
