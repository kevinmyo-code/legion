package com.kevin.legion.vehicle

import com.kevin.legion.data.local.MaintenanceItem
import com.kevin.legion.data.local.ServiceRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

/**
 * Pure-function regression for [MaintenanceAgent.describeItem] - THE live formatter that pre-seeds
 * [MaintenanceAgent.answer]'s prompt (mission-control ticket 16,
 * `.scratch/fleet-maintenance/issues/16-ticket-06-audited-a-dead-surface-and-missed-a-live-one.md`).
 * Ticket 06 required a seeded-interval disclosure on every model-facing surface and instead audited
 * the dead `CarToolbelt.maintenanceSchedule` (zero callers, since deleted); this is the regression
 * against the function that was actually missed.
 *
 * `internal` on [MaintenanceAgent.describeItem] (widened from `private` for exactly this test - see
 * its own doc comment) means no Context/Room/network is needed here, same posture as
 * [VehicleControllerIsDueTest]'s direct calls into [VehicleController]'s own internal pure
 * functions.
 */
class MaintenanceAgentDescribeItemTest {

    private val vehicleId = "test-mac"

    @Test
    fun `a SEEDED interval carries LEGION's-guess wording, in full words, in the interval clause`() {
        val item = MaintenanceItem(
            vehicleId = vehicleId, serviceName = "Oil Change",
            intervalSource = "SEEDED", intervalMiles = 7500, intervalMonths = 6,
            lastDoneMileage = 100_000,
        )
        val line = MaintenanceAgent.describeItem(item)
        assertTrue(
            "expected the guess wording after the interval clause, got: $line",
            line.contains("every 7,500 mi / every 6 mo (LEGION's guess, unconfirmed by the user)"),
        )
    }

    @Test
    fun `a LOOKUP interval carries the distinct factory-lookup wording, not the SEEDED wording`() {
        val item = MaintenanceItem(
            vehicleId = vehicleId, serviceName = "Tire Rotation",
            intervalSource = "LOOKUP", intervalMiles = 7500,
            lastDoneMileage = 50_000,
        )
        val line = MaintenanceAgent.describeItem(item)
        assertTrue(
            "expected the factory-lookup wording, got: $line",
            line.contains("every 7,500 mi (from a factory lookup, unconfirmed by the user)"),
        )
        assertFalse("must not also carry the SEEDED wording", line.contains("LEGION's guess"))
    }

    @Test
    fun `a CONFIRMED interval carries no suffix at all - the driver stated it`() {
        val item = MaintenanceItem(
            vehicleId = vehicleId, serviceName = "Coolant Flush",
            intervalSource = "CONFIRMED", intervalMiles = 30_000,
            lastDoneMileage = 10_000,
        )
        val line = MaintenanceAgent.describeItem(item)
        assertEquals("Coolant Flush: every 30,000 mi; last done at 10,000 mi", line)
    }

    @Test
    fun `an item with no interval on either axis gets no suffix either, regardless of intervalSource`() {
        // SEEDED by column default, nothing to doubt - intervalIsUnconfirmed's own second clause,
        // the exact orphan VehicleController.logServiceDirect creates for a hand-logged service with
        // no schedule yet.
        val item = MaintenanceItem(vehicleId = vehicleId, serviceName = "Brake Fluid", intervalSource = "SEEDED")
        val line = MaintenanceAgent.describeItem(item)
        assertEquals("Brake Fluid: no interval on file; last done UNKNOWN", line)
    }

    // --- Ticket 28: service_records-derived "last done" (`.scratch/hands-and-senses/issues/28-*`).
    // Real numbers pulled off the Jeep's own on-device database, not invented ones - the case that
    // exposed the bug in the first place.

    private fun dateOf(year: Int, month: Int, day: Int): Long =
        LocalDate.of(year, month, day).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()

    @Test
    fun `never render UNKNOWN while a non-deleted record exists - dateless anchor names the record's date`() {
        // The exact device state: Jeep, Oil Change, anchor at 227,483 mi with NO date - the anchor
        // alone would render UNKNOWN under the pre-ticket-28 formatter. A record exists, so it must
        // not.
        val item = MaintenanceItem(
            vehicleId = vehicleId, serviceName = "Oil Change", intervalSource = "CONFIRMED",
            lastDoneMileage = null, lastDoneDate = null,
        )
        val record = ServiceRecord(vehicleId = vehicleId, serviceName = "Oil Change", mileage = 227_374, date = dateOf(2026, 8, 12))
        val line = MaintenanceAgent.describeItem(item, record)
        assertFalse("must never render the bare UNKNOWN sentence while a record exists", line.contains("UNKNOWN"))
        assertTrue("expected the record's own date in the answer, got: $line", line.contains("227,374"))
    }

    @Test
    fun `an unreconcilable anchor-vs-record mileage gap states both facts, never a merged fiction`() {
        // The Jeep's actual numbers: anchor 227,483 mi / no date, record 227,374 mi / 12 Aug -
        // 109 miles apart, well beyond plausible drift. A merged "227,483 mi on 12 Aug" would
        // assert an event that never happened at that mileage.
        val item = MaintenanceItem(
            vehicleId = vehicleId, serviceName = "Oil Change", intervalSource = "CONFIRMED",
            lastDoneMileage = 227_483, lastDoneDate = null,
        )
        val record = ServiceRecord(vehicleId = vehicleId, serviceName = "Oil Change", mileage = 227_374, date = dateOf(2026, 8, 12))
        val line = MaintenanceAgent.describeItem(item, record)
        assertTrue("expected the record's mileage stated, got: $line", line.contains("227,374"))
        assertTrue("expected the anchor's mileage stated too, got: $line", line.contains("227,483"))
        assertFalse(
            "must never merge the anchor's mileage onto the record's date as one fact",
            line.contains("227,483 mi on"),
        )
    }

    @Test
    fun `an anchor mileage within plausible drift of the record is treated as the same event`() {
        val item = MaintenanceItem(
            vehicleId = vehicleId, serviceName = "Oil Change", intervalSource = "CONFIRMED",
            lastDoneMileage = 227_380, lastDoneDate = null,
        )
        val record = ServiceRecord(vehicleId = vehicleId, serviceName = "Oil Change", mileage = 227_374, date = dateOf(2026, 8, 12))
        val line = MaintenanceAgent.describeItem(item, record)
        assertTrue(
            "expected one merged sentence pairing the anchor's mileage with the record's date, got: $line",
            line.contains("at 227,380 mi on"),
        )
    }

    @Test
    fun `a dated anchor that is itself newer than the record wins unchanged - no record consulted`() {
        val newer = dateOf(2026, 8, 20)
        val item = MaintenanceItem(
            vehicleId = vehicleId, serviceName = "Oil Change", intervalSource = "CONFIRMED",
            lastDoneMileage = 227_500, lastDoneDate = newer,
        )
        val record = ServiceRecord(vehicleId = vehicleId, serviceName = "Oil Change", mileage = 227_374, date = dateOf(2026, 8, 12))
        val line = MaintenanceAgent.describeItem(item, record)
        assertTrue("expected the anchor's own newer mileage/date, got: $line", line.contains("227,500"))
        assertFalse("must not fall back to the older record when the anchor is itself newer", line.contains("227,374"))
    }

    @Test
    fun `neverDone still wins outright even when a record is passed in`() {
        val item = MaintenanceItem(vehicleId = vehicleId, serviceName = "Timing Belt", intervalSource = "CONFIRMED", neverDone = true)
        val record = ServiceRecord(vehicleId = vehicleId, serviceName = "Timing Belt", mileage = 50_000, date = dateOf(2020, 1, 1))
        val line = MaintenanceAgent.describeItem(item, record)
        assertTrue(line.endsWith("never been done"))
    }
}
