@file:Suppress("MaxLineLength") // test fixtures: invented wire rows read best on one line

package com.kevin.legion.finance

import java.time.LocalDateTime
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** [NetCashVoice]: every figure a ready string from one engine number, one as-of phrase. Invented figures. */
class NetCashVoiceTest {
    private val zone = ZoneOffset.ofHours(-6)
    private fun at(y: Int, mo: Int, d: Int, h: Int, mi: Int) = LocalDateTime.of(y, mo, d, h, mi).atZone(zone).toInstant()

    private fun iso(y: Int, mo: Int, d: Int, h: Int, mi: Int) = LocalDateTime.of(y, mo, d, h, mi).atOffset(zone).toString()

    private fun body(asOf: String) = NetCashWire(
        available = true,
        netCents = 213_175,
        currency = "USD",
        asOf = asOf,
        pendingCents = -8_520,
        parts = listOf(
            NetCashPartWire("checking", "Everyday Checking", "1000", "USD", 341_250, "bank_available", 0, 0, asOf),
            NetCashPartWire("card", "Rewards Card", "7823", "USD", -128_075, "current_plus_pending", -8_520, 2, asOf),
        ),
    )

    @Test
    fun `the day word comes from the phone's local date, at 00 10, 12 00 and 23 50`() {
        val late = at(2026, 11, 16, 23, 59)
        assertEquals("today at 12:10 AM", NetCashVoice.asOfPhrase(at(2026, 11, 16, 0, 10), late, zone))
        assertEquals("today at 12:00 PM", NetCashVoice.asOfPhrase(at(2026, 11, 16, 12, 0), late, zone))
        assertEquals("today at 11:50 PM", NetCashVoice.asOfPhrase(at(2026, 11, 16, 23, 50), late, zone))
        val justAfterMidnight = at(2026, 11, 17, 0, 20)
        assertEquals("today at 12:10 AM", NetCashVoice.asOfPhrase(at(2026, 11, 17, 0, 10), justAfterMidnight, zone))
        assertEquals("yesterday at 11:50 PM", NetCashVoice.asOfPhrase(at(2026, 11, 16, 23, 50), justAfterMidnight, zone))
        assertEquals("yesterday at 12:00 PM", NetCashVoice.asOfPhrase(at(2026, 11, 16, 12, 0), justAfterMidnight, zone))
        assertEquals("on Nov 14 at 9:12 AM", NetCashVoice.asOfPhrase(at(2026, 11, 14, 9, 12), justAfterMidnight, zone))
    }

    @Test
    fun `every figure is one engine number, the card counted as posted plus pending`() {
        val now = at(2026, 11, 16, 21, 29)
        val v = NetCashVoice.voice(FinanceRead.Fresh(body(iso(2026, 11, 16, 21, 4))), now, zone)
        assertTrue(v.hasNet)
        val says = v.figures.associate { it.what to it.say }
        assertEquals("$2,131.75", v.figures.first().say)
        assertTrue(v.figures.first().what.startsWith("net cash"))
        assertEquals("$3,412.50", says["in Everyday Checking ending 1000 as net cash counts it"])
        // Posted 1,195.55 plus 85.20 pending: the counted card amount, said positive (owed).
        val card = says["owed on Rewards Card ending 7823 as net cash counts it, pending charges included"]
        assertEquals(FinanceText.exact(119_555L + 8_520L), card)
        assertEquals("$1,280.75", card)
        assertEquals("$85.20", says["of which pending charges on Rewards Card ending 7823"])
        assertEquals("$85.20", says["pending charges at the bank, already counted in net cash"])
        assertEquals("today at 9:04 PM", v.asOf)
        assertNull(v.stale)
        assertEquals("Net cash now: \$2,131.75, as of today at 9:04 PM.", v.sayFirst)
    }

    @Test
    fun `over a day old says so as a ready sentence`() {
        val now = at(2026, 11, 16, 21, 29)
        val v = NetCashVoice.voice(FinanceRead.Fresh(body(iso(2026, 11, 15, 15, 29))), now, zone)
        assertEquals("yesterday at 3:29 PM", v.asOf)
        assertEquals(NetCashVoice.STALE_SENTENCE, v.stale)
        assertTrue(v.sayFirst.endsWith("as of yesterday at 3:29 PM. That figure is over a day old."))
    }

    @Test
    fun `a missing card gives no net figure, names the card, and keeps the parts that are there`() {
        val asOf = iso(2026, 11, 16, 21, 4)
        val missing = body(asOf).copy(
            available = false,
            netCents = null,
            pendingCents = null,
            reason = "No net figure: Travel Card ending 4410, the bank sent no balance for this card.",
            missing = listOf(NetCashMissingWire("card", "Travel Card", "4410", "the bank sent no balance for this card")),
        )
        val v = NetCashVoice.voice(FinanceRead.Fresh(missing), at(2026, 11, 16, 21, 29), zone)
        assertFalse(v.hasNet)
        assertTrue(v.sayFirst.startsWith("No net cash figure."))
        assertTrue(v.sayFirst.contains("Travel Card ending 4410"))
        assertFalse(v.figures.any { it.what.startsWith("net cash") })
        assertFalse(v.figures.any { it.say == "$2,131.75" })
        assertEquals(3, v.figures.size) // checking, the card that is there, its pending
    }

    @Test
    fun `the rule forbids arithmetic and other day words`() {
        val r = NetCashVoice.RULE
        for (w in listOf("never add", "subtract, round", "as_of phrase exactly", "stale", "missing", "no total")) {
            assertTrue(w, r.contains(w))
        }
    }
}
