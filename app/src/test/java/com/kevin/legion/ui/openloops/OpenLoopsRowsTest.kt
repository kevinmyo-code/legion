package com.kevin.legion.ui.openloops

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.kevin.legion.openloops.FakeOpenLoopsBackend
import com.kevin.legion.openloops.FakeOpenLoopsBackend.Companion.DENTIST
import com.kevin.legion.openloops.FakeOpenLoopsBackend.Companion.REGISTRATION
import com.kevin.legion.openloops.OpenLoopsController
import com.kevin.legion.service.OpenLoopsToolbox
import com.kevin.legion.spokenbrief.SpokenBriefs
import com.kevin.legion.ui.theme.soft.SoftTheme
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * ADR 0035 for ADR 0085: the hands path. The rows list each pending proposal with Track and
 * Dismiss, and each tap reaches the same controller the voice answers use; the switch is the one
 * "stop proposing tasks" flips. Made-up tasks.
 */
@RunWith(RobolectricTestRunner::class)
class OpenLoopsRowsTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val engine = FakeOpenLoopsBackend()

    private fun controller() = OpenLoopsController(backend = { engine })

    @After
    fun tearDown() {
        OpenLoopsToolbox.controllerFactory = OpenLoopsController::forContext
        OpenLoopsController.Hint.pending = null
    }

    @Test
    fun `each proposal has Track and Dismiss, and a tap reaches the engine through the controller`() {
        engine.proposals += listOf(DENTIST, REGISTRATION)
        compose.setContent { SoftTheme { OpenLoopsRows(controllerFor = { controller() }) } }
        compose.waitForIdle()
        compose.onNodeWithText("Call the dentist, no date").assertIsDisplayed()
        compose.onNodeWithText("Renew the registration by November 20 (Fri 20 Nov)").assertIsDisplayed()
        assertEquals(2, compose.onAllNodesWithText("Track").fetchSemanticsNodes().size)
        compose.onAllNodesWithText("Track")[0].performClick()
        compose.waitForIdle()
        assertEquals(listOf("p1" to null), engine.tracked)
        compose.onAllNodesWithText("Dismiss")[0].performClick()
        compose.waitForIdle()
        assertEquals(listOf("p2"), engine.dismissed)
        compose.onNodeWithText("No proposed to-dos.").assertIsDisplayed()
    }

    @Test
    fun `the stop phrase flips the same engine switch the row shows`() = runBlocking {
        OpenLoopsToolbox.controllerFactory = { OpenLoopsController(backend = { engine }) }
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        val stopped = SpokenBriefs.stop(context, "proposing tasks")
        assertTrue(stopped.ok)
        assertFalse(engine.proposing)
        compose.setContent { SoftTheme { OpenLoopsRows(controllerFor = { controller() }) } }
        compose.waitForIdle()
        compose.onNodeWithText("Propose to-dos").assertIsDisplayed()
        assertTrue(compose.onAllNodesWithText("Track").fetchSemanticsNodes().isEmpty())
    }
}
