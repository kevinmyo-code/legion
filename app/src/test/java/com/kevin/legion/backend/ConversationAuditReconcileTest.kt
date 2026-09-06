package com.kevin.legion.backend

import com.kevin.legion.data.local.CarDatabase
import com.kevin.legion.data.local.ConversationAudit
import com.kevin.legion.data.local.READ_THROUGH_REDACTED
import com.kevin.legion.engine.DeviceId
import com.kevin.legion.testutil.RoomTestReset
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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

        /** Settable so a [maybeAutoRun][ConversationAuditReconcile.maybeAutoRun]-adjacent test can
         *  assert the reported [ConversationAuditReconcile.Report.serverCountAfter] without a live
         *  HEAD count. */
        var serverCount = 0L

        /**
         * How many rows of each batch the server "accepts". Null accepts everything, which is the
         * ordinary case. Set it to model the failure this whole class of test exists for: a server
         * that returns 200 having written FEWER rows than it was sent, which is exactly what
         * `on conflict do nothing` does and exactly what nothing could see until
         * [ConversationAuditBackend.uploadConversationAuditBatch] started returning a count.
         */
        var acceptAtMost: Int? = null

        /** Set to make the next and every subsequent upload fail outright. */
        var failWith: Throwable? = null

        override suspend fun uploadConversationAuditBatch(batch: List<ConversationAuditUpload>): Result<Int> {
            // Recorded even on the failure path: a test asserting that a retry re-sends the SAME
            // rows needs to see what the failed attempt offered.
            batches.add(batch)
            failWith?.let { return Result.failure(it) }
            return Result.success(acceptAtMost?.coerceAtMost(batch.size) ?: batch.size)
        }

        override suspend fun countConversationAudit(): Result<Long> = Result.success(serverCount)
    }

    @Before
    fun clearState() {
        RoomTestReset.resetCarDatabaseSingleton()
        SupabaseConfig.clear(context)
    }

    private suspend fun insertRow(
        kind: String,
        content: String,
        redacted: Boolean = false,
        toolName: String = "",
        at: Long = 1_000L,
    ) {
        CarDatabase.getDatabase(context).conversationAuditDao().insert(
            ConversationAudit(
                turnSeq = 1L,
                kind = kind,
                toolName = toolName,
                content = content,
                redacted = redacted,
                at = at,
            ),
        )
    }

    private fun storedCursor() =
        ConversationAuditUploadCursor.lastUploadedId(context, DeviceId.current(context))

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


    // --- The watermark only moves over rows the server confirmed ------------------------------
    // Every test below is a regression test for one incident: on 2026-09-03 this reconcile's
    // server key was (device_id, local_id), the phone's AUTOINCREMENT rowid restarted, 142 rows
    // collided with 142 unrelated August rows, `on conflict do nothing` discarded all of them, the
    // call returned success, and the cursor advanced over the lot. Three days of the only durable
    // record of what a tool call did, gone, with a 14-day delete timer running.

    @Test
    fun `a failed upload leaves the watermark exactly where it was`() = runBlocking {
        repeat(3) { insertRow(ConversationAudit.Kind.COMPANION, "line $it") }
        val backend = FakeConversationAuditBackend()
        backend.failWith = ConversationAuditBackendException("Couldn't reach the server.")

        val result = ConversationAuditReconcile.run(context, backend)

        assertTrue("a transport failure must surface as a failure", result.isFailure)
        assertEquals("the watermark must not move on a failure", 0L, storedCursor())
    }

    @Test
    fun `a retry after a failure sends the same rows again`() = runBlocking {
        repeat(3) { insertRow(ConversationAudit.Kind.COMPANION, "line $it") }
        val backend = FakeConversationAuditBackend()
        backend.failWith = ConversationAuditBackendException("Couldn't reach the server.")
        ConversationAuditReconcile.run(context, backend)

        backend.failWith = null
        val report = ConversationAuditReconcile.run(context, backend).getOrThrow()

        assertEquals("every row must be re-offered, none skipped", 3, report.uploaded)
        assertEquals(2, backend.batches.size)
        assertEquals(
            "the retry must offer the identical rows, by identity not by position",
            backend.batches[0].map { it.clientUuid },
            backend.batches[1].map { it.clientUuid },
        )
    }

    @Test
    fun `a server that silently accepts nothing does not advance the watermark`() = runBlocking {
        // The 2026-09-03 shape exactly: HTTP success, zero rows written.
        repeat(3) { insertRow(ConversationAudit.Kind.COMPANION, "line $it") }
        val backend = FakeConversationAuditBackend()
        backend.acceptAtMost = 0

        val report = ConversationAuditReconcile.run(context, backend).getOrThrow()

        assertEquals("nothing reached the server, so nothing may be reported uploaded", 0, report.uploaded)
        assertEquals("and the discard must be visible, not silent", 3, report.notAccepted)
        assertEquals(0L, storedCursor())
    }

    @Test
    fun `a partially accepted batch advances only as far as the server confirmed`() = runBlocking {
        repeat(5) { insertRow(ConversationAudit.Kind.COMPANION, "line $it") }
        val backend = FakeConversationAuditBackend()
        backend.acceptAtMost = 2

        val report = ConversationAuditReconcile.run(context, backend).getOrThrow()

        assertEquals(2, report.uploaded)
        assertEquals(3, report.notAccepted)
        val firstBatch = backend.batches.single()
        assertEquals(
            "the watermark stops at the second row, never at the fifth",
            firstBatch[1].localId,
            storedCursor(),
        )
    }

    @Test
    fun `the rows a partial batch left behind are re-offered on the next run`() = runBlocking {
        repeat(5) { insertRow(ConversationAudit.Kind.COMPANION, "line $it") }
        val backend = FakeConversationAuditBackend()
        backend.acceptAtMost = 2
        ConversationAuditReconcile.run(context, backend).getOrThrow()

        backend.acceptAtMost = null
        val second = ConversationAuditReconcile.run(context, backend).getOrThrow()

        assertEquals("the three rows nobody confirmed must go up", 3, second.uploaded)
        assertEquals(0, second.notAccepted)
    }

    @Test
    fun `every uploaded row carries the client uuid the phone minted, never a fresh one`() = runBlocking {
        insertRow(ConversationAudit.Kind.USER, "hello")
        val stored = CarDatabase.getDatabase(context).conversationAuditDao().recent(1).single()
        val backend = FakeConversationAuditBackend()

        ConversationAuditReconcile.run(context, backend).getOrThrow()

        assertTrue("a minted uuid is never blank", stored.clientUuid.isNotBlank())
        assertEquals(stored.clientUuid, backend.batches.single().single().clientUuid)
    }

    @Test
    fun `a watermark left over from a previous incarnation of the table is discarded`() = runBlocking {
        // The next failure this cursor would have caused: prefs survive a Room table reset, so a
        // watermark of 142 against a table whose highest id is 3 skips every row, forever, in
        // silence. A cursor above max(id) is impossible unless the sequence restarted.
        repeat(3) { insertRow(ConversationAudit.Kind.COMPANION, "line $it") }
        ConversationAuditUploadCursor.advance(context, DeviceId.current(context), 142L)
        val backend = FakeConversationAuditBackend()

        val report = ConversationAuditReconcile.run(context, backend).getOrThrow()

        assertEquals("a stale watermark must not swallow the table", 3, report.uploaded)
    }

    @Test
    fun `a legitimate watermark is left alone`() = runBlocking {
        repeat(3) { insertRow(ConversationAudit.Kind.COMPANION, "line $it") }
        val dao = CarDatabase.getDatabase(context).conversationAuditDao()
        val secondId = dao.getAfterId(0L, 3)[1].id
        ConversationAuditUploadCursor.advance(context, DeviceId.current(context), secondId)
        val backend = FakeConversationAuditBackend()

        val report = ConversationAuditReconcile.run(context, backend).getOrThrow()

        assertEquals("only the row past the watermark goes up", 1, report.uploaded)
    }

    // --- The pending surface reports what is actually pending -----------------------------------

    @Test
    fun `pendingSummary counts every row past the watermark and dates the oldest`() = runBlocking {
        val now = 10_000_000_000L
        insertRow(ConversationAudit.Kind.USER, "old", at = now - 3 * 24 * 60 * 60 * 1000L)
        insertRow(ConversationAudit.Kind.COMPANION, "new", at = now - 60_000L)

        val pending = ConversationAuditReconcile.pendingSummary(context, now)

        assertEquals(2, pending?.rows)
        assertEquals(3 * 24 * 60 * 60 * 1000L, pending?.oldestAgeMs)
    }

    @Test
    fun `pendingSummary reports nothing pending once the watermark covers every row`() = runBlocking {
        insertRow(ConversationAudit.Kind.USER, "up")
        val backend = FakeConversationAuditBackend()
        ConversationAuditReconcile.run(context, backend).getOrThrow()

        val pending = ConversationAuditReconcile.pendingSummary(context)

        assertEquals(0, pending?.rows)
        assertNull("no pending rows means no oldest one", pending?.oldestAgeMs)
    }

    @Test
    fun `pendingSummary still counts rows a server accepted none of`() = runBlocking {
        // The number a person would have needed on 2026-09-04 to notice.
        repeat(4) { insertRow(ConversationAudit.Kind.COMPANION, "line $it") }
        val backend = FakeConversationAuditBackend()
        backend.acceptAtMost = 0
        ConversationAuditReconcile.run(context, backend).getOrThrow()

        assertEquals(4, ConversationAuditReconcile.pendingSummary(context)?.rows)
    }

    // --- ConversationAuditReconcile.maybeAutoRun's two guard halves --------------------------

    @Test
    fun `autoRunGate returns null when Supabase is not configured`() {
        // clearState's SupabaseConfig.clear(context) already left this unconfigured.
        assertNull(ConversationAuditReconcile.autoRunGate(context))
    }

    /** Same "drive the pure predicate, not the client-building gate" reasoning as
     *  `ObdSampleReconcileTest`'s identical test - see that test's own doc for why. */
    @Test
    fun `isThrottled is true immediately after a reservation, false once the floor elapses`() {
        val reservedAt = 10_000_000L
        ConversationAuditReconcile.setLastAutoRunAtForTest(reservedAt)

        assertTrue(ConversationAuditReconcile.isThrottled(reservedAt + 1_000L))
        assertTrue(ConversationAuditReconcile.isThrottled(reservedAt))
        assertFalse(ConversationAuditReconcile.isThrottled(reservedAt + 6 * 60_000L))
    }

    private fun fakeGateway(userId: String?) = object : SupabaseAuthGateway {
        override suspend fun signInWithPassword(email: String, password: String) = Unit
        override suspend fun signOut() = Unit
        override fun currentUserId(): String? = userId
        override suspend fun awaitSessionReady(timeoutMillis: Long): Boolean = true
        override suspend fun householdRosterSize(): Int = 0
    }

    @Test
    fun `runIfSignedIn no-ops for a genuinely signed-out session, never attempting an upload`() = runBlocking {
        insertRow(ConversationAudit.Kind.COMPANION, "should not upload")
        val backend = FakeConversationAuditBackend()
        val auth = SupabaseAuth(context, gatewayProvider = { fakeGateway(null) })

        ConversationAuditReconcile.runIfSignedIn(context, backend, auth)

        assertTrue(backend.batches.isEmpty())
        assertEquals(0L, ConversationAuditUploadCursor.lastUploadedId(context, DeviceId.current(context)))
    }

    @Test
    fun `runIfSignedIn runs once when signed in`() = runBlocking {
        insertRow(ConversationAudit.Kind.COMPANION, "should upload")
        val backend = FakeConversationAuditBackend()
        val auth = SupabaseAuth(context, gatewayProvider = { fakeGateway("user-1") })

        ConversationAuditReconcile.runIfSignedIn(context, backend, auth)

        assertEquals(1, backend.batches.sumOf { it.size })
    }
}
