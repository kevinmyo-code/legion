package com.kevin.legion.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** F2: the live context reaches the model in the setup, with no generation asked for. */
class LiveOpenContextTest {
    @Test
    fun `live context is appended to the system instruction in the old prefix wording`() {
        val out = LiveOpenContext.systemInstruction("RULES", "It is 9:05.")
        assertTrue(out.startsWith("RULES"))
        assertTrue(out.endsWith("(Current context, use naturally if relevant:\nIt is 9:05.)"))
    }

    @Test
    fun `no live context leaves the instruction untouched`() {
        assertEquals("RULES", LiveOpenContext.systemInstruction("RULES", "  "))
    }
}
