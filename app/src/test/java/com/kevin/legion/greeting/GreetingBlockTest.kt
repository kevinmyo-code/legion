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

    // Invented budgets (2026-11-16 caps): one on pace, one near its cap, two over.
    private val overSays = "Fuel: \$300.00 of \$200.00 spent. Over the cap."
    private val onPace = item("money:2026-11:Dining", "Dining: \$100.00 of \$400.00 spent by day 13 of 30.")
    private val near = item("money:2026-11:Books", "Books: \$85.00 of \$100.00 spent. Past 80% of the cap.")
    private val over = item("money:2026-11:Fuel", overSays)
    private val over2 = item("money:2026-11:Hobbies", "Hobbies: \$90.00 of \$60.00 spent. Over the cap.")
    private val workout = item("body:2026-11-13", "Push day is in progress.", kind = "workout")

    @Test fun `money is only a topic when over the cap, never near it or on pace`() {
        assertNull(GreetingBlock.pick(parsed(pack(money = facts(onPace))), noPlace, 8))
        assertNull(GreetingBlock.pick(parsed(pack(money = facts(onPace, near))), noPlace, 8))
        val chosen = GreetingBlock.pick(parsed(pack(money = facts(onPace, near, over))), noPlace, 8)
        assertEquals("money:2026-11:Fuel", chosen?.topic)
    }

    @Test fun `money leads at most once a day, however many budgets are over`() {
        val p = parsed(pack(money = facts(over, over2)))
        assertEquals("money", GreetingBlock.lead(p, noPlace, 13)?.source)
        // Money led earlier today, then something else: the second over-cap budget does not lead.
        val laterToday = GreetingBlock.History(today = listOf("money", "due"), last = "due")
        assertNull(GreetingBlock.lead(p, noPlace, 13, laterToday))
        // A new day: money may lead again.
        assertEquals("money", GreetingBlock.lead(p, noPlace, 13, GreetingBlock.History(last = "due"))?.source)
    }

    @Test fun `no kind leads twice in a row, and the two calendar sources are one kind`() {
        val p = parsed(pack(due = facts(due, item("due:3", "Lab report is due Friday.")), ended = facts(ended)))
        assertEquals("due:1", GreetingBlock.pick(p, noPlace, 8)?.topic)
        val afterDue = GreetingBlock.History(today = listOf("due"), last = "due")
        assertEquals("event:2", GreetingBlock.pick(p, noPlace, 8, afterDue)?.topic)
        val calendarOnly = parsed(pack(ended = facts(ended), today = facts(item("event:4", "Team sync at 3:00 PM."))))
        val afterCalendar = GreetingBlock.History(today = listOf("calendar"), last = "calendar")
        assertNull(GreetingBlock.pick(calendarOnly, noPlace, 8, afterCalendar))
    }

    @Test fun `a hello between two leads ends the run`() {
        val p = parsed(pack(due = facts(due)))
        val afterHello = GreetingBlock.History(today = listOf("due", GreetingBlock.HELLO), last = GreetingBlock.HELLO)
        assertEquals("due:1", GreetingBlock.pick(p, noPlace, 8, afterHello)?.topic)
    }

    @Test fun `body leads at most once a day`() {
        val p = parsed(pack(body = facts(workout)))
        assertEquals("body", GreetingBlock.lead(p, noPlace, 19)?.source)
        val laterToday = GreetingBlock.History(today = listOf("body", "due"), last = "due")
        assertNull(GreetingBlock.lead(p, noPlace, 19, laterToday))
        // The engine's body key is per day, so a raised workout is out through `said` as well.
        val raised = parsed(pack(body = facts(workout), said = listOf("body:2026-11-13")))
        assertNull(GreetingBlock.lead(raised, noPlace, 19))
    }

    @Test fun `six opens in one morning lead with three kinds, then say hello`() = runBlocking {
        // Invented: away from home, a workout not started, four budgets of which two are over.
        val away = GreetingPlace.read(GreetingTestSupport.signals())
        val start = parsed(pack(body = facts(workout), money = facts(onPace, near, over, over2)))
        var history = GreetingBlock.History.NONE
        val led = mutableListOf<String>()
        val said = mutableSetOf<String>()
        repeat(6) {
            val place = GreetingPlace.applyTopics(away, said, emptySet())
            val lead = GreetingBlock.lead(start.withSaid(said), place, 9, history)
            val kind = lead?.kind ?: GreetingBlock.HELLO
            led += kind
            lead?.let { said += it.item.topic }
            history = GreetingBlock.History(today = history.today + kind, last = kind)
        }
        val hello = GreetingBlock.HELLO
        assertEquals(listOf("place", "body", "money", hello, hello, hello), led)
    }

    @Test fun `topic keys map to their lead kinds`() {
        assertEquals("calendar", GreetingBlock.kindOfTopic("event:12:2026-11-13"))
        assertEquals("due", GreetingBlock.kindOfTopic("due:7"))
        assertEquals("body", GreetingBlock.kindOfTopic("body:2026-11-13"))
        assertEquals("money", GreetingBlock.kindOfTopic("money:2026-11:Fuel"))
        assertEquals("place", GreetingBlock.kindOfTopic("place:austin, texas"))
        assertNull(GreetingBlock.kindOfTopic("something"))
        assertEquals("calendar", GreetingBlock.kindOfSource("today_events"))
        assertEquals("calendar", GreetingBlock.kindOfSource("ended_events"))
        assertEquals("due", GreetingBlock.kindOfSource("due_soon"))
    }

    @Test fun `the lead is marked in the block and named as a subject, its facts never pasted into the rules`() {
        val p = parsed(pack(due = facts(due, item("due:3", "Lab report is due Friday."))))
        val lead = GreetingBlock.lead(p, noPlace, 8)!!
        val block = GreetingBlock.render(p, noPlace, morning, lead.item)
        assertTrue(block.lines().single { it.contains("Essay") }.endsWith(GreetingBlock.LEAD_MARK))
        assertFalse(block.lines().single { it.contains("Lab report") }.contains(GreetingBlock.LEAD_MARK))
        val prompt = GreetingPrompt.build(block, lead)
        assertTrue(prompt.contains("Your one subject: something he has due soon."))
        // The fact text is in the block once, and nowhere before it.
        val says = "Essay is due Friday, 5:00 PM."
        assertEquals(1, Regex(Regex.escape(says)).findAll(prompt).count())
        assertTrue(prompt.indexOf(says) > prompt.indexOf("FACTS PACK"))
        assertFalse(prompt.contains("This one is about money"))
    }

    @Test fun `a money lead carries no figure, the lead line is the category and the cap, nothing else`() {
        val p = parsed(pack(money = facts(onPace, over)))
        val lead = GreetingBlock.lead(p, noPlace, 13)!!
        val block = GreetingBlock.render(p, noPlace, morning, lead.item)
        val leadLine = block.lines().single { "Fuel" in it }
        assertEquals("    * Fuel: over its cap this month. ${GreetingBlock.LEAD_MARK}", leadLine)
        assertFalse(block.contains(overSays))
        val prompt = GreetingPrompt.build(block, lead)
        assertTrue(prompt.contains("Your one subject: his Fuel budget, which is over its cap."))
        assertTrue(prompt.contains("say no amount, no figure and no timing at all (no days, weeks or"))
        // 2026-11-16 (Kevin): "already" is allowed on a money lead; "again" stays banned.
        assertFalse(prompt.contains("no \"already\""))
        assertTrue(prompt.contains("so never \"again\""))
        val noCategory = GreetingBlock.moneyLeadLine(GreetingItem("money:x", "y"))
        assertEquals("One budget is over its cap this month.", noCategory)
    }

    @Test fun `place leads only on arrival, or on the day's first greeting`() = runBlocking {
        val away = GreetingPlace.read(GreetingTestSupport.signals())
        val here = away.items.single().topic
        val p = parsed(pack(due = facts(due)))
        // Same place as the last greeting, later in the day: not news.
        val sameLater = GreetingBlock.History(today = listOf("due"), last = "due", lastPlace = here)
        assertEquals("due:1", GreetingBlock.lead(p, away, 8, sameLater.copy(last = "calendar"))?.item?.topic)
        assertNull(GreetingBlock.lead(parsed(pack()), away, 8, sameLater))
        // Somewhere else at the last greeting (home, or another city): he has arrived.
        val fromHome = sameLater.copy(lastPlace = GreetingBlock.AT_HOME)
        assertEquals(here, GreetingBlock.lead(p, away, 8, fromHome)?.item?.topic)
        // The day's first greeting while away leads with the place even if nothing changed.
        assertEquals(here, GreetingBlock.lead(p, away, 8, GreetingBlock.History(lastPlace = here))?.item?.topic)
        // The key kept for each state of the place.
        assertEquals(here, GreetingBlock.placeKey(away))
        val home = GreetingPlace.read(GreetingTestSupport.signals(atHome = true))
        assertEquals(GreetingBlock.AT_HOME, GreetingBlock.placeKey(home))
        assertNull(GreetingBlock.placeKey(GreetingPlace.read(GreetingTestSupport.signals(fix = null))))
    }

    @Test fun `templates are trimmed before the block goes in, so nothing goes out indented`() {
        val p = parsed(pack(due = facts(due)))
        val lead = GreetingBlock.lead(p, noPlace, 8)!!
        val prompt = GreetingPrompt.build(GreetingBlock.render(p, noPlace, morning, lead.item), lead)
        assertTrue(prompt.startsWith("(System:"))
        assertTrue(prompt.lines().none { it.startsWith("        ") })
        assertFalse(prompt.contains("{"))
        val hello = GreetingPrompt.hello(listOf("x"))
        assertTrue(hello.startsWith("(System:"))
        assertFalse(hello.contains("{"))
    }

    @Test fun `the hello is asked not to reuse the last hellos, quoted safely`() {
        assertFalse(GreetingPrompt.hello().contains("Do not reuse"))
        val long = "a".repeat(200)
        val hello = GreetingPrompt.hello(listOf("He said \"hi\"\nthere", long, "  "))
        val line = hello.lines().single { it.startsWith("- Do not reuse this wording") }
        assertTrue(line, line.contains("\"He said hi there\""))
        assertTrue(line, line.contains("\"${"a".repeat(GreetingPrompt.MAX_AVOID_CHARS)}\""))
        assertFalse(line, line.contains("a".repeat(GreetingPrompt.MAX_AVOID_CHARS + 1)))
        assertTrue(hello.contains("nothing that refers to anything before this moment"))
    }

    @Test fun `the prompt forbids the things the rulings forbid and names no one`() {
        val p = parsed(pack(due = facts(due)))
        val lead = GreetingBlock.lead(p, noPlace, 8)!!
        val prompt = GreetingPrompt.build(GreetingBlock.render(p, noPlace, morning, lead.item), lead)
        listOf(
            // Kept (CLAUDE.md sections 1 and 7).
            "At most one question", "Mention nothing else", "nothing that is not in the facts", "never say it is clear",
            "never ask the same question twice", "Never \"two hours ago\"", "estimate", "absence", "streak",
            "use of this app", "Do not call any tool",
            // 2026-11-16: short and casual, a reaction, one rounded figure at most, never cents, no shame.
            "One short sentence, fifteen words at most", "one clause", "Casual and dry", "never read its facts out",
            "at most one, rounded", "Never cents", "never two amounts", "no shame, no nagging",
        ).forEach {
            assertTrue(it, prompt.contains(it))
        }
        assertFalse(prompt.contains("Lead with this one topic"))
        assertFalse(prompt.contains("Alfred"))
        assertFalse(prompt.contains("—"))
    }

    @Test fun `both greetings carry the no-name rule, with or without hellos to avoid`() {
        val p = parsed(pack(due = facts(due)))
        val lead = GreetingBlock.lead(p, noPlace, 8)!!
        val personal = GreetingPrompt.build(GreetingBlock.render(p, noPlace, morning, lead.item), lead)
        assertTrue(personal.contains(GreetingNames.rule(null)))
        assertTrue(GreetingPrompt.hello().contains(GreetingNames.rule(null)))
        // Five kept hellos (the most kept) do not crowd it out: it comes after them, whole.
        val five = listOf("Go on then.", "Well, look who it is.", "Ready when you are.", "Fire away.", "All ears.")
        val hello = GreetingPrompt.hello(five).lines()
        val avoidAt = hello.indexOfFirst { it.startsWith("- Do not reuse this wording") }
        val ruleAt = hello.indexOf(GreetingNames.rule(null))
        assertTrue(avoidAt >= 0)
        assertTrue("$avoidAt < $ruleAt", ruleAt > avoidAt)
        five.forEach { assertTrue(it, hello[avoidAt].contains(it)) }
    }

    @Test fun `with his name known, both prompts allow that name only`() {
        val p = parsed(pack(due = facts(due)))
        val lead = GreetingBlock.lead(p, noPlace, 8)!!
        val personal = GreetingPrompt.build(GreetingBlock.render(p, noPlace, morning, lead.item), lead, "Sam")
        val hello = GreetingPrompt.hello(name = "Sam")
        listOf(personal, hello).forEach { prompt ->
            assertTrue(prompt.contains(GreetingNames.rule("Sam")))
            assertFalse(prompt.contains(GreetingNames.rule(null)))
            assertFalse(prompt.contains("{name_rule}"))
        }
        val rule = GreetingNames.rule("Sam")
        listOf("no name other than Sam", "now and then", "invented name", "nickname", "title", "character name")
            .forEach { assertTrue(it, rule.contains(it)) }
    }

    @Test fun `a name in the last kept hello keeps it out of the next one`() {
        val used = GreetingPrompt.hello(listOf("Go on then.", "Morning, Sam. What's first?"), name = "Sam")
        assertTrue(used, used.contains("The last hello used his name, so leave it out of this one."))
        // His own name is not dropped from the avoid list: the model sees how it was used.
        assertTrue(used.lines().single { it.startsWith("- Do not reuse") }.contains("Sam"))
        // Only the newest counts: an older one with the name, or no name known, adds nothing.
        val older = GreetingPrompt.hello(listOf("Morning, Sam. What's first?", "Go on then."), name = "Sam")
        assertFalse(older.contains("The last hello used his name"))
        assertFalse(GreetingPrompt.hello(listOf("Go on then."), name = "Sam").contains("The last hello used his name"))
        assertFalse(GreetingPrompt.hello(listOf("Morning, Sam.")).contains("The last hello used his name"))
    }

    @Test fun `a kept hello that names him is never quoted back`() {
        val hello = GreetingPrompt.hello(listOf("What's up, Marcus?", "Go on then.", "Felix, all ears."))
        val line = hello.lines().single { it.startsWith("- Do not reuse this wording") }
        assertTrue(line, line.contains("\"Go on then.\""))
        assertFalse(line, line.contains("Marcus"))
        assertFalse(line, line.contains("Felix"))
        assertFalse(GreetingPrompt.hello(listOf("What's up, Marcus?")).contains("Do not reuse"))
    }

    @Test fun `the hello hands over no facts and forbids every subject`() {
        val hello = GreetingPrompt.hello()
        assertFalse(hello.contains("FACTS PACK"))
        assertFalse(hello.contains(GreetingBlock.LEAD_MARK))
        listOf(
            "No subject at all", "money, budgets", "workouts", "where he is", "the time or the day of the week",
            "Use nothing from any", "context note in this conversation", "one short sentence at most", "absence",
            "streak", "use of this app", "Do not call any tool",
        ).forEach { assertTrue(it, hello.contains(it)) }
        assertFalse(hello.contains("Alfred"))
        assertFalse(hello.contains("—"))
    }

    @Test fun `every subject is a few words and carries no figure`() {
        val subjects = listOf(
            GreetingBlock.Lead(GreetingPlace.SOURCE, GreetingItem("place:x", "He is in X.")),
            GreetingBlock.Lead("body", GreetingItem("body:d", "w", kind = "workout")),
            GreetingBlock.Lead("body", GreetingItem("body:d", "w", kind = "weigh_in")),
            GreetingBlock.Lead("money", GreetingItem("money:2026-11:Fuel", overSays)),
            GreetingBlock.Lead("money", GreetingItem("money:bad", "x")),
            GreetingBlock.Lead("due_soon", GreetingItem("due:1", "x")),
            GreetingBlock.Lead("ended_events", GreetingItem("event:1", "x")),
            GreetingBlock.Lead("today_events", GreetingItem("event:1", "x")),
        ).map { GreetingPrompt.subject(it) }
        subjects.forEach { assertFalse(it, it.any(Char::isDigit) || it.contains("$")) }
        assertEquals("his Fuel budget, which is over its cap", subjects[3])
        assertEquals("a budget that is over its cap", subjects[4])
    }
}
