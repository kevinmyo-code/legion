package com.kevin.midnightai.vehicle

import org.junit.Assert.assertEquals
import org.junit.Test

class ObdHistoryTest {

    @Test
    fun `empty input returns empty list`() {
        assertEquals(emptyList<ObdHistory.DriveWindow>(), ObdHistory.splitDrives(emptyList()))
    }

    @Test
    fun `single sample returns one window with equal bounds`() {
        val windows = ObdHistory.splitDrives(listOf(1_000L))
        assertEquals(listOf(ObdHistory.DriveWindow(1_000L, 1_000L)), windows)
    }

    @Test
    fun `two samples 30s apart form one drive`() {
        // Newest-first: t=30_000 then t=0.
        val windows = ObdHistory.splitDrives(listOf(30_000L, 0L))
        assertEquals(listOf(ObdHistory.DriveWindow(fromMs = 0L, toMs = 30_000L)), windows)
    }

    @Test
    fun `two samples 20 minutes apart form two drives`() {
        val newest = 20 * 60 * 1000L
        val windows = ObdHistory.splitDrives(listOf(newest, 0L))
        assertEquals(
            listOf(
                ObdHistory.DriveWindow(fromMs = newest, toMs = newest),
                ObdHistory.DriveWindow(fromMs = 0L, toMs = 0L),
            ),
            windows,
        )
    }

    @Test
    fun `realistic three-drive sequence splits correctly`() {
        // Drive A (oldest): 0, 30_000 (30s cadence).
        // Gap of 15 min, then drive B: 930_000, 960_000, 990_000 (30s cadence).
        // Gap of 20 min, then drive C (newest): 2_190_000, 2_220_000.
        val driveA = listOf(30_000L, 0L)
        val driveB = listOf(990_000L, 960_000L, 930_000L)
        val driveC = listOf(2_220_000L, 2_190_000L)
        // Sanity: both gaps really do exceed DRIVE_GAP_MS.
        check(driveB.last() - driveA.first() > ObdHistory.DRIVE_GAP_MS)
        check(driveC.last() - driveB.first() > ObdHistory.DRIVE_GAP_MS)
        val timestamps = driveC + driveB + driveA // newest-first, as recentTimestamps() returns it
        val windows = ObdHistory.splitDrives(timestamps)
        assertEquals(
            listOf(
                ObdHistory.DriveWindow(fromMs = 2_190_000L, toMs = 2_220_000L),
                ObdHistory.DriveWindow(fromMs = 930_000L, toMs = 990_000L),
                ObdHistory.DriveWindow(fromMs = 0L, toMs = 30_000L),
            ),
            windows,
        )
    }

    @Test
    fun `gap exactly at the boundary does not split`() {
        val newest = ObdHistory.DRIVE_GAP_MS
        val windows = ObdHistory.splitDrives(listOf(newest, 0L))
        assertEquals(listOf(ObdHistory.DriveWindow(fromMs = 0L, toMs = newest)), windows)
    }

    @Test
    fun `gap one millisecond past the boundary does split`() {
        val newest = ObdHistory.DRIVE_GAP_MS + 1
        val windows = ObdHistory.splitDrives(listOf(newest, 0L))
        assertEquals(
            listOf(
                ObdHistory.DriveWindow(fromMs = newest, toMs = newest),
                ObdHistory.DriveWindow(fromMs = 0L, toMs = 0L),
            ),
            windows,
        )
    }

    @Test
    fun `pidLabel is total and never crashes on an unknown code`() {
        assertEquals("RPM", ObdHistory.pidLabel("010C"))
        assertEquals("Coolant", ObdHistory.pidLabel("0105"))
        assertEquals("Engine load", ObdHistory.pidLabel("0104"))
        assertEquals("Airflow (MAF)", ObdHistory.pidLabel("0110"))
        assertEquals("Speed", ObdHistory.pidLabel("010D"))
        assertEquals("Fuel level", ObdHistory.pidLabel("012F"))
        assertEquals("Short fuel trim", ObdHistory.pidLabel("0106"))
        assertEquals("Long fuel trim", ObdHistory.pidLabel("0107"))
        assertEquals("Battery voltage", ObdHistory.pidLabel("ATRV"))
        assertEquals("Intake air", ObdHistory.pidLabel("010F"))
        assertEquals("MPG", ObdHistory.pidLabel("MPG_TRIP"))
        assertEquals("Trip distance", ObdHistory.pidLabel("TRIP_MILES"))
        assertEquals("Cold start", ObdHistory.pidLabel("COLD_START"))
        assertEquals("XYZZY", ObdHistory.pidLabel("XYZZY"))
    }
}
