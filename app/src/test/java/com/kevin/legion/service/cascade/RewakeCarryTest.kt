package com.kevin.legion.service.cascade

import com.kevin.legion.service.ChatTurn
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Kevin, 2026-11-24: wake Alfred again within about a minute of a conversation ending and he still
 * has that conversation. These are the rules a caller can see: what is kept, for how long, and what
 * is never kept.
 */
class RewakeCarryTest {

    private val turns = listOf(
        ChatTurn(fromUser = true, text = "what should I put on"),
        ChatTurn(fromUser = false, text = "Either the live record or the studio one."),
    )

    private fun ended(
        carry: RewakeCarry,
        atMs: Long,
        reason: String = "stopped",
        test: Boolean = false,
        outsideTextRead: Boolean = false,
        turns: List<ChatTurn> = this.turns,
    ) = carry.ended(reason, turns, test, outsideTextRead, atMs)

    @Test
    fun `the next conversation inside the window gets the last one's turns`() {
        val carry = RewakeCarry()
        ended(carry, atMs = 1_000)
        assertEquals(turns, carry.take(nowMs = 1_000 + 30_000))
    }

    @Test
    fun `the note says where the carried turns end, as the last model line before his words`() {
        assertFalse("a model line, so it reads as the boundary", RewakeCarry.NOTE.isBlank())
        assertEquals("one minute", 60_000L, RewakeCarry.WINDOW_MS)
    }

    @Test
    fun `a conversation opened after the window gets nothing`() {
        val carry = RewakeCarry()
        ended(carry, atMs = 1_000)
        assertEquals(emptyList<ChatTurn>(), carry.take(nowMs = 1_000 + RewakeCarry.WINDOW_MS + 1))
    }

    @Test
    fun `the last moment of the window still carries`() {
        val carry = RewakeCarry()
        ended(carry, atMs = 1_000)
        assertTrue(carry.take(nowMs = 1_000 + RewakeCarry.WINDOW_MS).isNotEmpty())
    }

    /**
     * With the turns the WIRING really passes. `CascadeSession.shutdown` hands over
     * `carried + history`, so a re-wake that heard nothing closes with an EMPTY history and a
     * non-empty carry, never the empty list a holder test can invent. Which means the close keeps
     * the turns again and re-stamps them: the window runs from the last close, failed wakes
     * included. That slide is accepted (he is plainly still trying to talk) and pinned here, both
     * halves, so it cannot change unnoticed.
     */
    @Test
    fun `a re-wake that goes nowhere does not spend the context, and slides the window`() {
        val carry = RewakeCarry()
        ended(carry, atMs = 1_000)
        val carriedIn = carry.take(nowMs = 2_000)
        assertEquals("he wakes Alfred", turns, carriedIn)
        // It heard nothing and closed on noise, passing what was in front of the model: the carry.
        ended(carry, atMs = 3_000, reason = "noise", turns = carriedIn)
        assertEquals("his next try still has it", turns, carry.take(nowMs = 4_000))
        assertEquals(
            "the window now runs from the failed wake, not the conversation that had the turns",
            turns,
            carry.take(nowMs = 1_000 + RewakeCarry.WINDOW_MS + 1),
        )
        assertEquals(
            "and it does expire, a minute after that last close",
            emptyList<ChatTurn>(),
            carry.take(nowMs = 3_000 + RewakeCarry.WINDOW_MS + 1),
        )
    }

    @Test
    fun `a close that is not the end of a conversation leaves the context alone`() {
        val carry = RewakeCarry()
        ended(carry, atMs = 1_000)
        carry.take(nowMs = 2_000)
        for (reason in listOf("preempted", "destroyed", "crisis")) {
            ended(carry, atMs = 3_000, reason = reason, turns = listOf(ChatTurn(true, "something")))
        }
        assertEquals("not taken away by a teardown", turns, carry.take(nowMs = 4_000))
    }

    @Test
    fun `what a conversation leaves includes what it carried in, so a second follow-up reaches it`() {
        val carry = RewakeCarry()
        ended(carry, atMs = 1_000)
        val carriedIn = carry.take(nowMs = 2_000)
        val own = listOf(ChatTurn(true, "play the other one"), ChatTurn(false, "Putting on the studio one."))
        ended(carry, atMs = 3_000, turns = carriedIn + own)
        val second = carry.take(nowMs = 4_000)
        assertEquals("the first conversation and the second, in order", carriedIn + own, second)
    }

    @Test
    fun `his new chat tap wipes it`() {
        val carry = RewakeCarry()
        ended(carry, atMs = 1_000)
        ended(carry, atMs = 1_100, reason = "new conversation")
        assertEquals(
            "he asked to start fresh",
            emptyList<ChatTurn>(),
            carry.take(nowMs = 1_200),
        )
        assertTrue(RewakeCarry.wipes("new conversation", test = false, outsideTextRead = false))
    }

    /**
     * The case the close could not reach: he taps "new chat" with no conversation open, so there is
     * no [RewakeCarry.ended] call to carry the reason, and the holder is exactly where it has
     * something in it. `LiveSessionController.newConversation` clears it directly instead
     * ([RewakeCarryWiringTest] pins that it does so unconditionally).
     */
    @Test
    fun `a wipe with no conversation open forgets the last one`() {
        val carry = RewakeCarry()
        ended(carry, atMs = 1_000)
        carry.wipe()
        assertEquals("he asked to start fresh", emptyList<ChatTurn>(), carry.take(nowMs = 1_100))
    }

    /** And the crisis teardown uses the same door, whatever kind of conversation it landed in. */
    @Test
    fun `a wipe after a conversation already carried its turns forward forgets those too`() {
        val carry = RewakeCarry()
        ended(carry, atMs = 1_000)
        val carriedIn = carry.take(nowMs = 2_000)
        ended(carry, atMs = 3_000, turns = carriedIn + ChatTurn(fromUser = true, text = "and after that"))
        carry.wipe()
        assertEquals(emptyList<ChatTurn>(), carry.take(nowMs = 3_100))
    }

    /**
     * The seam where the carried conversation meets this one. A kept conversation ends on a reply
     * (`fromUser = false`) and [RewakeCarry.NOTE] is another model line, so a one-to-one mapping
     * would send two `model` contents in a row. `TextChatEngine.capHistory` merges consecutive
     * same-role lines, which is what keeps the request alternating; this pins that it does.
     */
    @Test
    fun `the carried turns, the note and his words reach the model as alternating roles`() {
        val carriedBlock = turns + ChatTurn(fromUser = false, text = RewakeCarry.NOTE)
        val contents = CascadePrompt.contents(carriedBlock, "play the other one", preamble = "")
        val roles = (0 until contents.length()).map { contents.getJSONObject(it).getString("role") }
        assertEquals(listOf("user", "model", "user"), roles)
        val reply = contents.getJSONObject(1).getJSONArray("parts").getJSONObject(0).getString("text")
        assertTrue("the note rides the last model line, merged into it", reply.endsWith(RewakeCarry.NOTE))
        assertTrue("and the reply it was merged with is still there", reply.contains("studio one"))
        val last = contents.getJSONObject(2).getJSONArray("parts").getJSONObject(0).getString("text")
        assertEquals("his new words are the last content", "play the other one", last)
    }

    @Test
    fun `a conversation that opened too late never gets it back`() {
        val carry = RewakeCarry()
        ended(carry, atMs = 1_000)
        carry.take(nowMs = 1_000 + RewakeCarry.WINDOW_MS + 1)
        assertEquals("emptied on its way past", emptyList<ChatTurn>(), carry.take(nowMs = 1_500))
    }

    @Test
    fun `a clock that went backwards carries nothing`() {
        assertFalse(RewakeCarry.carries(endedAtMs = 10_000, nowMs = 9_000))
    }

    /**
     * ADR 0090, as amended 2026-11-21. The guard cannot be bypassed by a carried turn because a
     * conversation that read outside text keeps NOTHING: there is no turn to carry, so no taint to
     * leave behind and no outside text to leak. Reverting the `outsideTextRead` term in
     * [RewakeCarry.wipes] turns this red.
     */
    @Test
    fun `a conversation that read outside text leaves nothing at all`() {
        val carry = RewakeCarry()
        ended(carry, atMs = 1_000, outsideTextRead = true)
        assertEquals(
            "no turns, so nothing to carry a write taint or outside text forward",
            emptyList<ChatTurn>(),
            carry.take(nowMs = 1_100),
        )
        assertTrue("by the wipe rule", RewakeCarry.wipes("stopped", test = false, outsideTextRead = true))
    }

    @Test
    fun `a read-through conversation also wipes what an earlier one left`() {
        val carry = RewakeCarry()
        ended(carry, atMs = 1_000)
        ended(carry, atMs = 1_100, outsideTextRead = true)
        assertEquals(emptyList<ChatTurn>(), carry.take(nowMs = 1_200))
    }

    @Test
    fun `a test conversation leaves nothing`() {
        val carry = RewakeCarry()
        ended(carry, atMs = 1_000, test = true)
        assertEquals(emptyList<ChatTurn>(), carry.take(nowMs = 1_100))
    }

    @Test
    fun `a conversation with nothing said leaves nothing of its own`() {
        val carry = RewakeCarry()
        ended(carry, atMs = 1_000, turns = emptyList())
        assertEquals(emptyList<ChatTurn>(), carry.take(nowMs = 1_100))
    }

    @Test
    fun `only the end of a conversation he was in leaves anything`() {
        for (reason in listOf("stopped", "sleep_phrase", "dismissed", "follow_up", "check_in_answered")) {
            assertTrue(reason, RewakeCarry.keeps(reason, turns))
        }
        // A close he did not ask for: a teardown, a hand-over, a crisis, a failed voice, a ring window.
        for (reason in listOf("destroyed", "preempted", "crisis", "voice_failed_mic", "mic_held", "ring_ended")) {
            assertFalse(reason, RewakeCarry.keeps(reason, turns))
        }
    }

    @Test
    fun `what is held is capped as a prompt caps history`() {
        val carry = RewakeCarry()
        val many = (1..80).map { ChatTurn(fromUser = it % 2 == 1, text = "line $it") }
        ended(carry, atMs = 1_000, turns = many)
        val taken = carry.take(nowMs = 1_100)
        assertTrue("capped as a prompt caps history", taken.size in 1..30)
        assertTrue("the newest turns are the ones kept", taken.any { it.text.contains("line 80") })
        assertFalse("and the oldest are not", taken.any { it.text == "line 1" })
    }
}
