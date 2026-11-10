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
import com.kevin.legion.memory.MemoryOutcome
import com.kevin.legion.openloops.Listing
import com.kevin.legion.openloops.OpenLoopsBackend
import com.kevin.legion.openloops.OpenLoopsBrief
import com.kevin.legion.openloops.OpenLoopsController
import com.kevin.legion.openloops.Tracked
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
import java.time.LocalDate

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

    /** The engine from before open loops, as DjangoOpenLoopsBackend maps its 404 page. */
    private object OldEngine : OpenLoopsBackend {
        private val no = MemoryOutcome.Refused(OpenLoopsBrief.NOT_ON_ENGINE, 404)
        override suspend fun list(): MemoryOutcome<Listing> = no
        override suspend fun offer(): MemoryOutcome<Listing> = no
        override suspend fun track(id: String, dueDate: LocalDate?): MemoryOutcome<Tracked> = no
        override suspend fun dismiss(id: String): MemoryOutcome<String> = no
        override suspend fun setProposing(on: Boolean): MemoryOutcome<String> = no
    }

    @Test
    fun `against an engine without open loops the rows say not available yet, never none, and offer no switch`() {
        val old = OpenLoopsController(backend = { OldEngine })
        compose.setContent { SoftTheme { OpenLoopsRows(controllerFor = { old }) } }
        compose.waitForIdle()
        compose.onNodeWithText(OpenLoopsBrief.NOT_ON_ENGINE).assertIsDisplayed()
        assertTrue(compose.onAllNodesWithText("No proposed to-dos.").fetchSemanticsNodes().isEmpty())
        assertTrue(compose.onAllNodesWithText("Track").fetchSemanticsNodes().isEmpty())
        assertTrue(
            "no switch to flip",
            compose.onAllNodes(androidx.compose.ui.test.isToggleable()).fetchSemanticsNodes().isEmpty(),
        )
    }
}
