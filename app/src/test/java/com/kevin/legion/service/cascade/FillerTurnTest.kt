package com.kevin.legion.service.cascade

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** [FillerTurn]'s rule, written down in its KDoc, and its streaming hold. Invented lines only. */
class FillerTurnTest {

    @Test
    fun `every scripted filler is filler, whatever the case and final punctuation`() {
        for (f in ScriptedLines.FILLERS) {
            assertTrue(f, FillerTurn.isFillerOnly(f))
            assertTrue(f, FillerTurn.isFillerOnly("  ${f.lowercase().trimEnd('.')}!  "))
        }
    }

    @Test
    fun `short filler-like lines are filler`() {
        for (s in listOf(
            "Checking the modules.", "Checking now.", "Looking into it.", "Let me check that.",
            "One moment please.", "Hold on.", "Checking your calendar.", "Checking. Let me look.",
        )) {
            assertTrue(s, FillerTurn.isFillerOnly(s))
        }
    }

    @Test
    fun `lines with content, more words or other openers are not filler`() {
        for (s in listOf(
            "", "   ", "Looking good.", "On it, timer set.", "Checking: two codes.", "Checking 3 modules.",
            "Checking the modules on the Fit now.", "It is mild.", "Checking. It is mild.",
            "Checking the modules. Looking.", "Okay.", "Done.", "Let me look at the two codes there.",
        )) {
            assertFalse(s, FillerTurn.isFillerOnly(s))
        }
        assertEquals(4, FillerTurn.MAX_WORDS)
    }

    @Test
    fun `the hold keeps a line back only while it may still be filler`() {
        // Filler: held all the way, judged at the end.
        val a = FillerTurn.Hold()
        assertEquals("", a.feed("Check"))
        assertEquals("", a.feed("ing the "))
        assertEquals("", a.feed("modules."))
        assertEquals("", a.end())
        assertTrue(a.filler)
        // Content after a filler sentence: everything goes at once, nothing lost.
        val b = FillerTurn.Hold()
        assertEquals("", b.feed("Checking."))
        assertEquals("Checking. Two", b.feed(" Two"))
        assertEquals(" codes.", b.feed(" codes."))
        assertEquals("", b.end())
        assertFalse(b.filler)
        // Not a filler opener: straight through.
        val c = FillerTurn.Hold()
        assertEquals("It is", c.feed("It is"))
        assertFalse(c.filler)
        // Held, then judged not filler at the end: released whole.
        val d = FillerTurn.Hold()
        assertEquals("", d.feed("Looking good."))
        assertEquals("Looking good.", d.end())
        assertFalse(d.filler)
    }

    // --- before any tool: exact membership only (2026-11-19) ---------------------------------------

    @Test
    fun `exact filler only is membership in the scripted lines, one or more, nothing else`() {
        for (f in ScriptedLines.FILLERS) assertTrue(f, FillerTurn.isExactFillerOnly(f.uppercase()))
        assertTrue(FillerTurn.isExactFillerOnly("Checking. Let me look."))
        assertTrue(FillerTurn.isExactFillerOnly("Checking.Let me look!"))
        // The opener rule after a tool is not used here: these are not the scripted lines.
        for (notFiller in listOf("Checking the modules.", "Looking into it.", "On it, timer set.", "Checking. Two codes.", "", "Check")) {
            assertFalse(notFiller, FillerTurn.isExactFillerOnly(notFiller))
        }
        assertEquals("Two codes.", FillerTurn.stripExactFillers("Checking. One moment.Two codes."))
        assertEquals("Looking good.", FillerTurn.stripExactFillers("Looking good."))
    }

    @Test
    fun `the first-round hold withholds a filler-only reply with no call, passes it on with a call`() {
        val a = FillerTurn.FirstHold()
        assertEquals("", a.feed("Let me "))
        assertEquals("", a.feed("look."))
        assertEquals("", a.end(calls = false))
        assertTrue(a.filler)

        val b = FillerTurn.FirstHold()
        assertEquals("", b.feed("Checking."))
        assertEquals("Checking.", b.end(calls = true))
        assertFalse(b.filler)

        val c = FillerTurn.FirstHold()
        assertEquals("", c.feed("Checking. "))
        assertEquals("It is", c.feed("It is"))
        assertEquals(" mild.", c.feed(" mild."))
        assertEquals("", c.end(calls = false))
        assertFalse(c.filler)
    }
}
