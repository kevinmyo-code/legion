package com.kevin.legion.service

import com.kevin.legion.service.ChatEntry.Kind
import com.kevin.legion.service.ChatEntry.Via
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The assistant panel's reducers: tagging, overlap outcomes, session-only lifetime. Plain JVM. */
class ChatTranscriptTest {

    private fun kinds(t: ChatTranscript) = t.entries.map { it.kind }

    @Test
    fun `a typed turn shows the question, then the reply tagged shown not spoken`() {
        val t = ChatTranscript().typedSent("when did we last buy shampoo")
        assertEquals("", t.pendingTypedReply)
        val done = t.typedReplied("You logged shampoo on Sep 28.")
        assertNull(done.pendingTypedReply)
        assertEquals(listOf(Kind.USER, Kind.ASSISTANT), kinds(done))
        assertEquals(Via.TYPED, done.entries[0].via)
        assertEquals(Via.TYPED, done.entries[1].via) // for an assistant line TYPED means "shown, not spoken"
        assertEquals("You logged shampoo on Sep 28.", done.entries[1].text)
    }

    @Test
    fun `a spoken turn adds both lines tagged spoken`() {
        val t = ChatTranscript().turnComplete("what is on today", "Soccer at five.", typed = false)
        assertEquals(listOf(Kind.USER, Kind.ASSISTANT), kinds(t))
        assertTrue(t.entries.all { it.via == Via.SPOKEN })
    }

    @Test
    fun `a spoken turn with no transcript invents no user line`() {
        val t = ChatTranscript().turnComplete("", "Hello.", typed = false)
        assertEquals(listOf(Kind.ASSISTANT), kinds(t))
    }

    @Test
    fun `a typed turn that fails says what did not happen, not that it was not sent`() {
        val t = ChatTranscript().typedSent("hi")
            .typedFailed("Not answered: couldn't reach Gemini. Nothing was changed.")
        assertNull(t.pendingTypedReply)
        assertEquals(listOf(Kind.USER, Kind.SYSTEM), kinds(t))
        assertEquals("Not answered: couldn't reach Gemini. Nothing was changed.", t.entries[1].text)
    }

    @Test
    fun `a spoken turn finishing while a typed reply is pending leaves the typed one pending`() {
        // The typed reply comes from the text model, not the voice socket: voice does not cancel it.
        val t = ChatTranscript().typedSent("q").turnComplete("never mind", "Okay.", typed = false)
        assertEquals("", t.pendingTypedReply)
        assertEquals(listOf(Kind.USER, Kind.USER, Kind.ASSISTANT), kinds(t))
        val done = t.typedReplied("Here you are.")
        assertEquals(Kind.ASSISTANT, done.entries.last().kind)
        assertEquals(Via.TYPED, done.entries.last().via)
    }

    @Test
    fun `the history for the text model is every user and assistant line, typed or spoken`() {
        val t = ChatTranscript()
            .turnComplete("what's on tomorrow", "Dentist at nine.", typed = false)
            .tool("list_events", ok = true)
            .notSent("x", "Not sent: there is no internet connection.")
            .typedSent("move it to ten")
            .typedReplied("Moved to ten.")
        assertEquals(
            listOf(
                ChatTurn(true, "what's on tomorrow"),
                ChatTurn(false, "Dentist at nine."),
                ChatTurn(true, "move it to ten"),
                ChatTurn(false, "Moved to ten."),
            ),
            t.textHistory(),
        )
    }

    @Test
    fun `a message that was not sent carries its text and the reason in words`() {
        val t = ChatTranscript().notSent("add milk", "The assistant isn't set up: add a Gemini key in Setup.")
        val e = t.entries.single()
        assertEquals(Kind.NOT_SENT, e.kind)
        assertEquals("add milk", e.text)
        assertEquals("The assistant isn't set up: add a Gemini key in Setup.", e.note)
        assertNull(t.pendingTypedReply)
    }


    @Test
    fun `a successful tool gets a plain line, a failed one says it did not run with the tool's own words`() {
        val ok = ChatTranscript().tool("add_to_checklist", ok = true)
        assertEquals("Add to checklist", ok.entries.single().text)
        assertNull(ok.entries.single().note)
        val bad = ChatTranscript().tool("add_to_checklist", ok = false, detail = "No list called groceries.")
        assertEquals("Did not run: No list called groceries.", bad.entries.single().note)
    }

    @Test
    fun `a voice tool line on the first turn after a close survives that turn`() {
        val ended = ChatTranscript().turnComplete("a", "b", typed = false).sessionEnded()
        val t = ended.tool("get_current_time", ok = true).turnComplete("time?", "Four.", typed = false)
        assertEquals(listOf(Kind.TOOL, Kind.USER, Kind.ASSISTANT), kinds(t))
    }

    @Test
    fun `a typed tool line after the voice socket closed does not wipe the panel`() {
        val t = ChatTranscript().turnComplete("a", "b", typed = false)
            .typedSent("q")
            .sessionEnded()
            .tool("log_meal", ok = true)
        assertEquals(listOf(Kind.USER, Kind.ASSISTANT, Kind.USER, Kind.SYSTEM, Kind.TOOL), kinds(t))
    }

    @Test
    fun `a closed session keeps what was said and says it ended`() {
        val t = ChatTranscript().turnComplete("a", "b", typed = false).sessionEnded()
        assertTrue(t.ended)
        assertEquals("Conversation ended.", t.entries.last().text)
        assertEquals(3, t.entries.size)
    }

    @Test
    fun `an empty panel stays empty when the session closes, and ending twice adds one line`() {
        assertTrue(ChatTranscript().sessionEnded().isEmpty)
        val once = ChatTranscript().turnComplete("a", "b", typed = false).sessionEnded()
        assertEquals(once, once.sessionEnded())
    }

    @Test
    fun `a voice socket closing leaves a pending typed reply alone`() {
        val t = ChatTranscript().turnComplete("a", "b", typed = false).typedSent("q").sessionEnded()
        assertEquals("", t.pendingTypedReply)
        assertEquals("Conversation ended.", t.entries.last().text)
    }

    @Test
    fun `a socket closing under a typed-only chat adds nothing`() {
        // The prewarm socket cycles every couple of minutes; a typed chat is not a voice conversation.
        val t = ChatTranscript().typedSent("q").typedReplied("a")
        assertEquals(t, t.sessionEnded())
    }

    @Test
    fun `a typed message after the voice socket closed continues the panel instead of wiping it`() {
        val ended = ChatTranscript().turnComplete("remember my tyre pressure is 35", "Noted.", typed = false)
            .sessionEnded()
        val next = ended.typedSent("what did I just tell you")
        assertFalse(next.ended)
        assertEquals(
            listOf(Kind.USER, Kind.ASSISTANT, Kind.SYSTEM, Kind.USER),
            kinds(next),
        )
        // And the text model sees the spoken exchange above the marker.
        assertEquals(3, next.textHistory().size)
    }

    @Test
    fun `the first spoken line of a new voice session starts the panel afresh`() {
        val ended = ChatTranscript().turnComplete("a", "b", typed = false).sessionEnded()
        val next = ended.turnComplete("new question", "", typed = false)
        assertFalse(next.ended)
        assertEquals(listOf(Kind.USER), kinds(next))
    }

    @Test
    fun `entry ids keep increasing across a fresh start so list keys never collide`() {
        val ended = ChatTranscript().turnComplete("a", "b", typed = false).sessionEnded()
        val next = ended.turnComplete("x", "", typed = false)
        assertTrue(next.entries.single().id > ended.entries.last().id)
    }

    @Test
    fun `the panel is bounded`() {
        var t = ChatTranscript()
        repeat(ChatTranscript.MAX_ENTRIES + 25) { t = t.tool("get_current_time", ok = true) }
        assertEquals(ChatTranscript.MAX_ENTRIES, t.entries.size)
    }
}
