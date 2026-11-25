package com.kevin.midnightai.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The health monitor's "already told" marker, and the loss it was written to close.
 *
 * Before 2026-11-24 the monitor kept ONE set and advanced it the moment it asked for a line to be
 * spoken (`knownCodes = codes`), so a code whose line was gated or dropped was never announced and
 * never would be: the next scan found it in the baseline and said nothing. The last two tests are
 * that case, the second driven through the real holding area rather than a mock of it.
 *
 * `onScan` is the whole per-scan decision, so these cover the monitor's rule and not just a set
 * primitive. What is left in the loop and NOT covered here: that the line is raised as
 * [ProactiveKind.URGENT], and that `onSpoken` is the only thing wired to [AnnouncedCodes.markTold].
 */
class AnnouncedCodesTest {

    @Test fun `codes present at the first reading are the baseline and are never announced`() {
        val announced = AnnouncedCodes()
        assertEquals(emptySet<String>(), announced.onScan(setOf("P0301", "P0171"), firstReading = true))
        assertEquals(emptySet<String>(), announced.onScan(setOf("P0301", "P0171"), firstReading = false))
    }

    @Test fun `a new code is announced once`() {
        val announced = AnnouncedCodes()
        announced.onScan(setOf("P0171"), firstReading = true)
        assertEquals(setOf("P0301"), announced.onScan(setOf("P0171", "P0301"), firstReading = false))
        announced.markTold(setOf("P0301"))
        assertEquals(emptySet<String>(), announced.onScan(setOf("P0171", "P0301"), firstReading = false))
    }

    @Test fun `a code that clears and comes back is news again`() {
        val announced = AnnouncedCodes()
        announced.onScan(setOf("P0301"), firstReading = true)
        announced.onScan(emptySet(), firstReading = false) // the light went out
        assertEquals(setOf("P0301"), announced.onScan(setOf("P0301"), firstReading = false))
    }

    /**
     * The regression that two sets could have introduced and the old single set got for free:
     * "told" must be pruned to what the ECU still reports, and pruning must happen BEFORE the
     * answer, or a code that cleared this very scan would stay suppressed for one more.
     */
    @Test fun `an unspoken code is announced on every scan until it is really said`() {
        val announced = AnnouncedCodes()
        announced.onScan(setOf("P0171"), firstReading = true)
        val codes = setOf("P0171", "P0301")
        assertEquals(setOf("P0301"), announced.onScan(codes, firstReading = false))
        assertEquals(setOf("P0301"), announced.onScan(codes, firstReading = false))
        assertEquals(setOf("P0301"), announced.onScan(codes, firstReading = false))
        announced.markTold(setOf("P0301"))
        assertEquals(emptySet<String>(), announced.onScan(codes, firstReading = false))
    }

    /**
     * The whole point, end to end: a trouble code held mid-conversation and dropped is raised
     * AGAIN on the next scan, because nothing reported it spoken. Chained through the real
     * [HeldLines], so this fails if either half regresses.
     *
     * The companion case - that an urgent line is not dropped on a goodbye in the first place - is
     * `HeldLinesTest`. This one covers what happens when it is dropped for one of the other
     * reasons (stale, evicted, a socket that never connected, a tap taking over).
     */
    @Test fun `a code whose line was dropped is announced again on the next scan`() {
        val announced = AnnouncedCodes()
        val held = HeldLines(maxAgeMs = 1_000, capacity = 2)
        val drops = mutableListOf<String>()
        val onDropped: (ProactiveBus.SpeakRequest, String) -> Unit = { r, why -> drops += "${r.prompt}|$why" }
        announced.onScan(emptySet(), firstReading = true)
        val codes = setOf("P0301")

        // Scan 1: the code is new, so the monitor asks for a line and the controller holds it
        // mid-conversation. The marker advances only from onSpoken, which nothing calls.
        val untold = announced.onScan(codes, firstReading = false)
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
        val second = announced.onScan(codes, firstReading = false)
        assertEquals(codes, second)

        // Spoken this time: the socket took it, so onSpoken ran.
        ProactiveBus.SpeakRequest("a new trouble code", ProactiveKind.URGENT) { announced.markTold(second) }
            .onSpoken()
        assertEquals(emptySet<String>(), announced.onScan(codes, firstReading = false))
    }
}
