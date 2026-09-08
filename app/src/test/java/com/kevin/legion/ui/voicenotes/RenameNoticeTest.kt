package com.kevin.legion.ui.voicenotes

import com.kevin.legion.voice.VoiceNoteController
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [renameNotice] - what the recordings screen actually says after a rename.
 *
 * **This exists because the screen used to say nothing at all.**
 * `VoiceNoteController.rename` returned a bare `Boolean` that was `true` even when the push failed,
 * and `VoiceNoteDetailScreen` discarded it, so a rename the engine refused closed the dialog with
 * no word of it (found on the A25, 2026-09-07). A pure function is what makes the WORDING testable
 * without a Compose harness - see [renameNotice]'s own doc comment.
 *
 * Plain JUnit, no Robolectric: nothing here touches a [android.content.Context].
 */
class RenameNoticeTest {

    @Test
    fun `a plain success says nothing`() {
        assertNull(
            "silence is the strong state - a working rename needs no line on screen",
            renameNotice(VoiceNoteController.RenameResult.Renamed),
        )
    }

    @Test
    fun `a missing row is reported, never as a rename that happened`() {
        val notice = renameNotice(VoiceNoteController.RenameResult.NotFound)
        assertEquals("That recording is no longer here, so nothing was renamed.", notice)
    }

    @Test
    fun `the engine's own refusal reaches the screen unreworded`() {
        // A rule the server holds and the phone does not can only be explained by the server -
        // the same reason PlaceController.engineRefusal relays rather than paraphrases.
        val sentence = "A recording's title can be at most 200 characters. Nothing was changed."
        assertEquals(
            sentence,
            renameNotice(VoiceNoteController.RenameResult.Refused(sentence)),
        )
    }

    @Test
    fun `an unreachable engine's divergence reaches the screen too`() {
        val sentence = "Renamed on this phone, but the server didn't get it. (unreachable)"
        assertEquals(
            sentence,
            renameNotice(VoiceNoteController.RenameResult.SavedOnThisPhoneOnly(sentence)),
        )
    }
}
