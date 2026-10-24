package com.kevin.legion.ui.dumb

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Matrix notes states over a fake source: loading, empty, unreadable apart from empty, write failures. */
class MatrixNotesViewModelTest {
    private class Source(
        var items: List<MatrixNoteItem> = emptyList(),
        var readThrows: Boolean = false,
        var ok: Boolean = true,
    ) : MatrixNotesSource {
        val calls = mutableListOf<String>()

        override suspend fun items(): List<MatrixNoteItem> {
            if (readThrows) error("Room is closed")
            return items
        }

        override suspend fun add(text: String): Boolean = ok.also { calls += "add:$text" }

        override suspend fun rename(id: Long, text: String): Boolean = ok.also { calls += "rename:$id:$text" }

        override suspend fun setDone(id: Long, done: Boolean): Boolean = ok.also { calls += "done:$id:$done" }

        override suspend fun remove(id: Long): Boolean = ok.also { calls += "remove:$id" }
    }

    private fun model(source: Source) =
        MatrixNotesViewModel(source, CoroutineScope(Dispatchers.Unconfined), Dispatchers.Unconfined)

    private val milk = MatrixNoteItem(1, "Buy milk", done = false)

    @Test
    fun `loading, empty and unreadable are three different sentences`() {
        val vm = model(Source())
        assertEquals(MatrixNotesText.LOADING, MatrixNotesText.listNote(vm.state.value))
        vm.refresh()
        assertEquals(MatrixNotesText.EMPTY, MatrixNotesText.listNote(vm.state.value))
        val broken = model(Source(readThrows = true))
        broken.refresh()
        assertEquals(MatrixNotesText.UNREADABLE, MatrixNotesText.listNote(broken.state.value))
    }

    @Test
    fun `a failed refresh keeps the notes and says they may be out of date`() {
        val source = Source(items = listOf(milk))
        val vm = model(source)
        vm.refresh()
        source.readThrows = true
        vm.refresh()
        assertNull(MatrixNotesText.listNote(vm.state.value))
        assertEquals(MatrixNotesText.STALE, MatrixNotesText.staleNote(vm.state.value))
        assertEquals(1, vm.state.value.items.size)
    }

    @Test
    fun `adding a note writes it, clears the field and says so`() {
        val source = Source()
        val vm = model(source)
        vm.setDraft("  Call mum ")
        vm.add()
        assertEquals(listOf("add:Call mum"), source.calls)
        assertEquals("", vm.state.value.draft)
        assertEquals("Saved a new note.", vm.state.value.notice)
    }

    @Test
    fun `a failed add keeps the typed text and says nothing was changed`() {
        val vm = model(Source(ok = false))
        vm.setDraft("Call mum")
        vm.add()
        assertEquals("Call mum", vm.state.value.draft)
        assertEquals("That note was not saved. Nothing was changed.", vm.state.value.notice)
    }

    @Test
    fun `a blank note is not sent`() {
        val source = Source()
        val vm = model(source)
        vm.setDraft("   ")
        vm.add()
        assertTrue(source.calls.isEmpty())
    }

    @Test
    fun `opening a note loads its text for editing and a save sends the new text`() {
        val source = Source(items = listOf(milk))
        val vm = model(source)
        vm.refresh()
        vm.open(1)
        assertEquals("Buy milk", vm.state.value.editText)
        vm.setEditText("Buy oat milk")
        vm.saveEdit()
        assertEquals(listOf("rename:1:Buy oat milk"), source.calls)
        assertEquals("Saved the change.", vm.state.value.notice)
    }

    @Test
    fun `an empty edit is refused in words and nothing is sent`() {
        val source = Source(items = listOf(milk))
        val vm = model(source)
        vm.refresh()
        vm.open(1)
        vm.setEditText(" ")
        vm.saveEdit()
        assertTrue(source.calls.isEmpty())
        assertEquals("A note can't be empty. Nothing was changed.", vm.state.value.notice)
    }

    @Test
    fun `ticking says ticked, a repeating note is refused, a failure says it was not ticked`() {
        val source = Source(items = listOf(milk, MatrixNoteItem(2, "Pills", done = false, repeats = true)))
        val vm = model(source)
        vm.refresh()
        vm.open(1)
        vm.toggleDone()
        assertEquals("Note ticked.", vm.state.value.notice)
        vm.open(2)
        vm.toggleDone()
        assertTrue(vm.state.value.notice!!.contains("repeats"))
        assertFalse(source.calls.any { it.startsWith("done:2") })
        source.ok = false
        vm.open(1)
        vm.toggleDone()
        assertEquals("The note was not ticked. Nothing was changed.", vm.state.value.notice)
    }

    @Test
    fun `delete asks first, a failure keeps the note open, success closes it`() {
        val source = Source(items = listOf(milk))
        val vm = model(source)
        vm.refresh()
        vm.open(1)
        vm.askDelete()
        assertTrue(vm.state.value.confirmingDelete)
        assertTrue(source.calls.isEmpty())
        source.ok = false
        vm.confirmDelete()
        assertEquals(1L, vm.state.value.selectedId)
        assertEquals("The note was not deleted. Nothing was changed.", vm.state.value.notice)
        source.ok = true
        vm.askDelete()
        vm.confirmDelete()
        assertNull(vm.state.value.selectedId)
        assertEquals("Deleted the note.", vm.state.value.notice)
    }

    @Test
    fun `rows say done, date and repeats in words`() {
        val item = MatrixNoteItem(
            3, "Pills", done = true, whenLabel = "Oct 24, 9:00 AM", repeats = true, placeLabel = "Home",
        )
        assertEquals("[x] Pills", MatrixNotesText.rowText(item))
        assertEquals("Oct 24, 9:00 AM - Repeats - At Home", MatrixNotesText.rowDetail(item))
        assertNull(MatrixNotesText.rowDetail(milk))
    }
}
