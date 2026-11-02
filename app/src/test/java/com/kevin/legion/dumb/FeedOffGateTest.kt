package com.kevin.legion.dumb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** In-memory [FeedOffGateStore]: what a second gate reads is what the first wrote. */
class FakeFeedOffGateStore : FeedOffGateStore {
    val map = HashMap<String, Long>()
    override fun get(target: String) = map[target]
    override fun set(target: String, requestedAtMs: Long?) {
        if (requestedAtMs == null) map.remove(target) else map[target] = requestedAtMs
    }
}

/** Turning the feed block down costs the same as a pass (ADR 0066, 2026-11-02). */
class FeedOffGateTest {
    private var now = 10_000_000L
    private val store = FakeFeedOffGateStore()
    private fun gate() = FeedOffGate(store) { now }

    private val off = FeedOffRule.SWITCH
    private val offSentence = "I am choosing to turn off the feed blocker"

    @Test
    fun `the sentences are the ruled ones, case-insensitive and trimmed`() {
        assertEquals(offSentence, FeedOffRule.SWITCH_SENTENCE)
        assertEquals("I am choosing to unblock Instagram", FeedOffRule.unblockSentence("Instagram"))
        assertTrue(FeedOffRule.matches("  i am CHOOSING to turn off the feed blocker ", offSentence))
        assertFalse(FeedOffRule.matches("I am choosing to turn off the  feed blocker", offSentence))
        assertFalse(FeedOffRule.matches(FeedPassRule.sentence("Instagram"), FeedOffRule.unblockSentence("Instagram")))
    }

    @Test
    fun `the wait is the pass's wait`() {
        assertEquals(FeedPassRule.WAIT_MS, gate().request(off).remainingMs)
        assertEquals(60_000L, FeedPassRule.WAIT_MS)
    }

    @Test
    fun `cannot confirm before the wait is over`() {
        val g = gate()
        g.request(off)
        now += 59_000L
        assertEquals(GateResult.TooEarly(1_000L), g.confirm(off, offSentence, offSentence))
        assertEquals(1_000L, g.waitRemainingMs(off))
    }

    @Test
    fun `confirm needs a request first`() {
        assertEquals(GateResult.NotRequested, gate().confirm(off, offSentence, offSentence))
    }

    @Test
    fun `wrong sentence changes nothing, right one confirms and clears`() {
        val g = gate()
        g.request(off)
        now += 60_000L
        assertEquals(GateResult.WrongSentence, g.confirm(off, offSentence, "turn it off"))
        assertEquals(0L, g.waitRemainingMs(off))
        assertEquals(GateResult.Confirmed, g.confirm(off, offSentence, offSentence.uppercase()))
        assertNull(g.waitRemainingMs(off))
        assertTrue(store.map.isEmpty())
    }

    @Test
    fun `asking again keeps the original instant`() {
        val g = gate()
        g.request(off)
        now += 40_000L
        assertEquals(GateResult.Waiting(20_000L), g.request(off))
        assertEquals(10_000_000L, store.map.getValue(off))
    }

    @Test
    fun `the wait survives a new gate over the same store, like a process kill`() {
        gate().request(off)
        now += 30_000L
        assertEquals(30_000L, gate().waitRemainingMs(off))
    }

    @Test
    fun `an unfinished request lapses 10 minutes after the tap`() {
        val g = gate()
        g.request(off)
        now += FeedPassRule.REQUEST_LAPSE_MS
        assertEquals(0L, g.waitRemainingMs(off))
        now += 1L
        assertNull(g.waitRemainingMs(off))
        assertEquals(GateResult.NotRequested, g.confirm(off, offSentence, offSentence))
        assertTrue(store.map.isEmpty())
    }

    @Test
    fun `a clock set backwards cannot stretch the wait or keep a request alive`() {
        val g = gate()
        g.request(off)
        now += 30_000L
        // Back by less than the elapsed time: clamped, still within a minute.
        now -= 20_000L
        assertEquals(50_000L, g.waitRemainingMs(off))
        // Back to before the tap: the request is gone, not stretched.
        now -= 20_000L
        assertNull(g.waitRemainingMs(off))
    }

    @Test
    fun `cancel drops the request`() {
        val g = gate()
        g.request(off)
        g.cancel(off)
        assertNull(g.waitRemainingMs(off))
    }

    @Test
    fun `targets are independent`() {
        val g = gate()
        g.request("com.instagram.android")
        now += 30_000L
        g.request(off)
        assertEquals(30_000L, g.waitRemainingMs("com.instagram.android"))
        assertEquals(60_000L, g.waitRemainingMs(off))
        assertNull(g.waitRemainingMs("com.reddit.frontpage"))
    }

    @Test
    fun `only an unblock needs the gate`() {
        assertTrue(FeedOffRule.flipNeedsGate(blockedWithoutPass = true))
        assertFalse(FeedOffRule.flipNeedsGate(blockedWithoutPass = false))
    }

    @Test
    fun `an app on a pass is still gated, because the flip is read without the pass`() {
        val sources = object : BlockSources {
            override fun keptPackages() = emptySet<String>()
            override fun systemPackages() = emptySet<String>()
            override fun exemptPackages() = emptySet<String>()
            override fun category(packageName: String) = AppCategoryTag.OTHER
        }
        val overrides = object : AppOverrides {
            val m = HashMap<String, AppOverride>()
            override fun get(packageName: String) = m[packageName]
            override fun set(packageName: String, override: AppOverride?) {
                if (override == null) m.remove(packageName) else m[packageName] = override
            }
        }
        val ig = "com.instagram.android"
        val policy = BlockPolicy("com.kevin.legion", sources, overrides, passLeftMs = { 5 * 60_000L })
        assertFalse(policy.verdict(ig).blocked)
        assertTrue(FeedOffRule.flipNeedsGate(policy.verdictWithoutPass(ig).blocked))
    }

    @Test
    fun `the notice is up only when the switch is on and the service is off`() {
        assertTrue(FeedBlockWatchRule.warn(switchOn = true, accessibilityOn = false))
        assertFalse(FeedBlockWatchRule.warn(switchOn = true, accessibilityOn = true))
        assertFalse(FeedBlockWatchRule.warn(switchOn = false, accessibilityOn = false))
        assertFalse(FeedBlockWatchRule.warn(switchOn = false, accessibilityOn = true))
    }
}
