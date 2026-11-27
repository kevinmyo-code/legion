package com.kevin.legion.ui.assistant

import android.app.Application
import com.kevin.legion.claude.ClaudeOutcome
import com.kevin.legion.claude.ClaudeSessionController
import com.kevin.legion.claude.ClaudeWording
import com.kevin.legion.claude.ProblemReport
import com.kevin.legion.claude.FakeClaudeBackend
import com.kevin.legion.claude.FakeClaudeBackend.Companion.machine
import com.kevin.legion.service.AssistantChatStore
import com.kevin.legion.service.ChatEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/** The button door of the problem report: same controller as the voice tool, outcome said in words. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class ReportProblemButtonTest {
    private val dispatcher = StandardTestDispatcher()
    private val backend = FakeClaudeBackend()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        AssistantChatStore.clear()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        AssistantChatStore.clear()
    }

    private val ref = ProblemReport.ReplyRef(1_700_000_000_000L, ProblemReport.hashOf("a reply"))

    private fun viewModel() = AssistantChatViewModel(RuntimeEnvironment.getApplication() as Application).also {
        it.reportController = { ClaudeSessionController(backend) }
    }

    private fun lastLine(): String =
        AssistantChatStore.transcript.value.entries.last { it.kind == ChatEntry.Kind.SYSTEM }.text

    @Test
    fun `a filed report is said as filed, with the reply position and no reply text`() = runTest(dispatcher) {
        backend.machines(machine("Alienware"))
        viewModel().reportProblem(ref, "wrong day")
        advanceUntilIdle()
        assertTrue(lastLine().startsWith("Report filed."))
        val request = backend.created.single().request
        assertTrue(request.contains("text hash ${ref.hash}"))
        assertTrue(request.endsWith("Kevin's words: wrong day"))
    }

    @Test
    fun `an engine that is down says nothing was reported`() = runTest(dispatcher) {
        backend.statusOut = ClaudeOutcome.Unreachable("down")
        viewModel().reportProblem(ref, "wrong day")
        advanceUntilIdle()
        assertEquals(ClaudeWording.failed(backend.statusOut, "report it"), lastLine())
        assertTrue(backend.created.isEmpty())
    }
}
