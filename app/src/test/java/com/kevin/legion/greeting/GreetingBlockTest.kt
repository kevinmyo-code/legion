package com.kevin.legion.greeting

import com.kevin.legion.greeting.GreetingTestSupport.empty
import com.kevin.legion.greeting.GreetingTestSupport.facts
import com.kevin.legion.greeting.GreetingTestSupport.item
import com.kevin.legion.greeting.GreetingTestSupport.pack
import com.kevin.legion.greeting.GreetingTestSupport.parsed
import com.kevin.legion.greeting.GreetingTestSupport.unreadable
import java.time.LocalTime
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GreetingBlockTest {
    private val noPlace = GreetingSource(GreetingPlace.SOURCE, SourceState.EMPTY, "He is at home.")
    private val morning = LocalTime.of(8, 5)

    private val due = item("due:1", "Essay is due Friday, 5:00 PM.")
    private val ended = item("event:2", "Dentist ended yesterday, 4:30 PM.")

    @Test fun `each state is rendered as its own sentence, unreadable never as empty`() {
        val p = parsed(
            pack(
                ended = facts(ended),
                today = empty("Nothing else is on the calendar for today."),
                due = unreadable("could not read to-dos (OperationalError)"),
            ),
        )
        val text = GreetingBlock.render(p, noPlace, morning)
        assertTrue(text.contains("(facts):"))
        assertTrue(text.contains("Dentist ended yesterday"))
        assertTrue(text.contains("Today's remaining events (EMPTY: read, and nothing is on file)"))
        val dueLine = text.lines().first { it.contains("due within 24 hours") }
        assertTrue(dueLine, dueLine.contains("COULD NOT BE READ, which is not the same as empty"))
        assertFalse(dueLine, dueLine.contains("EMPTY"))
        assertTrue(text.contains("ONLY things the greeting may mention"))
    }

    @Test fun `a source the engine left out of its reply is unreadable not empty`() {
        val p = FactsParser.parse(
            """{"today":"2026-11-13","utc_offset":"-06:00","sources":{},"said":[],"closed":[],""" +
                """"topics":{"state":"read"}}""",
        )
        assertTrue(p.sources.all { it.state == SourceState.UNREADABLE })
    }

    @Test fun `a facts source with no readable item is unreadable`() {
        val p = parsed(pack(due = """{"state":"facts","says":"1 on file.","items":[]}"""))
        assertEquals(SourceState.UNREADABLE, p.sources.first { it.name == "due_soon" }.state)
    }

    @Test fun `place unknown is said as unknown and never guessed`() = runBlocking {
        val src = GreetingPlace.read(GreetingTestSupport.signals(fix = null))
        assertEquals(SourceState.UNREADABLE, src.state)
        val text = GreetingBlock.render(parsed(pack()), src, morning)
        assertTrue(text.contains("where he is is unknown"))
        assertTrue(text.contains("Do not guess a city"))
        assertFalse(text.contains("America/"))
    }

    @Test fun `place states away at home unnamed and no saved home`() = runBlocking {
        val away = GreetingPlace.read(GreetingTestSupport.signals())
        assertEquals(SourceState.FACTS, away.state)
        assertEquals("place:austin, texas", away.items.single().topic)
        assertTrue(away.items.single().says.contains("away from home"))
        assertEquals(SourceState.EMPTY, GreetingPlace.read(GreetingTestSupport.signals(atHome = true)).state)
        val unnamed = GreetingPlace.read(GreetingTestSupport.signals(area = AreaLookup.NotFound))
        assertEquals(SourceState.UNREADABLE, unnamed.state)
        val failed = GreetingPlace.read(GreetingTestSupport.signals(area = AreaLookup.Unavailable("offline")))
        assertEquals(SourceState.UNREADABLE, failed.state)
        val noHome = GreetingPlace.read(GreetingTestSupport.signals(atHome = null))
        assertEquals(SourceState.FACTS, noHome.state)
        assertTrue(noHome.items.single().says.contains("do not say he is away"))
    }

    @Test fun `the block holds nothing outside the pack and the place`() {
        val p = parsed(pack(ended = facts(ended)))
        val text = GreetingBlock.render(p, noPlace, morning)
        // Every line is the header, a source label with its state, or an item the pack carried.
        val items = p.sources.flatMap { it.items }.map { it.says } + noPlace.says
        text.lines().drop(1).forEach { line ->
            val ok = line.startsWith("- ") || items.any { line.contains(it) }
            assertTrue(line, ok)
        }
    }

    @Test fun `the pick skips said and closed topics`() {
        val p = parsed(
            pack(
                due = facts(due, item("due:3", "Lab report is due Friday, 6:00 PM.")),
                ended = facts(ended),
                said = listOf("due:1"),
                closed = listOf("event:2"),
            ),
        )
        val chosen = GreetingBlock.pick(p, noPlace, 8)
        assertEquals("due:3", chosen?.topic)
        val nothing = parsed(
            pack(due = facts(due), ended = facts(ended), said = listOf("due:1"), closed = listOf("event:2")),
        )
        assertNull(GreetingBlock.pick(nothing, noPlace, 8))
        assertFalse(GreetingBlock.hasTopic(nothing, noPlace))
    }

    @Test fun `a raised item from the engine is not offered again`() {
        val p = parsed(pack(due = facts(item("due:1", "Essay is due Friday.", raised = true))))
        assertNull(GreetingBlock.pick(p, noPlace, 8))
        assertTrue(GreetingBlock.render(p, noPlace, morning).contains("already raised or answered: do not mention"))
    }

    @Test fun `the order follows the time of day`() {
        val p = parsed(pack(due = facts(due), ended = facts(ended)))
        assertEquals("due:1", GreetingBlock.pick(p, noPlace, 8)?.topic)
        assertEquals("event:2", GreetingBlock.pick(p, noPlace, 19)?.topic)
    }

    @Test fun `away from home leads at any hour, and a closed place is dropped`() = runBlocking {
        val away = GreetingPlace.read(GreetingTestSupport.signals())
        val p = parsed(pack(due = facts(due)))
        assertEquals("place:austin, texas", GreetingBlock.pick(p, away, 8)?.topic)
        val closed = GreetingPlace.applyTopics(away, emptySet(), setOf("place:austin, texas"))
        assertEquals(SourceState.EMPTY, closed.state)
        assertEquals("due:1", GreetingBlock.pick(p, closed, 8)?.topic)
        val said = GreetingPlace.applyTopics(away, setOf("place:austin, texas"), emptySet())
        assertNull(GreetingBlock.pick(parsed(pack()), said, 8))
    }

    @Test fun `money is only a topic when over or near the cap`() {
        val onPace = item("money:2026-11:Dining", "Dining: \$100.00 of \$400.00 spent by day 13 of 30.")
        assertNull(GreetingBlock.pick(parsed(pack(money = facts(onPace))), noPlace, 8))
        val over = item("money:2026-11:Fuel", "Fuel: \$300.00 of \$200.00 spent. Over the cap.")
        val chosen = GreetingBlock.pick(parsed(pack(money = facts(onPace, over))), noPlace, 8)
        assertEquals("money:2026-11:Fuel", chosen?.topic)
    }

    @Test fun `the prompt forbids the things the rulings forbid and names no one`() {
        val p = parsed(pack(due = facts(due)))
        val lead = GreetingBlock.pick(p, noPlace, 8)!!
        val prompt = GreetingPrompt.build(GreetingBlock.render(p, noPlace, morning), lead)
        assertNotNull(prompt)
        listOf(
            "At most one question", "Mention nothing that is not in the facts", "never say it is clear",
            "never ask the same question twice", "Never \"two hours ago\"", "estimate", "absence",
            "Do not call any tool",
        ).forEach {
            assertTrue(it, prompt.contains(it))
        }
        assertFalse(prompt.contains("Alfred"))
        assertFalse(prompt.contains("—"))
    }
}
