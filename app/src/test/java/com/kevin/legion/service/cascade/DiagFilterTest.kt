@file:Suppress("MaxLineLength") // test fixtures read best on one line

package com.kevin.legion.service.cascade

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** After a diagnostics answer the phone said itself, the model may not restate codes, counts or verdicts. */
class DiagFilterTest {

    @Test
    fun `bare codes, module counts and bare verdicts are restatements`() {
        for (s in listOf(
            "Clean bill.", "The engine's clear.", "All good.", "Nothing wrong there.", "It's fine.",
            "Seven modules answered.", "3 module(s) answered.", "P0420 is still there.", "P0420 is gone.",
            "P zero four two zero again.", "p 0 4 2 0.", "Looks healthy.", "No problems.", "Okay.",
            "Checking.", "On it.", "You have P0420 stored.",
        )) {
            assertTrue(s, DiagFilter.restatesResult(s))
        }
    }

    @Test
    fun `a follow-up that adds nothing about the result passes`() {
        for (s in listOf(
            "Want me to scan every module?", "I can look one of them up.", "Say the word if you want more.",
            "Is the key at on?",
        )) {
            assertFalse(s, DiagFilter.restatesResult(s))
        }
    }

    // 2026-11-21 (the engine twin's review, V1): a warning was dropped for holding a verdict word.
    @Test
    fun `a sentence with a safety cue is never dropped, whatever else it says`() {
        for (s in listOf(
            "It's not okay to drive: pull over.", "It's not ok to drive, pull over.",
            "If the check engine light is flashing, stop driving.", "Don't drive it until the codes are clear.",
            "Do not drive it if it's overheating.", "Clean, but if you see smoke, pull over.",
            "The brakes are fine, just get them looked at.", "Oil pressure is okay.", "A temperature warning is not fine.",
        )) {
            assertFalse(s, DiagFilter.restatesResult(s))
            assertFalse(s, DiagFilter.restatesResult(s, said = s))
        }
        assertEquals(DiagFilter.SAFETY_CUES.size, DiagFilter.SAFETY_CUES.toSet().size)
    }

    @Test
    fun `a verdict word inside other content passes`() {
        for (s in listOf(
            "I can clear them if you say yes.", "It's clear tonight, by the way.",
            "That one is fine to drive on for now, but book it in this week.",
        )) {
            assertFalse(s, DiagFilter.restatesResult(s))
        }
    }

    @Test
    fun `a code explained passes, unless it repeats the spoken line word for word`() {
        val said = "One stored engine code: P0301, cylinder 1 misfire detected."
        for (s in listOf(
            "P0301 means cylinder 1 is misfiring.", "P0301 usually points to a plug or a coil on cylinder 1.",
            "One stored code, P-zero-four-twenty, for catalyst efficiency.",
        )) {
            assertFalse(s, DiagFilter.restatesResult(s))
            assertFalse(s, DiagFilter.restatesResult(s, said))
        }
        // Word for word from the line: a repeat, dropped (with or without a code in it).
        assertTrue(DiagFilter.restatesResult("P0301, cylinder 1 misfire detected.", said))
        assertTrue(DiagFilter.restatesResult("Cylinder 1 misfire detected.", said))
        // Too short to call a repeat on its own.
        assertFalse(DiagFilter.restatesResult("Misfire detected.", said))
    }

    @Test
    fun `the stream drops whole sentences and keeps the rest in order`() {
        val f = DiagFilter()
        val out = f.feed("Clean bill. Want me to ") + f.feed("look one up? Seven modules ") + f.feed("answered.")
        val tail = f.flush()
        assertEquals("Want me to look one up? ", out)
        assertEquals("", tail)
        assertEquals(2, f.dropped)
    }

    @Test
    fun `in the stream a warning after a verdict is said, the verdict is not`() {
        val f = DiagFilter("No stored engine codes.")
        val out = f.feed("All clear. It's not okay to drive: ") + f.feed("pull over.")
        val tail = f.flush()
        assertEquals("It's not okay to drive: pull over.", out + tail)
        assertEquals(1, f.dropped)
    }
}
