@file:Suppress("MaxLineLength") // test fixtures: invented wire rows read best on one line

package com.kevin.legion.finance

import com.kevin.legion.backend.engine.EngineFailure
import com.kevin.legion.backend.engine.EngineHttpException
import java.math.BigDecimal
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Net cash on the phone (ADR 0094): every state says its as-of time, a figure over a day old says
 * so, a saved copy says it is the last known one, a missing part gives NO figure and names the
 * account, and low cash is marked in words (the colour only repeats them). Invented figures only.
 */
class NetCashTextTest {
    private val zone = ZoneOffset.UTC
    private val now = Instant.parse("2026-11-16T15:00:00Z")

    private val body = NetCashWire(
        available = true,
        netCents = 140_218,
        currency = "USD",
        asOf = "2026-11-16T14:05:00+00:00",
        pendingCents = -2_150,
        parts = listOf(
            NetCashPartWire("checking", "Everyday Checking", "1000", "USD", 180_000, "bank_available", -650, 1, "2026-11-16T14:05:00+00:00"),
            NetCashPartWire("card", "Rewards Card", "7823", "USD", -39_782, "current_plus_pending", -1_500, 2, "2026-11-16T14:05:00+00:00"),
        ),
        history = NetCashHistoryWire(
            start = "2026-11-14",
            end = "2026-11-16",
            short = true,
            points = listOf(NetCashPointWire("2026-11-14", 150_000), NetCashPointWire("2026-11-15", 145_000), NetCashPointWire("2026-11-16", 140_218)),
        ),
    )

    @Test
    fun `the rule beside say_first asks for the as-of time, the missing account and no sum of its own`() {
        val r = NetCashText.SAY_RULE
        for (w in listOf("say_first first", "'as of' time", "over a day old", "missing", "no total", "Never add")) {
            assertTrue(w, r.contains(w))
        }
    }

    @Test
    fun `a fresh figure says its as-of time and is not flagged`() {
        val m = NetCashText.model(FinanceRead.Fresh(body), floorCents = null, now = now, zone = zone)
        assertTrue(m.hasFigure)
        assertEquals("\$1,402.18", m.figure)
        assertEquals("net cash", m.label)
        assertEquals("As of 14:05 today", m.line)
        assertNull(m.flag)
    }

    @Test
    fun `older than a day says so in words`() {
        val old = body.copy(asOf = "2026-11-15T09:12:00+00:00")
        val m = NetCashText.model(FinanceRead.Fresh(old), null, now, zone)
        assertEquals("Over a day old: as of 09:12 yesterday", m.line)
    }

    @Test
    fun `a saved copy says it is the last known figure`() {
        val read = FinanceRead.Saved(body, 0L, "Saved 3 hours ago. The engine could not be reached.")
        val m = NetCashText.model(read, null, now, zone)
        assertEquals("Last known: as of 14:05 today", m.line)
        assertEquals("Saved 3 hours ago. The engine could not be reached.", m.savedNote)
    }

    @Test
    fun `a missing balance gives no figure and names the account`() {
        val missing = NetCashWire(
            available = false,
            reason = "No net cash figure: Rewards Card ending 7823: the bank sent no balance for this card.",
            missing = listOf(NetCashMissingWire("card", "Rewards Card", "7823", "the bank sent no balance for this card")),
            parts = body.parts.take(1),
        )
        val m = NetCashText.model(FinanceRead.Fresh(missing), null, now, zone)
        assertFalse(m.hasFigure)
        assertEquals("No figure", m.figure)
        assertEquals("No net figure: Rewards Card ending 7823, the bank sent no balance for this card.", m.line)
        assertTrue(NetCashText.spoken(FinanceRead.Fresh(missing), now, zone).startsWith("No net cash figure."))
    }

    @Test
    fun `unreadable is never zero`() {
        val m = NetCashText.model(FinanceRead.Unavailable("The engine could not be reached."), null, now, zone)
        assertFalse(m.hasFigure)
        assertEquals("No figure", m.figure)
        assertEquals("Couldn't read net cash. The engine could not be reached.", m.line)
        assertEquals("Reading net cash.", NetCashText.model(null, null, now, zone).line)
    }

    @Test
    fun `below zero and below the floor are said in the label`() {
        val negative = body.copy(netCents = -4_500)
        val m = NetCashText.model(FinanceRead.Fresh(negative), floorCents = 50_000, now = now, zone = zone)
        assertEquals("below zero", m.flag)
        assertEquals("net cash, below zero", m.label)
        assertEquals("-\$45.00", m.figure)
        val low = NetCashText.model(FinanceRead.Fresh(body), floorCents = 200_000, now = now, zone = zone)
        assertEquals("net cash, below your \$2,000 floor", low.label)
        assertNull(NetCashText.model(FinanceRead.Fresh(body), floorCents = 100_000, now = now, zone = zone).flag)
    }

    @Test
    fun `the working lists checking then each card with its pending`() {
        val rows = NetCashText.rows(body)
        assertEquals("Everyday Checking ending 1000", rows[0].label)
        assertEquals("\$1,800.00", rows[0].value)
        assertEquals(
            "the bank's available balance, pending already taken off; of which pending -\$6.50 (1)",
            rows[0].note,
        )
        assertEquals("-\$397.82", rows[1].value)
        assertEquals("owed, pending charges included; of which pending -\$15.00 (2)", rows[1].note)
    }

    @Test
    fun `the line is the engine's points with the floor as its guide and says it is posted`() {
        val card = NetCashText.trend(body, floorCents = 50_000)!!
        assertEquals("usd", card.unit)
        assertEquals(listOf("2026-11-14", "2026-11-15", "2026-11-16"), card.series.single().points.map { it.at })
        assertEquals(BigDecimal.valueOf(140_218), card.series.single().points.last().value)
        assertEquals(BigDecimal.valueOf(50_000), card.guide?.value)
        assertEquals(
            "Past days are posted figures only; pending charges are counted in today's point. " +
                "The bank feed reaches back to Nov 14.",
            NetCashText.trendNote(body),
        )
    }

    @Test
    fun `the spoken answer leads with net cash and its as-of time`() {
        val said = NetCashText.spoken(FinanceRead.Fresh(body), now, zone)
        assertTrue(said, said.startsWith("Net cash now: \$1,402.18 (USD), as of 14:05 today."))
        assertTrue(said, said.contains("Of which pending at the bank: -\$21.50."))
    }

    @Test
    fun `the floor is typed in dollars and kept in cents`() {
        assertEquals(50_000L, NetCashFloor.parse("500"))
        assertEquals(120_050L, NetCashFloor.parse("$1,200.5"))
        assertNull(NetCashFloor.parse("five hundred"))
        assertNull(NetCashFloor.parse(" "))
    }

    @Test
    fun `the controller reads the net cash route and keeps a copy for offline`() = runBlocking {
        val cache = object : FinanceCache {
            val map = mutableMapOf<String, Pair<String, Long>>()
            override fun read(key: String) = map[key]
            override fun write(key: String, body: String, savedAtMs: Long) {
                map[key] = body to savedAtMs
            }
        }
        var reply: Result<String> = Result.success(
            """{"available": true, "net_cents": 140218, "currency": "USD", "as_of": "2026-11-16T14:05:00Z",
               "pending_cents": -2150, "parts": [], "missing": [], "history": null, "reason": null}""",
        )
        val asked = mutableListOf<String>()
        val controller = FinanceController(
            backend = { FinanceBackend { path, _ -> asked += path; reply } },
            cache = cache,
            clock = { 0L },
        )
        assertEquals(140_218L, controller.netCash().valueOrNull?.netCents)
        assertEquals("/api/finance/net-cash", asked.single())
        reply = Result.failure(EngineHttpException(EngineFailure.Unreachable("http://engine", "offline")))
        val offline = controller.netCash()
        assertTrue(offline is FinanceRead.Saved)
        assertEquals(140_218L, offline.valueOrNull?.netCents)
    }
}
