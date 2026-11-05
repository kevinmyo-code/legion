package com.kevin.legion.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** [OutsideNoteQueue]: notes wait in order, and a withdrawn one never reaches the model. */
class OutsideNoteQueueTest {

    @Test
    fun `a withdrawn note is dropped and the rest drain in order`() {
        val q = OutsideNoteQueue()
        q.add("claude_task_5", "five")
        q.add("claude_task_6", "six")
        q.add("claude_task_7", "seven")
        q.withdraw("claude_task_6")
        assertTrue(q.isNotEmpty())
        assertEquals(listOf("five", "seven"), q.drain())
        assertFalse(q.isNotEmpty())
        assertEquals(emptyList<String>(), q.drain())
    }

    @Test
    fun `withdrawing the only note leaves nothing to send`() {
        val q = OutsideNoteQueue()
        q.add("claude_task_6", "six")
        q.withdraw("claude_task_6")
        assertFalse(q.isNotEmpty())
        assertTrue(q.drain().isEmpty())
    }
}
