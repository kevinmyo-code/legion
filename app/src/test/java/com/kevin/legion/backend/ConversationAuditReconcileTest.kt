package com.kevin.legion.backend

import com.kevin.legion.data.local.CarDatabase
import com.kevin.legion.data.local.ConversationAudit
import com.kevin.legion.data.local.READ_THROUGH_REDACTED
import com.kevin.legion.engine.DeviceId
import com.kevin.legion.testutil.RoomTestReset
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * [ConversationAuditReconcile] - exercised against an in-memory [FakeConversationAuditBackend]
 * and a real (Robolectric) Room, never a network. The redaction-preservation tests are the
 * load-bearing ones here: ticket 24's ruling that this table is safe to sync rests entirely on
 * redaction having already happened before a row ever reached [com.kevin.legion.data.local.ConversationAuditDao],
 * so this reconcile's only job regarding it is to NOT touch [ConversationAudit.content]/[ConversationAudit.redacted]
 * on the way out.
 */
@RunWith(RobolectricTestRunner::class)
class ConversationAuditReconcileTest {
    private val context = RuntimeEnvironment.getApplication()

    private class FakeConversationAuditBackend : ConversationAuditBackend {
        val batches = mutableListOf<List<ConversationAuditUpload>>()

        override suspend fun uploadConversationAuditBatch(batch: List<ConversationAuditUpload>): Result<Unit> {
            batches.add(batch)
            return Result.success(Unit)
        }
    }

    @Before
    fun clearState() {
        RoomTestReset.resetCarDatabaseSingleton()
    }

    private suspend fun insertRow(
        kind: String,
        content: String,
        redacted: Boolean = false,
        toolName: String = "",
    ) {
        CarDatabase.getDatabase(context).conversationAuditDao().insert(
            ConversationAudit(
                turnSeq = 1L,
                kind = kind,
                toolName = toolName,
                content = content,
                redacted = redacted,
                at = 1_000L,
            ),
        )
    }

    @Test
    fun `a re-run uploads nothing new`() = runBlocking {
        insertRow(ConversationAudit.Kind.USER, "what's my oil life")
        val backend = FakeConversationAuditBackend()

        val first = ConversationAuditReconcile.run(context, backend).getOrThrow()
        assertEquals(1, first.uploaded)

        val second = ConversationAuditReconcile.run(context, backend).getOrThrow()
        assertEquals(0, second.uploaded)
        assertEquals(1, second.sourceCount)
    }

    @Test
    fun `a redacted tool row uploads with its redaction intact and its tool name preserved`() = runBlocking {
        insertRow(
            ConversationAudit.Kind.TOOL_RESULT,
            READ_THROUGH_REDACTED,
            redacted = true,
            toolName = "read_mail",
        )
        val backend = FakeConversationAuditBackend()

        ConversationAuditReconcile.run(context, backend).getOrThrow()

        val uploaded = backend.batches.single().single()
        assertTrue(uploaded.redacted)
        assertEquals(READ_THROUGH_REDACTED, uploaded.content)
        assertEquals("read_mail", uploaded.toolName)
    }

    @Test
    fun `a USER row uploads unredacted`() = runBlocking {
        insertRow(ConversationAudit.Kind.USER, "remember that my dentist is Dr. Kim")
        val backend = FakeConversationAuditBackend()

        ConversationAuditReconcile.run(context, backend).getOrThrow()

        val uploaded = backend.batches.single().single()
        assertFalse(uploaded.redacted)
        assertEquals("remember that my dentist is Dr. Kim", uploaded.content)
    }

    @Test
    fun `reported counts match what was actually sent`() = runBlocking {
        repeat(4) { insertRow(ConversationAudit.Kind.COMPANION, "line $it") }
        val backend = FakeConversationAuditBackend()

        val report = ConversationAuditReconcile.run(context, backend).getOrThrow()

        assertEquals(4, report.sourceCount)
        assertEquals(4, report.uploaded)
        assertEquals(4, backend.batches.sumOf { it.size })
    }

    @Test
    fun `uploaded rows carry this device's id`() = runBlocking {
        insertRow(ConversationAudit.Kind.USER, "hello")
        val backend = FakeConversationAuditBackend()

        ConversationAuditReconcile.run(context, backend).getOrThrow()

        assertEquals(DeviceId.current(context), backend.batches.single().single().deviceId)
    }
}
