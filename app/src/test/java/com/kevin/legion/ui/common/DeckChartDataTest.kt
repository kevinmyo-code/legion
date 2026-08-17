package com.kevin.legion.ui.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Unit tests for [DeckChartData.kt]'s pure layer. Plain JUnit, no Robolectric
 * - nothing here touches Android (no `android.util.Base64`-shaped landmine
 * like [com.kevin.legion.ai.SubAgentPartsTest] had to work around), matching
 * ticket 14's brief: "unit-test the label formatting and gap logic (pure
 * functions, no Robolectric)".
 *
 * Four things this suite exists to pin, each named directly in ticket 14:
 * gap preservation, day bucketing across a UTC/local-day disagreement, exact
 * money labels, and the degenerate cases (empty/single/all-equal) that must
 * never divide by zero.
 */
class DeckChartDataTest {

    // ------------------------------------------------------------ gap preservation

    @Test
    fun `an empty day buckets to null, never to a zero DeckPoint`() {
        val zone = ZoneId.of("UTC")
        val day0 = ZonedDateTime.of(2026, 8, 1, 12, 0, 0, 0, zone).toInstant().toEpochMilli()
        val day1 = ZonedDateTime.of(2026, 8, 2, 12, 0, 0, 0, zone).toInstant().toEpochMilli()
        val day2 = ZonedDateTime.of(2026, 8, 3, 12, 0, 0, 0, zone).toInstant().toEpochMilli()
        // Samples only land on day0 and day2 - day1 (the middle bucket) must
        // come back null, not a DeckPoint holding 0f. A caller that folded a
        // missing day into 0 before it reached this function would be
        // exactly the lie CLAUDE.md §4 rule six exists to refuse.
        val samples = listOf(day0 to 10f, day2 to 30f)
        val points = bucketDailyAverage(samples, startMs = day0, endMs = day2, zone = zone)

        assertEquals(3, points.size)
        assertEquals(10f, points[0]!!.y)
        assertNull(points[1])
        assertEquals(30f, points[2]!!.y)
    }

    @Test
    fun `multiple samples in one day average, not sum`() {
        val zone = ZoneId.of("UTC")
        val morning = ZonedDateTime.of(2026, 8, 1, 6, 0, 0, 0, zone).toInstant().toEpochMilli()
        val evening = ZonedDateTime.of(2026, 8, 1, 20, 0, 0, 0, zone).toInstant().toEpochMilli()
        val points = bucketDailyAverage(listOf(morning to 10f, evening to 20f), startMs = morning, endMs = evening, zone = zone)

        assertEquals(1, points.size)
        assertEquals(15f, points[0]!!.y)
    }

    @Test
    fun `an all-gap series still reports the correct bucket count`() {
        val zone = ZoneId.of("UTC")
        val day0 = ZonedDateTime.of(2026, 8, 1, 12, 0, 0, 0, zone).toInstant().toEpochMilli()
        val day2 = ZonedDateTime.of(2026, 8, 3, 12, 0, 0, 0, zone).toInstant().toEpochMilli()
        val points = bucketDailyAverage(emptyList(), startMs = day0, endMs = day2, zone = zone)

        assertEquals(3, points.size)
        assertTrue(points.all { it == null })
    }

    // ------------------------------------------------------ bucketDailySumCents (money)

    @Test
    fun `a covered day with no samples sums to a genuine zero`() {
        val zone = ZoneId.of("UTC")
        val day0 = ZonedDateTime.of(2026, 8, 1, 12, 0, 0, 0, zone).toInstant().toEpochMilli()
        val day1 = ZonedDateTime.of(2026, 8, 2, 12, 0, 0, 0, zone).toInstant().toEpochMilli()
        val day0Start = ZonedDateTime.of(2026, 8, 1, 0, 0, 0, 0, zone).toInstant().toEpochMilli()
        val day1Start = ZonedDateTime.of(2026, 8, 2, 0, 0, 0, 0, zone).toInstant().toEpochMilli()
        val covered = listOf(day0Start..day1Start)
        // No samples at all - both days sit inside the covered window, so
        // both must read 0L (a statement covered them and nothing was
        // spent), never null.
        val sums = bucketDailySumCents(emptyList(), startMs = day0, endMs = day1, coveredRanges = covered, zone = zone)

        assertEquals(2, sums.size)
        assertEquals(0L, sums[0])
        assertEquals(0L, sums[1])
    }

    @Test
    fun `a day outside every covered range is a null gap, not a zero`() {
        val zone = ZoneId.of("UTC")
        val day0 = ZonedDateTime.of(2026, 8, 1, 12, 0, 0, 0, zone).toInstant().toEpochMilli()
        val day1 = ZonedDateTime.of(2026, 8, 2, 12, 0, 0, 0, zone).toInstant().toEpochMilli()
        val day0Start = ZonedDateTime.of(2026, 8, 1, 0, 0, 0, 0, zone).toInstant().toEpochMilli()
        // Only day0 is covered - day1 falls outside every account's
        // statement window and must come back null, never folded into 0L.
        val covered = listOf(day0Start..day0Start)
        val sums = bucketDailySumCents(emptyList(), startMs = day0, endMs = day1, coveredRanges = covered, zone = zone)

        assertEquals(2, sums.size)
        assertEquals(0L, sums[0])
        assertNull(sums[1])
    }

    @Test
    fun `a sample on an uncovered day still sums instead of gapping`() {
        val zone = ZoneId.of("UTC")
        val day0 = ZonedDateTime.of(2026, 8, 1, 12, 0, 0, 0, zone).toInstant().toEpochMilli()
        val day1 = ZonedDateTime.of(2026, 8, 2, 12, 0, 0, 0, zone).toInstant().toEpochMilli()
        val day0Start = ZonedDateTime.of(2026, 8, 1, 0, 0, 0, 0, zone).toInstant().toEpochMilli()
        // day1 is outside coveredRanges, but a real transaction landed on
        // it - data trumps the coverage claim, so it must sum, not gap.
        val covered = listOf(day0Start..day0Start)
        val sums = bucketDailySumCents(listOf(day1 to 500L), startMs = day0, endMs = day1, coveredRanges = covered, zone = zone)

        assertEquals(2, sums.size)
        assertEquals(0L, sums[0])
        assertEquals(500L, sums[1])
    }

    @Test
    fun `null coveredRanges defaults every empty day to zero`() {
        val zone = ZoneId.of("UTC")
        val day0 = ZonedDateTime.of(2026, 8, 1, 12, 0, 0, 0, zone).toInstant().toEpochMilli()
        val day1 = ZonedDateTime.of(2026, 8, 2, 12, 0, 0, 0, zone).toInstant().toEpochMilli()
        // No coverage concept at all - caller asserts full coverage, so
        // every empty day is a genuine 0L, matching bucketDailyAverage's
        // posture for callers with no coverage bookkeeping.
        val sums = bucketDailySumCents(emptyList(), startMs = day0, endMs = day1, coveredRanges = null, zone = zone)

        assertEquals(2, sums.size)
        assertEquals(0L, sums[0])
        assertEquals(0L, sums[1])
    }

    @Test
    fun `bucketDailySumCents bucket count matches dailyBuckets across a DST window`() {
        // America/Chicago springs forward in March; this window straddles a
        // DST-shift day, so the bucket count must match dailyBuckets' own
        // walk-by-calendar-date, not a naive millis-per-day division.
        val chicago = ZoneId.of("America/Chicago")
        val start = ZonedDateTime.of(2026, 3, 7, 23, 0, 0, 0, chicago).toInstant().toEpochMilli()
        val end = ZonedDateTime.of(2026, 3, 10, 1, 0, 0, 0, chicago).toInstant().toEpochMilli()
        val sums = bucketDailySumCents(emptyList(), startMs = start, endMs = end, coveredRanges = null, zone = chicago)

        assertEquals(dailyBuckets(start, end, chicago).size, sums.size)
    }

    @Test
    fun `bucketDailySumCents sums exact Long cents with no rounding drift`() {
        val zone = ZoneId.of("UTC")
        val morning = ZonedDateTime.of(2026, 8, 1, 6, 0, 0, 0, zone).toInstant().toEpochMilli()
        val evening = ZonedDateTime.of(2026, 8, 1, 20, 0, 0, 0, zone).toInstant().toEpochMilli()
        // 184212L is the exact figure DeckChartDataTest already pins for
        // centsLabel - proving the sum itself never drifts before it ever
        // reaches formatting.
        val sums = bucketDailySumCents(
            listOf(morning to 100_000L, evening to 84_212L),
            startMs = morning,
            endMs = evening,
            coveredRanges = null,
            zone = zone,
        )

        assertEquals(1, sums.size)
        assertEquals(184_212L, sums[0])
    }

    // ---------------------------------------------------- day bucketing across a zone

    @Test
    fun `a Chicago evening sample lands on ITS local day, not UTC's`() {
        // 2026-08-01 23:00 America/Chicago (UTC-5 under CDT) is already
        // 2026-08-02 04:00 UTC. Bucketing with the UTC zone would put this
        // sample on Aug 2; bucketing with America/Chicago must put it on
        // Aug 1 - this is exactly the class of bug util/Dates.kt's
        // documentDate doc comment records ("a receipt printed 04/18/2026
        // displayed as Apr 17, 2026 on the A17K at UTC-5").
        val chicago = ZoneId.of("America/Chicago")
        val lateEveningChicago = ZonedDateTime.of(2026, 8, 1, 23, 0, 0, 0, chicago).toInstant().toEpochMilli()
        val chicagoDayStart = ZonedDateTime.of(2026, 8, 1, 0, 0, 0, 0, chicago).toInstant().toEpochMilli()

        val bucketedInChicago = bucketDailyAverage(
            listOf(lateEveningChicago to 42f),
            startMs = lateEveningChicago,
            endMs = lateEveningChicago,
            zone = chicago,
        )
        assertEquals(1, bucketedInChicago.size)
        assertEquals(chicagoDayStart, bucketedInChicago[0]!!.xMs)

        // The same instant, bucketed against a UTC-anchored window that
        // starts and ends on UTC's Aug 2, is a DIFFERENT calendar day and
        // must land as the one sample landing on UTC's Aug 2 bucket, not
        // Chicago's Aug 1 - proving the zone parameter, not a hardcoded
        // assumption, is what decides the bucket.
        val utc = ZoneId.of("UTC")
        val utcDayStart = ZonedDateTime.of(2026, 8, 2, 0, 0, 0, 0, utc).toInstant().toEpochMilli()
        val bucketedInUtc = bucketDailyAverage(
            listOf(lateEveningChicago to 42f),
            startMs = lateEveningChicago,
            endMs = lateEveningChicago,
            zone = utc,
        )
        assertEquals(1, bucketedInUtc.size)
        assertEquals(utcDayStart, bucketedInUtc[0]!!.xMs)
    }

    @Test
    fun `dailyBuckets across a zone spans the correct local days`() {
        val chicago = ZoneId.of("America/Chicago")
        val start = ZonedDateTime.of(2026, 8, 1, 23, 0, 0, 0, chicago).toInstant().toEpochMilli()
        val end = ZonedDateTime.of(2026, 8, 3, 1, 0, 0, 0, chicago).toInstant().toEpochMilli()
        // Aug 1, Aug 2, Aug 3 in Chicago's own calendar - three buckets, not
        // two, even though the raw millis span is barely 26 hours.
        val buckets = dailyBuckets(start, end, chicago)
        assertEquals(3, buckets.size)
    }

    // ------------------------------------------------------------------- money labels

    @Test
    fun `centsLabel formats 184212 cents exactly, no rounding drift`() {
        assertEquals("1,842.12", centsLabel(184_212L))
    }

    @Test
    fun `centsLabel handles negative and zero exactly`() {
        assertEquals("-1,842.12", centsLabel(-184_212L))
        assertEquals("0.00", centsLabel(0L))
    }

    @Test
    fun `centsLabel never rounds a value Double would mangle`() {
        // 0.1 + 0.2 in Double famously isn't 0.3; a cents-as-Double path
        // would risk exactly this class of drift on a figure this kit is
        // required to render exactly (CLAUDE.md §4 rule three).
        assertEquals("0.30", centsLabel(30L))
        assertEquals("10,000.01", centsLabel(1_000_001L))
    }

    // ---------------------------------------------------- deckWholeDollarLabel (bar-top labels)

    @Test
    fun `deckWholeDollarLabel rounds a sub-dollar remainder down to the whole dollar`() {
        // 238.06 -> "238" - the brief's own worked example (238 is what Kevin already reads on
        // the single-bar CRED label today, so this is the register the whole-dollar band matches).
        assertEquals("238", deckWholeDollarLabel(23_806L))
    }

    @Test
    fun `deckWholeDollarLabel on an exact whole dollar is unchanged by rounding`() {
        assertEquals("1", deckWholeDollarLabel(100L))
        assertEquals("238", deckWholeDollarLabel(23_800L))
    }

    @Test
    fun `deckWholeDollarLabel rounds a half-dollar UP, not down or to even`() {
        // 238.50 -> 239, not 238 - round-half-up, done as integer math (see the function's own
        // doc for why this never touches Double).
        assertEquals("239", deckWholeDollarLabel(23_850L))
        assertEquals("1", deckWholeDollarLabel(50L)) // 0.50 -> 1
    }

    @Test
    fun `deckWholeDollarLabel either side of the 1,000-dollar k-cutover`() {
        assertEquals("999", deckWholeDollarLabel(99_900L)) // $999.00 - still plain digits
        assertEquals("1k", deckWholeDollarLabel(100_000L)) // $1,000.00 - now abbreviated
    }

    @Test
    fun `deckWholeDollarLabel carries one decimal of thousands below 10k, exactly the brief's own example`() {
        // 3,500.00 -> "3.5k" - the brief's own worked example.
        assertEquals("3.5k", deckWholeDollarLabel(350_000L))
    }

    @Test
    fun `deckWholeDollarLabel drops a decimal that rounds to exactly a whole thousand`() {
        // 3,000.00 -> "3k", never "3.0k" - a decimal that carries no information is chart noise.
        assertEquals("3k", deckWholeDollarLabel(300_000L))
    }

    @Test
    fun `deckWholeDollarLabel either side of the 10,000-dollar decimal cutover`() {
        // Just below 10k: still one decimal of thousands (9,950 rounds to the 10.0k tenths cell).
        assertEquals("10k", deckWholeDollarLabel(995_000L))
        // At or above 10k: whole thousands only, no decimal - 12,400.00 -> "12k", the brief's own
        // worked example.
        assertEquals("12k", deckWholeDollarLabel(1_240_000L))
    }

    @Test
    fun `deckWholeDollarLabel on a large value stays whole-thousands and rounds to the nearest one`() {
        assertEquals("235k", deckWholeDollarLabel(23_456_789L)) // $234,567.89 -> 235k
    }

    // --------------------------------------------------------------- degenerate cases

    @Test
    fun `computeLineScale on an empty list never divides by zero`() {
        val scale = computeLineScale(emptyList())
        assertTrue(scale.max > scale.min)
    }

    @Test
    fun `computeLineScale on a single value pads around it`() {
        val scale = computeLineScale(listOf(50f))
        assertTrue(scale.min < 50f)
        assertTrue(scale.max > 50f)
    }

    @Test
    fun `computeLineScale on a single value of exactly zero still pads`() {
        val scale = computeLineScale(listOf(0f))
        assertTrue(scale.min < 0f)
        assertTrue(scale.max > 0f)
    }

    @Test
    fun `computeLineScale on an all-equal series pads instead of collapsing`() {
        val scale = computeLineScale(listOf(12.4f, 12.4f, 12.4f, 12.4f))
        assertTrue(scale.span > 0f)
        assertTrue(scale.min < 12.4f && scale.max > 12.4f)
    }

    @Test
    fun `computeBarScale on an empty or all-null bar list never divides by zero`() {
        val emptyScale = computeBarScale(emptyList())
        assertTrue(emptyScale.max > 0f)
        val allNullScale = computeBarScale(listOf(null, null, null))
        assertTrue(allNullScale.max > 0f)
    }

    @Test
    fun `computeBarScale on all-zero bars never divides by zero`() {
        val scale = computeBarScale(listOf(DeckBar("MON", 0f), DeckBar("TUE", 0f)))
        assertTrue(scale.max > 0f)
    }

    @Test
    fun `computeBarScale fits a target tick that sits above every bar's value`() {
        val bars = listOf(DeckBar("MON", 10f, targetValue = 100f))
        val scale = computeBarScale(bars)
        assertTrue(scale.max > 100f)
    }

    @Test
    fun `deckRangeStartMs for ALL is the zero lower bound`() {
        val now = ZonedDateTime.of(2026, 8, 8, 9, 0, 0, 0, ZoneId.of("UTC")).toInstant().toEpochMilli()
        assertEquals(0L, deckRangeStartMs(DeckRange.ALL, now))
    }

    @Test
    fun `deckRangeStartMs for 7D includes today plus six prior days`() {
        val zone = ZoneId.of("UTC")
        val now = ZonedDateTime.of(2026, 8, 8, 9, 0, 0, 0, zone).toInstant().toEpochMilli()
        val start = deckRangeStartMs(DeckRange.SEVEN_DAY, now, zone)
        val expectedStart = ZonedDateTime.of(2026, 8, 2, 0, 0, 0, 0, zone).toInstant().toEpochMilli()
        assertEquals(expectedStart, start)
        // Exactly seven day-buckets from that start through "now"'s day.
        assertEquals(7, dailyBuckets(start, now, zone).size)
    }

    @Test
    fun `dailyBuckets returns empty for a misordered range rather than throwing`() {
        val zone = ZoneId.of("UTC")
        val now = ZonedDateTime.of(2026, 8, 8, 9, 0, 0, 0, zone).toInstant().toEpochMilli()
        val earlier = ZonedDateTime.of(2026, 8, 1, 9, 0, 0, 0, zone).toInstant().toEpochMilli()
        assertTrue(dailyBuckets(now, earlier, zone).isEmpty())
    }
}
