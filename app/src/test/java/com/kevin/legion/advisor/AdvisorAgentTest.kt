package com.kevin.legion.advisor

import com.kevin.legion.data.local.AdvisorAdvice
import com.kevin.legion.data.local.Goal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-function coverage of [AdvisorAgent]'s prompt assembly - no Context, no Room, no network,
 * matching the same "split out for unit-testability" pattern
 * [com.kevin.legion.ai.CompanionProfileStore] already uses for its own decision logic. Verifies:
 * a [AdvisorBrief] with a null playbook/synthesisNote and empty [AdvisorBrief.writableOps] (the
 * HOME case, ticket 09) still composes a valid, non-empty prompt; the harness safety copy rides
 * every composed prompt regardless of which persona clause is handed in; a proposal round-trips
 * through [AdvisorAnswer.proposal]/[AdvisorAdvice.proposalJson] shape; and every aspect's composed
 * prompt measures under its ticket-11 ceiling.
 */
class AdvisorAgentTest {

    private val fakeDigestBuilder = object : DigestBuilder {
        override val aspect = AdvisorAspect.HOME
        override suspend fun build(context: android.content.Context): String = "unused in these tests"
    }

    // --- HOME: null playbook, null synthesisNote is allowed but ALSO exercised with a note ------

    @Test
    fun `HOME brief with null playbook and empty writableOps composes without error`() {
        val brief = AdvisorBrief(
            aspect = AdvisorAspect.HOME,
            playbook = null,
            synthesisNote = "Spot the cross-domain interaction; name the goal most at risk.",
            digestBuilder = fakeDigestBuilder,
            writableOps = emptySet(),
        )
        val context = AdvisorAgent.composeContext(
            brief = brief,
            digest = "BIO weight trend: down 0.4kg/wk. CRED groceries: 87.55 remaining.",
            goals = emptyList(),
            adviceLog = emptyList(),
        )

        assertFalse("must never emit an empty PLAYBOOK: header when there is no playbook", context.contains("PLAYBOOK:"))
        assertTrue(context.contains("HOW TO REASON HERE:"))
        assertTrue(context.contains("DIGEST:"))
        assertTrue(context.contains("GOALS:"))
        assertTrue(context.contains("RECENT ADVICE"))
        assertTrue(brief.writableOps.isEmpty())
    }

    @Test
    fun `a brief with neither playbook nor synthesisNote composes a valid, non-empty prompt`() {
        val brief = AdvisorBrief(
            aspect = AdvisorAspect.HOME,
            digestBuilder = fakeDigestBuilder,
        )
        val context = AdvisorAgent.composeContext(brief, digest = "d", goals = emptyList(), adviceLog = emptyList())

        assertFalse(context.contains("PLAYBOOK:"))
        assertFalse(context.contains("HOW TO REASON HERE:"))
        assertTrue(context.isNotBlank())
        assertTrue(context.contains("DIGEST:\nd"))
    }

    @Test
    fun `an aspect brief with a playbook emits it under a PLAYBOOK header`() {
        val brief = AdvisorBrief(
            aspect = AdvisorAspect.BIO,
            playbook = "Never suggest a deficit below what a doctor would flag as unsafe.",
            digestBuilder = fakeDigestBuilder,
        )
        val context = AdvisorAgent.composeContext(brief, digest = "d", goals = emptyList(), adviceLog = emptyList())
        assertTrue(context.contains("PLAYBOOK:\nNever suggest a deficit"))
    }

    // --- Harness safety copy rides every prompt regardless of persona ---------------------------

    @Test
    fun `harness rules are present in the system instruction regardless of persona clause`() {
        val alfred = AdvisorAgent.composeSystemInstruction("You are Alfred, dry and brief.")
        val dorothy = AdvisorAgent.composeSystemInstruction("You are Dorothy, warm and fussing.")

        for (instruction in listOf(alfred, dorothy)) {
            assertTrue(instruction.contains("Never manufacture pull"))
            assertTrue(instruction.contains("The app computes, you interpret"))
            assertTrue(instruction.contains("basis"))
            assertTrue(instruction.contains("never assert"))
            assertTrue(instruction.contains(AdvisorAnswer.RESPONSE_SCHEMA.trim().take(40)))
        }
        // Only the persona-specific line should differ between the two.
        assertTrue(alfred.contains("Alfred"))
        assertFalse(dorothy.contains("Alfred"))
    }

    @Test
    fun `a custom persona clause still rides the same fixed harness rules`() {
        val custom = AdvisorAgent.composeSystemInstruction("You are Zephyr, an upbeat coach voice.")
        assertTrue(custom.contains(HarnessPrompt.RULES))
    }

    // --- Proposal round-trip ---------------------------------------------------------------------

    @Test
    fun `a parsed proposal round-trips unopened into AdvisorAdvice-shaped fields`() {
        val raw = """{"spoken": "Sure, want me to set that?", "proposal": "{\"op\":\"set_goal\"}"}"""
        val answer = AdvisorAnswer.parse(raw)!!
        assertEquals("{\"op\":\"set_goal\"}", answer.proposal)
        assertEquals("pending", AdvisorAgent.outcomeFor(proposalPresent = answer.proposal != null))
    }

    @Test
    fun `a purely conversational answer with no proposal is recorded accepted, not pending`() {
        val raw = """{"spoken": "You're on track."}"""
        val answer = AdvisorAnswer.parse(raw)!!
        assertEquals(null, answer.proposal)
        assertEquals("accepted", AdvisorAgent.outcomeFor(proposalPresent = answer.proposal != null))
    }

    // --- formatGoals / formatAdviceLog ------------------------------------------------------------

    @Test
    fun `formatGoals renders not logged for an empty goal list`() {
        assertEquals(DigestText.notLogged(), AdvisorAgent.formatGoals(emptyList()))
    }

    @Test
    fun `formatGoals renders a number-tracked goal with its target and unit`() {
        val goal = Goal(lineageId = 1, aspect = "bio", statement = "Get to 175", targetValue = 175.0, unit = "lbs")
        val out = AdvisorAgent.formatGoals(listOf(goal))
        assertTrue(out.contains("Get to 175"))
        assertTrue(out.contains("175.0 lbs"))
    }

    @Test
    fun `formatGoals renders a prose-only goal without a manufactured number`() {
        val goal = Goal(lineageId = 1, aspect = "log", statement = "Ship the deck")
        val out = AdvisorAgent.formatGoals(listOf(goal))
        assertEquals("- [log] Ship the deck", out)
    }

    @Test
    fun `formatAdviceLog renders not logged for an empty window`() {
        assertEquals(DigestText.notLogged(), AdvisorAgent.formatAdviceLog(emptyList()))
    }

    @Test
    fun `formatAdviceLog renders question, gist, and outcome per row`() {
        val row = AdvisorAdvice(
            aspect = "bio",
            questionText = "Am I on track?",
            gist = "Roughly, yes.",
            adviceText = "Roughly, yes - full text here.",
            outcome = "accepted",
        )
        val out = AdvisorAgent.formatAdviceLog(listOf(row))
        assertTrue(out.contains("Am I on track?"))
        assertTrue(out.contains("Roughly, yes."))
        assertTrue(out.contains("accepted"))
        assertFalse("only the gist rides the prompt, never the full adviceText", out.contains("full text here"))
    }

    // --- Token ceiling (ticket 11: 4,000/aspect, 1,500 for HOME) -----------------------------------

    @Test
    fun `a synthetic BIO-shaped prompt measures under the 4000-token aspect ceiling`() {
        assertUnderCeiling(AdvisorAspect.BIO, playbookChars = 2397 * 4, ceiling = 4000)
    }

    @Test
    fun `a synthetic CRED-shaped prompt measures under the 4000-token aspect ceiling`() {
        assertUnderCeiling(AdvisorAspect.CRED, playbookChars = 2240 * 4, ceiling = 4000)
    }

    @Test
    fun `a synthetic FLEET-shaped prompt (trimmed to the 2500-token playbook cap) measures under the 4000-token ceiling`() {
        assertUnderCeiling(AdvisorAspect.FLEET, playbookChars = 2500 * 4, ceiling = 4000)
    }

    @Test
    fun `a synthetic LOG-shaped prompt measures under the 4000-token aspect ceiling`() {
        assertUnderCeiling(AdvisorAspect.LOG, playbookChars = 1994 * 4, ceiling = 4000)
    }

    @Test
    fun `a synthetic HOME-shaped prompt (no playbook) measures under the 1500-token HOME ceiling`() {
        val brief = AdvisorBrief(
            aspect = AdvisorAspect.HOME,
            synthesisNote = "one headline line per aspect; name the goal most at risk".repeat(3),
            digestBuilder = fakeDigestBuilder,
        )
        val digest = syntheticDigest(180)
        val goals = syntheticGoals(3, "home")
        val adviceLog = syntheticAdviceLog(3)
        val system = AdvisorAgent.composeSystemInstruction("You are Alfred, dry and brief.")
        val context = AdvisorAgent.composeContext(brief, digest, goals, adviceLog)
        val question = "How am I doing overall?"

        val total = AdvisorAgent.estimateTokens(system) + AdvisorAgent.estimateTokens(context) +
            AdvisorAgent.estimateTokens(question)
        assertTrue("HOME prompt estimated at $total tokens, must be under 1500", total < 1500)
    }

    private fun assertUnderCeiling(aspect: AdvisorAspect, playbookChars: Int, ceiling: Int) {
        val brief = AdvisorBrief(
            aspect = aspect,
            playbook = "x".repeat(playbookChars),
            digestBuilder = fakeDigestBuilder,
        )
        val digest = syntheticDigest(220)
        val goals = syntheticGoals(3, aspect.key)
        val adviceLog = syntheticAdviceLog(AdvisorAgent.ADVICE_LOG_WINDOW)
        val system = AdvisorAgent.composeSystemInstruction("You are Alfred, an English butler past sixty. Dry, brief, concrete.")
        val context = AdvisorAgent.composeContext(brief, digest, goals, adviceLog)
        val question = "Am I on track for my goal?"

        val total = AdvisorAgent.estimateTokens(system) + AdvisorAgent.estimateTokens(context) +
            AdvisorAgent.estimateTokens(question)
        assertTrue("$aspect prompt estimated at $total tokens, must be under $ceiling", total < ceiling)
    }

    private fun syntheticDigest(approxTokens: Int): String = "BUDGET groceries target 400.00 actual 312.45 remaining 87.55 [proven] ".repeat(approxTokens / 12 + 1)

    private fun syntheticGoals(count: Int, aspect: String): List<Goal> =
        (1..count).map { Goal(lineageId = it.toLong(), aspect = aspect, statement = "Goal number $it", targetValue = it.toDouble(), unit = "units") }

    private fun syntheticAdviceLog(count: Int): List<AdvisorAdvice> =
        (1..count).map {
            AdvisorAdvice(
                aspect = "bio",
                questionText = "Question $it about progress?",
                gist = "Gist $it: roughly on track, keep going.",
                adviceText = "Full advice text $it, considerably longer than the gist.",
                outcome = "accepted",
            )
        }
}
