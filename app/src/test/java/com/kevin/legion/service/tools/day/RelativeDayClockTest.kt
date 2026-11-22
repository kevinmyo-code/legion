@file:Suppress("MaxLineLength") // test fixtures read best on one line

package com.kevin.legion.service.tools.day

import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The day path's one clock ([RelativeDay.clock]) gives the LOCAL date at every edge of the night, and
 * "tonight" is a word the tools take. Times are invented: a Tuesday evening in Central time that is
 * already Wednesday in UTC.
 */
class RelativeDayClockTest {
    private val chicago = ZoneId.of("America/Chicago")
    private val tuesday = LocalDate.of(2027, 3, 9)

    @After fun unpin() = RelativeDay.pin(null)

    private fun pinAt(utc: String, zone: ZoneId) = RelativeDay.pin(Clock.fixed(Instant.parse(utc), zone))

    @Test fun `at 21 40 Central, 03 40 UTC the next day, today and tonight are the local Tuesday`() {
        pinAt("2027-03-10T03:40:00Z", chicago)
        assertEquals(tuesday.plusDays(1), Instant.parse("2027-03-10T03:40:00Z").atZone(ZoneOffset.UTC).toLocalDate())
        assertEquals(tuesday, RelativeDay.today())
        assertEquals(chicago, RelativeDay.now().zone)
        assertEquals(21, RelativeDay.now().hour)
        assertEquals(tuesday, RelativeDay.parse("today", RelativeDay.today()))
        assertEquals(tuesday, RelativeDay.parse(" Tonight ", RelativeDay.today()))
        assertEquals(tuesday.plusDays(1), RelativeDay.parse("tomorrow", RelativeDay.today()))
    }

    @Test fun `just after local midnight the new local day has begun`() {
        pinAt("2027-03-10T06:15:00Z", chicago) // 00:15 Central on Wednesday
        assertEquals(tuesday.plusDays(1), RelativeDay.today())
        assertEquals(tuesday.plusDays(1), RelativeDay.parse("tonight", RelativeDay.today()))
    }

    @Test fun `in a zone ahead of UTC the local day leads the UTC one`() {
        val singapore = ZoneId.of("Asia/Singapore")
        pinAt("2027-03-09T23:30:00Z", singapore) // 07:30 Wednesday in Singapore, still Tuesday in UTC
        assertEquals(tuesday.plusDays(1), RelativeDay.today())
        assertEquals(tuesday.plusDays(1), RelativeDay.parse("tonight", RelativeDay.today()))
        assertEquals(tuesday, RelativeDay.parse("yesterday", RelativeDay.today()))
    }

    @Test fun `a window says in words how it stands to today`() {
        val today = tuesday
        assertEquals("today", RelativeDay.windowIs(today, today, today))
        assertEquals("tomorrow, not today (today is Tuesday 2027-03-09)", RelativeDay.windowIs(today.plusDays(1), today.plusDays(1), today))
        assertEquals("yesterday, not today (today is Tuesday 2027-03-09)", RelativeDay.windowIs(today.minusDays(1), today.minusDays(1), today))
        assertEquals("not today (today is Tuesday 2027-03-09)", RelativeDay.windowIs(today.plusDays(3), today.plusDays(4), today))
        assertEquals("a range that includes today, Tuesday 2027-03-09", RelativeDay.windowIs(today, today.plusDays(6), today))
    }

    @Test fun `the word tonight puts a model's tomorrow back on the day the prompt gave`() {
        val turn = RelativeDay.TurnDates(tuesday, java.time.LocalTime.of(21, 40), onlyDateCall = true)
        val args = org.json.JSONObject().put("from", "2027-03-10").put("to", "2027-03-10")
        val (fixed, _) = RelativeDay.corrected("read_calendar", args, "is the lab report due tonight?", turn)!!
        assertEquals("2027-03-09", fixed.getString("from"))
        assertEquals("2027-03-09", fixed.getString("to"))
    }
}
