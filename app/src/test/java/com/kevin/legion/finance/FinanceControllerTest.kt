package com.kevin.legion.finance

import com.kevin.legion.backend.engine.EngineFailure
import com.kevin.legion.backend.engine.EngineHttpException
import java.time.YearMonth
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Household-finance 13: a fresh read is saved; engine down shows the saved copy with its age in words. */
class FinanceControllerTest {

    private class MemCache : FinanceCache {
        val map = mutableMapOf<String, Pair<String, Long>>()
        override fun read(key: String) = map[key]
        override fun write(key: String, body: String, savedAtMs: Long) {
            map[key] = body to savedAtMs
        }
    }

    private val hour = 3_600_000L
    private var now = 10 * hour
    private val cache = MemCache()
    private var reply: Result<String> = Result.success("")
    private val asked = mutableListOf<Pair<String, Map<String, String>>>()
    private val controller = FinanceController(
        backend = { FinanceBackend { path, query -> asked += path to query; reply } },
        cache = cache,
        clock = { now },
    )

    private val safeBody = """{"available": true, "currency": "USD", "safe_to_spend_cents": -165700,
        "until": "2026-11-20", "until_reason": "next_income", "until_is_estimate": true,
        "top_up_needed_cents": 200000, "top_up_by": "2026-11-11", "top_up_reason": "below zero",
        "a_field_from_a_newer_engine": 1}"""

    private fun unreachable() = Result.failure<String>(
        EngineHttpException(EngineFailure.Unreachable("http://engine", "offline")),
    )

    @Test
    fun `a fresh read is parsed and saved`() = runBlocking {
        reply = Result.success(safeBody)
        val read = controller.safeToSpend()
        assertTrue(read is FinanceRead.Fresh)
        assertEquals(-165_700L, read.valueOrNull?.safeCents)
        assertEquals("/api/finance/safe-to-spend", asked.single().first)
        assertTrue(cache.map.containsKey("safe"))
    }

    @Test
    fun `engine down shows the saved copy and says how old it is`() = runBlocking {
        reply = Result.success(safeBody)
        controller.safeToSpend()
        now += 3 * hour
        reply = unreachable()
        val read = controller.safeToSpend()
        assertTrue(read is FinanceRead.Saved)
        assertEquals(-165_700L, read.valueOrNull?.safeCents)
        assertEquals("Saved 3 hours ago. The engine could not be reached.", read.noteOrNull)
    }

    @Test
    fun `engine down with nothing saved says why, never a zero`() = runBlocking {
        reply = unreachable()
        val read = controller.budgets(YearMonth.of(2026, 11))
        assertEquals(FinanceRead.Unavailable("The engine could not be reached."), read)
    }

    @Test
    fun `no engine signed in falls back to the saved copy too`() = runBlocking {
        cache.write("networth:USD", """{"currency": "USD", "total_cents": 5}""", now - 2 * hour)
        val noEngine = FinanceController(backend = { null }, cache = cache, clock = { now })
        val read = noEngine.netWorth()
        assertTrue(read is FinanceRead.Saved)
        assertEquals("Saved 2 hours ago. ${FinanceWords.NO_ENGINE}", read.noteOrNull)
    }

    @Test
    fun `a 409 is the engine's own sentence and is not covered by a saved copy`() = runBlocking {
        cache.write("cashflow:2026-11", """{"month": "2026-11"}""", now)
        reply = Result.failure(
            EngineHttpException(EngineFailure.Refused(409, """{"detail": "No USD/SGD rate is stored."}""")),
        )
        val read = controller.cashFlow(YearMonth.of(2026, 11))
        assertEquals(FinanceRead.Refused("No USD/SGD rate is stored."), read)
        assertEquals(mapOf("month" to "2026-11"), asked.single().second)
    }

    @Test
    fun `a body this build cannot read falls back rather than crashing`() = runBlocking {
        reply = Result.success("not json")
        val read = controller.accounts()
        assertEquals(FinanceRead.Unavailable(FinanceWords.UNREADABLE), read)
    }
}
