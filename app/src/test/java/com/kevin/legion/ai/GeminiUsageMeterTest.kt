package com.kevin.legion.ai

import com.kevin.legion.data.local.CarDatabase
import com.kevin.legion.data.local.GeminiUsage
import com.kevin.legion.testutil.RoomTestReset
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * [GeminiUsageMeter] - the first thing in LEGION that records a MEASURED token count.
 *
 * Every test here goes through the `...Now` suspend bodies rather than the fire-and-forget public
 * entry points, so the assertion runs after the write rather than racing it. See
 * [GeminiUsageMeter.recordRestCallNow]'s doc for why that split exists.
 */
@RunWith(RobolectricTestRunner::class)
class GeminiUsageMeterTest {
    private val context = RuntimeEnvironment.getApplication()

    @Before
    fun setUp() {
        RoomTestReset.resetCarDatabaseSingleton()
        GeminiUsageMeter.resetForTest(context)
    }

    @After
    fun tearDown() {
        GeminiUsageMeter.resetForTest(null)
        RoomTestReset.drainArchDiskIoPool()
    }

    private fun dao() = CarDatabase.getDatabase(context).geminiUsageDao()

    @Test
    fun `one live session is one row however many usage reports arrive`() = runTest {
        // The Live API sends usageMetadata repeatedly on one socket. One row per SESSION, not per
        // message, is what makes a spend figure line up with a conversation.
        GeminiUsageMeter.recordLiveUsageNow(context, "session-a", "live-model", 100, 20, 130)
        GeminiUsageMeter.recordLiveUsageNow(context, "session-a", "live-model", 250, 60, 340)
        GeminiUsageMeter.recordLiveUsageNow(context, "session-a", "live-model", 400, 90, 520)

        val row = checkNotNull(dao().bySessionKey("session-a"))
        assertEquals("three reports, one row", 3, row.reports)
        assertEquals(GeminiUsage.SURFACE_LIVE, row.surface)
        assertEquals(400, row.promptTokens)
        assertEquals(90, row.responseTokens)
        assertEquals(520, row.totalTokens)
    }

    @Test
    fun `repeat reports are maxed, never summed`() = runTest {
        // Whether Live's usageMetadata is cumulative or incremental is NOT verified on device.
        // Maxing is exact if it is cumulative and a floor if it is incremental; summing would
        // multiply the real figure by the message count. See GeminiUsage's class doc.
        GeminiUsageMeter.recordLiveUsageNow(context, "session-b", "m", 100, 10, 110)
        GeminiUsageMeter.recordLiveUsageNow(context, "session-b", "m", 100, 10, 110)
        GeminiUsageMeter.recordLiveUsageNow(context, "session-b", "m", 100, 10, 110)

        val row = checkNotNull(dao().bySessionKey("session-b"))
        assertEquals("the total must be the greatest seen, not the sum of the three", 110, row.totalTokens)
        assertEquals("but the report count is the evidence that says which arithmetic applied", 3, row.reports)
    }

    @Test
    fun `an out-of-order report never drags a figure backwards`() = runTest {
        GeminiUsageMeter.recordLiveUsageNow(context, "session-c", "m", 900, 200, 1100)
        GeminiUsageMeter.recordLiveUsageNow(context, "session-c", "m", 100, 20, 120)

        val row = checkNotNull(dao().bySessionKey("session-c"))
        assertEquals(900, row.promptTokens)
        assertEquals(1100, row.totalTokens)
    }

    @Test
    fun `a report that omits a field does not erase one an earlier report gave`() = runTest {
        // SQLite's MAX(x, y) is NULL when EITHER argument is NULL, which would silently wipe a
        // figure the API had already reported. GeminiUsageDao.foldReport coalesces around that.
        GeminiUsageMeter.recordLiveUsageNow(context, "session-d", "m", 500, 100, 600)
        GeminiUsageMeter.recordLiveUsageNow(context, "session-d", "m", null, null, null)

        val row = checkNotNull(dao().bySessionKey("session-d"))
        assertEquals(500, row.promptTokens)
        assertEquals(100, row.responseTokens)
        assertEquals(600, row.totalTokens)
    }

    @Test
    fun `a count the API never reported stays null and is never stored as zero`() = runTest {
        // The single most important property of this table. Gemini omits usageMetadata on some 200
        // responses; storing that unknown as 0 would let the Setup screen report "0 tokens" over a
        // window of unmeasured calls - telling Kevin he is fine exactly when the app cannot see.
        GeminiUsageMeter.recordLiveUsageNow(context, "session-e", "m", null, null, null)

        val row = checkNotNull(dao().bySessionKey("session-e"))
        assertNull(row.promptTokens)
        assertNull(row.responseTokens)
        assertNull(row.totalTokens)
        assertEquals("the call still happened, and that is a fact worth keeping", 1, row.reports)
    }

    @Test
    fun `a blank session key is dropped rather than merged under an empty key`() = runTest {
        GeminiUsageMeter.recordLiveUsage("", "m", 10, 10, 20)
        // The public entry point returns before touching the scope at all when the key is blank,
        // so there is nothing to await here.
        assertNull(dao().bySessionKey(""))
    }

    @Test
    fun `every rest call is its own row, because rest counts sum`() = runTest {
        GeminiUsageMeter.recordRestCallNow(context, "flash-lite", 100, 20, 120)
        GeminiUsageMeter.recordRestCallNow(context, "flash-lite", 200, 40, 240)
        GeminiUsageMeter.recordRestCallNow(context, "flash-lite", 300, 60, 360)

        assertEquals(3, dao().rowCountSince(0))
        assertEquals(720L, dao().totalTokensSince(0))
    }

    @Test
    fun `an unreported rest call is counted separately from the sum it is missing from`() = runTest {
        GeminiUsageMeter.recordRestCallNow(context, "m", 100, 20, 120)
        GeminiUsageMeter.recordRestCallNow(context, "m", null, null, null)

        assertEquals("the sum reports only what was measured", 120L, dao().totalTokensSince(0))
        assertEquals("and this is how the sentence knows to call it a floor", 1, dao().unreportedCountSince(0))
        assertEquals(2, dao().rowCountSince(0))
    }

    @Test
    fun `a window where nothing reported a total sums to null, not zero`() = runTest {
        GeminiUsageMeter.recordRestCallNow(context, "m", null, null, null)
        GeminiUsageMeter.recordRestCallNow(context, "m", null, null, null)

        assertNull(
            "SUM over all-null must stay null - the caller turns this into words, not a 0",
            dao().totalTokensSince(0),
        )
        assertEquals(2, dao().rowCountSince(0))
    }

    @Test
    fun `connects are counted per day and split by whether anyone spoke`() = runTest {
        GeminiUsageMeter.recordConnectNow(context, carriedTurn = false)
        GeminiUsageMeter.recordConnectNow(context, carriedTurn = false)
        GeminiUsageMeter.recordConnectNow(context, carriedTurn = false)
        GeminiUsageMeter.recordConnectNow(context, carriedTurn = true)

        val spend = checkNotNull(GeminiUsageMeter.spend(context))
        assertEquals(3, spend.connectsThisMonth)
        assertEquals(1, spend.connectsWithTurnThisMonth)
        assertEquals(
            "two sockets opened, paid for their setup prompt and were never spoken into",
            2,
            spend.connectsWithoutTurnThisMonth,
        )
    }

    @Test
    fun `spend reads back what was measured, today and this month`() = runTest {
        GeminiUsageMeter.recordRestCallNow(context, "m", 100, 20, 120)
        GeminiUsageMeter.recordLiveUsageNow(context, "s", "m", 1000, 500, 1500)

        val spend = checkNotNull(GeminiUsageMeter.spend(context))
        assertEquals(1620L, spend.tokensToday)
        assertEquals(1620L, spend.tokensThisMonth)
        assertEquals(2, spend.callsToday)
        assertEquals(0, spend.unreportedToday)
        assertTrue("nothing has been set aside in a clean database", spend.setAside.isEmpty())
    }

    @Test
    fun `metering before init is a silent no-op, never a crash`() = runTest {
        GeminiUsageMeter.resetForTest(null)
        GeminiUsageMeter.recordRestCall("m", 1, 1, 2)
        GeminiUsageMeter.recordLiveUsage("s", "m", 1, 1, 2)
        GeminiUsageMeter.recordLiveConnect()
        GeminiUsageMeter.recordLiveConnectCarriedTurn()
        // Reaching this line without throwing IS the assertion: observability must never break the
        // thing it observes, and SubAgent's REST path calls this from inside a live HTTP handler.
        GeminiUsageMeter.resetForTest(context)
        assertEquals(0, dao().rowCountSince(0))
    }
}
