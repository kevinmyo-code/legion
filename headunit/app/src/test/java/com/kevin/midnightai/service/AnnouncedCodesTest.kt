package com.kevin.midnightai.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The health monitor's "already told" marker, and the loss it was written to close.
 *
 * Before 2026-11-24 the monitor kept ONE set and advanced it the moment it asked for a line to be
 * spoken (`knownCodes = codes`), so a code whose line was gated or dropped was never announced and
 * never would be: the next scan found it in the baseline and said nothing. The last test here is
 * that case, driven through the real holding area rather than a mock of it.
 */
class AnnouncedCodesTest {

    @Test fun `codes present at the first reading are the baseline and are never announced`() {
        val announced = AnnouncedCodes()
        announced.markTold(setOf("P0301", "P0171"))
        assertEquals(emptySet<String>(), announced.untold(setOf("P0301", "P0171")))
    }

    @Test fun `a new code is announced once`() {
        val announced = AnnouncedCodes()
        announced.markTold(setOf("P0171"))
        assertEquals(setOf("P0301"), announced.untold(setOf("P0171", "P0301")))
        announced.markTold(setOf("P0301"))
        assertEquals(emptySet<String>(), announced.untold(setOf("P0171", "P0301")))
    }

    @Test fun `a code that clears and comes back is news again`() {
        val announced = AnnouncedCodes()
        announced.markTold(setOf("P0301"))
        announced.keepOnly(emptySet()) // the light went out
        assertEquals(setOf("P0301"), announced.untold(setOf("P0301")))
    }

    /**
     * The whole point: a trouble code held mid-conversation and dropped on the goodbye is raised
     * AGAIN on the next scan, because nothing reported it spoken. Chained through the real
     * [HeldLines], so this fails if either half regresses.
     *
     * The companion case - that an urgent line is not dropped on a goodbye in the first place - is
     * `HeldLinesTest`. This one covers what happens when it is dropped for one of the other
     * reasons (stale, evicted, a socket that never connected).
     */
    @Test fun `a code whose line was dropped is announced again on the next scan`() {
        val announced = AnnouncedCodes()
        val held = HeldLines(maxAgeMs = 1_000, capacity = 2)
        val drops = mutableListOf<String>()
        val onDropped: (ProactiveBus.SpeakRequest, String) -> Unit = { r, why -> drops += "${r.prompt}|$why" }
        val codes = setOf("P0301")

        // Scan 1: the code is new, so the monitor asks for a line and the controller holds it
        // mid-conversation. The marker advances only from onSpoken, which nothing calls.
        val untold = announced.untold(codes)
        assertEquals(codes, untold)
        held.hold(
            ProactiveBus.SpeakRequest("a new trouble code", ProactiveKind.URGENT) { announced.markTold(untold) },
            nowMs = 0,
            onDropped = onDropped,
        )

        // The line goes stale before any socket takes it.
        held.takeFresh(nowMs = 5_000, onDropped = onDropped)
        assertTrue(drops.single().endsWith("stale after 5000 ms"))

        // Scan 2: still untold, so the driver hears about it after all.
        assertEquals(codes, announced.untold(codes))

        // Spoken this time.
        val second = announced.untold(codes)
        ProactiveBus.SpeakRequest("a new trouble code", ProactiveKind.URGENT) { announced.markTold(second) }
            .onSpoken()
        assertEquals(emptySet<String>(), announced.untold(codes))
    }
}
