package com.kevin.legion.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** What the assistant calls him (2026-11-20). The names are made up for the test; none is his. */
class OwnerNameTest {
    @Test
    fun `the name he typed wins over the engine's, and only the first word is used`() {
        assertEquals("Sam", OwnerName.resolve("Sam", "Robin Ames"))
        assertEquals("Robin", OwnerName.resolve("  ", "Robin Ames"))
        assertEquals("Robin", OwnerName.resolve(null, " Robin "))
        assertNull(OwnerName.resolve("", ""))
        assertNull(OwnerName.resolve(null, null))
    }

    @Test
    fun `only letters, apostrophes and hyphens are kept`() {
        assertEquals("Jean-Luc", OwnerName.clean("Jean-Luc Ames"))
        assertEquals("O'Neil", OwnerName.clean("O'Neil"))
        assertEquals("Sam", OwnerName.clean("\"Sam\","))
        assertNull(OwnerName.clean("123"))
        assertEquals(40, OwnerName.clean("a".repeat(90))!!.length)
    }

    @Test
    fun `the persona line gives his name when known and forbids every name when not`() {
        val known = OwnerName.personaLine("Sam")
        assertTrue(known, known.contains("Their first name is Sam."))
        assertTrue(known, known.contains("now and then, not in every reply"))
        assertTrue(known, known.contains("never any other name, nickname or title"))
        val none = OwnerName.personaLine(null)
        assertTrue(none, none.contains("call them by no name at all"))
        assertTrue(none, none.contains("never make one up"))
        assertEquals(none, OwnerName.personaLine("  "))
        assertFalse(none.contains("if you know it"))
    }

    @Test
    fun `the engine's name is re-read at most once a day`() {
        assertTrue(OwnerName.stale(0L, OwnerName.ENGINE_REFRESH_MS + 1))
        assertFalse(OwnerName.stale(1_000L, 1_000L + OwnerName.ENGINE_REFRESH_MS))
    }

    @Test
    fun `the identity clause ends with the name line, and the persona no longer says if you know it`() {
        val withName = AssistantIdentity.withOwner("CLAUSE", "Sam")
        assertTrue(withName.startsWith("CLAUSE\n\n"))
        assertTrue(withName.endsWith(OwnerName.personaLine("Sam")))
        assertTrue(AssistantIdentity.withOwner("CLAUSE", null).endsWith(OwnerName.personaLine(null)))
        assertFalse(ALFRED.clause.contains("if you know it"))
        assertTrue(ALFRED.clause.contains("no nickname or title for them"))
    }
}
