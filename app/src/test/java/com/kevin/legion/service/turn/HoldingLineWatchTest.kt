package com.kevin.legion.service.turn

import com.kevin.legion.service.turn.HoldingLineWatch.Outcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Ticket 04 run 3's dead holding lines (async-lookups ticket 07): F1, F2 and the run 3c turn. */
class HoldingLineWatchTest {

    private fun HoldingLineWatch.turn(
        startedAtMs: Long,
        said: String,
        toolCalled: Boolean = false,
        lookupInFlight: Boolean = false,
    ) = onTurnComplete(startedAtMs, said, toolCalled, lookupInFlight)

    @Test
    fun `the detector on the run's real shapes`() {
        assertTrue(HoldingLineWatch.isHoldingLine("Checking."))
        assertTrue(HoldingLineWatch.isHoldingLine("Let me look."))
        assertTrue(HoldingLineWatch.isHoldingLine("Let me look.Checking."))
        assertTrue(HoldingLineWatch.isHoldingLine("One moment."))
        assertFalse(HoldingLineWatch.isHoldingLine("No codes."))
        assertFalse(
            HoldingLineWatch.isHoldingLine(
                "The last codes remain P one two eight two and P zero seven zero zero, recorded on the " +
                    "twenty-fifth of August.",
            ),
        )
        assertFalse(HoldingLineWatch.isHoldingLine("Checking P0700."))
        assertFalse(HoldingLineWatch.isHoldingLine(""))
    }

    @Test
    fun `known gap - the prompt's own six-word example reads as an answer`() {
        assertFalse(HoldingLineWatch.isHoldingLine("Give me a second with that."))
    }

    @Test
    fun `F1 - a holding line after the result fires once, then a real answer releases`() {
        val w = HoldingLineWatch()
        w.onAsyncResultSent("ask_fleet", atMs = 18_135L)
        assertEquals(Outcome.LATE_HOLDING, w.turn(18_901L, "Checking."))
        assertEquals(Outcome.NONE, w.turn(21_000L, "Let me look."))
        assertEquals("ask_fleet", w.pendingTool())
        assertEquals(Outcome.ANSWERED, w.turn(25_000L, "No codes stored."))
        assertNull(w.pendingTool())
    }

    @Test
    fun `F2 - user speech in between changes nothing about the late holding line`() {
        val w = HoldingLineWatch()
        w.onAsyncResultSent("ask_fleet", atMs = 4_516L)
        // The user's "what?" is not a model turn; the model's "Let me look." began at 08.58.
        assertEquals(Outcome.LATE_HOLDING, w.turn(8_581L, "Let me look."))
    }

    @Test
    fun `the ordinary holding line the result arrived during is not a late one`() {
        val w = HoldingLineWatch()
        // "Checking the records." began at 57.924, the result went back at 58.638.
        w.onAsyncResultSent("ask_body", atMs = 58_638L)
        assertEquals(Outcome.NONE, w.turn(57_924L, "Checking the records."))
        assertEquals("ask_body", w.pendingTool())
        assertEquals(Outcome.ANSWERED, w.turn(60_478L, "Five meals logged, most recent first."))
    }

    @Test
    fun `a holding line covering a new tool call after a result is not late`() {
        val w = HoldingLineWatch()
        w.onAsyncResultSent("ask_fleet", atMs = 1_000L)
        assertEquals(Outcome.NONE, w.turn(2_000L, "Checking again.", toolCalled = true))
        assertEquals("ask_fleet", w.pendingTool())
    }

    @Test
    fun `a tool-call-only turn after the result changes nothing`() {
        val w = HoldingLineWatch()
        w.onAsyncResultSent("ask_fleet", atMs = 1_000L)
        assertEquals(Outcome.NONE, w.turn(0L, "", toolCalled = true))
        assertEquals("ask_fleet", w.pendingTool())
    }

    @Test
    fun `speech with no transcript counts as an answer`() {
        val w = HoldingLineWatch()
        w.onAsyncResultSent("ask_goals", atMs = 1_000L)
        assertEquals(Outcome.ANSWERED, w.turn(2_000L, ""))
    }

    @Test
    fun `run 3c - a holding line with no tool and nothing pending fires once`() {
        val w = HoldingLineWatch()
        assertEquals(Outcome.EMPTY_HOLDING, w.turn(15_694L, "Checking."))
        // Once: a second bare holding line does not nudge again.
        assertEquals(Outcome.NONE, w.turn(18_000L, "Let me look."))
        // A real answer releases the hold.
        assertEquals(Outcome.ANSWERED, w.turn(20_000L, "Twenty-four hour fasts tend to cost some muscle."))
        assertEquals(Outcome.NONE, w.turn(30_000L, "Anything else?"))
    }

    @Test
    fun `run 3c - calling the tool after the nudge releases the hold`() {
        val w = HoldingLineWatch()
        assertEquals(Outcome.EMPTY_HOLDING, w.turn(1_000L, "Checking."))
        assertEquals(Outcome.ANSWERED, w.turn(0L, "", toolCalled = true))
    }

    @Test
    fun `a holding line with its tool call, or with a lookup still running, is the normal shape`() {
        val w = HoldingLineWatch()
        assertEquals(Outcome.NONE, w.turn(1_000L, "Checking.", toolCalled = true))
        assertEquals(Outcome.NONE, w.turn(5_000L, "Still checking.", lookupInFlight = true))
    }

    @Test
    fun `the instructions name the tool, and are never user speech`() {
        val late = HoldingLineWatch.lateInstruction("ask_fleet")
        assertTrue(late.startsWith("(System note, not spoken by the user:"))
        assertTrue(late.contains("`ask_fleet`"))
        assertTrue(late.contains("answer the user's question from it now", ignoreCase = true))
        val empty = HoldingLineWatch.EMPTY_INSTRUCTION
        assertTrue(empty.startsWith("(System note, not spoken by the user:"))
        assertTrue(empty.contains("called no tool"))
        assertTrue(empty.contains("no tool for it"))
    }

    /** Run 6 (A25, 2026-10-29): "Checking." with no tool, then "My apologies, I'm ready for your next request." */
    @Test
    fun `the empty holding line note says act in this turn, never apologise or wait`() {
        val empty = HoldingLineWatch.EMPTY_INSTRUCTION
        assertTrue(empty.contains("in this same turn"))
        assertTrue(empty.contains("call the tool for the lookup you implied"))
        assertTrue(empty.contains("answer the question from what you already have"))
        assertTrue(empty.contains("Do not apologise"))
        assertTrue(empty.contains("do not wait for the user to ask again"))
        val late = HoldingLineWatch.lateInstruction("ask_fleet")
        assertTrue(late.contains("Do not apologise"))
        assertTrue(late.contains("do not wait for the user to ask again"))
        for (note in listOf(empty, late)) {
            assertTrue(note.endsWith(".)"))
            assertFalse(note.contains("Alfred", ignoreCase = true))
            assertFalse(note.contains("\u2014"))
        }
    }
}
