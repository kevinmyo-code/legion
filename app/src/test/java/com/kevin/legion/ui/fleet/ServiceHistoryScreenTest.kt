package com.kevin.legion.ui.fleet

import com.kevin.legion.data.local.ServiceRecord
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

/**
 * Pure-logic coverage for [groupServiceRecordsByYear] (ticket 11 §3's year grouping on the SERVICE
 * HISTORY screen). No Room, no Android dependency, plain JVM test - same posture as [FleetRowsTest].
 */
class ServiceHistoryScreenTest {
    private fun atLocalYear(year: Int, month: Int, day: Int): Long =
        LocalDate.of(year, month, day).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()

    @Test
    fun `groups records by year and preserves the newest-first order within a year`() {
        val records = listOf(
            ServiceRecord(id = 3, vehicleId = "V1", serviceName = "Oil Change", mileage = 3, date = atLocalYear(2026, 8, 1)),
            ServiceRecord(id = 2, vehicleId = "V1", serviceName = "Tire Rotation", mileage = 2, date = atLocalYear(2026, 2, 1)),
            ServiceRecord(id = 1, vehicleId = "V1", serviceName = "Air Filter", mileage = 1, date = atLocalYear(2025, 6, 1)),
        )

        val grouped = groupServiceRecordsByYear(records)

        assertEquals(2, grouped.size)
        assertEquals(2026, grouped[0].first)
        assertEquals(listOf(3L, 2L), grouped[0].second.map { it.id })
        assertEquals(2025, grouped[1].first)
        assertEquals(listOf(1L), grouped[1].second.map { it.id })
    }

    @Test
    fun `an empty list groups to an empty list`() {
        assertEquals(emptyList<Pair<Int, List<ServiceRecord>>>(), groupServiceRecordsByYear(emptyList()))
    }
}
