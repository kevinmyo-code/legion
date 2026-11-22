package com.kevin.legion.service.cascade

import com.kevin.legion.service.ChatTurn
import com.kevin.legion.service.LiveAudit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the next turn sees of a reply that read outside text (web, mail, a brief). A bare
 * placeholder made the model treat the question as never answered and keep going back to it,
 * so the reply is kept as a note that it was answered and is done. Made-up place and figures.
 */
class CascadeHistoryTest {
    private val answered =
        "(Answered from a web search or other outside text. That answer was not kept. Done unless he asks again.)"

    @Test
    fun `a read-through reply is kept as the answered note, never its words or the audit string`() {
        val kept = CascadePrompt.keptReply("A flat in Invented City is about 1,900 a month.", readThrough = true)
        assertEquals(answered, kept)
        assertFalse(kept == LiveAudit.REDACTED)
    }

    @Test
    fun `any other reply is kept as said`() {
        assertEquals("Your quiz is at noon.", CascadePrompt.keptReply("Your quiz is at noon.", readThrough = false))
    }

    @Test
    fun `the next turn carries the note in place of the web answer and the new question last`() {
        val history = listOf(
            ChatTurn(fromUser = true, text = "how much is a flat in Invented City"),
            ChatTurn(
                fromUser = false,
                text = CascadePrompt.keptReply("A flat there is about 1,900 a month.", readThrough = true),
            ),
        )
        val contents = CascadePrompt.contents(history, "any podcast ideas for the drive?", "")
        val sent = contents.toString()
        assertFalse(sent.contains("1,900"))
        assertFalse(sent.contains(LiveAudit.REDACTED))
        assertEquals(3, contents.length())
        assertEquals(answered, contents.getJSONObject(1).getJSONArray("parts").getJSONObject(0).getString("text"))
        val last = contents.getJSONObject(2)
        assertEquals("user", last.getString("role"))
        assertEquals("any podcast ideas for the drive?", last.getJSONArray("parts").getJSONObject(0).getString("text"))
    }

    @Test
    fun `the voice clause closes a marked turn and sends a follow-up back to a fresh look`() {
        val clause = CascadePrompt.VOICE_CLAUSE
        assertTrue(clause.contains("A reply marked as answered from a web search or other outside text is finished"))
        assertTrue(clause.contains("never go back to it on your own"))
        // A same-topic follow-up is looked up again, never answered from what was said before.
        assertTrue(clause.contains("If he asks about that topic again, look it up again; never answer it from memory."))
        // Mail and the news brief are marked the same way, so the line names them too.
        assertTrue(
            clause.contains(
                "say answers read from outside (the web, a mail, the news brief) are not kept, " +
                    "and offer to read it or look it up again.",
            ),
        )
    }
}
