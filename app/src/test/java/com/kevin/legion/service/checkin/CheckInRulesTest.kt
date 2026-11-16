package com.kevin.legion.service.checkin

import com.kevin.legion.greeting.FactsPack
import com.kevin.legion.greeting.FactsParser
import com.kevin.legion.greeting.GreetingBlock
import com.kevin.legion.greeting.GreetingItem
import com.kevin.legion.greeting.GreetingPlace
import com.kevin.legion.greeting.GreetingSource
import com.kevin.legion.greeting.SourceState
import com.kevin.legion.service.ProactiveCategory
import com.kevin.legion.service.cascade.ScriptedLines
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneOffset
import kotlin.random.Random

/**
 * Listening check-ins (alfred-greets ticket 03): the limits, the four moments, one retry then
 * drop, and the closers. Every fact here is invented.
 */
class CheckInRulesTest {
    private val zone = ZoneOffset.UTC
    private val noon = LocalDateTime.of(2030, 3, 4, 12, 0)

    private fun ms(t: LocalDateTime) = t.toInstant(zone).toEpochMilli()

    private fun now(
        time: LocalTime = LocalTime.NOON,
        switchOn: Boolean = true,
        call: Boolean = false,
        matrix: Boolean = false,
        busy: Boolean = false,
        mayListen: Boolean = true,
        silenced: Boolean = false,
        meeting: Boolean = false,
        listener: Boolean = true,
    ) = CheckInRules.Now(switchOn, time, call, matrix, busy, mayListen, silenced, meeting, listener)

    // ------------------------------------------------------------------ the limits

    @Test
    fun `a plain afternoon may check in`() {
        assertNull(CheckInRules.refusal(now()))
    }

    @Test
    fun `every limit refuses on its own`() {
        assertEquals("switched_off", CheckInRules.refusal(now(switchOn = false)))
        assertEquals("call", CheckInRules.refusal(now(call = true)))
        assertEquals("matrix", CheckInRules.refusal(now(matrix = true)))
        assertEquals("conversation", CheckInRules.refusal(now(busy = true)))
        assertEquals("cannot_listen", CheckInRules.refusal(now(mayListen = false)))
        assertEquals("silenced", CheckInRules.refusal(now(silenced = true)))
        assertEquals("meeting", CheckInRules.refusal(now(meeting = true)))
        assertEquals("no_service", CheckInRules.refusal(now(listener = false)))
    }

    @Test
    fun `quiet hours run 10pm to 8am and wrap midnight`() {
        assertEquals("quiet_hours", CheckInRules.refusal(now(time = LocalTime.of(22, 0))))
        assertEquals("quiet_hours", CheckInRules.refusal(now(time = LocalTime.of(3, 0))))
        assertEquals("quiet_hours", CheckInRules.refusal(now(time = LocalTime.of(7, 59))))
        assertNull(CheckInRules.refusal(now(time = LocalTime.of(8, 0))))
        assertNull(CheckInRules.refusal(now(time = LocalTime.of(21, 59))))
    }

    @Test
    fun `only a question opens the window`() {
        assertTrue(CheckInRules.isQuestion("Did the run go to plan?"))
        assertTrue(CheckInRules.isQuestion("Lab report due at five. Started on it? "))
        assertFalse(CheckInRules.isQuestion("The lab report is due at five."))
        assertFalse(CheckInRules.isQuestion(""))
    }

    @Test
    fun `a no to one family never silences another`() {
        assertEquals("check_in_events", CheckInRules.ruleId("ended_events"))
        assertEquals("check_in_events", CheckInRules.ruleId("today_events"))
        assertEquals("check_in_due", CheckInRules.ruleId("due_soon"))
        assertEquals("check_in_body", CheckInRules.ruleId("body"))
        assertEquals("check_in_money", CheckInRules.ruleId("money"))
        assertEquals("check_in_place", CheckInRules.ruleId(GreetingPlace.SOURCE))
    }

    @Test
    fun `check-ins have their own switch`() {
        assertEquals("check_ins", ProactiveCategory.CHECK_INS.key)
        assertTrue(ProactiveCategory.CHECK_INS.hasContent)
    }

    // ------------------------------------------------------------------ the moments

    private fun source(name: String, vararg items: GreetingItem, says: String = "on file") =
        GreetingSource(name, SourceState.FACTS, says, items = items.toList())

    private fun empty(name: String) = GreetingSource(name, SourceState.EMPTY, "nothing on file")

    private fun pack(vararg sources: GreetingSource, said: Set<String> = emptySet(), closed: Set<String> = emptySet()) =
        FactsPack(
            today = "2030-03-04",
            utcOffset = "+00:00",
            zoneNote = null,
            sources = FactsParser.ENGINE_SOURCES.map { n -> sources.firstOrNull { it.name == n } ?: empty(n) },
            said = said,
            closed = closed,
            topicsRead = true,
            topicsReason = null,
        )

    private val home = GreetingSource(GreetingPlace.SOURCE, SourceState.EMPTY, "He is at home.")

    private fun ended(topic: String, endedAt: LocalDateTime) =
        GreetingItem(topic, "Invented seminar ended.", endedAtMs = ms(endedAt))

    private fun pick(
        p: FactsPack,
        at: LocalDateTime = noon,
        trigger: CheckInMoments.Trigger = CheckInMoments.Trigger.TICK,
        ledger: CheckInLedger = CheckInLedger(),
        place: GreetingSource = home,
    ) = CheckInMoments.pick(p, place, at, trigger, ledger, zone)

    @Test
    fun `an event that ended 30 to 60 minutes ago is a moment, and only then`() {
        val p = pack(source("ended_events", ended("event:a", noon.minusMinutes(45))))
        assertEquals(CheckInMoment.EVENT_ENDED, pick(p)!!.moment)
        assertNull(pick(pack(source("ended_events", ended("event:a", noon.minusMinutes(10))))))
        assertNull(pick(pack(source("ended_events", ended("event:a", noon.minusMinutes(90))))))
    }

    @Test
    fun `something due within a day is a moment`() {
        val due = GreetingItem("due:x", "Invented essay due at 5pm.", passesAtMs = ms(noon.plusHours(5)))
        val got = pick(pack(source("due_soon", due)))!!
        assertEquals(CheckInMoment.DUE_SOON, got.moment)
        assertEquals("due_soon", got.sourceName)
    }

    @Test
    fun `away from home is a moment, an unknown home is not`() {
        val item = GreetingItem("place:springfield", "He is in Springfield, away from home.", kind = "place")
        val away = source(GreetingPlace.SOURCE, item, says = GreetingPlace.AWAY_SAYS)
        assertEquals(CheckInMoment.ARRIVED, pick(pack(), place = away)!!.moment)
        val unknown = source(GreetingPlace.SOURCE, item, says = "No home place is saved.")
        assertNull(pick(pack(), place = unknown))
    }

    @Test
    fun `the morning moment is the first unlock between 8 and 11, once a day`() {
        val body = GreetingItem("body:2030-03-04", "Invented: leg day planned.", kind = "workout")
        val p = pack(source("body", body))
        val morning = LocalDateTime.of(2030, 3, 4, 9, 0)
        assertEquals(CheckInMoment.MORNING, pick(p, morning, CheckInMoments.Trigger.UNLOCK)!!.moment)
        assertNull(pick(p, morning, CheckInMoments.Trigger.TICK))
        assertNull(pick(p, morning, CheckInMoments.Trigger.UNLOCK, CheckInLedger(morningDay = "2030-03-04")))
        assertNull(pick(p, LocalDateTime.of(2030, 3, 4, 11, 30), CheckInMoments.Trigger.UNLOCK))
    }

    @Test
    fun `the morning moment keeps the greeting's caps`() {
        val body = GreetingItem("body:2030-03-04", "Invented: leg day planned.", kind = "workout")
        val p = pack(source("body", body))
        val morning = LocalDateTime.of(2030, 3, 4, 9, 0)
        val unlock = CheckInMoments.Trigger.UNLOCK
        val ledger = CheckInLedger()
        // Body leads at most once a day: a greeting already led with it today, so no morning ask.
        val ledToday = GreetingBlock.History(today = listOf("body"), last = "body")
        assertNull(CheckInMoments.pick(p, home, morning, unlock, ledger, zone, ledToday))
        assertEquals(
            CheckInMoment.MORNING,
            CheckInMoments.pick(p, home, morning, unlock, ledger, zone, GreetingBlock.History.NONE)!!.moment,
        )
    }

    @Test
    fun `a topic raised, answered or silenced on the engine is never asked`() {
        val e = ended("event:a", noon.minusMinutes(45))
        assertNull(pick(pack(source("ended_events", e), said = setOf("event:a"))))
        assertNull(pick(pack(source("ended_events", e), closed = setOf("event:a"))))
        assertNull(pick(pack(source("ended_events", e.copy(raised = true)))))
    }

    @Test
    fun `two check-ins never land within the pacing gap`() {
        val p = pack(source("ended_events", ended("event:a", noon.minusMinutes(45))))
        assertNull(pick(p, ledger = CheckInLedger(lastRaisedAtMs = ms(noon.minusMinutes(10)))))
    }

    // ------------------------------------------------------------------ one retry, then drop

    @Test
    fun `no reply once allows one retry after the gap, then the topic is dropped`() {
        val p = pack(source("ended_events", ended("event:a", noon.minusMinutes(45))))
        val t0 = ms(noon)
        var ledger = CheckInLedger().raised("event:a", "ended_events", CheckInMoment.EVENT_ENDED, t0)
        val (afterFirst, doneFirst) = ledger.outcome("event:a", CheckInOutcome.NO_REPLY)
        assertFalse(doneFirst)
        ledger = afterFirst

        // Not before the retry gap, and not as a fresh topic either.
        assertNull(pick(p, noon.plusMinutes(60), ledger = ledger))
        val retry = pick(p, noon.plusHours(2), ledger = ledger)!!
        assertEquals("event:a", retry.item.topic)
        assertEquals(CheckInMoment.EVENT_ENDED, retry.moment)

        ledger = ledger.raised("event:a", "ended_events", CheckInMoment.EVENT_ENDED, ms(noon.plusHours(2)))
        val (afterSecond, doneSecond) = ledger.outcome("event:a", CheckInOutcome.NO_REPLY)
        assertTrue(doneSecond)
        assertNull(pick(p, noon.plusHours(6), ledger = afterSecond))
    }

    @Test
    fun `an answer or a statement closes the topic at once`() {
        val l = CheckInLedger().raised("due:x", "due_soon", CheckInMoment.DUE_SOON, 1L)
        assertTrue(l.outcome("due:x", CheckInOutcome.ANSWERED).second)
        assertTrue(l.outcome("due:x", CheckInOutcome.STATEMENT).second)
    }

    @Test
    fun `a raise that never played counts no ask but still waits the retry gap`() {
        val p = pack(source("ended_events", ended("event:a", noon.minusMinutes(45))))
        val l = CheckInLedger().raised("event:a", "ended_events", CheckInMoment.EVENT_ENDED, ms(noon))
        assertEquals(0, l.topics.getValue("event:a").asks)
        assertNull(pick(p, noon.plusMinutes(40), ledger = l))
    }

    @Test
    fun `the ledger survives a round trip and forgets old topics`() {
        val l = CheckInLedger(morningDay = "2030-03-04", lastRaisedAtMs = 9L)
            .raised("due:x", "due_soon", CheckInMoment.DUE_SOON, 100L)
            .outcome("due:x", CheckInOutcome.NO_REPLY).first
        val back = CheckInLedgerJson.parse(CheckInLedgerJson.write(l))
        assertEquals(l, back)
        assertTrue(back.pruned(100L + CheckInLedger.KEEP_MS + 1).topics.isEmpty())
    }

    // ------------------------------------------------------------------ the prompt and the closers

    @Test
    fun `the prompt asks one question, forbids his absence and calls no tool`() {
        val lead = GreetingItem("due:x", "Invented essay due at 5pm.")
        val prompt = CheckInPrompt.build("FACTS PACK. invented", lead, CheckInMoment.DUE_SOON)
        assertTrue(prompt.contains(lead.says))
        assertTrue(prompt.contains("ends with a question mark"))
        assertTrue(prompt.contains("Never mention his absence"))
        assertTrue(prompt.contains("Do not call any tool"))
        assertTrue(prompt.contains("FACTS PACK. invented"))
        assertTrue(prompt.contains("Say no amount of money"))
    }

    @Test
    fun `closers never repeat back to back and never name the assistant`() {
        val r = Random(7)
        var last: String? = null
        repeat(200) {
            val next = ClosingLines.pick(last, r)
            assertNotEquals(last, next)
            last = next
        }
        ClosingLines.LINES.forEach { line ->
            assertFalse(line.contains("Alfred"))
            assertFalse(line.contains("later", ignoreCase = true)) // no promise of a next time
            assertTrue(line in ScriptedLines.KEEP) // kept as audio on disk, free from the second use
        }
    }

    @Test
    fun `the engine's ended_at is read for ended events`() {
        val json = """
            {"today":"2030-03-04","utc_offset":"+00:00","topics":{"state":"read"},"said":[],"closed":[],
             "sources":{"ended_events":{"state":"facts","says":"1","items":[
               {"topic":"event:a","says":"Invented seminar.","ended_at":"2030-03-04T11:15:00+00:00"}]}}}
        """.trimIndent()
        val item = FactsParser.parse(json).sources.first { it.name == "ended_events" }.items.single()
        assertEquals(ms(LocalDateTime.of(2030, 3, 4, 11, 15)), item.endedAtMs)
    }
}
