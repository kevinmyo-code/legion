package com.kevin.midnightai.time

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

class SntpTest {

    // 2026-10-13T20:42:00.250Z
    private val t = Instant.parse("2026-10-13T20:42:00.250Z").toEpochMilli()

    /** A server reply to [request]: receive at [t2], transmit at [t3]. */
    private fun reply(
        request: ByteArray,
        t2: Long,
        t3: Long,
        mode: Int = 4,
        stratum: Int = 1,
        leap: Int = 0,
    ): ByteArray {
        val b = ByteArray(Sntp.PACKET_SIZE)
        b[0] = ((leap shl 6) or (4 shl 3) or mode).toByte()
        b[1] = stratum.toByte()
        System.arraycopy(request, 40, b, 24, 8) // originate = our transmit
        Sntp.writeTimestamp(b, 32, t2)
        Sntp.writeTimestamp(b, 40, t3)
        return b
    }

    @Test
    fun `request is a v4 client packet carrying the token`() {
        val req = Sntp.buildRequest(t)
        assertEquals(48, req.size)
        assertEquals(0x23, req[0].toInt() and 0xff) // LI 0, VN 4, mode 3
        assertEquals(t, Sntp.readTimestamp(req, 40))
    }

    @Test
    fun `timestamps round trip to the millisecond`() {
        val buf = ByteArray(8)
        for (ms in listOf(0L, 1L, 999L, t, Instant.parse("2040-01-01T00:00:00.001Z").toEpochMilli())) {
            Sntp.writeTimestamp(buf, 0, ms)
            assertEquals(ms, Sntp.readTimestamp(buf, 0))
        }
    }

    @Test
    fun `seconds field is NTP epoch`() {
        val buf = ByteArray(8)
        Sntp.writeTimestamp(buf, 0, 0L) // 1970-01-01 = 2208988800 = 0x83AA7E80
        assertEquals(0x83.toByte(), buf[0])
        assertEquals(0xAA.toByte(), buf[1])
        assertEquals(0x7E.toByte(), buf[2])
        assertEquals(0x80.toByte(), buf[3])
    }

    @Test
    fun `offset is UTC minus the monotonic client clock`() {
        // Client monotonic clock reads 5_000 at send and 5_100 at receive (100 ms round trip,
        // 10 ms held by the server). Server stamps true UTC.
        val req = Sntp.buildRequest(123L)
        val t2 = t + 45
        val t3 = t + 55
        val r = Sntp.parse(req, reply(req, t2, t3), 48, t1 = 5_000L, t4 = 5_100L)
        // midpoint of client = 5050, midpoint of server = t + 50
        assertEquals(t + 50 - 5_050L, r.offsetMs)
        assertEquals(90L, r.roundTripMs)
    }

    @Test
    fun `compute matches the NTP formula`() {
        val r = Sntp.compute(t1 = 1_000, t2 = 2_000, t3 = 2_004, t4 = 1_010)
        assertEquals(((2_000 - 1_000) + (2_004 - 1_010)) / 2L, r.offsetMs)
        assertEquals(6L, r.roundTripMs)
    }

    @Test
    fun `rejects a reply that does not echo our transmit timestamp`() {
        val req = Sntp.buildRequest(1L)
        val other = Sntp.buildRequest(2L)
        assertRejected { Sntp.parse(req, reply(other, t, t), 48, 0, 10) }
    }

    @Test
    fun `rejects non-server modes, kiss-o-death, unsynchronized and short packets`() {
        val req = Sntp.buildRequest(7L)
        assertRejected { Sntp.parse(req, reply(req, t, t, mode = 3), 48, 0, 10) }
        assertRejected { Sntp.parse(req, reply(req, t, t, stratum = 0), 48, 0, 10) }
        assertRejected { Sntp.parse(req, reply(req, t, t, stratum = 16), 48, 0, 10) }
        assertRejected { Sntp.parse(req, reply(req, t, t, leap = 3), 48, 0, 10) }
        assertRejected { Sntp.parse(req, reply(req, t, t), 47, 0, 10) }
        assertRejected { Sntp.parse(req, reply(req, t, 0L), 48, 0, 10) }
    }

    @Test
    fun `accepts broadcast mode and a leap warning`() {
        val req = Sntp.buildRequest(9L)
        Sntp.parse(req, reply(req, t, t, mode = 5, leap = 1), 48, 0, 10)
    }

    private fun assertRejected(block: () -> Unit) {
        try {
            block()
            fail("expected Sntp.Rejected")
        } catch (_: Sntp.Rejected) {
        }
    }
}

class TrustedClockCoreTest {

    private var elapsed = 10_000L
    private var wall = 0L
    private val core = TrustedClockCore({ elapsed }, { wall })
    private val utc = Instant.parse("2026-10-13T20:42:00Z").toEpochMilli()

    @Test
    fun `untrusted falls back to the wall clock`() {
        wall = 42L
        assertFalse(core.isTrusted)
        assertEquals(42L, core.nowMs())
        assertNull(core.skewMs())
    }

    @Test
    fun `trusted time follows the monotonic clock and ignores wall resets`() {
        wall = utc + 3 * 86_400_000L // head unit three days ahead
        core.accept(ClockSync(offsetMs = utc - elapsed, source = ClockSource.SNTP, atElapsedMs = elapsed))
        assertEquals(utc, core.nowMs())
        assertEquals(3 * 86_400_000L, core.skewMs())

        elapsed += 60_000L
        wall = 0L // the unit reset its clock to 1970
        assertEquals(utc + 60_000L, core.nowMs())
        assertEquals(-(utc + 60_000L), core.skewMs())
        assertEquals(60_000L, core.ageMs())
    }

    @Test
    fun `http date offset centres the one-second header`() {
        val dateMs = HttpDate.parseMs("Tue, 13 Oct 2026 20:42:00 GMT")!!
        assertEquals(utc, dateMs)
        // Sent at elapsed 1_000, answered at 1_200: midpoint 1_100, Date centred at +500.
        assertEquals(utc + 500 - 1_100, HttpDate.offsetMs(dateMs, 1_000, 1_200))
        assertNull(HttpDate.parseMs("not a date"))
        assertNull(HttpDate.parseMs(null))
    }
}

class PersistedSyncTest {

    private fun saved(bootCount: Int = 7) = PersistedSync(
        offsetMs = 1L, source = ClockSource.SNTP, atElapsedMs = 100_000L,
        bootCount = bootCount, bootWallMs = 1_000_000L,
    )

    @Test
    fun `same boot restores`() {
        assertTrue(PersistedSync.restorable(saved(), 7, nowElapsed = 200_000L, nowWall = 0L))
    }

    @Test
    fun `different boot count or a monotonic clock that went backwards does not`() {
        assertFalse(PersistedSync.restorable(saved(), 8, 200_000L, 0L))
        assertFalse(PersistedSync.restorable(saved(), 7, 50_000L, 0L))
    }

    @Test
    fun `too old does not`() {
        assertFalse(PersistedSync.restorable(saved(), 7, 100_000L + PersistedSync.MAX_AGE_MS + 1, 0L))
    }

    @Test
    fun `without a boot counter the wall-clock boot instant decides`() {
        // boot instant then: 1_000_000; now: wall - elapsed
        assertTrue(PersistedSync.restorable(saved(-1), -1, 200_000L, 1_200_000L + 30_000L))
        assertFalse(PersistedSync.restorable(saved(-1), -1, 200_000L, 1_200_000L + 3_600_000L))
    }
}

class ZoneResolverTest {

    @Test
    fun `phone wins when followed, then manual, then engine, then default`() {
        assertEquals(
            ZoneId.of("America/Denver") to ZoneSource.PHONE,
            ZoneResolver.resolve("America/Denver", true, "America/New_York", "America/Phoenix", "America/Chicago"),
        )
        assertEquals(
            ZoneId.of("America/New_York") to ZoneSource.MANUAL,
            ZoneResolver.resolve("America/Denver", false, "America/New_York", "America/Phoenix", "America/Chicago"),
        )
        assertEquals(
            ZoneId.of("America/Phoenix") to ZoneSource.ENGINE,
            ZoneResolver.resolve(null, true, " ", "America/Phoenix", "America/Chicago"),
        )
        assertEquals(
            ZoneId.of("America/Chicago") to ZoneSource.DEFAULT,
            ZoneResolver.resolve("Not/AZone", true, "garbage", null, "America/Chicago"),
        )
    }
}

class TimeTextTest {

    private val chicago = ZoneId.of("America/Chicago")

    @Test
    fun `prompt line states weekday, ISO date, local time and offset, never the zone id`() {
        val now = ZonedDateTime.of(2026, 10, 13, 15, 42, 0, 0, chicago)
        val line = TimeText.promptLine(now, trusted = true)
        assertTrue(line, line.startsWith("Today is Tuesday 2026-10-13, 15:42 local time (UTC-05:00). "))
        assertTrue(line, line.contains("Wed 2026-10-14"))
        assertTrue(line, line.contains("Fri 2026-10-16"))
        assertFalse(line, line.contains("Chicago"))
        assertFalse(line, line.contains("not network-verified"))
        assertTrue(TimeText.promptLine(now, trusted = false).contains("not network-verified"))
    }

    @Test
    fun `the date comes from the trusted instant in the trusted zone, not UTC`() {
        // 2026-10-14T02:30Z is still Tuesday evening in Houston.
        val now = Instant.parse("2026-10-14T02:30:00Z").atZone(chicago)
        assertTrue(TimeText.promptLine(now, true).startsWith("Today is Tuesday 2026-10-13, 21:30"))
    }

    @Test
    fun `engine line tells the agent to send offset windows and convert UTC back`() {
        val now = ZonedDateTime.of(2026, 10, 13, 15, 42, 0, 0, chicago)
        val text = TimeText.engineLine(now, true)
        assertTrue(text, text.contains("from 2026-10-16T00:00:00-05:00 to 2026-10-16T23:59:59-05:00"))
        // 02:00Z on the 16th is the evening of the 15th in Houston: the exact shift Kevin hit.
        assertTrue(text, text.contains("2026-10-16T02:00:00Z is Thursday 2026-10-15 21:00 local"))
        assertFalse(text, text.contains("Chicago"))
    }

    @Test
    fun `winter offset and UTC label`() {
        val jan = ZonedDateTime.of(2027, 1, 5, 8, 0, 0, 0, chicago)
        assertTrue(TimeText.promptLine(jan, true).contains("(UTC-06:00)"))
        assertEquals("UTC+00:00", TimeText.offsetLabel(java.time.ZoneOffset.UTC))
        assertEquals("UTC+05:30", TimeText.offsetLabel(java.time.ZoneOffset.ofHoursMinutes(5, 30)))
    }

    @Test
    fun `skew and status lines`() {
        assertEquals("within 1 s", TimeText.skewLabel(400))
        assertEquals("12 s ahead", TimeText.skewLabel(12_300))
        assertEquals("3 min 4 s behind", TimeText.skewLabel(-184_000))
        assertEquals("2 h 5 min ahead", TimeText.skewLabel(7_500_000))
        assertEquals("3 d 2 h behind", TimeText.skewLabel(-(3 * 86_400_000L + 7_200_000L)))
        assertEquals(
            "Clock: synced via time.google.com (device off by 3 min 4 s behind)",
            TimeText.statusLine(true, -184_000, ClockSource.SNTP),
        )
        assertEquals(
            "Clock: synced via web time (device within 1 s)",
            TimeText.statusLine(true, 10, ClockSource.HTTP_DATE),
        )
        assertEquals(
            "Clock: not synced yet, using the head unit's clock",
            TimeText.statusLine(false, null, null),
        )
    }
}
