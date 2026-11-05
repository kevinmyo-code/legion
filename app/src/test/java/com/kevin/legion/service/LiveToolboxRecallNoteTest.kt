package com.kevin.legion.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The note on every non-empty `recall_memory` result (2026-10-30: generic gift ideas and "their"
 * for his wife, with six memories about her in front of the model). Guards its presence and its
 * register, never its obedience.
 */
class LiveToolboxRecallNoteTest {
    private val note = LiveToolbox.RECALL_USE_NOTE.lowercase()

    @Test
    fun `the note asks for names, pronouns and specifics`() {
        assertTrue(note.contains("name the people"))
        assertTrue(note.contains("pronouns"))
        assertTrue(note.contains("never 'they'"))
        assertTrue(note.contains("rather than generic"))
    }

    /** 2026-11-05: what is remembered is said as the memory states it, with nothing added. */
    @Test
    fun `the note forbids adding details the memories do not hold`() {
        assertTrue(note.contains("say only what a memory states"))
        assertTrue(note.contains("add no detail"))
    }

    @Test
    fun `the note keeps the register and names no assistant`() {
        assertFalse(Regex("\\bsir\\b").containsMatchIn(note))
        assertFalse(Regex("\\bmr\\b").containsMatchIn(note))
        assertFalse(note.contains("alfred"))
    }
}
