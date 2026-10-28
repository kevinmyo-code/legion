package com.kevin.legion.service.turn

import com.kevin.legion.service.turn.StillCheckingNudge.Companion.STILL_CHECKING_AFTER_MS
import com.kevin.legion.service.turn.StillCheckingNudge.Companion.STILL_CHECKING_AGAIN_AFTER_MS
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The still-checking nudge (async-lookups ticket 07, Kevin 2026-10-28). */
class StillCheckingNudgeTest {
    private fun started(): StillCheckingNudge = StillCheckingNudge().apply { onAsyncCall("c1", "ask_fleet", 0L) }

    @Test
    fun `fires at 4 s when idle`() {
        val n = started()
        assertNull(n.due(STILL_CHECKING_AFTER_MS - 1, quiet = true))
        assertEquals(
            StillCheckingNudge.Nudge("ask_fleet", STILL_CHECKING_AFTER_MS),
            n.due(STILL_CHECKING_AFTER_MS, quiet = true),
        )
    }

    @Test
    fun `does not fire while speaking`() {
        val n = started()
        assertNull(n.due(5_000L, quiet = false))
        // Once quiet again, it fires late rather than never.
        assertEquals(6_000L, n.due(6_000L, quiet = true)?.afterMs)
    }

    @Test
    fun `does not fire after the result`() {
        val n = started()
        n.onResult("c1")
        assertNull(n.due(5_000L, quiet = true))
        assertFalse(n.hasPending())
    }

    @Test
    fun `second nudge at 12 s only, and never a third`() {
        val n = started()
        assertEquals(4_000L, n.due(4_000L, quiet = true)?.afterMs)
        assertNull(n.due(8_000L, quiet = true))
        assertNull(n.due(STILL_CHECKING_AGAIN_AFTER_MS - 1, quiet = true))
        assertEquals(12_000L, n.due(STILL_CHECKING_AGAIN_AFTER_MS, quiet = true)?.afterMs)
        assertNull(n.due(30_000L, quiet = true))
        assertTrue(n.hasPending()) // still running; just no more nudges
    }

    @Test
    fun `cancelled by barge-in`() {
        val n = started()
        n.onUserActivity()
        assertNull(n.due(5_000L, quiet = true))
        assertFalse(n.hasPending())
    }

    @Test
    fun `cancelled by a toolCallCancellation`() {
        val n = started()
        n.onCancelled(listOf("c1"))
        assertNull(n.due(5_000L, quiet = true))
    }

    @Test
    fun `never while a blocking tool is in flight`() {
        val n = started()
        n.onBlockingCall("b1")
        assertNull(n.due(5_000L, quiet = true))
        n.onResult("b1")
        assertEquals("ask_fleet", n.due(5_000L, quiet = true)?.tool)
    }

    @Test
    fun `the second call of a chain gets its own clock, and two nudges are never close together`() {
        val n = StillCheckingNudge()
        n.onAsyncCall("c1", "ask_fleet", 0L)
        assertEquals(4_000L, n.due(4_000L, quiet = true)?.afterMs)
        n.onAsyncCall("c2", "ask_fleet", 5_000L)
        // c2 is 4 s old at 9 s, but c1's nudge was only 5 s ago: allowed (4 s apart minimum).
        assertNull(n.due(7_900L, quiet = true))
        assertEquals(4_000L, n.due(9_000L, quiet = true)?.afterMs)
    }

    @Test
    fun `session end clears everything`() {
        val n = started()
        n.onBlockingCall("b1")
        n.clear()
        assertFalse(n.hasPending())
        assertNull(n.due(5_000L, quiet = true))
    }

    @Test
    fun `the instruction names the tool, forbids facts and is not user speech`() {
        val text = StillCheckingNudge.instruction("ask_fleet")
        assertTrue(text.startsWith("(System note, not spoken by the user:"))
        assertTrue(text.contains("`ask_fleet`"))
        assertTrue(text.contains("no figure, date, name, fact or outcome"))
        assertFalse(text.contains("—"))
    }
}
