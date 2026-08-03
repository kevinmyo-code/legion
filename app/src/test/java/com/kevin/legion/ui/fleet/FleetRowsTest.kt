package com.kevin.legion.ui.fleet

import com.kevin.legion.data.local.CodeEvent
import com.kevin.legion.data.local.MaintenanceItem
import com.kevin.legion.data.local.OdbSample
import org.junit.Assert.assertEquals
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
    fun `an overdue item reports OVERDUE with the mileage-axis subtitle`() {
        val item = MaintenanceItem(
            vehicleId = vehicleId, serviceName = "Oil Change",
            intervalMiles = 5000, lastDoneMileage = 132_400,
        )
        val rows = buildDueRows(listOf(item), currentMileage = 138_000, now = now)
        assertEquals(1, rows.size)
        assertEquals("OVERDUE", rows[0].value)
        assertEquals(true, rows[0].overdue)
        assertEquals("every 5,000 mi - last at 132,400", rows[0].sub)
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
        val rows = buildDueRows(listOf(item), currentMileage = 138_200, now = now)
        assertEquals("in 1800 miles", rows[0].value)
        assertEquals(false, rows[0].overdue)
    }

    @Test
    fun `a not-yet-due time item reports remaining days, never the miles axis`() {
        val item = MaintenanceItem(
            vehicleId = vehicleId, serviceName = "Cabin Air Filter",
            intervalMonths = 12, lastDoneDate = now - monthMs,
        )
        val rows = buildDueRows(listOf(item), currentMileage = 100_000, now = now)
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
        val rows = buildDueRows(listOf(known, unknown), currentMileage = 2000, now = now)
        assertEquals(1, rows.size)
        assertEquals("Oil Change", rows[0].label)
    }

    @Test
    fun `overdue items sort ahead of not-yet-due items, without reordering within either group`() {
        val overdueA = MaintenanceItem(vehicleId = vehicleId, serviceName = "A", intervalMiles = 100, lastDoneMileage = 0)
        val upcoming = MaintenanceItem(vehicleId = vehicleId, serviceName = "B", intervalMiles = 100, lastDoneMileage = 990)
        val overdueC = MaintenanceItem(vehicleId = vehicleId, serviceName = "C", intervalMiles = 100, lastDoneMileage = 0)
        val rows = buildDueRows(listOf(overdueA, upcoming, overdueC), currentMileage = 1000, now = now)
        assertEquals(listOf("A", "C", "B"), rows.map { it.label })
    }

    @Test
    fun `an anchored item with no interval on file reports honestly rather than a bogus remaining value`() {
        val item = MaintenanceItem(vehicleId = vehicleId, serviceName = "Something Logged Once", lastDoneMileage = 50_000)
        val rows = buildDueRows(listOf(item), currentMileage = 60_000, now = now)
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
}
