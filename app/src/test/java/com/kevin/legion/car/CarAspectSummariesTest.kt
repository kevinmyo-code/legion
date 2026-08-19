package com.kevin.legion.car

import com.kevin.legion.car.CarAspectSummaries.CarRow
import com.kevin.legion.car.CarAspectSummaries.GaugeReading
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-logic coverage for the Android Auto tab rows. `fleetRows` itself is NOT unit tested (Room +
 * `ObdBluetoothManager`-backed, would need Robolectric - same "not worth chasing" call CLAUDE.md
 * §10 already makes for `LedgerController`/`PantryController`'s DB-write paths); only the `shape*`
 * functions are exercised here, plain JVM, same posture as `ui/fleet/CarRowsTest.kt`.
 *
 * **RESCOPED 2026-08-18** (Kevin, live, an hour after the four-tab build: "i think the android auto
 * only has to show fleet data...we just need 2 things. push to talk and codes/telemetry gauges").
 * The old `today`/`money` coverage is gone with the code it tested; `shapeFleetRows` itself changed
 * shape entirely (connection + codes + gauges, not vehicle + maintenance-due) - see
 * `CarAspectSummaries.kt`'s own class doc for the full quote and the supersession note.
 */
class CarAspectSummariesTest {

    private val NOW = 1_000_000_000L

    // ------------------------------------------------------------------------------- connection row

    @Test
    fun `connection row states which car and whether the link is live, in the title`() {
        assertEquals(
            CarRow("Fleet · Outlander - connected", "OBD link is live"),
            CarAspectSummaries.shapeConnectionRow("Fleet · Outlander", connected = true),
        )
        assertEquals(
            CarRow("Fleet · Outlander - not connected", "OBD adapter is not linked right now"),
            CarAspectSummaries.shapeConnectionRow("Fleet · Outlander", connected = false),
        )
    }

    // ------------------------------------------------------------------------------- codes row

    @Test
    fun `codes-unknown is an honest row, never a fabricated clean scan`() {
        val row = CarAspectSummaries.shapeCodesRow(
            hasEverReadCodes = false, activeCodes = emptyList(), latestCodeCheckMs = null, nowMs = NOW,
        )
        assertEquals(CarRow("Codes not read", "no OBD trouble-code scan on file yet"), row)
    }

    @Test
    fun `a null timestamp with hasEverReadCodes true still reads as unknown, never trusted blindly`() {
        // Defensive: the two fields should never disagree in real data (fleetRows derives
        // latestCodeCheckMs from the same list hasEverReadCodes is built from), but the row must
        // stay honest even if a caller manages to pass them out of sync.
        val row = CarAspectSummaries.shapeCodesRow(
            hasEverReadCodes = true, activeCodes = emptyList(), latestCodeCheckMs = null, nowMs = NOW,
        )
        assertEquals("Codes not read", row.title)
    }

    @Test
    fun `scanned and clean states the check age, not just the absence of codes`() {
        val checkedAt = NOW - 5 * 60_000L // 5 minutes ago
        val row = CarAspectSummaries.shapeCodesRow(
            hasEverReadCodes = true, activeCodes = emptyList(), latestCodeCheckMs = checkedAt, nowMs = NOW,
        )
        assertEquals("No active codes - checked 5 minutes ago", row.title)
    }

    @Test
    fun `active codes are named and sorted, with the same age in words`() {
        val checkedAt = NOW - 60_000L // 1 minute ago
        val row = CarAspectSummaries.shapeCodesRow(
            hasEverReadCodes = true,
            activeCodes = listOf("P0420", "P0128"),
            latestCodeCheckMs = checkedAt,
            nowMs = NOW,
        )
        assertEquals("P0128, P0420 - checked 1 minute ago", row.title)
        assertEquals("2 code(s), last checked 1 minute ago", row.subtitle)
    }

    // ------------------------------------------------------------------------------- gauge row

    @Test
    fun `nothing connected and nothing cached says not connected, not a stale number`() {
        val row = CarAspectSummaries.shapeGaugeRow(
            "RPM", connected = false, sampleTimestampMs = null, formattedValue = null, nowMs = NOW,
        )
        assertEquals(CarRow("RPM - not connected", "connect the OBD adapter to read this"), row)
    }

    @Test
    fun `connected but no sample yet is distinct from not connected`() {
        val row = CarAspectSummaries.shapeGaugeRow(
            "RPM", connected = true, sampleTimestampMs = null, formattedValue = null, nowMs = NOW,
        )
        assertEquals(CarRow("RPM - waiting for first reading", "connected, no sample yet"), row)
    }

    @Test
    fun `a fresh sample reads live, number first`() {
        val row = CarAspectSummaries.shapeGaugeRow(
            "RPM", connected = true, sampleTimestampMs = NOW - 10_000L, formattedValue = "1800 rpm", nowMs = NOW,
        )
        assertEquals("1800 rpm - RPM · live", row.title)
        assertTrue(row.subtitle.contains("updated"))
    }

    @Test
    fun `a stale sample never reads as current, number still first`() {
        // Older than the 90 s fresh window (three missed 30 s ticks).
        val row = CarAspectSummaries.shapeGaugeRow(
            "RPM", connected = true, sampleTimestampMs = NOW - 20 * 60_000L, formattedValue = "0 rpm", nowMs = NOW,
        )
        assertEquals("0 rpm - RPM · 20 minutes ago, not live", row.title)
        assertTrue(row.subtitle.contains("last read"))
    }

    @Test
    fun `a sample exactly at the fresh boundary still reads live`() {
        val row = CarAspectSummaries.shapeGaugeRow(
            "RPM", connected = true, sampleTimestampMs = NOW - 90_000L, formattedValue = "0 rpm", nowMs = NOW,
        )
        assertTrue(row.title.endsWith("· live"))
    }

    // ------------------------------------------------------------------------------- shapeFleetRows composition

    @Test
    fun `fleet rows compose connection, codes, then every gauge in order`() {
        val rows = CarAspectSummaries.shapeFleetRows(
            vehicleLabel = "Fleet · Outlander",
            connected = true,
            gauges = listOf(
                GaugeReading("RPM", NOW - 10_000L, "1800 rpm"),
                GaugeReading("Coolant temp", NOW - 10_000L, "82°C"),
                GaugeReading("Speed"),
            ),
            hasEverReadCodes = true,
            activeCodes = emptyList(),
            latestCodeCheckMs = NOW - 60_000L,
            nowMs = NOW,
        )
        assertEquals(5, rows.size)
        assertTrue(rows[0].title.startsWith("Fleet · Outlander"))
        assertTrue(rows[1].title.startsWith("No active codes"))
        // Gauge rows lead with the NUMBER now, not the label - see shapeGaugeRow's own doc.
        assertTrue(rows[2].title.startsWith("1800 rpm"))
        assertTrue(rows[2].title.contains("RPM"))
        assertTrue(rows[3].title.startsWith("82°C"))
        assertTrue(rows[3].title.contains("Coolant temp"))
        assertEquals("Speed - waiting for first reading", rows[4].title)
    }
}
