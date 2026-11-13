package com.kevin.legion.ui.grades

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import com.kevin.legion.grades.FakeGradesBackend.Companion.assignment
import com.kevin.legion.grades.FakeGradesBackend.Companion.course
import com.kevin.legion.ui.theme.soft.SoftTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** The sentences the Grades screen must render: hidden total, error with and without old data. */
@RunWith(RobolectricTestRunner::class)
class GradesContentTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private fun show(state: GradesUiState) = compose.setContent {
        SoftTheme { GradesContent(state, GradesCallbacks()) }
    }

    @Test
    fun `a hidden total reads Canvas shows no total`() {
        show(GradesUiState(loading = false, courses = listOf(course(3, "History", score = null, grade = null))))
        compose.onNodeWithText("History").assertIsDisplayed()
        compose.onNodeWithTag("grades-no-total", useUnmergedTree = true).assertExists()
        compose.onNodeWithText("Canvas shows no total").assertIsDisplayed()
    }

    @Test
    fun `an error with no data shows the sentence and no empty list`() {
        show(
            GradesUiState(
                loading = false,
                error = "Grades did not load: Could not reach the engine. No grades have been read yet.",
            ),
        )
        compose.onNodeWithTag("grades-error").assertIsDisplayed()
        compose.onNodeWithText("The engine has no Canvas courses on file for you.").assertDoesNotExist()
    }

    @Test
    fun `an error after a good read still shows the old courses and when they were read`() {
        show(
            GradesUiState(
                loading = false,
                courses = listOf(course(1, "Physics")),
                lastReadAt = "2026-11-13 12:00",
                error = "Grades did not load: Could not reach the engine. " +
                    "Showing what was last read, at 2026-11-13 12:00.",
            ),
        )
        compose.onNodeWithTag("grades-error").assertIsDisplayed()
        compose.onNodeWithText("Physics").assertIsDisplayed()
        compose.onNodeWithTag("grades-read-at").assertIsDisplayed()
    }

    @Test
    fun `a course shows its assignments in words and the engine's need sentence`() {
        show(
            GradesUiState(
                loading = false,
                courses = listOf(
                    course(1, "Physics", assignments = listOf(assignment(9, "Lab 1", excused = true))),
                ),
                selectedCourseId = 1,
                need = NeedResult.Answer("It is already guaranteed.", false),
            ),
        )
        // Below the form in a lazy list, so scroll to it before asserting it is on screen.
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("excused"))
        compose.onNodeWithText("excused").assertIsDisplayed()
        compose.onNodeWithTag("grades-need-answer").assertIsDisplayed()
        compose.onNodeWithText("It is already guaranteed.").assertIsDisplayed()
    }
}

