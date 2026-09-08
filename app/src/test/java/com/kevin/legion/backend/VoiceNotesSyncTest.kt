package com.kevin.legion.backend

import com.kevin.legion.backend.engine.EngineBackends
import com.kevin.legion.backend.engine.EngineTransport
import com.kevin.legion.backend.engine.Transport
import com.kevin.legion.data.local.CarDatabase
import com.kevin.legion.data.local.VoiceNote
import com.kevin.legion.data.local.VoiceNoteKind
import com.kevin.legion.data.local.VoiceNoteProvenance
import com.kevin.legion.testutil.RoomTestReset
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.After
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
 * [VoiceNotesSync.pull] - the first server-to-phone path voice notes have ever had.
 * `DjangoVoiceNotesBackend.fetchChangedSince` existed with NO CALLER at all until 2026-09-07.
 *
 * The two properties this file exists to hold, beyond the ordinary merge branches:
 *
 * 1. **A pull never destroys.** The device copy is primary (Kevin, 2026-09-04) and there is no
 *    local `updatedAt` column to arbitrate with, so a matched row is filled where it is null and
 *    left alone where it is not - see [VoiceNotesSync.mergeInto]'s branch 4 for the cost that buys.
 * 2. **A tombstone runs ADR 0041's whole cascade**, audio file included. A row deleted server-side
 *    must not leave a `.m4a` behind claiming to be evidence for a transcript that is gone.
 */
@RunWith(RobolectricTestRunner::class)
class VoiceNotesSyncTest {
    private val context = RuntimeEnvironment.getApplication()

    private class FakeVoiceNotesPull(var rows: List<RemoteVoiceNote> = emptyList()) : VoiceNotesIncrementalPull {
        var lastSinceMs: Long? = null
        var failure: Throwable? = null

        override suspend fun fetchChangedSince(sinceMs: Long): Result<List<RemoteVoiceNote>> {
            lastSinceMs = sinceMs
            failure?.let { return Result.failure(it) }
            return Result.success(rows.filter { it.updatedAtMs >= sinceMs })
        }
    }

    @Before
    fun clearState() {
        RoomTestReset.resetCarDatabaseSingleton()
        VoiceNotesSync.setLastAutoPullAtForTest(0L)
    }

    @After
    fun drain() {
        RoomTestReset.drainArchDiskIoPool()
        File(context.cacheDir, "voicenotes").deleteRecursively()
    }

    private fun dao() = CarDatabase.getDatabase(context).voiceNoteDao()

    private fun remote(
        serverId: String,
        at: Long,
        title: String? = "Standup",
        summary: String? = "A summary.",
        transcript: String? = "A verbatim transcript.",
        deleted: Boolean = false,
    ) = RemoteVoiceNote(
        serverId = serverId,
        startedAtMs = 1_000L,
        endedAtMs = 2_000L,
        title = title,
        summary = summary,
        transcript = transcript,
        kind = VoiceNoteKind.SOLO,
        provenance = VoiceNoteProvenance.LLM_DERIVED,
        interrupted = false,
        updatedAtMs = at,
        deleted = deleted,
    )

    private suspend fun localNote(
        serverId: String?,
        title: String? = null,
        summary: String? = null,
        transcript: String? = null,
        audioPath: String? = null,
    ): Long = dao().insert(
        VoiceNote(
            serverId = serverId,
            startedAt = 1_000L,
            endedAt = 2_000L,
            title = title,
            summary = summary,
            transcript = transcript,
            audioPath = audioPath,
            kind = VoiceNoteKind.SOLO,
        ),
    )

    @Test
    fun `a recording that exists only server-side arrives on the phone, with no audio`() = runBlocking {
        val backend = FakeVoiceNotesPull(listOf(remote("srv-1", at = 5_000L)))

        val report = VoiceNotesSync.pull(context, backend)

        assertEquals(1, report.inserted)
        val stored = dao().getAll().single()
        assertEquals("srv-1", stored.serverId)
        assertEquals("Standup", stored.title)
        assertEquals("A verbatim transcript.", stored.transcript)
        assertNull("a pull can carry text onto a phone and never a recording off one", stored.audioPath)
    }

    @Test
    fun `a server row with no transcript is skipped rather than inserted as an empty claim`() = runBlocking {
        // VoiceNote's own contract: audioPath is never null while transcript is null, because the
        // audio is the only evidence there is. This phone holds neither, so the row would be a
        // recording it claims to have and has no trace of.
        val backend = FakeVoiceNotesPull(listOf(remote("srv-1", at = 5_000L, transcript = null, summary = null)))

        val report = VoiceNotesSync.pull(context, backend)

        assertEquals(1, report.skippedNoTranscript)
        assertEquals(0, report.inserted)
        assertTrue(dao().getAll().isEmpty())
    }

    @Test
    fun `a server tombstone deletes the row and its audio file together`() = runBlocking {
        val audio = File(context.cacheDir, "voicenotes/note.m4a").apply {
            parentFile?.mkdirs()
            writeBytes(byteArrayOf(1, 2, 3, 4))
        }
        localNote(serverId = "srv-1", title = "Standup", transcript = "text", audioPath = audio.absolutePath)
        val backend = FakeVoiceNotesPull(listOf(remote("srv-1", at = 9_000L, deleted = true)))

        val report = VoiceNotesSync.pull(context, backend)

        assertEquals(1, report.tombstoned)
        assertTrue("the row goes", dao().getAll().isEmpty())
        assertFalse("and ADR 0041's cascade takes the audio with it", audio.exists())
    }

    @Test
    fun `a server tombstone for a recording this phone never had is skipped`() = runBlocking {
        val backend = FakeVoiceNotesPull(listOf(remote("srv-ghost", at = 9_000L, deleted = true)))

        val report = VoiceNotesSync.pull(context, backend)

        assertEquals(1, report.skippedTombstoneNoLocalMatch)
        assertTrue(dao().getAll().isEmpty())
    }

    @Test
    fun `a pull fills a null local field from the server`() = runBlocking {
        val id = localNote(serverId = "srv-1", title = "Standup", transcript = "text", summary = null)
        val backend = FakeVoiceNotesPull(listOf(remote("srv-1", at = 9_000L, summary = "The summary.")))

        val report = VoiceNotesSync.pull(context, backend)

        assertEquals(1, report.updated)
        assertEquals("The summary.", dao().getById(id)!!.summary)
    }

    @Test
    fun `a pull never replaces a value this phone already holds`() = runBlocking {
        // The load-bearing half of "the device copy is primary": with no local clock, filling nulls
        // can only ever add information, and can never revert a rename made here or a transcript
        // whose push failed.
        val local = File(context.cacheDir, "voicenotes/kept.m4a").apply {
            parentFile?.mkdirs()
            writeBytes(byteArrayOf(9))
        }
        val id = localNote(
            serverId = "srv-1",
            title = "Kitchen notes",
            transcript = "the transcript this phone made",
            audioPath = local.absolutePath,
        )
        val backend = FakeVoiceNotesPull(
            listOf(remote("srv-1", at = 9_000L, title = "Standup", transcript = "a stale server transcript")),
        )

        val report = VoiceNotesSync.pull(context, backend)

        val row = dao().getById(id)!!
        assertEquals("a local rename survives a pull", "Kitchen notes", row.title)
        assertEquals("and so does a local transcript", "the transcript this phone made", row.transcript)
        // Both halves in one row, deliberately: the fixture leaves `summary` null, so the same
        // merge that refused to touch the two filled columns DID fill the empty one. That is the
        // whole rule - add, never replace - and a test that only showed the refusal would not
        // distinguish it from a merge that does nothing at all.
        assertEquals("a null local field is still filled from the server", "A summary.", row.summary)
        assertEquals(1, report.updated)
        assertTrue("and the audio is untouched", local.exists())
    }

    @Test
    fun `a local recording the server has never seen is left completely alone`() = runBlocking {
        val id = localNote(serverId = null, title = "Not pushed yet", transcript = "text")
        val backend = FakeVoiceNotesPull(listOf(remote("srv-1", at = 5_000L)))

        VoiceNotesSync.pull(context, backend)

        assertEquals("Not pushed yet", dao().getById(id)!!.title)
        assertEquals("and the server row still arrived alongside it", 2, dao().getAll().size)
    }

    @Test
    fun `a first pull asks for everything, never for nothing`() = runBlocking {
        val backend = FakeVoiceNotesPull()
        VoiceNotesSync.pull(context, backend)
        assertEquals(0L, backend.lastSinceMs)
    }

    @Test
    fun `a failed fetch throws and leaves the watermark untouched`() = runBlocking {
        val backend = FakeVoiceNotesPull(listOf(remote("srv-1", at = 4_000L)))
        VoiceNotesSync.pull(context, backend)
        assertEquals(4_000L, VoiceNotesPullCursor.lastPulledAtMs(context))

        backend.failure = VoiceNotesBackendException("engine unreachable")
        val thrown = runCatching { VoiceNotesSync.pull(context, backend) }.exceptionOrNull()

        assertTrue(thrown is VoiceNotesBackendException)
        assertEquals(4_000L, VoiceNotesPullCursor.lastPulledAtMs(context))
    }

    @Test
    fun `maybeAutoPull does nothing at all on the Supabase transport`() = runBlocking {
        assertEquals(
            Transport.SUPABASE,
            EngineTransport(context).transportFor(EngineBackends.ASPECT_VOICE_NOTES),
        )
        val id = localNote(serverId = "srv-1", title = "Standup", transcript = "text")

        VoiceNotesSync.maybeAutoPull(context)

        assertEquals("no watermark may even be created", 0L, VoiceNotesPullCursor.lastPulledAtMs(context))
        assertEquals("Standup", dao().getById(id)!!.title)
    }

    @Test
    fun `maybeAutoPull on Django with no engine configured is a silent no-op`() = runBlocking {
        EngineTransport(context).setTransport(EngineBackends.ASPECT_VOICE_NOTES, Transport.DJANGO)

        VoiceNotesSync.maybeAutoPull(context)

        assertEquals(0L, VoiceNotesPullCursor.lastPulledAtMs(context))
        assertTrue(dao().getAll().isEmpty())
    }
}
