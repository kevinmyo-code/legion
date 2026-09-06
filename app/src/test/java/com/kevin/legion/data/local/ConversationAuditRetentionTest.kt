package com.kevin.legion.data.local

import com.kevin.legion.testutil.RoomTestReset
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * The rolling retention window on `conversation_audit`, and the one condition it now refuses to
 * delete under: **the server has not confirmed the row yet.**
 *
 * **Why this file exists.** The trim used to be `WHERE at < :cutoff` alone - a blind fourteen-day
 * timer. That is a fine rule for a cache and the wrong one for the only durable record of what a
 * tool call did. In September 2026 the upload silently discarded every row for three days (see
 * [ConversationAudit.clientUuid] for the key collision that caused it) while the trim went on
 * counting down towards deleting the sole surviving copy of that evidence. A silent upload failure
 * plus a blind timer is a shredder, and neither half was visible on its own.
 *
 * So retention now stops at the upload watermark. These tests pin BOTH directions, because a trim
 * that never deletes is not retention and a trim that deletes an unsent row is the bug:
 * an un-uploaded row survives its window, and an uploaded one does not.
 */
@RunWith(RobolectricTestRunner::class)
class ConversationAuditRetentionTest {
    private val context = RuntimeEnvironment.getApplication()

    private val windowMs = CONVERSATION_AUDIT_RETENTION_DAYS * 24 * 60 * 60 * 1000L

    @Before
    fun clearState() {
        RoomTestReset.resetCarDatabaseSingleton()
    }

    private fun dao() = CarDatabase.getDatabase(context).conversationAuditDao()

    /** A row well outside the retention window, inserted directly so its [ConversationAudit.at] is
     *  under the test's control rather than `System.currentTimeMillis()`. Returns its local id. */
    private suspend fun insertAgedRow(content: String): Long {
        dao().insert(
            ConversationAudit(
                turnSeq = 1L,
                kind = ConversationAudit.Kind.TOOL_RESULT,
                toolName = "ask_fleet",
                content = content,
                at = System.currentTimeMillis() - windowMs - 60_000L,
            ),
        )
        return dao().recent(1).single().id
    }

    @Test
    fun `an un-uploaded row survives its retention window`() = runBlocking {
        insertAgedRow("the tool result nobody has a second copy of")

        // A fresh turn arrives, which is what triggers the trim. The watermark is 0: nothing on
        // this device has ever been confirmed by the server.
        dao().record(
            turnSeq = 2L,
            kind = ConversationAudit.Kind.USER,
            content = "what did you actually do",
            uploadedThroughId = 0L,
        )

        assertEquals("the aged row must still be here", 2, dao().count())
    }

    @Test
    fun `an uploaded row is deleted once it ages out`() = runBlocking {
        val agedId = insertAgedRow("already safely on the server")

        dao().record(
            turnSeq = 2L,
            kind = ConversationAudit.Kind.USER,
            content = "what did you actually do",
            uploadedThroughId = agedId,
        )

        assertEquals("retention still trims what is safely stored elsewhere", 1, dao().count())
        assertTrue(
            "and the survivor is the new row, not the aged one",
            dao().recent(1).single().id > agedId,
        )
    }

    @Test
    fun `the trim stops exactly at the watermark, keeping the first unconfirmed row`() = runBlocking {
        val confirmed = insertAgedRow("confirmed by the server")
        val unconfirmed = insertAgedRow("never reached the server")

        dao().trimUploadedOlderThan(System.currentTimeMillis() - windowMs, confirmed)

        val survivors = dao().recent(10).map { it.id }
        assertEquals(listOf(unconfirmed), survivors)
    }

    @Test
    fun `a watermark of zero deletes nothing, however old the rows are`() = runBlocking {
        insertAgedRow("one")
        insertAgedRow("two")

        dao().trimUploadedOlderThan(System.currentTimeMillis() - windowMs, 0L)

        assertEquals(
            "0 means nothing is confirmed, so nothing may be deleted - an install that has never " +
                "synced must over-retain rather than over-delete",
            2,
            dao().count(),
        )
    }

    @Test
    fun `record defaults to keeping everything when no watermark is passed`() = runBlocking {
        insertAgedRow("aged")

        // No uploadedThroughId argument at all - the default has to fail safe.
        dao().record(turnSeq = 2L, kind = ConversationAudit.Kind.USER, content = "hello")

        assertEquals(2, dao().count())
    }

    // --- The counts the visible surface is built on ---------------------------------------------

    @Test
    fun `countAfterId and oldestAtAfterId describe exactly the un-uploaded rows`() = runBlocking {
        val first = insertAgedRow("first")
        insertAgedRow("second")
        insertAgedRow("third")

        assertEquals(3, dao().countAfterId(0L))
        assertEquals(2, dao().countAfterId(first))

        val oldestPending = dao().oldestAtAfterId(first)
        val firstRowAt = dao().recent(10).first { it.id == first }.at
        assertTrue(
            "the oldest PENDING row must be newer than the confirmed one it follows",
            oldestPending != null && oldestPending >= firstRowAt,
        )
    }

    @Test
    fun `oldestAtAfterId is null when nothing is pending`() = runBlocking {
        val only = insertAgedRow("only")
        assertEquals(null, dao().oldestAtAfterId(only))
    }

    @Test
    fun `maxId reports the highest local id, or null on an empty table`() = runBlocking {
        assertEquals(null, dao().maxId())
        val id = insertAgedRow("one")
        assertEquals(id, dao().maxId())
    }
}
