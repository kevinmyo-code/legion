package com.kevin.legion.ui

import com.kevin.legion.backend.ConversationAuditReconcile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [auditTrailBacklogSentence] - the one sentence the Setup screen says about the audit trail's
 * upload backlog.
 *
 * **The reason this sentence is tested at all.** `conversation_audit` uploaded nothing for three
 * days in September 2026 while every surface in the app reported success, and the only contrary
 * evidence was a row count in a database nobody was looking at. This sentence is the whole visible
 * defence against a repeat, so the states it distinguishes are the point: unreadable must never
 * read as empty (CLAUDE.md section 1), and a backlog must carry an AGE, because a count alone does
 * not tell a four-minute lag apart from a fortnight of evidence about to expire.
 */
class AuditTrailBacklogSentenceTest {

    private fun pending(rows: Int, ageMs: Long?) = ConversationAuditReconcile.Pending(rows, ageMs)

    @Test
    fun `an unreadable table says so, and never says nothing is waiting`() {
        val sentence = auditTrailBacklogSentence(null)

        assertTrue(sentence.contains("Couldn't read"))
        assertFalseContains(sentence, "waiting")
        assertFalseContains(sentence, "on the server")
    }

    @Test
    fun `an empty backlog says everything is on the server`() {
        assertEquals(
            "Every conversation and tool call on this device is on the server.",
            auditTrailBacklogSentence(pending(0, null)),
        )
    }

    @Test
    fun `a backlog reports the count and the oldest row's age`() {
        val sentence = auditTrailBacklogSentence(pending(142, 3 * 24 * 60 * 60 * 1000L))

        assertEquals(
            "142 rows waiting to reach the server, oldest 3 days old. Nothing is deleted while it waits.",
            sentence,
        )
    }

    @Test
    fun `the backlog sentence says the waiting rows are not being deleted`() {
        // Retention keeps an un-uploaded row (ConversationAuditDao.trimUploadedOlderThan), and the
        // sentence has to say so - otherwise a backlog reads as data already lost, which it is not.
        assertTrue(
            auditTrailBacklogSentence(pending(5, 60_000L)).contains("Nothing is deleted while it waits"),
        )
    }

    @Test
    fun `one row is singular`() {
        assertTrue(auditTrailBacklogSentence(pending(1, 60_000L)).startsWith("1 row waiting"))
    }

    @Test
    fun `the age is rendered coarsely at each scale`() {
        assertTrue(auditTrailBacklogSentence(pending(2, 30_000L)).contains("oldest under a minute old"))
        assertTrue(auditTrailBacklogSentence(pending(2, 60_000L)).contains("oldest 1 minute old"))
        assertTrue(auditTrailBacklogSentence(pending(2, 5 * 60_000L)).contains("oldest 5 minutes old"))
        assertTrue(auditTrailBacklogSentence(pending(2, 60 * 60_000L)).contains("oldest 1 hour old"))
        assertTrue(auditTrailBacklogSentence(pending(2, 5 * 60 * 60_000L)).contains("oldest 5 hours old"))
        assertTrue(auditTrailBacklogSentence(pending(2, 24 * 60 * 60_000L)).contains("oldest 1 day old"))
        assertTrue(auditTrailBacklogSentence(pending(2, 14 * 24 * 60 * 60_000L)).contains("oldest 14 days old"))
    }

    @Test
    fun `a backlog with no readable age still reports the count`() {
        val sentence = auditTrailBacklogSentence(pending(3, null))

        assertTrue(sentence.startsWith("3 rows waiting to reach the server."))
        assertFalseContains(sentence, "oldest")
    }

    private fun assertFalseContains(haystack: String, needle: String) =
        assertTrue("\"$haystack\" must not contain \"$needle\"", !haystack.contains(needle))
}
