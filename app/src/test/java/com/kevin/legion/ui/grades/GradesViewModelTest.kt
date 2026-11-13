package com.kevin.legion.ui.grades

import com.kevin.legion.grades.FakeGradesBackend
import com.kevin.legion.grades.FakeGradesBackend.Companion.assignment
import com.kevin.legion.grades.FakeGradesBackend.Companion.course
import com.kevin.legion.grades.GradeWords
import com.kevin.legion.grades.GradesOutcome
import com.kevin.legion.grades.NeedAnswer
import java.time.ZoneOffset
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Plain JVM: the Grades screen's state over a fake backend (daily-loops ticket 03). */
@OptIn(ExperimentalCoroutinesApi::class)
class GradesViewModelTest {
    private val backend = FakeGradesBackend()
    private var now = NOON

    @Before
    fun main() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun reset() = Dispatchers.resetMain()

    private fun viewModel() = GradesViewModel(backend, nowMs = { now }, zone = ZoneOffset.UTC)

    @Test
    fun `courses load with the engine's grade and the read time`() {
        backend.coursesResult = GradesOutcome.Ok(listOf(course(1), course(2, score = 70.0, grade = "C-")))
        val state = viewModel().state.value
        assertFalse(state.loading)
        assertEquals(listOf(1L, 2L), state.courses?.map { it.courseId })
        assertEquals("2026-11-13 12:00", state.lastReadAt)
        assertNull(state.error)
        assertEquals("A-, 91.5%", GradeWords.headline(state.courses!![0]))
    }

    @Test
    fun `no courses on file is an empty list, not an error`() {
        val state = viewModel().state.value
        assertEquals(emptyList<Any>(), state.courses)
        assertNull(state.error)
    }

    @Test
    fun `a course that hides its total says Canvas shows no total`() {
        backend.coursesResult = GradesOutcome.Ok(listOf(course(3, score = null, grade = null)))
        val c = viewModel().state.value.courses!!.single()
        assertTrue(c.hidesTotal)
        assertEquals("Canvas shows no total", GradeWords.headline(c))
    }

    @Test
    fun `an error with no prior data says it did not load and keeps no list`() {
        backend.coursesResult = GradesOutcome.Unreachable("Could not reach the engine.")
        val state = viewModel().state.value
        assertNull(state.courses)
        assertNull(state.lastReadAt)
        assertEquals("Grades did not load: Could not reach the engine. No grades have been read yet.", state.error)
    }

    @Test
    fun `an error after a good read keeps the old data and says when it was read`() {
        backend.coursesResult = GradesOutcome.Ok(listOf(course(1)))
        val vm = viewModel()
        now = NOON + 3_600_000
        backend.coursesResult = GradesOutcome.Unreachable("Could not reach the engine.")
        vm.refresh()
        val state = vm.state.value
        assertEquals(listOf(1L), state.courses?.map { it.courseId })
        assertEquals("2026-11-13 12:00", state.lastReadAt)
        assertEquals(
            "Grades did not load: Could not reach the engine. Showing what was last read, at 2026-11-13 12:00.",
            state.error,
        )
        backend.coursesResult = GradesOutcome.Ok(listOf(course(1)))
        vm.refresh()
        assertNull(vm.state.value.error)
        assertEquals("2026-11-13 13:00", vm.state.value.lastReadAt)
    }

    @Test
    fun `assignment lines say excused, missing, late and not graded yet in words`() {
        assertEquals("18 / 20", GradeWords.assignmentLine(assignment(1, score = 18.0)))
        assertEquals("excused", GradeWords.assignmentLine(assignment(2, excused = true)))
        assertEquals("not graded yet (20 points)", GradeWords.assignmentLine(assignment(3)))
        assertEquals("0 / 20, missing", GradeWords.assignmentLine(assignment(4, score = 0.0, missing = true)))
        assertEquals("17.5 / 20, late", GradeWords.assignmentLine(assignment(5, score = 17.5, late = true)))
    }

    @Test
    fun `what I need shows the engine's sentence verbatim, estimate flag kept`() {
        backend.coursesResult = GradesOutcome.Ok(listOf(course(1)))
        val vm = viewModel()
        vm.select(1)
        vm.setTarget("B")
        vm.setGroup("Exams")
        backend.needResult = GradesOutcome.Ok(NeedAnswer("needed", ESTIMATE_SENTENCE, true))
        vm.askNeed()
        assertEquals(listOf("1", "B", "", "Exams", ""), backend.needCalls.single())
        assertEquals(
            NeedResult.Answer(ESTIMATE_SENTENCE, true),
            vm.state.value.need,
        )
        assertFalse(vm.state.value.needBusy)
    }

    @Test
    fun `a 400 from the engine is shown as its own sentence`() {
        backend.coursesResult = GradesOutcome.Ok(listOf(course(1)))
        val vm = viewModel()
        vm.select(1)
        val sentence = "Nothing was worked out. Say a target: a percent (85) or a letter (B)."
        backend.needResult = GradesOutcome.Refused(sentence, 400)
        vm.askNeed()
        assertEquals(NeedResult.Failed(sentence), vm.state.value.need)
    }

    @Test
    fun `an unreachable engine on what I need says nothing was worked out`() {
        backend.coursesResult = GradesOutcome.Ok(listOf(course(1)))
        val vm = viewModel()
        vm.select(1)
        backend.needResult = GradesOutcome.Unreachable("Could not reach the engine.")
        vm.askNeed()
        assertEquals(
            NeedResult.Failed("Nothing was worked out. Could not reach the engine."),
            vm.state.value.need,
        )
    }

    @Test
    fun `selecting a course clears the last answer and refresh drops a vanished course`() {
        backend.coursesResult = GradesOutcome.Ok(listOf(course(1), course(2)))
        val vm = viewModel()
        vm.select(2)
        assertEquals(2L, vm.state.value.selected?.courseId)
        backend.coursesResult = GradesOutcome.Ok(listOf(course(1)))
        vm.refresh()
        assertNull(vm.state.value.selectedCourseId)
    }

    private companion object {
        /** 2026-11-13 12:00 UTC. */
        const val NOON = 1_794_571_200_000L
        const val ESTIMATE_SENTENCE = "You need about 82% on the final. This is an estimate."
    }
}
