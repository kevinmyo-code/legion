package com.kevin.legion.ui.companions

import android.app.Application
import com.kevin.legion.memory.FakeMemberMemoryBackend
import com.kevin.legion.memory.FakeMemberMemoryBackend.Companion.NOW
import com.kevin.legion.memory.FakeMemberMemoryBackend.Companion.memory
import com.kevin.legion.memory.FakeMemoryReviewBackend
import com.kevin.legion.memory.FakeMemoryReviewBackend.Companion.STALE
import com.kevin.legion.memory.FakeMemoryReviewBackend.Companion.change
import com.kevin.legion.memory.InMemoryMemoryCache
import com.kevin.legion.memory.MemoryController
import com.kevin.legion.memory.MemoryOutcome
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * memory-review ticket 09: the Memory screen's To check and Retired, over a fake controller. Accept,
 * Dismiss and Accept all go through the same [MemoryController]; a stale accept says the engine's
 * 409 words; offline disables the buttons and says why. Made-up memories only.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class MemoryReviewViewModelTest {

    private val memories = FakeMemberMemoryBackend(mutableListOf(memory("a1", "Sam's pharmacy is Maple Pharmacy")))
    private val review = FakeMemoryReviewBackend(
        mutableListOf(change(1), change(2, cluster = "9.1"), change(3, cluster = "9.1")),
    )

    @Before
    fun main() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun reset() = Dispatchers.resetMain()

    private fun viewModel() = MemoryViewModel(
        RuntimeEnvironment.getApplication() as Application,
        MemoryController(memories, InMemoryMemoryCache(), now = { NOW }, review = review),
    )

    @Test
    fun `pending changes load on open, grouped for Accept all`() {
        val state = viewModel().state.value
        assertEquals(listOf(1L, 2L, 3L), state.review.changes.map { it.id })
        assertEquals(listOf(1, 2), state.review.clusters.map { it.size })
        assertFalse(state.review.offline)
    }

    @Test
    fun `accept sends that change and reloads what is left`() {
        val vm = viewModel()
        vm.acceptChange(vm.state.value.review.changes.first())
        assertTrue("accept:1" in review.calls)
        assertEquals("Done. Your memory is updated.", vm.state.value.message)
        assertEquals(listOf(2L, 3L), vm.state.value.review.changes.map { it.id })
    }

    @Test
    fun `dismiss sends that change and says nothing changed`() {
        val vm = viewModel()
        vm.dismissChange(vm.state.value.review.changes.first())
        assertTrue("dismiss:1" in review.calls)
        assertEquals("Dismissed. Nothing was changed.", vm.state.value.message)
    }

    @Test
    fun `accept all takes the whole group`() {
        val vm = viewModel()
        vm.acceptCluster("9.1")
        assertTrue("cluster:9.1" in review.calls)
        assertEquals(listOf(1L), vm.state.value.review.changes.map { it.id })
    }

    @Test
    fun `a stale accept says the engine's 409 words`() {
        review.staleIds += 1L
        val vm = viewModel()
        vm.acceptChange(vm.state.value.review.changes.first())
        assertEquals(STALE, vm.state.value.message)
    }

    @Test
    fun `offline the buttons are off and a line says why`() {
        review.failWith = MemoryOutcome.Unreachable("No network.")
        val state = viewModel().state.value
        assertTrue(state.review.offline)
        assertTrue(state.review.note!!.contains("can't be reached"))
    }

    @Test
    fun `the retired filter lists what the review retired and restore reverts it`() {
        review.retiredRows += memory("old", "Sam had a dentist appointment on August 3")
            .copy(retiredReason = "expired", retiredChange = 44L)
        val vm = viewModel()
        vm.setFilter(MemoryFilter.Retired)
        val listed = vm.state.value.retired as MemoryView.Listed
        assertEquals(listOf("old"), listed.memories.map { it.id })
        vm.restore(listed.memories.single())
        assertTrue("revert:44" in review.calls)
        assertEquals(emptyList<String>(), (vm.state.value.retired as MemoryView.Listed).memories.map { it.id })
    }

    @Test
    fun `words for why a row was retired`() {
        assertEquals("Date passed", retiredWords("expired"))
        assertEquals("Replaced by a newer fact", retiredWords("contradicted"))
        assertEquals("From your conversation on November 7", evidenceWords("2026-11-07"))
        assertEquals(null, evidenceWords(null))
    }
}
