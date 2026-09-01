package com.kevin.legion.ui.voicenotes

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
// assertExists/assertDoesNotExist are MEMBER functions on SemanticsNodeInteraction, not top-level
// declarations - importing them by name does not resolve and fails the whole test source set's
// compilation, not just this file. Called as `onNodeWithText(...).assertExists()` below.
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.kevin.legion.data.local.VoiceNote
import com.kevin.legion.data.local.VoiceNoteKind
import com.kevin.legion.ui.theme.LegionTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

/**
 * Ticket 04's own load-bearing verification: "the derived-summary wording is present on both the
 * list row and the detail" and "an interrupted note renders as interrupted" - both a list-row and
 * a detail requirement, so both [VoiceNoteRow] and [VoiceNoteDetail] are pinned here directly
 * rather than through the full [VoiceNotesScreen] (which also stands up a [android.media.MediaPlayer]
 * and Room reads that would make this a much heavier test for the same two assertions).
 *
 * Same runner/graphics-mode/rule shape as [com.kevin.legion.ui.common.HelpRowTest] - the only
 * `createAndroidComposeRule` precedent in this repo.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class VoiceNotesRowsTest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    private fun note(
        title: String? = "Kickoff",
        summary: String? = "We decided to ship Tuesday.",
        transcript: String? = "Full verbatim text.",
        interrupted: Boolean = false,
    ) = VoiceNote(
        id = 1L,
        startedAt = 1_700_000_000_000L,
        endedAt = 1_700_000_060_000L,
        title = title,
        summary = summary,
        transcript = transcript,
        audioPath = "/tmp/note.m4a",
        kind = VoiceNoteKind.MEETING,
        interrupted = interrupted,
    )

    // -------------------------------------------------------------------- list row

    @Test
    fun `the list row states the summary is AI-generated, in words`() {
        composeTestRule.setContent {
            LegionTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    VoiceNoteRow(note = note(), onClick = {})
                }
            }
        }
        composeTestRule.onNodeWithText("AI-generated summary: We decided to ship Tuesday.", substring = true)
            .assertExists()
    }

    @Test
    fun `an interrupted recording says so on the list row itself, not only the detail`() {
        composeTestRule.setContent {
            LegionTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    VoiceNoteRow(note = note(interrupted = true), onClick = {})
                }
            }
        }
        composeTestRule.onNodeWithText("Interrupted - this recording may be incomplete", substring = true)
            .assertExists()
    }

    @Test
    fun `a non-interrupted row never renders the interrupted line`() {
        composeTestRule.setContent {
            LegionTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    VoiceNoteRow(note = note(interrupted = false), onClick = {})
                }
            }
        }
        composeTestRule.onNodeWithText("Interrupted", substring = true).assertDoesNotExist()
    }

    @Test
    fun `a note with no summary yet says so, never a blank line pretending nothing is missing`() {
        composeTestRule.setContent {
            LegionTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    VoiceNoteRow(note = note(summary = null), onClick = {})
                }
            }
        }
        composeTestRule.onNodeWithText("Not transcribed yet", substring = true).assertExists()
    }

    // -------------------------------------------------------------------- detail

    @Test
    fun `the detail states the summary is AI-generated and not a verbatim account`() {
        composeTestRule.setContent {
            LegionTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    VoiceNoteDetail(note = note(), playing = false, onTogglePlayback = {})
                }
            }
        }
        composeTestRule.onNodeWithText("AI-generated summary - not a verbatim account:", substring = true)
            .assertExists()
        composeTestRule.onNodeWithText("We decided to ship Tuesday.", substring = true).assertExists()
    }

    @Test
    fun `the detail states an interrupted recording may be incomplete`() {
        composeTestRule.setContent {
            LegionTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    VoiceNoteDetail(note = note(interrupted = true), playing = false, onTogglePlayback = {})
                }
            }
        }
        composeTestRule.onNodeWithText("interrupted before it finished", substring = true).assertExists()
    }
}
