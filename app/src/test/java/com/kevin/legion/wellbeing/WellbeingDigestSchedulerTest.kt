package com.kevin.legion.wellbeing

import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pure-logic coverage for [WellbeingDigestScheduler.nextTriggerAt] - identical arithmetic to
 * [com.kevin.legion.sitrep.SitrepScheduler.nextTriggerAt], tested here separately because the two
 * are deliberately separate `internal` copies (see [WellbeingDigestScheduler]'s own doc for why).
 * No `Context`, no real `AlarmManager`.
 */
class WellbeingDigestSchedulerTest {

    private val zone: ZoneId = ZoneId.of("UTC")

    @Test
    fun `schedules later today when the time has not passed yet`() {
        val now = Instant.parse("2026-08-21T08:00:00Z").toEpochMilli()
        val next = WellbeingDigestScheduler.nextTriggerAt(hour = 9, minute = 30, now = now, zone = zone)
        assertEquals(Instant.parse("2026-08-21T09:30:00Z").toEpochMilli(), next)
    }

    @Test
    fun `rolls to tomorrow when the time already passed today`() {
        val now = Instant.parse("2026-08-21T10:00:00Z").toEpochMilli()
        val next = WellbeingDigestScheduler.nextTriggerAt(hour = 9, minute = 30, now = now, zone = zone)
        assertEquals(Instant.parse("2026-08-22T09:30:00Z").toEpochMilli(), next)
    }

    @Test
    fun `rolls to tomorrow when the time is exactly now, never fires twice in one moment`() {
        val now = Instant.parse("2026-08-21T09:30:00Z").toEpochMilli()
        val next = WellbeingDigestScheduler.nextTriggerAt(hour = 9, minute = 30, now = now, zone = zone)
        assertEquals(Instant.parse("2026-08-22T09:30:00Z").toEpochMilli(), next)
    }
}
