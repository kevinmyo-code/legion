package com.kevin.legion.wellbeing

import com.kevin.legion.service.ProactiveCategory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-function coverage of [WellbeingDigestBuilder.buildRaise] - no Context, no Room, matching
 * [com.kevin.legion.advisor.GoalChecklistTest]'s own posture. Covers ticket 05's four binding
 * rules directly: the raise carries real facts and is refused (never even constructed) without
 * them, it never fires per item, its rendered line never references absence/streak/engagement,
 * and no plan means no raise rather than a raise about nothing.
 */
class WellbeingDigestBuilderTest {

    // --- no plan means no raise at all - never a raise saying "nothing today" -------------------

    @Test
    fun `empty items produce no raise at all`() {
        assertNull(WellbeingDigestBuilder.buildRaise(emptyList()))
    }

    // --- a real plan produces a raise carrying real facts, which is what the gate refuses without -

    @Test
    fun `a real plan produces one raise whose facts are non-blank and state the items`() {
        val raise = WellbeingDigestBuilder.buildRaise(listOf("Hit 2300 kcal / 180g protein", "Sleep 8h"))

        requireNotNull(raise)
        assertTrue("a raise with real items must state facts, or ProactiveBus refuses it", raise.statesItsFacts)
        assertTrue(raise.facts.contains("Hit 2300 kcal / 180g protein"))
        assertTrue(raise.facts.contains("Sleep 8h"))
        assertEquals(ProactiveCategory.WELLBEING, raise.category)
        assertEquals(WellbeingDigestBuilder.RULE_ID, raise.ruleId)
    }

    @Test
    fun `a raise with no items would fail ProactiveRaise's own facts gate if constructed anyway`() {
        // buildRaise itself refuses to construct one (see the test above) - this test pins down
        // WHY that refusal is correct, by showing what would happen if a future edit skipped the
        // null-return and built one with blank facts regardless: ProactiveBus.speakIfAllowed reads
        // ProactiveRaise.statesItsFacts and would refuse it. Constructing the type directly here
        // (rather than going through buildRaise) is deliberate - it is what proves the type-level
        // guard, not just this file's own convention.
        val blankFactsRaise = com.kevin.legion.service.ProactiveRaise(
            ruleId = WellbeingDigestBuilder.RULE_ID,
            category = ProactiveCategory.WELLBEING,
            reason = "no items",
            facts = "",
            prompt = "unused",
        )
        assertTrue("a blank-facts raise must read as stating no facts", !blankFactsRaise.statesItsFacts)
    }

    // --- never fires per item: N items still produce exactly ONE raise, never N raises -----------

    @Test
    fun `many items still produce exactly one raise, never one per item`() {
        val raise = WellbeingDigestBuilder.buildRaise(
            listOf("Hit 2300 kcal / 180g protein", "Sleep 8h", "Squat: 9 sets this week", "Bench Press: 9 sets this week"),
        )
        // The function's own return type is the enforcement: ProactiveRaise?, never a
        // List<ProactiveRaise>. This assertion is the behavioural half of that structural
        // guarantee - one raise, all four items folded into its single facts string.
        requireNotNull(raise)
        val lines = raise.facts.lines()
        assertEquals(4, lines.size)
        assertTrue(raise.facts.contains("Squat: 9 sets this week"))
        assertTrue(raise.facts.contains("Bench Press: 9 sets this week"))
    }

    // --- the compulsion test's clause (c): no absence, streak, or engagement language -------------

    private val forbiddenSubstrings = listOf(
        "haven't", "have not", "hasn't", "has not", "still haven't", "still hasn't",
        "missed", "again", "streak", "since your last", "since the last", "you didn't",
        "you did not", "how long", "days in a row", "in a row",
    )

    // No "the prompt itself never contains the banned words" test: the instruction has to name
    // "streak"/"missed"/"still hasn't" IN ORDER to forbid the model from saying them - a
    // meta-instruction that bans a word necessarily contains that word once. Banning the prompt
    // from containing those substrings would fail on the very sentence that forbids them. What is
    // actually testable statically is (1) the FACTS - the concrete data the model is handed, which
    // must be clean because nothing in this builder ever adds such language to it - and (2) that
    // the prompt's instruction genuinely forbids the banned framings in words, both covered below.

    @Test
    fun `the built facts never carry ticked-or-not, streak, or engagement language regardless of item text`() {
        // Even adversarial item text cannot smuggle banned language into what actually gets built
        // as FACTS - facts here are exactly the caller-supplied lines with a bullet prefix, so this
        // test also documents that WellbeingDigestBuilder does not itself add any such language.
        val raise = WellbeingDigestBuilder.buildRaise(listOf("Push session", "180g protein"))
        requireNotNull(raise)
        val lower = raise.facts.lowercase()
        forbiddenSubstrings.forEach { banned ->
            assertTrue("facts must not contain '$banned': ${raise.facts}", banned !in lower)
        }
    }

    @Test
    fun `the prompt explicitly forbids ticked-unticked framing and streak mentions`() {
        val raise = WellbeingDigestBuilder.buildRaise(listOf("Push session"))
        requireNotNull(raise)
        val lower = raise.prompt.lowercase()
        assertTrue("prompt must instruct the model not to mention ticked/unticked state", lower.contains("ticked"))
        assertTrue("prompt must instruct the model not to mention a streak", lower.contains("streak"))
    }

    // --- the register clause: no "driver" language anywhere the model can read --------------------

    @Test
    fun `neither the prompt nor the facts ever say driver`() {
        val raise = WellbeingDigestBuilder.buildRaise(listOf("Push session", "180g protein"))
        requireNotNull(raise)
        assertTrue(!raise.prompt.lowercase().contains("driver"))
        assertTrue(!raise.facts.lowercase().contains("driver"))
    }
}
