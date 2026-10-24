package com.kevin.legion.ui.dumb

import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Matrix calendar's states over a fake source. Unconfined scope and io: each launch runs to
 * completion before the call returns, so assertions read the state straight after.
 */
class MatrixCalendarViewModelTest {
    private val zone = ZoneId.of("America/Chicago")
    private val today = LocalDate.of(2026, 10, 24)
    private val nowMs = today.atTime(10, 0).atZone(zone).toInstant().toEpochMilli()

    private class Source(
        var rows: List<MatrixAgendaRow> = emptyList(),
        var readThrows: Boolean = false,
        var saveOk: Boolean = true,
        var deleteOk: Boolean = true,
        var doneOk: Boolean = true,
    ) : MatrixCalendarSource {
        val saved = mutableListOf<Triple<Long?, String, MatrixEventTimes>>()
        val deleted = mutableListOf<Long>()
        val ticks = mutableListOf<Pair<Long, Boolean>>()

        override suspend fun agenda(from: LocalDate, toExclusive: LocalDate, zone: ZoneId): List<MatrixAgendaRow> {
            if (readThrows) error("Room is closed")
            return rows
        }

        override suspend fun saveEvent(id: Long?, title: String, times: MatrixEventTimes): Boolean {
            saved += Triple(id, title, times)
            return saveOk
        }

        override suspend fun deleteEvent(id: Long): Boolean {
            deleted += id
            return deleteOk
        }

        override suspend fun setDone(row: MatrixAgendaRow, done: Boolean): Boolean {
            ticks += row.id to done
            return doneOk
        }
    }

    private fun model(source: Source) = MatrixCalendarViewModel(
        source, zone, { nowMs }, CoroutineScope(Dispatchers.Unconfined), Dispatchers.Unconfined,
    )

    private fun event(id: Long = 1) =
        MatrixAgendaRow(id, MatrixAgendaKind.EVENT, "Dentist", nowMs, nowMs + 3_600_000L, false)

    private fun task(done: Boolean = false) =
        MatrixAgendaRow(2, MatrixAgendaKind.TASK, "Essay", nowMs, null, false, done = done, tickable = true)

    @Test
    fun `starts loading, then a read lands as rows`() {
        val vm = model(Source(rows = listOf(event())))
        assertTrue(vm.state.value.loading)
        vm.refresh()
        assertFalse(vm.state.value.loading)
        assertFalse(vm.state.value.readFailed)
        assertEquals(1, vm.state.value.rows.size)
    }

    @Test
    fun `a failed read is readFailed, not an empty week`() {
        val vm = model(Source(readThrows = true))
        vm.refresh()
        assertTrue(vm.state.value.readFailed)
        assertTrue(vm.state.value.rows.isEmpty())
        assertEquals(MatrixCalendarText.UNREADABLE, MatrixCalendarText.note(vm.state.value))
    }

    @Test
    fun `a failed refresh keeps the rows already on screen and flags the failure`() {
        val source = Source(rows = listOf(event()))
        val vm = model(source)
        vm.refresh()
        source.readThrows = true
        vm.refresh()
        assertTrue(vm.state.value.readFailed)
        assertEquals(1, vm.state.value.rows.size)
    }

    @Test
    fun `tapping an event opens its form and tapping a task ticks it`() {
        val source = Source(rows = listOf(event(), task()))
        val vm = model(source)
        vm.refresh()
        vm.tapRow(event())
        assertEquals("Dentist", vm.state.value.form?.title)
        vm.cancelForm()
        vm.tapRow(task())
        assertEquals(listOf(2L to true), source.ticks)
        assertEquals("\"Essay\" ticked.", vm.state.value.notice)
    }

    @Test
    fun `a tick that failed says it did not happen`() {
        val vm = model(Source(doneOk = false))
        vm.tapRow(task())
        assertEquals("\"Essay\" was not ticked. Nothing changed.", vm.state.value.notice)
    }

    @Test
    fun `a repeating reminder is refused in words and nothing is written`() {
        val source = Source()
        val vm = model(source)
        vm.tapRow(MatrixAgendaRow(3, MatrixAgendaKind.REMINDER, "Pills", nowMs, null, false, repeats = true))
        assertTrue(source.ticks.isEmpty())
        assertTrue(vm.state.value.notice!!.contains("can't be ticked off here"))
    }

    @Test
    fun `adding an event saves it, closes the form and says so`() {
        val source = Source()
        val vm = model(source)
        vm.startAdd(today)
        vm.editForm { it.copy(title = "Lunch", time = "12:00") }
        vm.save()
        assertEquals(1, source.saved.size)
        assertNull(source.saved.single().first)
        assertEquals("Lunch", source.saved.single().second)
        assertNull(vm.state.value.form)
        assertEquals("Saved \"Lunch\".", vm.state.value.notice)
    }

    @Test
    fun `a bad form is not sent to the source and says why`() {
        val source = Source()
        val vm = model(source)
        vm.startAdd(today)
        vm.save()
        assertTrue(source.saved.isEmpty())
        assertTrue(vm.state.value.form!!.error!!.contains("title"))
    }

    @Test
    fun `a rejected save keeps the form open and says it was not saved`() {
        val vm = model(Source(saveOk = false))
        vm.startAdd(today)
        vm.editForm { it.copy(title = "Lunch") }
        vm.save()
        assertNotNull(vm.state.value.form)
        assertEquals("\"Lunch\" was not saved. Nothing was changed.", vm.state.value.form!!.error)
    }

    @Test
    fun `delete asks first, then deletes, and a failed delete says so`() {
        val source = Source()
        val vm = model(source)
        vm.tapRow(event(5))
        vm.askDelete()
        assertTrue(source.deleted.isEmpty())
        assertTrue(vm.state.value.form!!.confirmingDelete)
        source.deleteOk = false
        vm.confirmDelete()
        assertEquals("\"Dentist\" was not deleted. Nothing was changed.", vm.state.value.form!!.error)
        source.deleteOk = true
        vm.confirmDelete()
        assertEquals(listOf(5L, 5L), source.deleted)
        assertNull(vm.state.value.form)
        assertEquals("Deleted \"Dentist\".", vm.state.value.notice)
    }
}
