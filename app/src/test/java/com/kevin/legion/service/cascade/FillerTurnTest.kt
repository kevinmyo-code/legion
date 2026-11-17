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
}
