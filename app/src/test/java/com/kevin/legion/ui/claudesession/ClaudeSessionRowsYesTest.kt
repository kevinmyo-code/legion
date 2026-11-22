package com.kevin.legion.ui.claudesession

import com.kevin.legion.claude.ClaudeOverview
import com.kevin.legion.claude.ClaudeStatus
import com.kevin.legion.claude.ClaudeTask
import com.kevin.legion.claude.FakeClaudeBackend.Companion.task
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** One question, one yes (alfred-sessions review, finding 2): the Yes button goes once a yes went. */
class ClaudeSessionRowsYesTest {
    private fun rows(vararg t: ClaudeTask) =
        ClaudeSessionRows.tasks(ClaudeOverview(ClaudeStatus(emptyList(), emptyList()), t.toList()))
            .associateBy { it.id }

    @Test
    fun `a question shows Yes until a yes to it exists`() {
        val asked = task(41, ClaudeTask.NEEDS_CONFIRMATION, question = "Send it?")
        assertTrue(rows(asked).getValue(41).needsConfirmation)
        val yes = task(42, ClaudeTask.QUEUED, parent = 41).copy(confirms = true)
        assertFalse(rows(yes, asked).getValue(41).needsConfirmation)
        val words = task(43, ClaudeTask.QUEUED, parent = 41)
        assertTrue("a follow-up in words is not a yes", rows(words, asked).getValue(41).needsConfirmation)
    }
}
