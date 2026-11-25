package com.kevin.midnightai.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HeldLinesTest {

    private val drops = mutableListOf<String>()
    private val onDropped: (ProactiveBus.SpeakRequest, String) -> Unit = { r, why -> drops += "${r.prompt}|$why" }

    private fun lines(maxAge: Long = 1_000, capacity: Int = 2, maxFailures: Int = 1) =
        HeldLines(maxAge, capacity, maxFailures)

    private fun line(
        prompt: String,
        kind: ProactiveKind = ProactiveKind.ROUTINE,
        onSpoken: () -> Unit = {},
    ) = ProactiveBus.SpeakRequest(prompt, kind, onSpoken)

    @Test fun `a fresh held line is taken once`() {
        val h = lines()
        assertNull(h.hold(line("a"), 0, onDropped = onDropped))
        assertEquals("a", h.takeFresh(500, onDropped)?.prompt)
        assertNull(h.takeFresh(500, onDropped))
        assertEquals(emptyList<String>(), drops)
    }

    @Test fun `a stale line is dropped and reported, never spoken`() {
        val h = lines(maxAge = 1_000)
        h.hold(line("old"), 0, onDropped = onDropped)
        assertNull(h.takeFresh(1_001, onDropped))
        assertEquals(listOf("old|stale after 1001 ms"), drops)
    }

    @Test fun `a stale line ahead of a fresh one does not block it`() {
        val h = lines(maxAge = 1_000, capacity = 3)
        h.hold(line("old"), 0, onDropped = onDropped)
        h.hold(line("new"), 2_000, onDropped = onDropped)
        assertEquals("new", h.takeFresh(2_100, onDropped)?.prompt)
        assertEquals(1, drops.size)
    }

    @Test fun `a full area evicts the oldest and says so`() {
        val h = lines(capacity = 1)
        h.hold(line("first"), 0, onDropped = onDropped)
        h.hold(line("second"), 10, onDropped = onDropped)
        assertEquals(listOf("first|area full"), drops)
        assertEquals("second", h.takeFresh(20, onDropped)?.prompt)
    }

    @Test fun `a line is retried once and dropped on the second failure`() {
        val h = lines(maxFailures = 1)
        assertNull(h.hold(line("x"), 0, failures = 1, onDropped = onDropped))
        assertEquals("failed 2 times", h.hold(line("x"), 0, failures = 2, onDropped = onDropped))
        assertEquals(listOf("x|failed 2 times"), drops)
        assertEquals(1, h.size)
    }

    /**
     * Ruled 2026-11-24: a goodbye ends the conversation, so a ROUTINE line held for a turn
     * boundary is dropped rather than spoken on the warm socket after the sign-off. A conversation
     * that merely went quiet keeps today's behaviour and still gets its line.
     */
    @Test fun `a park after a goodbye drops the routine lines, a quiet park speaks one`() {
        val afterGoodbye = lines(capacity = 3)
        afterGoodbye.hold(line("the oil change is due"), 0, onDropped = onDropped)
        afterGoodbye.hold(line("ninety nine thousand miles"), 0, onDropped = onDropped)
        assertNull(afterGoodbye.onPark(afterDismissal = true, nowMs = 100, onDropped = onDropped))
        assertEquals(
            listOf(
                "the oil change is due|the driver said goodbye",
                "ninety nine thousand miles|the driver said goodbye",
            ),
            drops,
        )
        assertEquals(0, afterGoodbye.size)

        val wentQuiet = lines(capacity = 3)
        wentQuiet.hold(line("the oil change is due"), 0, onDropped = onDropped)
        assertEquals(
            "the oil change is due",
            wentQuiet.onPark(afterDismissal = false, nowMs = 100, onDropped = onDropped)?.prompt,
        )
        assertEquals(0, wentQuiet.size)
    }

    /**
     * The other half of that ruling, and the reason the bus carries a kind at all: the word was
     * "routine". A fresh trouble code and an answer the driver asked for are neither dropped nor
     * lost - they are spoken on the warm socket after the sign-off.
     */
    @Test fun `a goodbye keeps an urgent line and an asked-for one, and drops the chatter with them`() {
        val h = lines(capacity = 3)
        h.hold(line("idle chatter"), 0, onDropped = onDropped)
        h.hold(line("Spotify is playing now", ProactiveKind.REQUESTED), 0, onDropped = onDropped)
        h.hold(line("a new trouble code", ProactiveKind.URGENT), 0, onDropped = onDropped)

        // Most urgent first, even though it was held last.
        assertEquals(
            "a new trouble code",
            h.onPark(afterDismissal = true, nowMs = 100, onDropped = onDropped)?.prompt,
        )
        assertEquals(listOf("idle chatter|the driver said goodbye"), drops)
        // The asked-for answer is still there for the next park, not dropped.
        assertEquals(1, h.size)
        assertEquals(
            "Spotify is playing now",
            h.onPark(afterDismissal = true, nowMs = 200, onDropped = onDropped)?.prompt,
        )
    }

    @Test fun `chatter never evicts an urgent line, and is itself refused when the area is full`() {
        val h = lines(capacity = 2)
        h.hold(line("a new trouble code", ProactiveKind.URGENT), 0, onDropped = onDropped)
        h.hold(line("the oil change is due"), 0, onDropped = onDropped)

        // Full: the routine line is the one evicted, not the alert.
        assertNull(h.hold(line("an overheat", ProactiveKind.URGENT), 10, onDropped = onDropped))
        assertEquals(listOf("the oil change is due|area full"), drops)

        // Full of urgent lines: the incoming chatter is what gets dropped.
        assertEquals(
            "area full of more urgent lines",
            h.hold(line("idle chatter"), 20, onDropped = onDropped),
        )
        assertEquals(2, h.size)
        assertEquals("a new trouble code", h.takeFresh(30, onDropped)?.prompt)
        assertEquals("an overheat", h.takeFresh(30, onDropped)?.prompt)
    }

    /**
     * The marker rule, from the holding area's side: nothing in here ever reports a line SPOKEN.
     * `onSpoken` fires only on the controller's two paths that feed the socket, which is what lets
     * [AnnouncedCodes] raise a dropped trouble code again instead of believing it was said.
     */
    @Test fun `a dropped line is never reported spoken`() {
        var spoken = false
        val h = lines(maxAge = 1_000, capacity = 1)
        h.hold(line("a new trouble code", ProactiveKind.URGENT) { spoken = true }, 0, onDropped = onDropped)
        assertNull(h.takeFresh(5_000, onDropped))
        assertFalse(spoken)

        h.hold(line("an overheat", ProactiveKind.URGENT) { spoken = true }, 0, onDropped = onDropped)
        h.clear("session closed", onDropped)
        assertFalse(spoken)

        // And a line that IS taken is still only reported spoken by its sender, not by being taken.
        h.hold(line("a new trouble code", ProactiveKind.URGENT) { spoken = true }, 0, onDropped = onDropped)
        assertTrue(h.takeFresh(10, onDropped) != null)
        assertFalse(spoken)
    }

    @Test fun `clear reports every held line`() {
        val h = lines(capacity = 3)
        h.hold(line("a"), 0, onDropped = onDropped)
        h.hold(line("b"), 0, onDropped = onDropped)
        h.clear("session closed", onDropped)
        assertEquals(listOf("a|session closed", "b|session closed"), drops)
        assertEquals(0, h.size)
    }
}
