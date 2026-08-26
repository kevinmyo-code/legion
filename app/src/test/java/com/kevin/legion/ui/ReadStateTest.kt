package com.kevin.legion.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Backend-erp phase 3. Pure JUnit, no Robolectric, matching `TodayGapResolversTest` - the whole
 * point of keeping [readStateLine] Compose-free is that its rules are checkable without a screen.
 *
 * These tests are the reason the staleness rule can ship before phase 4. The app cannot trigger it
 * yet, because reads are local and complete in milliseconds, so without these the rule would be an
 * untested half sitting in the tree - which is precisely what the `DatabaseSnapshot` restore turned
 * out to be on the one path nobody had exercised.
 */
class ReadStateTest {

    private val t0 = 1_800_000_000_000L

    @Test
    fun `fresh data says nothing at all`() {
        // Silence is the correct output for the normal case. A permanent "as of just now" would
        // train the eye past exactly the line that matters once reads go remote.
        val line = readStateLine(ReadState(loading = false, loadedAtMs = t0), nowMs = t0 + 1_000)
        assertNull(line)
    }

    @Test
    fun `data just under the threshold still says nothing`() {
        val line = readStateLine(
            ReadState(loading = false, loadedAtMs = t0),
            nowMs = t0 + READ_STALE_AFTER_MS - 1,
        )
        assertNull(line)
    }

    @Test
    fun `data at the threshold is called out`() {
        val line = readStateLine(
            ReadState(loading = false, loadedAtMs = t0),
            nowMs = t0 + READ_STALE_AFTER_MS,
        )
        assertTrue("stale data must produce a line", line != null)
        assertTrue(line!!.advisory)
        assertTrue("should say how old, not just that it is old", line.text.contains("10 minutes"))
    }

    @Test
    fun `a failure with existing data keeps the data and says the refresh failed`() {
        // Kevin's ruling, 2026-08-26: keep last good data, say so in words. The screen must not
        // blank working figures over a transient blip, and must not show them silently either.
        val line = readStateLine(
            ReadState(loading = false, loadedAtMs = t0, failure = "no connection"),
            nowMs = t0 + 1_000,
        )
        assertTrue(line != null)
        assertTrue(line!!.advisory)
        assertTrue("must say the shown data is the last read", line.text.contains("last data read"))
        assertTrue("must carry the underlying reason", line.text.contains("no connection"))
    }

    @Test
    fun `a failure with no data ever loaded says there is nothing to trust`() {
        // Distinct from the case above and deliberately worded differently: there are no figures
        // on screen at all, so "showing the last data read" would be a lie.
        val line = readStateLine(
            ReadState(loading = false, loadedAtMs = null, failure = "no connection"),
            nowMs = t0,
        )
        assertTrue(line != null)
        assertEquals("Couldn't load this. no connection", line!!.text)
        assertTrue(line.advisory)
    }

    @Test
    fun `a failure outranks staleness`() {
        // Both are true at once; the failure is the more actionable fact and the age would be
        // misleading on its own, since the data may be far older than the last ATTEMPT suggests.
        val line = readStateLine(
            ReadState(loading = false, loadedAtMs = t0, failure = "timed out"),
            nowMs = t0 + READ_STALE_AFTER_MS * 5,
        )
        assertTrue(line!!.text.contains("timed out"))
        assertTrue("the failure wording wins", line.text.contains("Refresh failed"))
    }

    @Test
    fun `isFirstLoad and hasData are opposites and key off loadedAtMs, never off loading`() {
        // A screen that has failed every attempt since launch is NOT loading, has no data, and
        // must say so rather than showing an eternal spinner.
        val never = ReadState(loading = false, loadedAtMs = null, failure = "boom")
        assertTrue(never.isFirstLoad)
        assertTrue(!never.hasData)

        val loaded = ReadState(loading = false, loadedAtMs = t0)
        assertTrue(!loaded.isFirstLoad)
        assertTrue(loaded.hasData)

        // Still loading, but a previous load succeeded: there IS data behind the screen.
        val refreshing = ReadState(loading = true, loadedAtMs = t0)
        assertTrue(refreshing.hasData)
    }

    @Test
    fun `compactAge rounds to something a person would say`() {
        assertEquals("a minute", compactAge(30_000))
        assertEquals("a minute", compactAge(60_000))
        assertEquals("9 minutes", compactAge(9 * 60_000L))
        assertEquals("an hour", compactAge(60 * 60_000L))
        assertEquals("3 hours", compactAge(3 * 60 * 60_000L))
        assertEquals("a day", compactAge(24 * 60 * 60_000L))
        assertEquals("2 days", compactAge(48 * 60 * 60_000L))
    }

    @Test
    fun `the default ReadState is the honest pre-load state`() {
        // Defaults matter here: a screen that has not started yet must read as loading with no
        // data, not as loaded-and-empty. That collision is what phase 3 exists to remove.
        val fresh = ReadState()
        assertTrue(fresh.loading)
        assertTrue(fresh.isFirstLoad)
        assertNull(readStateLine(fresh, nowMs = t0))
    }
}
