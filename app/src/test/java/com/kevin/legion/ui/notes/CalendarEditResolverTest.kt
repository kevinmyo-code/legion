package com.kevin.legion.ui.notes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Exercises `ui/notes/CalendarEditResolver.kt` - ticket 22
 * (`.scratch/google-account-integration/issues/22-edit-calendar-entries-from-log.md`)'s pure
 * edit/delete branching. Plain JUnit, no Compose/Android, same posture as [ScheduleIntentResolverTest].
 */
class CalendarEditResolverTest {

    private val readOnlyAccess = 200 // CAL_ACCESS_READ, Kevin's "Holidays in United States" (ticket 17)
    private val writableAccess = 700 // CAL_ACCESS_OWNER, a normal owned calendar

    // ------------------------------------------------------------------------------- rowAction

    @Test
    fun `below CAL_ACCESS_CONTRIBUTOR is always READ_ONLY, recurring or not`() {
        assertEquals(CalendarEditResolver.RowAction.READ_ONLY, CalendarEditResolver.rowAction(readOnlyAccess, recurring = false))
        assertEquals(CalendarEditResolver.RowAction.READ_ONLY, CalendarEditResolver.rowAction(readOnlyAccess, recurring = true))
    }

    @Test
    fun `writable and not recurring is EDITABLE`() {
        assertEquals(CalendarEditResolver.RowAction.EDITABLE, CalendarEditResolver.rowAction(writableAccess, recurring = false))
    }

    @Test
    fun `writable and recurring is EDITABLE_RECURRING`() {
        assertEquals(CalendarEditResolver.RowAction.EDITABLE_RECURRING, CalendarEditResolver.rowAction(writableAccess, recurring = true))
    }

    @Test
    fun `exactly CAL_ACCESS_CONTRIBUTOR is writable, one below is not`() {
        assertEquals(
            CalendarEditResolver.RowAction.EDITABLE,
            CalendarEditResolver.rowAction(CalendarEditResolver.CAL_ACCESS_CONTRIBUTOR, recurring = false),
        )
        assertEquals(
            CalendarEditResolver.RowAction.READ_ONLY,
            CalendarEditResolver.rowAction(CalendarEditResolver.CAL_ACCESS_CONTRIBUTOR - 1, recurring = false),
        )
    }

    // ------------------------------------------------------------------------------ scopePrompt

    @Test
    fun `a non-recurring event gets no prompt on edit - nothing to disambiguate`() {
        assertNull(CalendarEditResolver.scopePrompt(CalendarEditResolver.RowAction.EDITABLE, CalendarEditResolver.Operation.EDIT))
    }

    @Test
    fun `a non-recurring event gets no SCOPE prompt on delete either - it still needs a plain confirm elsewhere`() {
        assertNull(CalendarEditResolver.scopePrompt(CalendarEditResolver.RowAction.EDITABLE, CalendarEditResolver.Operation.DELETE))
    }

    @Test
    fun `a read-only row gets no prompt for either operation`() {
        assertNull(CalendarEditResolver.scopePrompt(CalendarEditResolver.RowAction.READ_ONLY, CalendarEditResolver.Operation.EDIT))
        assertNull(CalendarEditResolver.scopePrompt(CalendarEditResolver.RowAction.READ_ONLY, CalendarEditResolver.Operation.DELETE))
    }

    @Test
    fun `a recurring event always gets a this-one-or-all prompt, worded per operation`() {
        val editPrompt = CalendarEditResolver.scopePrompt(CalendarEditResolver.RowAction.EDITABLE_RECURRING, CalendarEditResolver.Operation.EDIT)
        val deletePrompt = CalendarEditResolver.scopePrompt(CalendarEditResolver.RowAction.EDITABLE_RECURRING, CalendarEditResolver.Operation.DELETE)
        assertEquals("Just this one, or the whole series?", editPrompt)
        assertEquals("Delete just this one, or the whole series?", deletePrompt)
    }

    // -------------------------------------------------------------------------- read-only wording

    @Test
    fun `the read-only reason is a real sentence, not a blank string`() {
        assert(CalendarEditResolver.READ_ONLY_REASON.isNotBlank())
    }

    @Test
    fun `the single-delete confirm is a real sentence, not a blank string`() {
        assert(CalendarEditResolver.SINGLE_DELETE_CONFIRM.isNotBlank())
    }
}
