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
        val t = ChatTranscript()
            .typedSent("when did we last buy shampoo")
            .typedReplyProgress("You logged")
        assertEquals("You logged", t.pendingTypedReply)
        val done = t.turnComplete(
            heard = "when did we last buy shampoo",
            said = "You logged shampoo on Sep 28.",
            typed = true,
        )
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
    fun `a typed turn that comes back with no text says so instead of showing nothing`() {
        val t = ChatTranscript().typedSent("hi").turnComplete("hi", "", typed = true)
        assertEquals(listOf(Kind.USER, Kind.SYSTEM), kinds(t))
        assertEquals("No written reply came back for that.", t.entries[1].text)
    }

    @Test
    fun `typing over speech adds the stopped-speaking line`() {
        val t = ChatTranscript().interruptedByTyping()
        assertEquals("Stopped speaking because you typed.", t.entries.single().text)
    }

    @Test
    fun `push-to-talk over a pending typed reply keeps the partial text and says voice took over`() {
        val t = ChatTranscript().typedSent("q").typedReplyProgress("Partial answer").voiceTookOver()
        assertNull(t.pendingTypedReply)
        val partial = t.entries.last()
        assertEquals(Kind.ASSISTANT, partial.kind)
        assertEquals("Partial answer", partial.text)
        assertEquals("Stopped because you started talking.", partial.note)
    }

    @Test
    fun `voice taking over with nothing pending changes nothing`() {
        val t = ChatTranscript().turnComplete("a", "b", typed = false)
        assertEquals(t, t.voiceTookOver())
    }

    @Test
    fun `a spoken turn finishing while a typed reply was pending closes the typed one out`() {
        val t = ChatTranscript().typedSent("q").turnComplete("never mind", "Okay.", typed = false)
        assertNull(t.pendingTypedReply)
        assertEquals(listOf(Kind.USER, Kind.SYSTEM, Kind.USER, Kind.ASSISTANT), kinds(t))
    }

    @Test
    fun `typing again while a reply is pending cuts the first one off`() {
        val t = ChatTranscript().typedSent("one").typedReplyProgress("Half").typedSent("two")
        assertEquals(listOf(Kind.USER, Kind.ASSISTANT, Kind.USER), kinds(t))
        assertEquals("Cut off because you sent another message.", t.entries[1].note)
        assertEquals("", t.pendingTypedReply)
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
    fun `a queued message that then fails to connect clears the pending reply and says so`() {
        val t = ChatTranscript().typedSent("hi").notSent("", "Not sent: couldn't connect.", clearPending = true)
        assertNull(t.pendingTypedReply)
        assertEquals(Kind.NOT_SENT, t.entries.last().kind)
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
    fun `a session that ends with a reply half-arrived does not drop the partial`() {
        val t = ChatTranscript().typedSent("q").typedReplyProgress("Partway").sessionEnded()
        assertNull(t.pendingTypedReply)
        assertTrue(t.entries.any { it.text == "Partway" && it.note != null })
    }

    @Test
    fun `the first line of a new session starts the panel afresh`() {
        val ended = ChatTranscript().turnComplete("a", "b", typed = false).sessionEnded()
        val next = ended.typedSent("new question")
        assertFalse(next.ended)
        assertEquals(listOf(Kind.USER), kinds(next))
    }

    @Test
    fun `entry ids keep increasing across a fresh start so list keys never collide`() {
        val ended = ChatTranscript().turnComplete("a", "b", typed = false).sessionEnded()
        val next = ended.typedSent("x")
        assertTrue(next.entries.single().id > ended.entries.last().id)
    }

    @Test
    fun `the panel is bounded`() {
        var t = ChatTranscript()
        repeat(ChatTranscript.MAX_ENTRIES + 25) { t = t.interruptedByTyping() }
        assertEquals(ChatTranscript.MAX_ENTRIES, t.entries.size)
    }
}
