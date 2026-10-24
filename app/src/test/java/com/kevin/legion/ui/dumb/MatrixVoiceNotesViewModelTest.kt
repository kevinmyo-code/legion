package com.kevin.legion.ui.dumb

import com.kevin.legion.data.local.VoiceNote
import com.kevin.legion.data.local.VoiceNoteKind
import com.kevin.legion.voice.VoiceNoteRecordingState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Matrix voice notes states over a fake recorder source. */
class MatrixVoiceNotesViewModelTest {
    private class Source(
        var notes: List<VoiceNote> = emptyList(),
        var readThrows: Boolean = false,
        var startRefusal: String? = null,
        var renameSaid: String? = null,
        var deleteSaid: String? = null,
        var retrySaid: String? = null,
    ) : MatrixVoiceNotesSource {
        override val recording = MutableStateFlow<VoiceNoteRecordingState>(VoiceNoteRecordingState.Idle)
        val calls = mutableListOf<String>()

        override suspend fun list(): List<VoiceNote> {
            if (readThrows) error("Room is closed")
            return notes
        }

        override suspend fun start(): String? {
            calls += "start"
            if (startRefusal == null) recording.value = VoiceNoteRecordingState.Recording(1, 1_000L)
            return startRefusal
        }

        override suspend fun stop() {
            calls += "stop"
            recording.value = VoiceNoteRecordingState.Idle
        }

        override suspend fun rename(id: Long, title: String): String? = renameSaid.also { calls += "rename:$id:$title" }

        override suspend fun delete(id: Long): String? = deleteSaid.also { calls += "delete:$id" }

        override suspend fun retry(id: Long): String? = retrySaid.also { calls += "retry:$id" }
    }

    private fun model(source: Source) =
        MatrixVoiceNotesViewModel(source, CoroutineScope(Dispatchers.Unconfined), Dispatchers.Unconfined)

    private fun note(
        id: Long = 1,
        title: String? = "Standup",
        summary: String? = null,
        transcript: String? = null,
        failure: String? = null,
        interrupted: Boolean = false,
    ) = VoiceNote(
        id = id, startedAt = 1_000_000L, endedAt = 1_042_000L, title = title, summary = summary,
        transcript = transcript, audioPath = "/a.m4a", kind = VoiceNoteKind.SOLO,
        interrupted = interrupted, transcriptionFailureReason = failure,
    )

    @Test
    fun `loading, empty and unreadable are three different sentences`() {
        val vm = model(Source())
        assertEquals(MatrixVoiceNotesText.LOADING, MatrixVoiceNotesText.listNote(vm.state.value))
        vm.refresh()
        assertEquals(MatrixVoiceNotesText.EMPTY, MatrixVoiceNotesText.listNote(vm.state.value))
        val broken = model(Source(readThrows = true))
        broken.refresh()
        assertEquals(MatrixVoiceNotesText.UNREADABLE, MatrixVoiceNotesText.listNote(broken.state.value))
    }

    @Test
    fun `the recording state flows into the screen state and labels the action`() {
        val source = Source()
        val vm = model(source)
        assertEquals("Record", MatrixVoiceNotesText.recordActionLabel(vm.state.value.recording))
        vm.record()
        assertTrue(vm.state.value.recording is VoiceNoteRecordingState.Recording)
        assertEquals("Stop", MatrixVoiceNotesText.recordActionLabel(vm.state.value.recording))
        assertEquals("Recording - 0:05", MatrixVoiceNotesText.recordingLine(vm.state.value.recording, 6_000L))
        vm.stop()
        assertEquals("Not recording", MatrixVoiceNotesText.recordingLine(vm.state.value.recording, 6_000L))
        assertEquals(MatrixVoiceNotesText.STOPPED, vm.state.value.notice)
    }

    @Test
    fun `a refused start shows the controller's own sentence and no recording`() {
        val vm = model(Source(startRefusal = "The microphone is busy."))
        vm.record()
        assertEquals("The microphone is busy.", vm.state.value.startRefusal)
        assertTrue(vm.state.value.recording is VoiceNoteRecordingState.Idle)
    }

    @Test
    fun `the stopped notice never says the note is ready`() {
        assertFalse(MatrixVoiceNotesText.STOPPED.contains("is ready"))
        assertTrue(MatrixVoiceNotesText.STOPPED.contains("being transcribed"))
    }

    @Test
    fun `rows say the state word, label the summary as AI and say failure and interruption in words`() {
        val ready = note(summary = "Talked about the launch.", transcript = "full text")
        val detail = MatrixVoiceNotesText.rowDetail(ready)
        assertTrue(detail.contains("Ready"))
        assertTrue(detail.contains("AI-generated summary: Talked about the launch."))
        val failed = note(failure = "No network.")
        assertTrue(MatrixVoiceNotesText.rowDetail(failed).contains("No network."))
        assertTrue(MatrixVoiceNotesText.rowDetail(failed).contains("Transcription failed"))
        val cut = note(interrupted = true)
        assertTrue(MatrixVoiceNotesText.rowDetail(cut).contains("Interrupted - this recording may be incomplete"))
        assertEquals(MatrixTone.UNVERIFIED, MatrixVoiceNotesText.rowTone(cut))
        assertTrue(MatrixVoiceNotesText.rowDetail(note()).contains("Not transcribed yet"))
    }

    @Test
    fun `a long summary is cut on the list but whole in the detail`() {
        val long = "word ".repeat(60).trim()
        val ready = note(summary = long, transcript = "t")
        assertTrue(MatrixVoiceNotesText.rowDetail(ready).endsWith("..."))
        val body = MatrixVoiceNoteDetailText.bodyLines(ready).map { it.text }
        assertTrue(long in body)
        assertTrue(MatrixVoiceNoteDetailText.SUMMARY_LABEL in body)
        assertTrue(MatrixVoiceNoteDetailText.TRANSCRIPT_LABEL in body)
    }

    @Test
    fun `retry shows only after a failed transcription`() {
        assertTrue(MatrixVoiceNoteDetailText.canRetry(note(failure = "x")))
        assertFalse(MatrixVoiceNoteDetailText.canRetry(note()))
        assertFalse(MatrixVoiceNoteDetailText.canRetry(note(failure = "x", transcript = "done")))
    }

    @Test
    fun `rename says the controller's sentence when there is one and re-reads`() {
        val source = Source(notes = listOf(note()), renameSaid = "Renamed here, not on the server.")
        val vm = model(source)
        vm.refresh()
        vm.open(1)
        vm.startRename()
        assertEquals("Standup", vm.state.value.renameDraft)
        vm.setRenameDraft("Weekly")
        vm.saveRename()
        assertEquals("Renamed here, not on the server.", vm.state.value.notice)
        assertNull(vm.state.value.renameDraft)
        assertTrue("rename:1:Weekly" in source.calls)
    }

    @Test
    fun `delete asks first, a failure is said and the note stays, success closes it`() {
        val source = Source(notes = listOf(note()), deleteSaid = "Couldn't delete that recording from the server.")
        val vm = model(source)
        vm.refresh()
        vm.open(1)
        vm.askDelete()
        assertTrue(source.calls.none { it.startsWith("delete") })
        vm.confirmDelete()
        assertEquals("Couldn't delete that recording from the server.", vm.state.value.notice)
        assertEquals(1L, vm.state.value.selectedId)
        source.deleteSaid = null
        vm.askDelete()
        vm.confirmDelete()
        assertNull(vm.state.value.selectedId)
        assertEquals(MatrixVoiceNotesText.DELETED, vm.state.value.notice)
    }

    @Test
    fun `retry says it started, or why it could not`() {
        val source = Source(notes = listOf(note(failure = "x")))
        val vm = model(source)
        vm.refresh()
        vm.open(1)
        vm.retry()
        assertEquals(MatrixVoiceNotesText.RETRYING, vm.state.value.notice)
        source.retrySaid = "No audio is left for that recording, so it can't be retried."
        vm.retry()
        assertTrue(vm.state.value.notice!!.startsWith("No audio"))
    }
}
