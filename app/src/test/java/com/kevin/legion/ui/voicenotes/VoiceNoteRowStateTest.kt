package com.kevin.legion.ui.voicenotes

import com.kevin.legion.data.local.VoiceNote
import com.kevin.legion.data.local.VoiceNoteKind
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [voiceNoteRowState]/[voiceNoteRowStateLabel]/[formatMmSs]/[formatVoiceNoteDuration] - the
 * recordings-UI ticket's own instruction: "the list row state mapping (recorded/transcribing/
 * ready/interrupted)... must read differently" gets a pure-logic test, not just a rendered
 * assertion, so the branching itself is pinned independent of Compose.
 */
class VoiceNoteRowStateTest {

    private fun note(
        endedAt: Long? = 2_000,
        transcript: String? = "verbatim",
        summary: String? = "gist",
        interrupted: Boolean = false,
        transcriptionFailureReason: String? = null,
    ) = VoiceNote(
        startedAt = 1_000,
        endedAt = endedAt,
        transcript = transcript,
        summary = summary,
        audioPath = "/tmp/x.m4a",
        kind = VoiceNoteKind.SOLO,
        interrupted = interrupted,
        transcriptionFailureReason = transcriptionFailureReason,
    )

    // -------------------------------------------------------------------- voiceNoteRowState

    @Test
    fun `a note still recording - no endedAt yet - reads as RECORDED`() {
        val n = note(endedAt = null, transcript = null, summary = null)
        assertEquals(VoiceNoteRowState.RECORDED, voiceNoteRowState(n))
    }

    @Test
    fun `a stopped note with no transcript yet and no failure reads as TRANSCRIBING`() {
        val n = note(endedAt = 2_000, transcript = null, summary = null)
        assertEquals(VoiceNoteRowState.TRANSCRIBING, voiceNoteRowState(n))
    }

    @Test
    fun `a note with a transcript and summary reads as READY`() {
        val n = note(endedAt = 2_000, transcript = "verbatim", summary = "gist")
        assertEquals(VoiceNoteRowState.READY, voiceNoteRowState(n))
    }

    @Test
    fun `a stopped note with a stored failure reason and no transcript reads as FAILED, not TRANSCRIBING`() {
        val n = note(endedAt = 2_000, transcript = null, summary = null,
            transcriptionFailureReason = "The upload didn't finish processing: file status check failed (HTTP 404)")
        assertEquals(VoiceNoteRowState.FAILED, voiceNoteRowState(n))
    }

    @Test
    fun `a successful retry clears the failure reason and the row reads READY again, never FAILED`() {
        val n = note(endedAt = 2_000, transcript = "verbatim", summary = "gist",
            transcriptionFailureReason = null)
        assertEquals(VoiceNoteRowState.READY, voiceNoteRowState(n))
    }

    @Test
    fun `interrupted wins over every other signal, even a note that already has a transcript`() {
        val n = note(endedAt = 2_000, transcript = "verbatim", summary = "gist", interrupted = true)
        assertEquals(VoiceNoteRowState.INTERRUPTED, voiceNoteRowState(n))
    }

    @Test
    fun `interrupted wins even before a stop was ever observed`() {
        val n = note(endedAt = null, transcript = null, summary = null, interrupted = true)
        assertEquals(VoiceNoteRowState.INTERRUPTED, voiceNoteRowState(n))
    }

    @Test
    fun `the five states map to five distinct, non-blank words`() {
        val labels = VoiceNoteRowState.entries.map { voiceNoteRowStateLabel(it) }
        assertEquals(5, labels.toSet().size)
        labels.forEach { org.junit.Assert.assertTrue(it.isNotBlank()) }
    }

    // -------------------------------------------------------------------- formatMmSs

    @Test
    fun `formatMmSs floors to whole seconds and pads under a minute`() {
        assertEquals("0:00", formatMmSs(0))
        assertEquals("0:05", formatMmSs(5_000))
        assertEquals("0:59", formatMmSs(59_999))
    }

    @Test
    fun `formatMmSs carries minutes past sixty seconds`() {
        assertEquals("1:00", formatMmSs(60_000))
        assertEquals("2:03", formatMmSs(123_000))
    }

    @Test
    fun `formatMmSs never goes negative on a race between a clock read and a fresh start time`() {
        assertEquals("0:00", formatMmSs(-500))
    }

    // -------------------------------------------------------------------- formatVoiceNoteDuration

    @Test
    fun `an unfinished recording reports in progress, never a bogus duration`() {
        assertEquals("in progress", formatVoiceNoteDuration(startedAt = 1_000, endedAt = null))
    }

    @Test
    fun `a finished recording reports the elapsed span between started and ended`() {
        assertEquals("1:00", formatVoiceNoteDuration(startedAt = 1_000, endedAt = 61_000))
    }
}
