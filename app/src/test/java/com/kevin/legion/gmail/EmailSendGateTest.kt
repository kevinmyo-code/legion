package com.kevin.legion.gmail

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** ADR 0086's send gate: only a draft from this conversation, only on the user's own word send. */
class EmailSendGateTest {

    private var now = 1_000_000L
    private val gate = EmailSendGate { now }

    private fun drafted(id: String = "d1") =
        gate.register(id, "m-$id", "jane.roe@example.com", "Running late", "email jane that i'm running late")

    private fun refused(d: EmailSendGate.Decision) = (d as EmailSendGate.Decision.Refused).message

    @Test
    fun `send after the read-back with the word send goes ahead`() {
        drafted()
        val d = gate.check("d1", "send it")
        assertTrue(d is EmailSendGate.Decision.Go)
        assertEquals("jane.roe@example.com", (d as EmailSendGate.Decision.Go).draft.to)
    }

    @Test
    fun `an id this conversation did not make is refused`() {
        drafted()
        assertEquals(EmailSendGate.UNKNOWN_DRAFT, refused(gate.check("made-up", "send")))
    }

    @Test
    fun `a missing id is refused`() {
        drafted()
        assertEquals(EmailSendGate.NO_ID, refused(gate.check("", "send")))
    }

    @Test
    fun `the drafting turn cannot also send`() {
        drafted()
        assertEquals(EmailSendGate.NOT_OFFERED_YET, refused(gate.check("d1", "email jane that I'm running late")))
    }

    @Test
    fun `unheard words are not a send`() {
        drafted()
        assertEquals(EmailSendGate.UNHEARD, refused(gate.check("d1", "")))
        assertEquals(EmailSendGate.UNHEARD, refused(gate.check("d1", null)))
    }

    @Test
    fun `a bare yes or a check is not a send`() {
        drafted()
        assertTrue(refused(gate.check("d1", "yes")).startsWith("Not sent"))
        assertTrue(refused(gate.check("d1", "let me check it first")).startsWith("Not sent"))
        assertTrue(refused(gate.check("d1", "don't send it")).startsWith("Not sent"))
        assertTrue(refused(gate.check("d1", "should I send it?")).startsWith("Not sent"))
    }

    @Test
    fun `the same words cannot try twice`() {
        drafted()
        assertTrue(gate.check("d1", "send") is EmailSendGate.Decision.Go)
        assertEquals(EmailSendGate.SAY_SEND_AGAIN, refused(gate.check("d1", "send")))
        assertTrue(gate.check("d1", "yes send it") is EmailSendGate.Decision.Go)
    }

    @Test
    fun `a consumed draft can never send again`() {
        drafted()
        assertTrue(gate.check("d1", "send") is EmailSendGate.Decision.Go)
        gate.consume("d1")
        assertEquals(EmailSendGate.UNKNOWN_DRAFT, refused(gate.check("d1", "send it now")))
    }

    @Test
    fun `a draft older than the conversation window is forgotten`() {
        drafted()
        now += EmailSendGate.TTL_MS + 1
        assertEquals(EmailSendGate.UNKNOWN_DRAFT, refused(gate.check("d1", "send")))
    }

    @Test
    fun `find with no id is the newest draft`() {
        drafted("d1")
        drafted("d2")
        assertEquals("d2", gate.find(null)?.draftId)
        assertEquals("d1", gate.find("d1")?.draftId)
    }

    @Test
    fun `explicit send wording`() {
        assertTrue(EmailSendGate.isExplicitSend("Send."))
        assertTrue(EmailSendGate.isExplicitSend("yeah send it"))
        assertTrue(EmailSendGate.isExplicitSend("go ahead and send it"))
        assertFalse(EmailSendGate.isExplicitSend("go ahead"))
        assertFalse(EmailSendGate.isExplicitSend("send it after lunch"))
        assertFalse(EmailSendGate.isExplicitSend("can you send it"))
        assertFalse(EmailSendGate.isExplicitSend("no wait"))
        assertFalse(EmailSendGate.isExplicitSend("send it to Bob instead"))
    }
}
