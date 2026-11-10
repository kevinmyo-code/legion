package com.kevin.legion.openloops

import com.kevin.legion.openloops.FakeOpenLoopsBackend.Companion.DENTIST
import com.kevin.legion.openloops.FakeOpenLoopsBackend.Companion.DENTIST_BILLING
import com.kevin.legion.openloops.FakeOpenLoopsBackend.Companion.REGISTRATION
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.LocalDate

/**
 * ADR 0085 on the phone: the brief line's three states (proposals, none, unreadable), the voice
 * match read from the engine at call time, and the yes that pulls the new task in. Made-up tasks.
 */
class OpenLoopsControllerTest {

    private val engine = FakeOpenLoopsBackend()
    private val pulled = mutableListOf<String>()
    private var pullFails = false
    private val controller = OpenLoopsController(
        backend = { engine },
        afterTrack = { kind -> if (pullFails) error("offline") else pulled += kind },
    )

    @Before
    fun reset() {
        OpenLoopsController.Hint.pending = null
    }

    @After
    fun tearDown() {
        OpenLoopsController.Hint.pending = null
    }

    @Test
    fun `the spoken line names each task and its date, asks a question and never says when`() = runBlocking {
        engine.proposals += listOf(DENTIST, REGISTRATION)
        val line = controller.voiceLine()!!
        assertTrue(line.startsWith(OpenLoopsBrief.TITLE))
        assertTrue(line.contains("\"Call the dentist\""))
        assertTrue(line.contains("\"Renew the registration by November 20\" (date he named: Fri 20 Nov)"))
        assertTrue(line.contains("Ask once, as one short question"))
        assertTrue(line.contains("manage_item with action add") && line.contains("action remove"))
        assertTrue(line.contains("why_did_you_say_that with stop"))
        // Clause (c): no time words of its own, and it forbids them.
        for (word in listOf("ago", "yesterday", "last week", "days")) {
            assertFalse("line must not carry \"$word\"", line.contains(word))
        }
        assertTrue(line.contains("Never say when he said it, that it is still undone"))
        assertEquals(listOf("offer"), engine.calls)
    }

    @Test
    fun `none, switched off, or off on the engine is no line at all`() = runBlocking {
        assertNull(controller.voiceLine())
        engine.proposals += DENTIST
        engine.proposing = false
        assertNull(controller.voiceLine())
        engine.proposing = true
        engine.on = false
        assertNull(controller.voiceLine())
    }

    @Test
    fun `unreadable is a line that forbids the subject, never silence`() = runBlocking {
        engine.down = true
        assertEquals(OpenLoopsBrief.UNREADABLE_FOR_VOICE, controller.voiceLine())
        assertTrue(OpenLoopsBrief.UNREADABLE_FOR_VOICE.contains("say nothing about proposed to-dos"))
    }

    @Test
    fun `no engine on this phone is no line and no screen section`() = runBlocking {
        val none = OpenLoopsController(backend = { null })
        assertNull(none.voiceLine())
        assertNull(none.screenLine())
    }

    @Test
    fun `the screen line lists tasks with no instructions and counts nothing`() = runBlocking {
        engine.proposals += listOf(DENTIST, REGISTRATION)
        val line = controller.screenLine()!!
        assertEquals(
            "Call the dentist; Renew the registration by November 20 (Fri 20 Nov). " +
                "Track or dismiss each in Settings, Proactive speech.",
            line,
        )
        assertEquals(listOf("list"), engine.calls)
    }

    @Test
    fun `a spoken task matches only a proposal whose words it says, read from the engine now`() = runBlocking {
        engine.proposals += listOf(DENTIST, REGISTRATION)
        assertEquals(OpenLoopsController.Match.One(DENTIST), controller.match("call the dentist"))
        assertEquals(OpenLoopsController.Match.One(DENTIST), controller.match("Call dentist"))
        // The date may ride in the date argument instead of the words.
        assertEquals(OpenLoopsController.Match.One(REGISTRATION), controller.match("renew the registration"))
        // Equality, not containment: more words is a different task.
        assertEquals(OpenLoopsController.Match.None, controller.match("call the dentist office"))
        assertEquals(OpenLoopsController.Match.None, controller.match("dentist"))
        assertEquals(OpenLoopsController.Match.None, controller.match("buy milk"))
        assertEquals(6, engine.calls.count { it == "list" })
    }

    @Test
    fun `two proposals a phrase names are ambiguous`() = runBlocking {
        engine.proposals += listOf(DENTIST, DENTIST_BILLING, REGISTRATION, FakeOpenLoopsBackend.REGISTRATION_DEC)
        assertEquals(OpenLoopsController.Match.One(DENTIST_BILLING), controller.match("call the dentist billing desk"))
        assertTrue(controller.match("renew the registration") is OpenLoopsController.Match.Many)
    }

    @Test
    fun `once a list read shows none, manage_item does not ask the engine again`() = runBlocking {
        controller.view()
        engine.calls.clear()
        assertEquals(OpenLoopsController.Match.None, controller.match("call the dentist"))
        assertTrue(engine.calls.isEmpty())
    }

    @Test
    fun `a yes tracks it and pulls the aspect it landed in`() = runBlocking {
        engine.proposals += listOf(DENTIST, REGISTRATION)
        val undated = controller.track(DENTIST.id)
        val dated = controller.track(REGISTRATION.id)
        assertTrue(undated.ok && dated.ok)
        assertEquals(listOf("todo", "reminder"), pulled)
        assertEquals(listOf("p1" to null, "p2" to null), engine.tracked)
    }

    @Test
    fun `a date from his answer is passed through, never made up`() = runBlocking {
        engine.proposals += DENTIST
        controller.track(DENTIST.id, LocalDate.of(2026, 11, 13))
        assertEquals(listOf("p1" to LocalDate.of(2026, 11, 13)), engine.tracked)
    }

    @Test
    fun `a failed pull still says tracked, and when it reaches the phone`() = runBlocking {
        engine.proposals += DENTIST
        pullFails = true
        val answer = controller.track(DENTIST.id)
        assertTrue(answer.ok)
        assertTrue(answer.words.endsWith(OpenLoopsController.NEXT_SYNC))
    }

    @Test
    fun `an engine refusal or an outage changes nothing and says so`() = runBlocking {
        val refused = controller.track("nope")
        assertFalse(refused.ok)
        assertTrue(refused.words.startsWith("Nothing was changed"))
        engine.down = true
        val down = controller.dismiss("p1")
        assertFalse(down.ok)
        assertTrue(down.words.contains("could not be reached"))
        assertTrue(pulled.isEmpty())
    }

    @Test
    fun `stop proposing reaches the engine switch`() = runBlocking {
        val answer = controller.setProposing(false)
        assertTrue(answer.ok)
        assertFalse(engine.proposing)
        assertEquals(false, OpenLoopsController.Hint.pending)
    }

    @Test
    fun `the silence instruction and its usual phrasings name this, and the briefs' do not`() {
        assertTrue(OpenLoopsBrief.namesThis(OpenLoopsBrief.SILENCE_INSTRUCTION))
        for (said in listOf("task proposals", "Stop proposing to-dos", "proposed tasks", "open loops")) {
            assertTrue(said, OpenLoopsBrief.namesThis(said))
        }
        for (said in listOf("morning brief", "home brief", "the weather")) {
            assertFalse(said, OpenLoopsBrief.namesThis(said))
        }
    }

    @Test
    fun `the words a key is made of match the engine's own`() {
        assertEquals(setOf("call", "dentist"), OpenLoopsWords.content("I need to call the dentist"))
        assertEquals(OpenLoopsWords.content("Call the dentist"), OpenLoopsWords.content("dentist, call"))
    }
}
