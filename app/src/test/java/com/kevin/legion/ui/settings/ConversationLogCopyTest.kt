package com.kevin.legion.ui.settings

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The "Conversation log" row's copy (conversation-records ticket 03): plain words, no assistant name. */
class ConversationLogCopyTest {
    private val all = listOf(
        ConversationLogCopy.TITLE, ConversationLogCopy.SUBTITLE, ConversationLogCopy.KEEP,
        ConversationLogCopy.KEEP_DETAIL, ConversationLogCopy.MIRROR, ConversationLogCopy.MIRROR_DETAIL,
    )

    @Test fun `says what is sent, where, and how it is read`() {
        val s = ConversationLogCopy.SUBTITLE
        assertTrue(s.contains("what you and the assistant said"))
        assertTrue(s.contains("tool call"))
        assertTrue(s.contains("mail"))
        assertTrue(s.contains("household's engine"))
        assertTrue(s.contains("manage.py device_logs"))
    }

    @Test fun `never names the assistant and carries no em dash`() {
        for (text in all) {
            for (name in listOf("Alfred", "Aria", "Jarvis", "JARVIS", "Gemini")) assertFalse(text, text.contains(name))
            assertFalse(text, text.contains('—'))
        }
    }
}
