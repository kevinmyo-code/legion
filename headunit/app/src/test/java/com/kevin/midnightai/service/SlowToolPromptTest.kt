package com.kevin.midnightai.service

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every tool the unit gives more than the default 10 s must tell the driver it is working first,
 * because a BLOCKING tool call silences the Live model for its whole duration (see
 * [SlowToolPrompt]).
 *
 * The tiers are read from [LiveSessionController.toolTimeoutMs] rather than listed here, so a tool
 * put on a long tier later fails this test until its description carries the line. That is the
 * property worth guarding: the pairing of "slow in code" with "says so in the prompt", not today's
 * nine names.
 */
class SlowToolPromptTest {

    private fun declared(): List<JSONObject> {
        val all: JSONArray = LiveToolbox.declarations()
        return (0 until all.length()).map { all.getJSONObject(it) }
    }

    private fun description(fn: JSONObject) = fn.getString("description")

    private fun isSlow(name: String) =
        LiveSessionController.toolTimeoutMs(name) > LiveSessionController.toolTimeoutMs("get_health")

    @Test
    fun `every tool on a longer timeout tier tells the driver it is working`() {
        val missing = declared()
            .filter { isSlow(it.getString("name")) }
            .filterNot { description(it).contains(SlowToolPrompt.INSTRUCTION) }
            .map { it.getString("name") }
        assertTrue("slow tools with no wait line: $missing", missing.isEmpty())
    }

    @Test
    fun `the slow tiers are the nine tools this change covers`() {
        val slow = declared().map { it.getString("name") }.filter { isSlow(it) }.toSet()
        assertEquals(
            setOf(
                // The investigating sub-agents (45 s).
                "diagnose_codes", "triage_symptom", "ask_maintenance", "check_cold_start",
                // Blocking network calls on the same tier (45 s).
                "recommend_music", "register_vehicle", "check_recalls", "lookup_vin",
                // The engine bridge, the longest leash on the unit (150 s).
                "ask_engine",
            ),
            slow,
        )
    }

    @Test
    fun `a fast tool does not carry the line`() {
        // The line costs tokens on every session and reads as a stall to the driver, so it belongs
        // only where the wait is real. get_codes is the deliberate instant counterpart of
        // diagnose_codes and is the one most at risk of picking the line up by copy-paste.
        listOf("get_codes", "get_health", "get_next_service", "get_time").forEach { name ->
            val fn = declared().first { it.getString("name") == name }
            assertFalse(name, description(fn).contains(SlowToolPrompt.INSTRUCTION))
        }
    }

    @Test
    fun `the line claims no outcome`() {
        // SpeechHonesty.CANNOT_CLAUSE reserves the outcome verbs for a successful tool result in
        // the same turn. A wait line that said "done", "added" or "sent" would hand the model the
        // exact sentence the clause forbids, before the tool has even run.
        val words = listOf(" done", "added", "sent", "saved", "logged", "booked", "finished")
        words.forEach { w ->
            assertFalse(w, SlowToolPrompt.INSTRUCTION.lowercase().contains(w))
        }
    }

    @Test
    fun `ask_engine carries the line in its own constant`() {
        // ask_engine's description is AskEngine.DESCRIPTION, not a literal in LiveToolbox, so the
        // sweep above would still pass if the declaration were rewired to something else.
        assertTrue(AskEngine.DESCRIPTION.contains(SlowToolPrompt.INSTRUCTION))
    }
}
