package com.kevin.legion.dumb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** In-memory [FeedPassStore]: what a second controller reads is what the first wrote. */
class FakeFeedPassStore : FeedPassStore {
    val map = HashMap<String, FeedPass>()
    override fun get(packageName: String) = map[packageName]
    override fun set(packageName: String, pass: FeedPass?) {
        if (pass == null) map.remove(packageName) else map[packageName] = pass
    }
    override fun all(): Map<String, FeedPass> = map.toMap()
}

/** The 15 minute pass (ADR 0066): wall-clock wait, sentence, one app, refused in Matrix mode. */
class FeedPassTest {
    private var now = 10_000_000L
    private var matrix = false
    private val store = FakeFeedPassStore()
    private fun controller() = FeedPassController(store, matrixOn = { matrix }) { now }

    private val ig = "com.instagram.android"
    private val label = "Instagram"
    private val sentence = "I am choosing to open Instagram for 15 minutes"

    private fun granted(c: FeedPassController = controller()): PassResult {
        c.request(ig)
        now += FeedPassRule.WAIT_MS
        return c.grant(ig, label, sentence)
    }

    @Test
    fun `the sentence names the app and is case-insensitive and trimmed`() {
        assertEquals(sentence, FeedPassRule.sentence(label))
        assertTrue(FeedPassRule.sentenceMatches("  i am choosing to OPEN instagram for 15 minutes ", label))
        assertFalse(FeedPassRule.sentenceMatches("I am choosing to open TikTok for 15 minutes", label))
        assertFalse(FeedPassRule.sentenceMatches(DumbLeaveRule.SENTENCE, label))
    }

    @Test
    fun `cannot grant before the 60 second wait is over`() {
        val c = controller()
        assertEquals(PassResult.Waiting(60_000L), c.request(ig))
        now += 59_000L
        assertEquals(PassResult.TooEarly(1_000L), c.grant(ig, label, sentence))
        assertNull(c.passRemainingMs(ig))
    }

    @Test
    fun `asking again while waiting keeps the original instant`() {
        val c = controller()
        c.request(ig)
        now += 40_000L
        assertEquals(PassResult.Waiting(20_000L), c.request(ig))
        assertEquals(10_000_000L, store.map.getValue(ig).requestedAtMs)
    }

    @Test
    fun `the wait runs on the wall clock, so a new controller after a process kill sees it`() {
        controller().request(ig)
        now += 30_000L
        assertEquals(30_000L, controller().waitRemainingMs(ig))
        now += 30_000L
        assertEquals(PassResult.Granted(now + FeedPassRule.PASS_MS), controller().grant(ig, label, sentence))
    }

    @Test
    fun `a wrong sentence grants nothing`() {
        val c = controller()
        c.request(ig)
        now += FeedPassRule.WAIT_MS
        assertEquals(PassResult.WrongSentence, c.grant(ig, label, "let me in"))
        assertNull(c.passRemainingMs(ig))
    }

    @Test
    fun `granting without a request is refused`() {
        assertEquals(PassResult.NotRequested, controller().grant(ig, label, sentence))
    }

    @Test
    fun `a granted pass lasts 15 minutes and then is gone`() {
        val c = controller()
        assertTrue(granted(c) is PassResult.Granted)
        assertEquals(FeedPassRule.PASS_MS, c.passRemainingMs(ig))
        now += FeedPassRule.PASS_MS - 1L
        assertEquals(1L, c.passRemainingMs(ig))
        now += 1L
        assertNull(c.passRemainingMs(ig))
        assertTrue("a spent pass is cleared from the store", store.map.isEmpty())
    }

    @Test
    fun `one pass at a time per app, and only that app`() {
        val c = controller()
        val until = (granted(c) as PassResult.Granted).untilMs
        assertEquals(PassResult.AlreadyOpen(until), c.request(ig))
        assertNull(c.passRemainingMs("com.zhiliaoapp.musically"))
    }

    @Test
    fun `refused while Matrix mode is on, both asking and granting`() {
        matrix = true
        val c = controller()
        assertEquals(PassResult.MatrixOn, c.request(ig))
        assertTrue(store.map.isEmpty())
        matrix = false
        c.request(ig)
        now += FeedPassRule.WAIT_MS
        matrix = true
        assertEquals(PassResult.MatrixOn, c.grant(ig, label, sentence))
    }

    @Test
    fun `a wall clock set backwards cannot stretch the wait`() {
        assertEquals(FeedPassRule.WAIT_MS, FeedPassRule.waitRemainingMs(requestedAtMs = 5_000L, nowMs = 1_000L))
        assertEquals(0L, FeedPassRule.waitRemainingMs(requestedAtMs = 0L, nowMs = 999_999L))
    }

    @Test
    fun `a wall clock set backwards cannot stretch the pass`() {
        val farFuture = 10L * FeedPassRule.PASS_MS
        assertEquals(FeedPassRule.PASS_MS, FeedPassRule.passRemainingMs(grantedUntilMs = farFuture, nowMs = 0L))
        val c = controller()
        granted(c)
        now -= 60L * 60_000L
        assertNull("a clock set back to before the request ends the pass", c.passRemainingMs(ig))
    }

    @Test
    fun `an unfinished request lapses after 10 minutes`() {
        val c = controller()
        c.request(ig)
        now += FeedPassRule.REQUEST_LAPSE_MS + 1L
        assertNull(c.waitRemainingMs(ig))
        assertEquals(PassResult.NotRequested, c.grant(ig, label, sentence))
        assertEquals("a fresh request starts a fresh wait", PassResult.Waiting(60_000L), c.request(ig))
    }

    @Test
    fun `going back drops a pending request but never a running pass`() {
        val c = controller()
        c.request(ig)
        c.cancelRequest(ig)
        assertNull(c.waitRemainingMs(ig))
        granted(c)
        c.cancelRequest(ig)
        assertEquals(FeedPassRule.PASS_MS, c.passRemainingMs(ig))
    }

    @Test
    fun `running lists only granted, unspent passes`() {
        val c = controller()
        c.request("com.reddit.frontpage")
        val until = (granted(c) as PassResult.Granted).untilMs
        assertEquals(mapOf(ig to until), c.running())
        assertEquals(until, c.grantedUntilMs(ig))
        assertNull(c.grantedUntilMs("com.reddit.frontpage"))
        now = until
        assertNull(c.grantedUntilMs(ig))
    }

    @Test
    fun `pass words round minutes up`() {
        assertEquals("15 min pass, 15 min left", FeedPassRule.words(FeedPassRule.PASS_MS))
        assertEquals("15 min pass, 1 min left", FeedPassRule.words(1L))
        assertEquals("15 min pass, 2 min left", FeedPassRule.words(60_001L))
    }

    // Expiry while the passed app is still in front (the service's Handler check).

    @Test
    fun `expiry bounces only when the passed app is still the last foreground window`() {
        val until = 1_000_000L
        assertTrue(FeedPassRule.bounceAtExpiry(ig, ig, until, until))
        assertFalse("he already left it", FeedPassRule.bounceAtExpiry("com.bank.app", ig, until, until))
        assertFalse("nothing seen yet", FeedPassRule.bounceAtExpiry(null, ig, until, until))
        assertFalse("the pass has not run out", FeedPassRule.bounceAtExpiry(ig, ig, until, until - 1L))
    }

    @Test
    fun `the foreground tracker ignores overlays with no launcher entry`() {
        val t = ForegroundTracker()
        t.note(ig, countsAsApp = true)
        t.note("com.android.systemui", countsAsApp = false)
        t.note("com.samsung.android.honeyboard", countsAsApp = false)
        t.note(null, countsAsApp = true)
        assertEquals(ig, t.last)
        t.note("home.pkg", countsAsApp = true)
        assertEquals("home.pkg", t.last)
    }

    @Test
    fun `end to end, a passed app is open, then blocked again at expiry`() {
        val c = controller()
        val policy = BlockPolicy(
            "com.kevin.legion",
            object : BlockSources {
                override fun keptPackages() = emptySet<String>()
                override fun systemPackages() = emptySet<String>()
                override fun exemptPackages() = emptySet<String>()
                override fun category(packageName: String) = AppCategoryTag.OTHER
            },
            object : AppOverrides {
                override fun get(packageName: String): AppOverride? = null
                override fun set(packageName: String, override: AppOverride?) = Unit
            },
            matrixOn = { matrix },
            passLeftMs = c::passRemainingMs,
        )
        assertTrue(policy.isBlocked(ig))
        granted(c)
        assertEquals(BlockReason.PASS, policy.verdict(ig).reason)
        now += FeedPassRule.PASS_MS
        assertTrue(policy.isBlocked(ig))
    }
}
