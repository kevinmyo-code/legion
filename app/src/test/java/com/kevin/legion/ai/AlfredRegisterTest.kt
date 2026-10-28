package com.kevin.legion.ai

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the 2026-10-28 register (Kevin: livelier, the manner of a film-butler AI in our own words),
 * the way [AriaBrainHonestyClauseTest] pins the honesty clauses. Async-lookups ticket 06.
 *
 * **Presence only.** Nothing inspects the spoken audio, so whether the model actually sounds
 * livelier is owed to a phone run read through `tools/voice_audit.py`. A green run here says the
 * words are in the prompt, nothing more.
 */
class AlfredRegisterTest {

    /** The clause with its line wraps folded, so a re-wrap of the prose does not fail a pin. */
    private val clause = ALFRED.clause.replace(Regex("\\s+"), " ")

    /** The register's working rules, each named so a deletion says what was lost. */
    @Test
    fun `the clause carries the livelier register`() {
        val rules = mapOf(
            "quick, quietly amused" to "the temperament: quick, dry, quietly amused",
            "you react rather than recite" to "reacts rather than recites",
            "A number gets a read, not just the number" to "a number gets a read",
            "suspiciously round" to "the kind of read a number gets",
            "One understated remark per answer at most" to "wit is rationed to one remark",
            "never at the user" to "the remark is never at the user's expense",
            "offer it once, in a clause. If they pass, it is gone." to
                "anticipation offered once, never pushed (section 7 compulsion rules)",
            "Vary your phrasing" to "no stock line repeated",
            "\"Sir\" is for the moment that earns it; most answers have none." to "sir, sparingly",
            "No stock butler sign-offs" to "no stock sign-off",
            "one dry line, then stop" to "a sign-off is one dry line",
        )
        for ((phrase, why) in rules) {
            assertTrue("missing from ALFRED.clause: $why", clause.contains(phrase))
        }
    }

    /** The wit sits below honesty, in the register itself, not only in the shared rules. */
    @Test
    fun `honesty outranks wit in the register`() {
        for (phrase in listOf(
            "Honesty outranks wit.",
            "said plainly and first, never dressed up as a plausible sentence",
            "is an estimate, and you say so",
            "No exclamation marks.",
        )) {
            assertTrue("missing from ALFRED.clause: $phrase", clause.contains(phrase))
        }
    }

    /** The anchor restates shortClause last; it must carry the new manner, not the old flat one. */
    @Test
    fun `the short clause carries the register and drops the flat one`() {
        val s = ALFRED.shortClause
        assertTrue(s.contains("Quick, dry, quietly amused"))
        assertTrue(s.contains("a number gets a read"))
        assertTrue(s.contains("never at the user"))
        assertTrue(s.contains("Never guess a number you don't have."))
        assertFalse("the old flat instruction is gone", s.contains("Answer and stop"))
    }

    /** The holding line is varied now; the fixed one the audit heard every time is gone. */
    @Test
    fun `the fixed holding line is gone from the voice delivery`() {
        assertFalse(ALFRED.delivery.contains("\"One moment.\""))
        assertTrue(ALFRED.delivery.contains("worded fresh each time"))
        assertFalse(clause.contains("then you stop"))
    }

    /** CLAUDE.md section 9: no em dashes; the register itself forbids exclamation marks. */
    @Test
    fun `the register copy has no em dash and no exclamation mark`() {
        for (text in listOf(ALFRED.clause, ALFRED.delivery, ALFRED.shortClause, ALFRED.blurb)) {
            assertFalse(text.contains('—'))
            assertFalse(text.contains('!'))
        }
    }
}
