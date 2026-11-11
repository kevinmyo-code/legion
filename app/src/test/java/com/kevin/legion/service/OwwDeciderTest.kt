package com.kevin.legion.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Threshold, trigger gap and near-miss logging, one 80 ms score at a time. */
class OwwDeciderTest {
    private val step = 80L

    private fun run(d: OwwDecider, scores: List<Float>, startMs: Long = 10_000L) =
        scores.mapIndexed { i, s -> d.onScore(s, startMs + i * step) }

    @Test
    fun `a score at the threshold opens, one below does not`() {
        assertEquals(OwwDecider.Decision.Open(0.5f), OwwDecider(0.5f).onScore(0.5f, 10_000))
        assertNull(OwwDecider(0.5f).onScore(0.49f, 10_000))
    }

    @Test
    fun `one phrase scoring high for several frames opens once`() {
        val out = run(OwwDecider(0.5f), listOf(0.1f, 0.7f, 0.9f, 0.8f, 0.6f, 0.1f))
        assertEquals(1, out.count { it is OwwDecider.Decision.Open })
        // The run that opened is not also logged as a near miss when it ends.
        assertEquals(0, out.count { it is OwwDecider.Decision.NearMiss })
    }

    @Test
    fun `the gap is the engine's own trigger gap`() {
        assertEquals(4_000L, WakeWordEngine.MIN_TRIGGER_GAP_MS)
        val d = OwwDecider(0.5f)
        assertEquals(OwwDecider.Decision.Open(0.9f), d.onScore(0.9f, 10_000))
        d.onScore(0.1f, 10_080)
        assertNull("inside the gap", d.onScore(0.9f, 10_000 + 3_999))
        d.onScore(0.1f, 14_100)
        assertEquals(OwwDecider.Decision.Open(0.8f), d.onScore(0.8f, 10_000 + 4_000 + 200))
    }

    @Test
    fun `a run that peaks between half the threshold and the threshold is one near miss with its peak`() {
        val out = run(OwwDecider(0.6f), listOf(0.1f, 0.35f, 0.55f, 0.4f, 0.1f, 0.05f))
        assertEquals(listOf(OwwDecider.Decision.NearMiss(0.55f)), out.filterNotNull())
    }

    @Test
    fun `scores under half the threshold say nothing`() {
        assertEquals(emptyList<OwwDecider.Decision>(), run(OwwDecider(0.6f), listOf(0.1f, 0.29f, 0.2f, 0.0f)).filterNotNull())
    }

    @Test
    fun `a high run held back by the gap is not a near miss`() {
        val d = OwwDecider(0.5f)
        d.onScore(0.9f, 10_000)
        d.onScore(0.1f, 10_080)
        val out = run(d, listOf(0.9f, 0.9f, 0.1f), startMs = 11_000)
        assertEquals(emptyList<OwwDecider.Decision>(), out.filterNotNull())
    }
}
