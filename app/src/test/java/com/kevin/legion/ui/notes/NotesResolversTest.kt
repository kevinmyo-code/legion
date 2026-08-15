package com.kevin.legion.ui.notes

import com.kevin.legion.data.local.ItemList
import com.kevin.legion.data.local.ListItem
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Exercises the pure resolvers in `ui/notes/NotesResolvers.kt` - plain JUnit, no Compose/Room/
 * `Context`, same posture as [com.kevin.legion.ui.TodayGapResolversTest]. All fixtures invented.
 */
class NotesResolversTest {

    // ---------------------------------------------------------------- list-of-lists (ticket 07/11)

    // -------------------------------------------------------------------------- single list items

    // ------------------------------------------------------------------------------- MISSED

    @Test
    fun `missed rows resolve list names from the batched map, falling back when absent`() {
        val items = listOf(
            ListItem(id = 1, listId = 10, text = "Call the plumber", missedAt = 5_000L),
            ListItem(id = 2, listId = 99, text = "Orphaned", missedAt = 6_000L),
        )
        val rows = buildMissedRows(items, mapOf(10L to "Reminders"))
        assertEquals("Reminders", rows[0].listName)
        assertEquals("a list", rows[1].listName)
    }

    // --------------------------------------------------------------------------------- agenda

    // Anchored to a fixed calendar date and built through ZoneId.systemDefault - the same zone
    // groupAgendaByDay itself groups by (see NotesResolvers.kt's doc comment on device vs UTC) -
    // rather than raw epoch-millis literals, which are timezone-fragile: a millis value a few hours
    // past UTC midnight lands on a DIFFERENT calendar day depending on the machine running the test.
    private val zone = ZoneId.systemDefault()
    private val anchorDay = LocalDate.of(2026, 8, 10)
    private fun epochAt(day: LocalDate, hour: Int): Long = day.atTime(LocalTime.of(hour, 0)).atZone(zone).toInstant().toEpochMilli()

}
