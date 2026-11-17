package com.kevin.legion.service.cascade

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** After a diagnostics answer the phone said itself, the model may not restate codes, counts or verdicts. */
class DiagFilterTest {

    @Test
    fun `codes, module counts and verdict words are restatements`() {
        for (s in listOf(
            "Clean bill.", "The engine's clear.", "All good.", "Nothing wrong there.", "It's fine.",
            "Seven modules answered.", "3 module(s) answered.", "P0420 is still there.",
            "P zero four two zero again.", "p 0 4 2 0.", "Looks healthy.", "No problems.",
            "One stored code, P-zero-four-twenty, for catalyst efficiency.", "Checking.", "On it.",
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

    @Test
    fun `the stream drops whole sentences and keeps the rest in order`() {
        val f = DiagFilter()
        val out = f.feed("Clean bill. Want me to ") + f.feed("look one up? Seven modules ") + f.feed("answered.")
        val tail = f.flush()
        assertEquals("Want me to look one up? ", out)
        assertEquals("", tail)
        assertEquals(2, f.dropped)
    }
}
