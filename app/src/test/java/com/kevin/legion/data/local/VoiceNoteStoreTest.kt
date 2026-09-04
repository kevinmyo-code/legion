package com.kevin.legion.data.local

import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * [VoiceNoteStore.delete] - the ADR 0041 delete cascade ("audio, transcript and summary are
 * retained or destroyed together"). A summary outliving its transcript is the specific failure
 * this ticket exists to prevent, per ticket 02's own verification list, so it gets this test
 * rather than a comment.
 *
 * Plain JVM test, no Robolectric needed - [VoiceNoteStore] touches only [VoiceNoteDao] (faked
 * below with an in-memory map, no Room involved) and [java.io.File] (a real temp file, since Room
 * itself and Android's real filesystem behave identically here and a fake `File` would only be
 * testing the fake).
 */
class VoiceNoteStoreTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    /** The narrowest possible fake: an in-memory map behind [VoiceNoteDao]'s real interface,
     * so this test exercises [VoiceNoteStore] against the same contract Room generates against,
     * never a hand-rolled stand-in shape that could drift from it. */
    private class FakeVoiceNoteDao : VoiceNoteDao {
        private val rows = mutableMapOf<Long, VoiceNote>()
        private var nextId = 1L

        override suspend fun insert(note: VoiceNote): Long {
            val id = nextId++
            rows[id] = note.copy(id = id)
            return id
        }

        override suspend fun update(note: VoiceNote) {
            rows[note.id] = note
        }

        override suspend fun getById(id: Long): VoiceNote? = rows[id]

        override suspend fun getAll(): List<VoiceNote> = rows.values.sortedByDescending { it.startedAt }

        override suspend fun getInRange(startInclusive: Long, endExclusive: Long): List<VoiceNote> =
            rows.values.filter { it.startedAt >= startInclusive && it.startedAt < endExclusive }
                .sortedBy { it.startedAt }

        override suspend fun getUnended(): List<VoiceNote> = rows.values.filter { it.endedAt == null }

        override suspend fun getStalledTranscriptions(): List<VoiceNote> =
            rows.values.filter { it.transcriptionAttemptStartedAt != null }

        override suspend fun markTranscriptionAttemptStarted(id: Long, startedAt: Long) {
            rows[id]?.let { rows[id] = it.copy(transcriptionAttemptStartedAt = startedAt, transcriptionFailureReason = null) }
        }

        override suspend fun markTranscriptionFailed(id: Long, reason: String) {
            rows[id]?.let { rows[id] = it.copy(transcriptionFailureReason = reason, transcriptionAttemptStartedAt = null) }
        }

        override suspend fun applyTranscriptionSuccess(id: Long, title: String, summary: String, transcript: String) {
            rows[id]?.let {
                rows[id] = it.copy(
                    title = it.title ?: title,
                    summary = summary,
                    transcript = transcript,
                    transcriptionFailureReason = null,
                    transcriptionAttemptStartedAt = null,
                )
            }
        }

        override suspend fun getAllAudioPaths(): List<String> = rows.values.mapNotNull { it.audioPath }

        override suspend fun deleteById(id: Long) {
            rows.remove(id)
        }
    }

    @Test
    fun `deleting a note removes the row and its audio file`() = runTest {
        val dao = FakeVoiceNoteDao()
        val audioFile = tempFolder.newFile("note.m4a").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val id = dao.insert(
            VoiceNote(
                startedAt = 1_000,
                endedAt = 2_000,
                title = "Standup",
                summary = "Discussed the release date.",
                transcript = "Full verbatim text.",
                audioPath = audioFile.absolutePath,
                kind = VoiceNoteKind.MEETING,
            )
        )
        val store = VoiceNoteStore(dao)

        val deleted = store.delete(id)

        assertTrue("delete() must report success for a real row", deleted)
        assertNull("the row - and with it the transcript and summary, since neither is a " +
            "separate row - must be gone", dao.getById(id))
        assertFalse("the audio file must be gone; a summary/transcript surviving without its " +
            "audio anchor is exactly the ADR 0041 failure this test exists to catch", audioFile.exists())
    }

    @Test
    fun `deleting a note with no audio path yet still removes the row`() = runTest {
        // The shape of a note deleted the instant after VoiceNoteRecorder.start() inserted its
        // row but before any audio existed to point at - audioPath is always non-null in that
        // window in practice (ticket 01 writes it at insert time), but a store-level delete must
        // not assume a non-null audioPath either way.
        val dao = FakeVoiceNoteDao()
        val id = dao.insert(
            VoiceNote(startedAt = 1_000, kind = VoiceNoteKind.SOLO, audioPath = null),
        )
        val store = VoiceNoteStore(dao)

        assertTrue(store.delete(id))
        assertNull(dao.getById(id))
    }

    @Test
    fun `deleting a note that does not exist reports false, never a delete that did not happen`() = runTest {
        val store = VoiceNoteStore(FakeVoiceNoteDao())

        assertFalse(store.delete(id = 999))
    }

    /** Delegates every call to a real [FakeVoiceNoteDao] except [deleteById], which records
     * whether the audio file still existed AT THE MOMENT it fired - the one observation that
     * distinguishes "row deleted first" from "file deleted first" without depending on
     * [VoiceNoteDao.deleteById] actually throwing (this fake dao is hand-rolled, not a real Room
     * DAO, so a thrown exception here would only prove the fake can throw, not that Room's
     * generated `DELETE` can - see [VoiceNoteStore.delete]'s own doc comment for why the order,
     * not a thrown exception, is the thing worth pinning). A test that passed under EITHER
     * ordering would be worthless; this one only passes if the row-then-file order in the
     * production code is preserved. */
    private class OrderRecordingDao(
        private val delegate: FakeVoiceNoteDao,
        private val fileAtDeleteTime: () -> Boolean,
    ) : VoiceNoteDao by delegate {
        var fileExistedWhenRowWasDeleted: Boolean? = null

        override suspend fun deleteById(id: Long) {
            fileExistedWhenRowWasDeleted = fileAtDeleteTime()
            delegate.deleteById(id)
        }
    }

    @Test
    fun `the row is deleted before the file, not the other way around`() = runTest {
        // Pins the ORDER directly, per this ticket's own instruction: a test that passed under
        // both orderings (e.g. just asserting both are eventually gone, which the existing
        // "removes the row and its audio file" test above already does) would not catch a
        // regression back to the file-first order this method's own doc comment says was an
        // earlier, worse revision.
        val audioFile = tempFolder.newFile("note.m4a").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val inner = FakeVoiceNoteDao()
        val id = inner.insert(
            VoiceNote(startedAt = 1_000, kind = VoiceNoteKind.SOLO, audioPath = audioFile.absolutePath),
        )
        val dao = OrderRecordingDao(inner) { audioFile.exists() }
        val store = VoiceNoteStore(dao)

        val deleted = store.delete(id)

        assertTrue(deleted)
        assertTrue("the file must still exist at the moment the row is deleted - the row is " +
            "deleted FIRST, per VoiceNoteStore.delete's own doc comment", dao.fileExistedWhenRowWasDeleted == true)
        assertNull("the row must be gone once delete() returns", dao.getById(id))
        assertFalse("the file must be gone once delete() returns", audioFile.exists())
    }

    @Test
    fun `a dao failure while deleting the row leaves the audio file untouched`() = runTest {
        // The failure-shape half of the same ordering: if deleteById throws before the file step
        // ever runs, the file must survive. This fake dao is hand-rolled (not a real Room DAO), so
        // this genuinely exercises "deleteById threw" rather than merely asserting it could -
        // flagged here rather than silently treated as instrumented-test coverage.
        val audioFile = tempFolder.newFile("note.m4a").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val inner = FakeVoiceNoteDao()
        val id = inner.insert(
            VoiceNote(startedAt = 1_000, kind = VoiceNoteKind.SOLO, audioPath = audioFile.absolutePath),
        )
        val throwingDao = object : VoiceNoteDao by inner {
            override suspend fun deleteById(id: Long) {
                throw RuntimeException("simulated dao failure")
            }
        }
        val store = VoiceNoteStore(throwingDao)

        try {
            store.delete(id)
            org.junit.Assert.fail("delete() should propagate the dao failure, not swallow it")
        } catch (e: RuntimeException) {
            assertEquals("simulated dao failure", e.message)
        }

        assertTrue("row-first ordering means a dao failure must leave the file untouched - " +
            "nothing has been destroyed yet", audioFile.exists())
    }

    @Test
    fun `deleting a note whose audio file is already gone still removes the row`() = runTest {
        // The file may have been cleaned up by VoiceNoteRecorder's own orphan sweep, or simply
        // deleted out from under the app - delete() must not treat a missing file as a failure.
        val dao = FakeVoiceNoteDao()
        val missingPath = File(tempFolder.root, "already-gone.m4a").absolutePath
        val id = dao.insert(
            VoiceNote(startedAt = 1_000, kind = VoiceNoteKind.SOLO, audioPath = missingPath),
        )
        val store = VoiceNoteStore(dao)

        assertTrue(store.delete(id))
        assertNull(dao.getById(id))
    }
}
