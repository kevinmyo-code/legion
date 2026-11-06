package com.kevin.legion.bodycoach

import com.kevin.legion.backend.engine.EngineFailure
import com.kevin.legion.backend.engine.EngineHttpException
import com.kevin.legion.finance.FinanceBackend
import com.kevin.legion.finance.FinanceCache
import com.kevin.legion.finance.FinanceRead
import com.kevin.legion.finance.valueOrNull
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** fat-loss ticket 10: engine figures are read, saved, and shown with their age when it is down. */
class BodyCoachControllerTest {

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
    private val controller = BodyCoachController(
        backend = { FinanceBackend { path, query -> asked += path to query; reply } },
        cache = cache,
        clock = { now },
    )

    // A synthetic person; never anyone's real numbers.
    private val energyBody = """{"estimate": true, "basis": "formula", "basis_words": "from the formula",
        "missing": [], "weight_lbs": 209.4, "bmr_kcal": 1975, "maintenance_kcal": 3061,
        "suggested_target_kcal": 2311, "protein_g": 190, "target_rate_pct": 0.75,
        "proposal": {"id": "p-1", "current_target_kcal": 2400, "proposed_target_kcal": 2250, "words": "w"},
        "words": "Maintenance about 3,061 kcal, an estimate.", "a_newer_field": 1}"""

    private fun unreachable() = Result.failure<String>(
        EngineHttpException(EngineFailure.Unreachable("http://engine", "offline")),
    )

    @Test
    fun `a fresh energy read is parsed, saved and asked at the coach route`() = runBlocking {
        reply = Result.success(energyBody)
        val read = controller.energy()
        assertTrue(read is FinanceRead.Fresh)
        assertEquals(2311, read.valueOrNull?.suggestedTargetKcal)
        assertEquals("p-1", read.valueOrNull?.proposal?.id)
        assertEquals("/api/body/coach/energy/", asked.single().first)
    }

    @Test
    fun `engine down shows the saved copy with its age, and the digest reads it without a network`() = runBlocking {
        reply = Result.success(energyBody)
        controller.energy()
        now += 2 * hour
        reply = unreachable()
        val read = controller.energy()
        assertTrue(read is FinanceRead.Saved)
        assertTrue((read as FinanceRead.Saved).note.contains("could not be reached"))
        val calls = asked.size
        val saved = controller.savedEnergy()
        assertEquals(calls, asked.size)
        assertEquals(3061, saved.valueOrNull?.maintenanceKcal)
    }

    @Test
    fun `nothing saved and no engine is unavailable in words, never a zero`() = runBlocking {
        reply = unreachable()
        val read = controller.energy()
        assertTrue(read is FinanceRead.Unavailable)
        assertNull(read.valueOrNull)
        assertTrue(controller.savedEnergy() is FinanceRead.Unavailable)
    }

    @Test
    fun `a 400 is the engine's own sentence`() = runBlocking {
        reply = Result.failure(EngineHttpException(EngineFailure.Refused(400, """{"detail": "No profile."}""")))
        val read = controller.trend()
        assertEquals(FinanceRead.Refused("No profile."), read)
        assertEquals(mapOf("days" to "90"), asked.single().second)
    }
}
