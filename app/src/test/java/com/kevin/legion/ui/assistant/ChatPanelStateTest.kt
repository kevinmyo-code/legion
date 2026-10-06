package com.kevin.legion.ui.assistant

import com.kevin.legion.service.ChatEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The chat panel's open flag and unread dot. Plain JVM. */
class ChatPanelStateTest {

    private fun typedReply(id: Long) = ChatEntry(id, ChatEntry.Kind.ASSISTANT, "r", ChatEntry.Via.TYPED)
    private fun spokenReply(id: Long) = ChatEntry(id, ChatEntry.Kind.ASSISTANT, "r", ChatEntry.Via.SPOKEN)
    private fun typedQuestion(id: Long) = ChatEntry(id, ChatEntry.Kind.USER, "q", ChatEntry.Via.TYPED)

    @Test
    fun `starts closed with no dot`() {
        val s = ChatPanelState()
        assertFalse(s.open)
        assertFalse(s.unread)
    }

    @Test
    fun `a typed reply while closed sets the dot and does not open the panel`() {
        val s = ChatPanelState()
        s.observe(emptyList())
        s.observe(listOf(typedQuestion(1)))
        assertFalse(s.unread)
        s.observe(listOf(typedQuestion(1), typedReply(2)))
        assertTrue(s.unread)
        assertFalse(s.open)
    }

    @Test
    fun `opening clears the dot`() {
        val s = ChatPanelState()
        s.observe(emptyList())
        s.observe(listOf(typedReply(1)))
        assertTrue(s.unread)
        s.show()
        assertTrue(s.open)
        assertFalse(s.unread)
    }

    @Test
    fun `a reply while open never leaves a dot behind`() {
        val s = ChatPanelState()
        s.observe(emptyList())
        s.show()
        s.observe(listOf(typedReply(1)))
        s.hide()
        assertFalse(s.unread)
        assertFalse(s.open)
    }

    @Test
    fun `a spoken reply does not set the dot`() {
        val s = ChatPanelState()
        s.observe(emptyList())
        s.observe(listOf(spokenReply(1)))
        assertFalse(s.unread)
    }

    @Test
    fun `a conversation already there at the first look is not new`() {
        val s = ChatPanelState()
        s.observe(listOf(typedQuestion(1), typedReply(2)))
        assertFalse(s.unread)
    }

    @Test
    fun `new conversation restarts ids and the next reply still counts`() {
        val s = ChatPanelState()
        s.observe(emptyList())
        s.observe(listOf(typedQuestion(1), typedReply(2), typedQuestion(3)))
        s.observe(emptyList())
        s.observe(listOf(typedQuestion(1), typedReply(2)))
        assertTrue(s.unread)
    }

    @Test
    fun `the button label follows the companion and says a reply is waiting in words`() {
        assertEquals("Chat with Dorothy", chatButtonLabel("Dorothy", unread = false))
        assertEquals("Chat with Rose, new reply", chatButtonLabel("Rose", unread = true))
    }
}
