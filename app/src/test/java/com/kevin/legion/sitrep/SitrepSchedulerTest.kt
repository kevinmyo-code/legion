package com.kevin.legion.sitrep

import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pure-logic coverage for [SitrepScheduler.nextTriggerAt] - the "re-arm on fire, never
 * `setRepeating`" arithmetic [SitrepScheduler]'s own class doc names as
 * [com.kevin.legion.notes.AlarmScheduler]'s precedent. No `Context`, no real `AlarmManager`.
 */
class SitrepSchedulerTest {

    private val zone: ZoneId = ZoneId.of("UTC")

    @Test
    fun `schedules later today when the time has not passed yet`() {
        // 2026-08-21 08:00 UTC
        val now = Instant.parse("2026-08-21T08:00:00Z").toEpochMilli()
        val next = SitrepScheduler.nextTriggerAt(hour = 9, minute = 30, now = now, zone = zone)
        assertEquals(Instant.parse("2026-08-21T09:30:00Z").toEpochMilli(), next)
    }

    @Test
    fun `rolls to tomorrow when the time already passed today`() {
        val now = Instant.parse("2026-08-21T10:00:00Z").toEpochMilli()
        val next = SitrepScheduler.nextTriggerAt(hour = 9, minute = 30, now = now, zone = zone)
        assertEquals(Instant.parse("2026-08-22T09:30:00Z").toEpochMilli(), next)
    }

    @Test
    fun `rolls to tomorrow when the time is exactly now, never fires twice in one moment`() {
        val now = Instant.parse("2026-08-21T09:30:00Z").toEpochMilli()
        val next = SitrepScheduler.nextTriggerAt(hour = 9, minute = 30, now = now, zone = zone)
        assertEquals(Instant.parse("2026-08-22T09:30:00Z").toEpochMilli(), next)
    }

    @Test
    fun `next trigger is always strictly in the future`() {
        val now = Instant.parse("2026-08-21T23:59:00Z").toEpochMilli()
        val next = SitrepScheduler.nextTriggerAt(hour = 0, minute = 0, now = now, zone = zone)
        assertEquals(Instant.parse("2026-08-22T00:00:00Z").toEpochMilli(), next)
    }
}
