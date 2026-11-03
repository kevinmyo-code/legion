package com.kevin.legion.vehicle

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What a trouble code shows (2026-11-03): a seed meaning plainly, an AI meaning with its label in
 * words, an unknown code "Looking this up..." until its lookup fails and then "No description yet",
 * and a manufacturer-specific code with a line saying so. Plus [DtcLookup]'s single-flight and retry.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DtcMeaningTest {

    private val seed = mapOf("P0420" to ("Catalyst System Efficiency Below Threshold (Bank 1)" to "Aging cat."))
    private val learned = mapOf("P1282" to ("Fuel Pump Relay Control Circuit" to "Relay or wiring."))

    @Test
    fun `seed meaning is the standard and carries no AI label`() {
        val m = DtcMeanings.resolve("P0420", seed, learned, emptySet())
        assertTrue(m is DtcMeaning.Standard)
        assertEquals("Catalyst System Efficiency Below Threshold (Bank 1)", DtcMeanings.headline(m))
        assertTrue(DtcMeanings.notes("P0420", m).isEmpty())
        assertFalse(DtcMeanings.glanceValue(m).contains("AI"))
    }

    @Test
    fun `learned meaning is labelled an AI explanation in words`() {
        val m = DtcMeanings.resolve("P1282", seed, learned, emptySet())
        assertTrue(m is DtcMeaning.AiExplanation)
        assertTrue(DtcMeanings.AI_LABEL in DtcMeanings.notes("P1282", m))
        assertEquals("AI explanation, not from the code standard", DtcMeanings.AI_LABEL)
        assertEquals(
            "Fuel Pump Relay Control Circuit (AI explanation, not from the code standard)",
            DtcMeanings.glanceValue(m),
        )
    }

    @Test
    fun `unknown code is looking up until its lookup fails, then says no description yet`() {
        val pending = DtcMeanings.resolve("P0999", seed, learned, emptySet())
        assertEquals(DtcMeaning.LookingUp, pending)
        assertEquals("Looking this up...", DtcMeanings.headline(pending))
        val failed = DtcMeanings.resolve("P0999", seed, learned, setOf("P0999"))
        assertEquals(DtcMeaning.NoDescription, failed)
        assertEquals("No description yet", DtcMeanings.headline(failed))
    }

    @Test
    fun `manufacturer-specific codes are recognised by the SAE split`() {
        for (c in listOf("P1282", "P1000", "P3000", "P3399", "B1001", "C1234", "U1100", "B2000", "U2100")) {
            assertTrue(c, DtcMeanings.isManufacturerSpecific(c))
        }
        for (c in listOf("P0420", "P2187", "P3400", "B0001", "C0035", "U0100")) {
            assertFalse(c, DtcMeanings.isManufacturerSpecific(c))
        }
        val m = DtcMeanings.resolve("P1282", seed, learned, emptySet())
        assertTrue(DtcMeanings.MANUFACTURER_NOTE in DtcMeanings.notes("P1282", m))
        val generic = DtcMeanings.resolve("P0420", seed, learned, emptySet())
        assertFalse(DtcMeanings.MANUFACTURER_NOTE in DtcMeanings.notes("P0420", generic))
    }

    @Test
    fun `lookup is single-flight, persists answers, and records failures`() = runTest(UnconfinedTestDispatcher()) {
        val gate = CompletableDeferred<Map<String, Pair<String, String>>?>()
        var calls = 0
        val persisted = mutableMapOf<String, Pair<String, String>>()
        val lookup = DtcLookup(
            scope = backgroundScope,
            describe = { _, _ -> calls++; gate.await() },
            persist = { persisted += it },
            now = { 0L },
        )
        lookup.request("1998 Jeep Cherokee", listOf("P1282", "P1390"))
        lookup.request("1998 Jeep Cherokee", listOf("P1282", "P1390"))
        assertEquals(1, calls)
        assertEquals(setOf("P1282", "P1390"), lookup.state.value.inFlight)

        gate.complete(mapOf("P1282" to ("Fuel Pump Relay Control Circuit" to "Relay or wiring.")))
        val s = lookup.state.value
        assertTrue(s.inFlight.isEmpty())
        assertEquals("Fuel Pump Relay Control Circuit", s.learned["P1282"]?.first)
        assertEquals(setOf("P1390"), s.failed)
        assertEquals(setOf("P1282"), persisted.keys)

        // A failed code is not re-asked inside the retry window; a learned one never is.
        lookup.request("1998 Jeep Cherokee", listOf("P1282", "P1390"))
        assertEquals(1, calls)
    }

    @Test
    fun `a failed lookup, null or thrown, reads no description yet and retries after the window`() =
        runTest(UnconfinedTestDispatcher()) {
            var clock = 0L
            var calls = 0
            val lookup = DtcLookup(
                scope = backgroundScope,
                describe = { _, _ -> calls++; if (calls == 1) null else error("offline") },
                persist = {},
                now = { clock },
                retryAfterMs = 1_000L,
            )
            lookup.request("", listOf("P1491"))
            val s = lookup.state.value
            assertEquals(DtcMeaning.NoDescription, DtcMeanings.resolve("P1491", emptyMap(), s.learned, s.failed))
            clock = 1_000L
            lookup.request("", listOf("P1491"))
            assertEquals(2, calls)
            assertEquals(setOf("P1491"), lookup.state.value.failed)
        }
}
