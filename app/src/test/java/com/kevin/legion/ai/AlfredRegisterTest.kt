package com.kevin.legion.ai

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the register, the way [AriaBrainHonestyClauseTest] pins the honesty clauses. Async-lookups
 * ticket 06, rewritten 2026-10-28 on Kevin's second ruling that day: a calm crew chief mixed with
 * an old friend, the name kept, no butler framing, no "sir" or "Mr", and no accent in the prompt.
 *
 * **Presence only.** Nothing inspects the spoken audio, so whether the model actually sounds like
 * this is owed to a phone run read through `tools/voice_audit.py`. A green run here says the words
 * are in the prompt (or out of it), nothing more.
 */
class AlfredRegisterTest {

    /** U+2014, built from its code point so this source file carries no em dash itself. */
    private val emDash = 0x2014.toChar()

    /** The clause with its line wraps folded, so a re-wrap of the prose does not fail a pin. */
    private val clause = ALFRED.clause.replace(Regex("\\s+"), " ")

    /** Every piece of register copy the model or the Companion screen sees. */
    private val copy = listOf(ALFRED.clause, ALFRED.delivery, ALFRED.shortClause, ALFRED.blurb) +
        ALFRED.greetings

    /** The register's working rules, each named so a deletion says what was lost. */
    @Test
    fun `the clause carries the crew chief and old friend register`() {
        val rules = mapOf(
            "like an old friend" to "the old-friend half of the register",
            "first-name terms" to "first-name terms, not a title",
            "warm when it counts" to "the crew-chief warmth",
            "Lead with the answer, numbers first." to "numbers first",
            "A number gets a read, not just the number" to "a number gets a read",
            "suspiciously round" to "the kind of read a number gets",
            "you react rather than recite" to "reacts rather than recites",
            "you say what you think" to "says what he thinks",
            "One understated remark per answer at most" to "wit is rationed to one remark",
            "never at the user" to "the remark is never at the user's expense",
            "offer it once, in a clause. If they pass, it is gone." to
                "anticipation offered once, never pushed (section 7 compulsion rules)",
            "Vary your phrasing" to "no stock line repeated",
            "it shows in what you do rather than what you say" to "fondness shown in deeds",
            "one short line, then stop" to "a sign-off is one line",
        )
        for ((phrase, why) in rules) {
            assertTrue("missing from ALFRED.clause: $why", clause.contains(phrase))
        }
    }

    /** Kevin: "no need for sir and mr". Named outright, with the stock lines, in clause and anchor. */
    @Test
    fun `the forbidden address and stock lines are named outright`() {
        assertTrue(clause.contains("Never say \"sir\", \"madam\" or \"Mr\""))
        assertTrue(clause.contains("Never \"Very good\", \"Right away\" or \"Shall I\"."))
        assertTrue(ALFRED.shortClause.contains("Never \"sir\" or \"Mr\"."))
        assertFalse("the permissive sir rule is gone", clause.contains("earns it"))
    }

    /** He is a person called Alfred. The name pulls toward butler lines; the copy must not. */
    @Test
    fun `no butler framing anywhere in the register copy`() {
        for (text in copy) {
            assertFalse("butler framing in: $text", text.contains("butler", ignoreCase = true))
            assertFalse("servant framing in: $text", text.contains("in service to"))
        }
    }

    /** Kevin: "drop the accent". The accent lives in the voice preset, not the prompt. */
    @Test
    fun `the accent rules are gone`() {
        for (text in copy) {
            for (accent in listOf(
                "British", "English", "Received Pronunciation", "Never American", "petrol",
                "sixth of August",
            )) {
                assertFalse("accent rule '$accent' in: $text", text.contains(accent))
            }
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

    /** The anchor restates shortClause last; it must carry the register, not the old flat one. */
    @Test
    fun `the short clause carries the register and drops the flat one`() {
        val s = ALFRED.shortClause
        assertTrue(s.contains("an old friend on first-name terms"))
        assertTrue(s.contains("Numbers first; a number gets a read."))
        assertTrue(s.contains("never at the user"))
        assertTrue(s.contains("Never guess a number you don't have."))
        assertFalse("the old flat instruction is gone", s.contains("Answer and stop"))
    }

    /** The holding line is varied; the fixed one the audit heard every time stays gone. */
    @Test
    fun `the fixed holding line is gone from the voice delivery`() {
        assertFalse(ALFRED.delivery.contains("\"One moment.\""))
        assertTrue(ALFRED.delivery.contains("worded fresh each time"))
        assertFalse(clause.contains("then you stop"))
    }

    /** CLAUDE.md section 9: no em dashes; the register itself forbids exclamation marks. */
    @Test
    fun `the register copy has no em dash and no exclamation mark`() {
        for (text in copy) {
            assertFalse(text.contains(emDash))
            assertFalse(text.contains('!'))
        }
    }
}
