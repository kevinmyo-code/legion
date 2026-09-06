package com.kevin.legion.ai

import com.kevin.legion.data.local.BackgroundPassState
import com.kevin.legion.data.local.CarDatabase
import com.kevin.legion.data.local.CompanionMemory
import com.kevin.legion.service.ConversationState
import com.kevin.legion.testutil.RoomTestReset
import com.kevin.legion.vehicle.ActiveVehicle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * The runaway [ReflectionEngine] was fixed for on 2026-09-06, pinned so it cannot come back.
 *
 * **The bug.** The "have we reflected on this yet" mark was derived from the newest
 * [CompanionMemory.Source.REFLECTION] row. A model that answered with an EMPTY insight list wrote
 * no row, so the mark never advanced, the same consolidated memories were still past it, the
 * importance sum was still over threshold, and the identical set went back to the model on the
 * next pass of a five-minute timer - 288 paid calls a day, forever, for an answer already given.
 * The model never had to fail; it only had to judge there was nothing worth saying, which is the
 * ordinary case once a cluster has been reflected on once.
 *
 * **The distinction every test here turns on** is between a call that ANSWERED with nothing and a
 * call that FAILED. Only the second is retryable. [ReflectionEngine.modelCallForTest] is
 * deliberately a raw-text seam so these tests drive that distinction through the REAL parser
 * rather than around it: `"[]"` is an empty answer, `null` is a failure.
 */
@RunWith(RobolectricTestRunner::class)
class ReflectionRunawayTest {
    private val context = RuntimeEnvironment.getApplication()
    private val vehicleId = "test-car"

    /** Every call the engine made to the model this test, in order, so a test can assert on how
     *  many times it PAID rather than only on what it wrote. */
    private val calls = mutableListOf<String>()

    @Before
    fun setUp() {
        RoomTestReset.resetCarDatabaseSingleton()
        ConversationState.setBusy(false)
        ActiveVehicle.select(context, vehicleId)
        // Both loops now refuse to run without a key, so a test of anything else has to supply
        // one. Plaintext fallback under Robolectric, same as ProactiveBusTest.
        CompanionProfile.saveGeminiKey(context, "test-key")
        GeminiKeyProvider.init(context)
        calls.clear()
    }

    @After
    fun tearDown() {
        ReflectionEngine.modelCallForTest = null
        ActiveVehicle.select(context, null)
        RoomTestReset.drainArchDiskIoPool()
    }

    private fun db() = CarDatabase.getDatabase(context)

    /** Answers with [reply]'s raw text; a null [reply] is a SOFT failure (offline, overloaded). */
    private fun seam(reply: String?) {
        outcomeSeam(if (reply == null) ModelPassOutcome.SoftFailure else ModelPassOutcome.Answered(reply))
    }

    /** The full seam, for the tests that care which KIND of failure came back. */
    private fun outcomeSeam(outcome: ModelPassOutcome) {
        ReflectionEngine.modelCallForTest = { listing ->
            calls.add(listing)
            outcome
        }
    }

    /** Enough consolidated material to clear the importance threshold (30), newest at [newestAt]. */
    private suspend fun seedConsolidated(newestAt: Long) {
        val dao = db().companionMemoryDao()
        for (i in 0 until 5) {
            dao.insert(
                CompanionMemory(
                    vehicleId = vehicleId,
                    text = "consolidated memory $i",
                    category = CompanionMemory.Category.DRIVER,
                    source = CompanionMemory.Source.CONSOLIDATED,
                    importance = 8,
                    createdAt = newestAt - (4L - i) * 1000L,
                    lastAccessedAt = newestAt,
                    updatedAtMs = newestAt,
                ),
            )
        }
    }

    /**
     * Simulates the backoff window elapsing, without making the test wait a real quarter of an
     * hour. Deliberately leaves `attempts` alone - clearing that too would be testing a different
     * thing (fresh input arriving), and would hide a cap that never counted.
     */
    private fun elapseBackoff() {
        db().openHelper.writableDatabase.execSQL("UPDATE background_pass_state SET nextAttemptAt = 0")
    }

    private suspend fun passRow() =
        db().backgroundPassStateDao().byKey(BackgroundPassState.reflectionKey(vehicleId))

    @Test
    fun `an empty but successful reflection advances the watermark and never runs again`() = runTest {
        val newest = 10_000_000L
        seedConsolidated(newest)
        seam("[]") // the model answered, and its answer was "nothing here"

        ReflectionEngine.reflectIfDue(context)
        ReflectionEngine.reflectIfDue(context)
        ReflectionEngine.reflectIfDue(context)

        assertEquals(
            "the same memories must be sent to the model exactly once - this is the whole bug",
            1,
            calls.size,
        )
        assertEquals(
            "an empty answer writes no reflection row, which is why the old watermark never moved",
            0,
            db().companionMemoryDao().bySource(vehicleId, CompanionMemory.Source.REFLECTION).size,
        )
        val pass = checkNotNull(passRow()) { "the pass must have recorded its own state" }
        assertEquals(
            "the watermark must sit on the newest memory the successful call considered",
            newest,
            pass.watermark,
        )
        assertEquals("an answered call is not a failure", 0, pass.attempts)
        assertTrue("an answered call must not be set aside", !pass.isSetAside)
    }

    @Test
    fun `material arriving after an empty answer is reflected on, so the watermark is not a mute`() = runTest {
        seedConsolidated(10_000_000L)
        seam("[]")
        ReflectionEngine.reflectIfDue(context)
        assertEquals(1, calls.size)

        // Five more memories, all newer than the mark. The fix must stop the loop repeating itself,
        // not stop it working.
        seedConsolidated(20_000_000L)
        ReflectionEngine.reflectIfDue(context)

        assertEquals("new material past the watermark must still be reflected on", 2, calls.size)
        assertEquals(20_000_000L, checkNotNull(passRow()).watermark)
    }

    @Test
    fun `a failed reflection retries, backs off, and is capped`() = runTest {
        seedConsolidated(10_000_000L)
        seam(null) // the call did not come back with an answer

        ReflectionEngine.reflectIfDue(context)
        assertEquals("a failure is retryable, so the first attempt happens", 1, calls.size)
        val first = checkNotNull(passRow())
        assertEquals(1, first.attempts)
        assertTrue("a failure must park the next attempt behind a backoff", first.nextAttemptAt > 0)

        // Without the clock moving, the very next pass must NOT pay again. This is the five-minute
        // timer's normal case and the one that used to cost a call every time.
        ReflectionEngine.reflectIfDue(context)
        assertEquals("a pass inside the backoff window must not call the model", 1, calls.size)

        // Drive it to the cap, one elapsed window at a time.
        var previousBackoff = first.nextAttemptAt
        for (attempt in 2..BackgroundPassState.MAX_ATTEMPTS) {
            elapseBackoff()
            ReflectionEngine.reflectIfDue(context)
            assertEquals("attempt $attempt should have called the model", attempt, calls.size)
            val row = checkNotNull(passRow())
            if (attempt < BackgroundPassState.MAX_ATTEMPTS) {
                assertEquals(attempt, row.attempts)
                assertNotEquals(
                    "each backoff must be longer than the last, not a fixed retry",
                    previousBackoff,
                    row.nextAttemptAt,
                )
                previousBackoff = row.nextAttemptAt
            }
        }

        val capped = checkNotNull(passRow())
        assertTrue("the cap must set the pass aside", capped.isSetAside)
        assertTrue("the reason must be recorded, in words", capped.setAsideReason.isNotBlank())

        elapseBackoff()
        ReflectionEngine.reflectIfDue(context)
        assertEquals(
            "a set-aside pass must never call the model again on the same material",
            BackgroundPassState.MAX_ATTEMPTS,
            calls.size,
        )
    }

    @Test
    fun `new material revives a set-aside pass, because the input is no longer the one that failed`() = runTest {
        seedConsolidated(10_000_000L)
        seam(null)
        repeat(BackgroundPassState.MAX_ATTEMPTS) {
            elapseBackoff()
            ReflectionEngine.reflectIfDue(context)
        }
        assertTrue(checkNotNull(passRow()).isSetAside)
        val callsWhenSetAside = calls.size

        // Comfortably past the pass row's own updatedAt, so "newer than the failed attempts"
        // is unambiguous rather than resting on millisecond ties.
        seedConsolidated(System.currentTimeMillis() + 60_000L)
        seam("[]")
        ReflectionEngine.reflectIfDue(context)

        assertEquals(
            "material the failed attempts never saw deserves one more go",
            callsWhenSetAside + 1,
            calls.size,
        )
        assertTrue("and a success clears the set-aside", !checkNotNull(passRow()).isSetAside)
    }

    @Test
    fun `unparseable output is a failure, not an empty answer`() = runTest {
        // The seam is raw text on purpose: this asserts the REAL parser draws the line, so a model
        // that starts replying in prose is treated as a broken call (bounded retry) rather than as
        // a considered "nothing here" (watermark advanced, material silently skipped).
        seedConsolidated(10_000_000L)
        seam("I could not find a pattern in these, sorry.")

        ReflectionEngine.reflectIfDue(context)

        val pass = checkNotNull(passRow())
        assertEquals("unparseable output must count as a failed attempt", 1, pass.attempts)
        assertEquals("and must NOT advance the watermark", 0L, pass.watermark)
    }

    @Test
    fun `no gemini key means no call at all`() = runTest {
        // This was NOT already true before 2026-09-06: the pass went straight to SubAgent, which
        // built a URL with an empty key and fired a real request every five minutes forever.
        seedConsolidated(10_000_000L)
        seam("[]")
        CompanionProfile.saveGeminiKey(context, "")
        GeminiKeyProvider.init(context)

        ReflectionEngine.reflectIfDue(context)

        assertEquals("an unconfigured install must make no call", 0, calls.size)
    }

    @Test
    fun `material below the importance threshold is not sent to the model`() = runTest {
        // The original gate, unchanged - asserted here so the runaway fix cannot quietly remove it.
        val dao = db().companionMemoryDao()
        dao.insert(
            CompanionMemory(
                vehicleId = vehicleId,
                text = "one small thing",
                category = CompanionMemory.Category.DRIVER,
                source = CompanionMemory.Source.CONSOLIDATED,
                importance = 3,
                createdAt = 10_000_000L,
                lastAccessedAt = 10_000_000L,
                updatedAtMs = 10_000_000L,
            ),
        )
        seam("[]")

        ReflectionEngine.reflectIfDue(context)

        assertEquals(0, calls.size)
    }

    @Test
    fun `a hard failure stops on the FIRST one, not the fifth`() = runTest {
        // Kevin, 2026-09-06: "i ran out of credits and im probably not gonna top up for a while."
        // A key with no quota fails identically every time, so the five attempts a soft failure
        // earns are five guaranteed-pointless calls. Hard failures skip straight to set-aside.
        seedConsolidated(10_000_000L)
        outcomeSeam(ModelPassOutcome.from(AgentResult.RateLimited))

        ReflectionEngine.reflectIfDue(context)

        assertEquals(1, calls.size)
        val pass = checkNotNull(passRow())
        assertTrue("one hard failure is enough", pass.isSetAside)
        assertTrue(pass.setAsideReason.contains("quota"))

        elapseBackoff()
        ReflectionEngine.reflectIfDue(context)
        ReflectionEngine.reflectIfDue(context)
        assertEquals("and nothing tries again on an exhausted key", 1, calls.size)
    }

    @Test
    fun `a rejected key is hard too, and says so rather than blaming quota`() = runTest {
        seedConsolidated(10_000_000L)
        outcomeSeam(ModelPassOutcome.from(AgentResult.KeyInvalid))

        ReflectionEngine.reflectIfDue(context)

        val pass = checkNotNull(passRow())
        assertTrue(pass.isSetAside)
        assertTrue(pass.setAsideReason.contains("rejected"))
    }

    @Test
    fun `offline and overloaded stay SOFT, so a dead signal does not park memory work for good`() = runTest {
        // The over-reach in treating 429 as permanent is deliberate and bounded; it must not spread
        // to failures the server told us were transient in as many words.
        seedConsolidated(10_000_000L)
        outcomeSeam(ModelPassOutcome.from(AgentResult.Offline))
        ReflectionEngine.reflectIfDue(context)
        assertTrue("offline must not set the pass aside", !checkNotNull(passRow()).isSetAside)

        outcomeSeam(ModelPassOutcome.from(AgentResult.Overloaded))
        elapseBackoff()
        ReflectionEngine.reflectIfDue(context)
        assertTrue("nor must overloaded", !checkNotNull(passRow()).isSetAside)
        assertEquals(2, checkNotNull(passRow()).attempts)
    }
}
