package com.kevin.midnightai.ai

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards that the head unit's prompt carries [CHAT_FIRST_CLAUSE], both of its halves, and carries
 * it in Moose's own register. Like [SpeechHonestyTest] it cannot guard obedience: nothing inspects
 * the spoken reply.
 *
 * The register test is the load-bearing one. The clause was first pasted here in the phone's words,
 * which say "his", in a prompt that otherwise says "the driver" and gives "his" no antecedent.
 */
class ChatFirstClauseTest {

    @Test
    fun theClauseIsInThePromptThatShips() {
        assertTrue(SHARED_INSTRUCTIONS.contains(CHAT_FIRST_CLAUSE))
        assertTrue("ends with a space so it concatenates", CHAT_FIRST_CLAUSE.endsWith(" "))
    }

    @Test
    fun itCarriesTheActionRule_inThePhonesWords() {
        // The phone pins this sentence verbatim (AriaBrainHonestyClauseTest); parity means the
        // head unit says it too, so a reader of either prompt gets the same rule.
        assertTrue(
            CHAT_FIRST_CLAUSE.contains(
                "Always call the matching tool before claiming you've done something.",
            ),
        )
    }

    @Test
    fun itCarriesTheChatHalf_withBothWorkedExamples() {
        assertTrue(CHAT_FIRST_CLAUSE.contains("Chat and general questions need no tool."))
        assertTrue(CHAT_FIRST_CLAUSE.contains("only when asked about them"))
        assertTrue(CHAT_FIRST_CLAUSE.contains("confirm a memory save only when asked"))
        assertTrue(CHAT_FIRST_CLAUSE.contains("\"I love hiking\" gets a reply, not a save"))
        assertTrue(CHAT_FIRST_CLAUSE.contains("an answer, not a calendar check"))
    }

    @Test
    fun itSpeaksOfTheDriver_neverHim() {
        assertTrue(CHAT_FIRST_CLAUSE.contains("the driver's calendar, memory or records"))
        assertFalse(
            "the head unit's prompt says \"the driver\"; \"his\" has no antecedent here",
            Regex("\\bhis\\b").containsMatchIn(CHAT_FIRST_CLAUSE),
        )
    }

    @Test
    fun theRetiredToolSentenceIsGone() {
        // Its four capabilities are each in their own declaration, sent as written: the head unit
        // folds nothing, so nothing was lost by deleting the sentence that listed them.
        assertFalse(SHARED_INSTRUCTIONS.contains("own live data"))
        assertFalse(SHARED_INSTRUCTIONS.contains("to control music"))
        assertFalse(SHARED_INSTRUCTIONS.contains("tag or show saved places"))
    }
}
