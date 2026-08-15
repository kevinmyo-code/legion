package com.kevin.legion.advisor.playbooks

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards ticket 15's binding safety content: the estimate-phrasing requirement and the
 * professional-referral boundaries in each shipped playbook constant. These are the parts
 * CLAUDE.md and the ticket both say a future trim must never reach - this test exists so a
 * silent future edit that deletes them fails the build instead of shipping quietly.
 *
 * Deliberately shallow (substring checks on the shipped constant), not a content-quality check.
 * It cannot catch a rewording that keeps the words but drops the meaning - that is a review
 * responsibility, not a test one.
 */
class PlaybookKeywordsTest {

    @Test
    fun `bio playbook is non-blank and carries its referral boundaries`() {
        val text = BioPlaybook.TEXT
        assertFalse(text.isBlank())
        assertTrue("estimate phrasing requirement", text.contains("estimate", ignoreCase = true))
        assertTrue("pain/injury boundary", text.contains("Pain or injury", ignoreCase = true))
        assertTrue(
            "medical conditions boundary",
            text.contains("Medical conditions", ignoreCase = true),
        )
        assertTrue(
            "disordered-eating boundary",
            text.contains("Disordered-eating", ignoreCase = true),
        )
        assertTrue("minors boundary", text.contains("Minors", ignoreCase = true))
        assertTrue("PEDs/supplement boundary", text.contains("SARMs", ignoreCase = true))
    }

    @Test
    fun `cred playbook is non-blank and carries its referral boundaries`() {
        val text = CredPlaybook.TEXT
        assertFalse(text.isBlank())
        assertTrue("estimate phrasing requirement", text.contains("estimate", ignoreCase = true))
        assertTrue("tax boundary", text.contains("Tax:", ignoreCase = true))
        assertTrue(
            "investment selection boundary",
            text.contains("Investment selection", ignoreCase = true),
        )
        assertTrue("insurance boundary", text.contains("Insurance:", ignoreCase = true))
        assertTrue(
            "debt restructuring boundary",
            text.contains("Debt crisis", ignoreCase = true) &&
                text.contains("restructuring", ignoreCase = true),
        )
    }

    @Test
    fun `fleet playbook is non-blank and carries its referral boundaries`() {
        val text = FleetPlaybook.TEXT
        assertFalse(text.isBlank())
        assertTrue("estimate phrasing requirement", text.contains("ESTIMATE"))
        assertTrue(
            "safety-critical systems boundary",
            text.contains("Safety-critical", ignoreCase = true) ||
                text.contains("safety-critical judgment", ignoreCase = true),
        )
        assertTrue(
            "owner's manual defers-to-manual boundary",
            text.contains("owner's manual", ignoreCase = true),
        )
        assertTrue(
            "physical inspection by a mechanic boundary",
            text.contains("physical inspection", ignoreCase = true),
        )
    }

    @Test
    fun `log playbook is non-blank and carries its planning-advisor contract`() {
        val text = LogPlaybook.TEXT
        assertFalse(text.isBlank())
        // LOG has no professional-referral boundaries (ticket 15 names none); the binding
        // content here is the pull-only, never-nag, never-guilt stance instead.
        assertTrue(
            "never-guilt stance",
            text.contains("never guilt", ignoreCase = true),
        )
        assertTrue(
            "never-nag / streak-free stance",
            text.contains("never scold about streaks", ignoreCase = true),
        )
    }

    @Test
    fun `every playbook stays under the 2500 token ceiling by the chars-per-4 fallback`() {
        // Fallback heuristic only (chars/4, per ticket 15's instructions) - the binding
        // measurement is countTokens against gemini-3.5-flash-lite, done by hand and recorded in
        // each file's KDoc. This test is a cheap regression tripwire, not the source of truth:
        // the measured 4.15 chars/token ratio on this codebase makes chars/4 a deliberately
        // pessimistic (slightly over-count) sanity check, so a false failure here means "go
        // re-measure with countTokens", not "the ceiling was actually breached".
        val ceilingChars = 2_500 * 4
        listOf(
            "BIO" to BioPlaybook.TEXT,
            "LOG" to LogPlaybook.TEXT,
            "FLEET" to FleetPlaybook.TEXT,
            "CRED" to CredPlaybook.TEXT,
        ).forEach { (name, text) ->
            assertTrue(
                "$name playbook is $ceilingChars chars or fewer (chars/4 sanity check)",
                text.length <= ceilingChars,
            )
        }
    }

    @Test
    fun `no playbook ships its Sources section`() {
        // CLAUDE.md section 7 / ticket 15: the Sources section is dev-facing licensing
        // documentation and must never ride the wire to the model.
        listOf(
            BioPlaybook.TEXT,
            LogPlaybook.TEXT,
            FleetPlaybook.TEXT,
            CredPlaybook.TEXT,
        ).forEach { text ->
            assertFalse(text.contains("## Sources"))
        }
    }
}
