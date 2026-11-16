package com.kevin.legion.service.cascade

import com.kevin.legion.greeting.CachedPack
import com.kevin.legion.greeting.FactsPack
import com.kevin.legion.greeting.GreetingItem
import com.kevin.legion.greeting.GreetingSource
import com.kevin.legion.greeting.SourceState
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalTime
import java.time.ZoneOffset
import java.time.ZonedDateTime

/**
 * [AgendaBlock] (voice quick wins C): facts, EMPTY and COULD NOT BE READ, the as-of time, the limits.
 * Invented data only.
 */
class AgendaBlockTest {

    private val zone = ZoneOffset.UTC

    /** 09:05 UTC on an invented day. */
    private val fetchedAt = ZonedDateTime.of(2099, 3, 3, 9, 5, 0, 0, zone).toInstant().toEpochMilli()

    private fun pack(vararg sources: GreetingSource) = FactsPack(
        today = "2099-03-03",
        utcOffset = "+00:00",
        zoneNote = null,
        sources = sources.toList(),
        said = emptySet(),
        closed = emptySet(),
        topicsRead = true,
        topicsReason = null,
    )

    private fun facts(name: String, vararg says: String) =
        GreetingSource(name, SourceState.FACTS, "${says.size} on file.", items = says.map { GreetingItem("t-$it", it) })

    @Test
    fun `facts are listed under their source, with the time the pack was read`() {
        val block = AgendaBlock.render(
            CachedPack(
                pack(
                    facts("today_events", "Invented seminar at 14:00."),
                    facts("due_soon", "Invented worksheet due at 23:59."),
                ),
                fetchedAt,
            ),
            zone,
        )
        assertTrue(block, block.contains("read from the household engine at 09:05"))
        assertTrue(block.contains("today is 2099-03-03"))
        assertTrue(block.contains("Today's remaining calendar events (facts):\n    * Invented seminar at 14:00."))
        assertTrue(block.contains("    * Invented worksheet due at 23:59."))
    }

    @Test
    fun `an empty source says EMPTY and an unread one is never passed off as clear`() {
        val block = AgendaBlock.render(
            CachedPack(
                pack(
                    GreetingSource("today_events", SourceState.EMPTY, "Nothing on the calendar for the rest of today."),
                    GreetingSource(
                        "due_soon", SourceState.UNREADABLE, "x", reason = "Invented: the engine could not read Canvas.",
                    ),
                ),
                fetchedAt,
            ),
            zone,
        )
        assertTrue(block, block.contains("Today's remaining calendar events (EMPTY: read, and nothing is on file)"))
        assertTrue(
            block.contains(
                "(COULD NOT BE READ, which is not the same as empty): Invented: the engine could not read Canvas.",
            ),
        )
    }

    @Test
    fun `a source missing from the pack reads as could not be read`() {
        val block =
            AgendaBlock.render(CachedPack(pack(facts("today_events", "Invented call at 16:00.")), fetchedAt), zone)
        assertTrue(
            block,
            block.contains("To-dos and Canvas work due in the 24 hours after it was read (COULD NOT BE READ"),
        )
    }

    @Test
    fun `the block says what it does not carry and sends it to the tools`() {
        val block =
            AgendaBlock.render(CachedPack(pack(facts("due_soon", "Invented essay due at 10:00.")), fetchedAt), zone)
        // Overdue work and later days are not in the pack: said, never implied away.
        assertTrue(block.contains("overdue"))
        assertTrue(block.contains("NOT listed here"))
        assertTrue(block.contains("any other day or range"))
        assertTrue(block.contains("day read_calendar"))
        // Every write goes through its tool.
        assertTrue(block.contains("(complete, add, move, delete) goes through its tool"))
        // The pack has no weather: said as missing, never as fine.
        assertTrue(block.contains(AgendaBlock.WEATHER_LINE))
        // Only the ruled words for an unread or empty source: no blanket "clear" or "free".
        for (word in listOf("clear", "free", "nothing due")) {
            assertFalse("$word in $block", block.lowercase().contains(word))
        }
    }

    @Test
    fun `withdrawn and unavailable say nothing about his day and point at the tool`() {
        val gone = AgendaBlock.withdrawn(LocalTime.of(10, 7))
        assertTrue(gone.contains("10:07"))
        assertTrue(gone.contains("out of date"))
        assertTrue(gone.contains("day read_calendar"))
        val none = AgendaBlock.unavailable()
        assertTrue(none.contains("says nothing about his day"))
        assertTrue(none.contains("day read_calendar"))
        assertTrue(AgendaBlock.TOOL_NOTE.contains("out of date"))
    }
}
