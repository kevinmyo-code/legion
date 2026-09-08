package com.kevin.legion.backend

import com.kevin.legion.backend.engine.EngineBackends
import com.kevin.legion.backend.engine.EngineFailure
import com.kevin.legion.backend.engine.EngineHttpException
import com.kevin.legion.backend.engine.EngineTransport
import com.kevin.legion.backend.engine.Transport
import com.kevin.legion.data.local.CarDatabase
import com.kevin.legion.data.local.VoiceNote
import com.kevin.legion.data.local.VoiceNoteKind
import com.kevin.legion.testutil.RoomTestReset
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * [VoiceNotesBackfill] - the route a voice note that predates the `voice_notes` transport flip has
 * to the engine. `VoiceNoteController.syncToBackend` has exactly two callers, a successful
 * transcription and a rename, so a note transcribed before the flip and never renamed since had no
 * route at all; nor did one whose transcription failed, which can never take the first path.
 *
 * In-memory fake, real (Robolectric) `voice_notes` table, no network.
 */
@RunWith(RobolectricTestRunner::class)
class VoiceNotesBackfillTest {
    private val context = RuntimeEnvironment.getApplication()

    private class FakeVoiceNotes : VoiceNotesBackend {
        /** Every [VoiceNoteFields] this fake was handed, kept so a test can inspect what actually
         * went on the wire rather than only what came back. */
        val sent = mutableListOf<VoiceNoteFields>()
        var refuse: (VoiceNoteFields) -> Throwable? = { null }
        private var minted = 0

        override suspend fun fetchActive(): Result<List<RemoteVoiceNote>> = Result.success(emptyList())

        override suspend fun upsert(serverId: String?, fields: VoiceNoteFields): Result<RemoteVoiceNote> {
            refuse(fields)?.let { return Result.failure(it) }
            sent += fields
            minted++
            return Result.success(
                RemoteVoiceNote(
                    serverId = serverId ?: "server-$minted",
                    startedAtMs = fields.startedAtMs,
                    endedAtMs = fields.endedAtMs,
                    title = fields.title,
                    summary = fields.summary,
                    transcript = fields.transcript,
                    kind = fields.kind,
                    provenance = "LLM_DERIVED",
                    interrupted = fields.interrupted,
                    updatedAtMs = 10_000L + minted,
                    deleted = false,
                )
            )
        }

        override suspend fun softDelete(serverId: String): Result<Boolean> = Result.success(false)
    }

    @Before
    fun clearState() {
        RoomTestReset.resetCarDatabaseSingleton()
        VoiceNotesBackfillCursor.resetForTest(context)
        VoiceNotesBackfill.setLastAutoRunAtForTest(0L)
    }

    @After
    fun drain() {
        RoomTestReset.drainArchDiskIoPool()
    }

    private fun dao() = CarDatabase.getDatabase(context).voiceNoteDao()

    private suspend fun note(
        startedAt: Long,
        transcript: String? = "a transcript",
        summary: String? = null,
        title: String? = null,
        audioPath: String? = "/data/voice/note.m4a",
        endedAt: Long? = startedAt + 60_000L,
        interrupted: Boolean = false,
        serverId: String? = null,
    ): Long = dao().insert(
        VoiceNote(
            serverId = serverId,
            startedAt = startedAt,
            endedAt = endedAt,
            title = title,
            summary = summary,
            transcript = transcript,
            audioPath = audioPath,
            kind = VoiceNoteKind.SOLO,
            interrupted = interrupted,
        )
    )

    private fun refusal(body: String) =
        EngineHttpException(EngineFailure.Refused(status = 400, body = body))

    @Test
    fun `every note the engine has never seen crosses exactly once and is stamped`() = runBlocking {
        note(startedAt = 1_000L)
        note(startedAt = 2_000L)
        val backend = FakeVoiceNotes()

        val report = VoiceNotesBackfill.run(context, backend)

        assertEquals(2, report.pushed)
        assertEquals(2, backend.sent.size)
        assertTrue("every row must carry a serverId afterwards", dao().getAll().all { it.serverId != null })
        assertEquals(null, report.stopped)
    }

    @Test
    fun `a second run is a no-op`() = runBlocking {
        note(startedAt = 1_000L)
        val backend = FakeVoiceNotes()
        VoiceNotesBackfill.run(context, backend)
        assertEquals(1, backend.sent.size)

        val second = VoiceNotesBackfill.run(context, backend)

        assertEquals(0, second.pushed)
        assertEquals("nothing may be sent a second time", 1, backend.sent.size)
    }

    @Test
    fun `a note that already has a serverId is counted, never pushed again`() = runBlocking {
        note(startedAt = 1_000L, serverId = "already-there")
        val backend = FakeVoiceNotes()

        val report = VoiceNotesBackfill.run(context, backend)

        assertEquals(0, report.pushed)
        assertEquals(1, report.alreadyPresent)
        assertTrue(backend.sent.isEmpty())
    }

    @Test
    fun `a refused note is skipped with its reason and every other note still crosses`() = runBlocking {
        // Ticket 09's lesson on this aspect. The realistic refusal is the engine's
        // summary-without-a-transcript rule, a shape legacy rows really can be in.
        note(startedAt = 1_000L)
        note(startedAt = 2_000L, transcript = null, summary = "a summary with nothing under it")
        note(startedAt = 3_000L)
        val backend = FakeVoiceNotes()
        backend.refuse = { fields ->
            if (fields.summary != null && fields.transcript == null) {
                refusal("""{"non_field_errors":["A summary needs the transcript it came from."]}""")
            } else {
                null
            }
        }

        val report = VoiceNotesBackfill.run(context, backend)

        assertEquals("the other two crossed", 2, report.pushed)
        assertEquals(1, report.skipped.size)
        assertEquals(
            "the engine's own sentence, unwrapped and not reworded",
            "A summary needs the transcript it came from.",
            report.skipped.single().reason,
        )
        assertEquals("a refusal is never a stop", null, report.stopped)
        assertEquals("and the note stays on the phone", 3, dao().getAll().size)

        val again = VoiceNotesBackfill.run(context, backend)
        assertEquals(0, again.pushed)
        assertEquals("still held back, and still said so", 1, again.unsyncableTotal)
    }

    @Test
    fun `an unreachable engine stops the run and the next run resumes at the same row`() = runBlocking {
        note(startedAt = 1_000L)
        note(startedAt = 2_000L)
        val backend = FakeVoiceNotes()
        backend.refuse = { EngineHttpException(EngineFailure.Unreachable("laptop", "engine unreachable")) }

        val stopped = VoiceNotesBackfill.run(context, backend)
        assertEquals(0, stopped.pushed)
        assertTrue("a stop is said in words", stopped.stopped?.contains("unreachable") == true)

        backend.refuse = { null }
        val retried = VoiceNotesBackfill.run(context, backend)
        assertEquals("both rows are still pending, none was skipped past", 2, retried.pushed)
    }

    @Test
    fun `a live recording is deferred, not skipped, and does not strand the rows after it`() = runBlocking {
        // endedAt null with interrupted false is a recording still running. Pushing it would race
        // VoiceNoteController.syncToBackend into two server rows for one recording; advancing the
        // high-water mark past it would strand it for good the moment it stopped.
        val live = note(startedAt = 1_000L, endedAt = null, interrupted = false, transcript = null)
        note(startedAt = 2_000L)
        val backend = FakeVoiceNotes()

        val first = VoiceNotesBackfill.run(context, backend)
        assertEquals(1, first.deferredStillRecording)
        assertEquals("the row after it still crossed", 1, first.pushed)
        assertNull("the live one was not sent", dao().getById(live)!!.serverId)

        // It stops. The next run picks it up rather than having skipped past it.
        dao().update(dao().getById(live)!!.copy(endedAt = 5_000L, transcript = "at last"))
        val second = VoiceNotesBackfill.run(context, backend)

        assertEquals(1, second.pushed)
        assertNotNull("the deferred row was never stranded", dao().getById(live)!!.serverId)
    }

    // ------------------------------------------------------------------ the audio never crosses

    @Test
    fun `no audio path is ever put on the wire`() = runBlocking {
        val path = "/data/user/0/com.kevin.legion/files/voice/2026-09-07T10-00.m4a"
        note(startedAt = 1_000L, audioPath = path, transcript = "words", title = "Kitchen")
        val backend = FakeVoiceNotes()

        VoiceNotesBackfill.run(context, backend)

        val fields = backend.sent.single()
        assertTrue(
            "the audio path must appear nowhere in what was sent",
            !fields.toString().contains(path) && !fields.toString().contains(".m4a"),
        )
        assertTrue("and the file reference is untouched on the row", dao().getAll().single().audioPath == path)
    }

    @Test
    fun `VoiceNoteFields has no audio field at all, so there is nowhere to put one`() {
        // The structural half of the guarantee, and the one that survives a future edit: the "no
        // audio on the wire" claim rests on the write shape having no such property, not on any
        // caller remembering to leave it out. `$stable` is a Compose-compiler-injected marker
        // present on every class in this module, so it is filtered rather than expected.
        val fields = VoiceNoteFields::class.java.declaredFields
            .map { it.name }
            .filterNot { it.startsWith("$") }
            .toSet()
        assertEquals(
            setOf("startedAtMs", "endedAtMs", "title", "summary", "transcript", "kind", "interrupted"),
            fields,
        )
        assertTrue(
            "no field of VoiceNoteFields may mention audio in any casing",
            fields.none { it.contains("audio", ignoreCase = true) || it.contains("path", ignoreCase = true) },
        )
    }

    @Test
    fun `RemoteVoiceNote carries no audio either, so a round trip cannot introduce one`() {
        val fields = RemoteVoiceNote::class.java.declaredFields
            .map { it.name }
            .filterNot { it.startsWith("$") }
        assertTrue(
            "the shape the engine hands back must have no audio field",
            fields.none { it.contains("audio", ignoreCase = true) },
        )
    }

    // ------------------------------------------------------------------ transport guards

    @Test
    fun `maybeAutoRun does nothing at all on the Supabase transport`() = runBlocking {
        assertEquals(
            Transport.SUPABASE,
            EngineTransport(context).transportFor(EngineBackends.ASPECT_VOICE_NOTES),
        )
        note(startedAt = 1_000L)

        VoiceNotesBackfill.maybeAutoRun(context)

        assertEquals("no cursor may even be created", 0L, VoiceNotesBackfillCursor.lastBackfilledId(context))
        assertNull("and nothing was stamped", dao().getAll().single().serverId)
    }

    @Test
    fun `maybeAutoRun on Django with no engine configured is a silent no-op`() = runBlocking {
        EngineTransport(context).setTransport(EngineBackends.ASPECT_VOICE_NOTES, Transport.DJANGO)
        note(startedAt = 1_000L)

        VoiceNotesBackfill.maybeAutoRun(context)

        assertEquals(0L, VoiceNotesBackfillCursor.lastBackfilledId(context))
        assertNull(dao().getAll().single().serverId)
    }
}
