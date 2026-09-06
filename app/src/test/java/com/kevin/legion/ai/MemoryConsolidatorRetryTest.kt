package com.kevin.legion.ai

import com.kevin.legion.data.local.BackgroundPassState
import com.kevin.legion.data.local.CarDatabase
import com.kevin.legion.data.local.EpisodicTurn
import com.kevin.legion.service.ConversationState
import com.kevin.legion.testutil.RoomTestReset
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * [MemoryConsolidator]'s unbounded retry, fixed 2026-09-06 and pinned here.
 *
 * **The bug.** A null distill left the session's turns in place and retried on the very next pass
 * of a five-minute timer, with no attempt counter, no backoff and no quarantine. One transcript
 * the model would never parse was therefore a paid call every five minutes for as long as the
 * phone stayed on - 288 a day, indefinitely, with nobody present to notice.
 *
 * Less severe in practice than [ReflectionRunawayTest]'s sibling bug and for one reason only: this
 * loop is driven by `episodic_turns`, and that table is emptied by success, so the runaway needs
 * an input that genuinely cannot be distilled. Reflection's runaway needed nothing but an ordinary
 * empty answer.
 */
@RunWith(RobolectricTestRunner::class)
class MemoryConsolidatorRetryTest {
    private val context = RuntimeEnvironment.getApplication()
    private val sessionId = "session-under-test"
    private val vehicleId = "test-car"

    private val calls = mutableListOf<String>()

    @Before
    fun setUp() {
        RoomTestReset.resetCarDatabaseSingleton()
        ConversationState.setBusy(false)
        CompanionProfile.saveGeminiKey(context, "test-key")
        GeminiKeyProvider.init(context)
        calls.clear()
    }

    @After
    fun tearDown() {
        MemoryConsolidator.modelCallForTest = null
        RoomTestReset.drainArchDiskIoPool()
    }

    private fun db() = CarDatabase.getDatabase(context)

    /** Answers with [reply]'s raw text; a null [reply] is a SOFT failure (offline, overloaded). */
    private fun seam(reply: String?) {
        outcomeSeam(if (reply == null) ModelPassOutcome.SoftFailure else ModelPassOutcome.Answered(reply))
    }

    /** The full seam, for the tests that care which KIND of failure came back. */
    private fun outcomeSeam(outcome: ModelPassOutcome) {
        MemoryConsolidator.modelCallForTest = { transcript ->
            calls.add(transcript)
            outcome
        }
    }

    private suspend fun seedTurns() {
        val dao = db().episodicTurnDao()
        dao.insert(
            EpisodicTurn(
                sessionId = sessionId, vehicleId = vehicleId,
                role = EpisodicTurn.Role.DRIVER, text = "something was said", timestamp = 1_000L,
            ),
        )
        dao.insert(
            EpisodicTurn(
                sessionId = sessionId, vehicleId = vehicleId,
                role = EpisodicTurn.Role.COMPANION, text = "and answered", timestamp = 1_001L,
            ),
        )
    }

    /** See [ReflectionRunawayTest.elapseBackoff] - simulates the wait without taking it. */
    private fun elapseBackoff() {
        db().openHelper.writableDatabase.execSQL("UPDATE background_pass_state SET nextAttemptAt = 0")
    }

    private suspend fun passRow() =
        db().backgroundPassStateDao().byKey(BackgroundPassState.consolidationKey(sessionId))

    @Test
    fun `a poisoned transcript is set aside with its reason, and its turns are kept`() = runTest {
        seedTurns()
        seam("the model started explaining itself instead of returning JSON")

        repeat(BackgroundPassState.MAX_ATTEMPTS) {
            elapseBackoff()
            MemoryConsolidator.consolidatePending(context)
        }

        assertEquals(
            "the cap must be the number of paid attempts, not one per pass forever",
            BackgroundPassState.MAX_ATTEMPTS,
            calls.size,
        )
        val pass = checkNotNull(passRow()) { "the session must have recorded its own state" }
        assertTrue("the session must be set aside once the cap is burned", pass.isSetAside)
        assertTrue("and the reason must be recorded, in words", pass.setAsideReason.isNotBlank())
        assertTrue(
            "the reason must say the turns are kept - it is the thing a person reading it needs",
            pass.setAsideReason.contains("kept"),
        )
        assertEquals(
            "set ASIDE, not deleted: the transcript is still the only record of that conversation",
            2,
            db().episodicTurnDao().forSession(sessionId).size,
        )

        elapseBackoff()
        MemoryConsolidator.consolidatePending(context)
        assertEquals(
            "a set-aside session must never be sent to the model again",
            BackgroundPassState.MAX_ATTEMPTS,
            calls.size,
        )
    }

    @Test
    fun `a pass inside the backoff window costs nothing`() = runTest {
        seedTurns()
        seam(null)

        MemoryConsolidator.consolidatePending(context)
        assertEquals(1, calls.size)
        // No elapseBackoff: this is what the five-minute timer's next tick actually looks like, and
        // it used to be a second paid call.
        MemoryConsolidator.consolidatePending(context)
        MemoryConsolidator.consolidatePending(context)
        assertEquals("the backoff must actually hold", 1, calls.size)

        val pass = checkNotNull(passRow())
        assertEquals(1, pass.attempts)
        assertTrue(pass.nextAttemptAt > 0)
    }

    @Test
    fun `a model that answers with nothing finishes the session rather than retrying it`() = runTest {
        seedTurns()
        seam("[]") // read the transcript, found nothing durable in it - a real answer

        MemoryConsolidator.consolidatePending(context)

        assertEquals(1, calls.size)
        assertEquals(
            "an answered-with-nothing distill finishes with the transcript, so its turns go",
            0,
            db().episodicTurnDao().forSession(sessionId).size,
        )
        assertEquals(
            "and the retry row goes with them - one row per PENDING session, not per conversation",
            null,
            passRow(),
        )

        MemoryConsolidator.consolidatePending(context)
        assertEquals("nothing is pending, so nothing is paid for", 1, calls.size)
    }

    @Test
    fun `no gemini key means no call at all`() = runTest {
        // NOT already true before 2026-09-06: distill() went straight to SubAgent, which built a
        // URL with an empty key and fired a real request per pending session, every five minutes.
        seedTurns()
        seam("[]")
        CompanionProfile.saveGeminiKey(context, "")
        GeminiKeyProvider.init(context)

        MemoryConsolidator.consolidatePending(context)

        assertEquals(0, calls.size)
        assertEquals(
            "and the turns are untouched, so nothing is lost by declining to run",
            2,
            db().episodicTurnDao().forSession(sessionId).size,
        )
    }

    @Test
    fun `a hard failure stops the whole sweep after one call, not one call per session`() = runTest {
        // Three pending conversations and an exhausted key. Every session would fail identically,
        // so the sweep must learn that once - the difference between one wasted call and one per
        // conversation the phone has ever had, every five minutes.
        seedTurns()
        val dao = db().episodicTurnDao()
        for (extra in listOf("session-b", "session-c")) {
            dao.insert(
                EpisodicTurn(
                    sessionId = extra, vehicleId = vehicleId,
                    role = EpisodicTurn.Role.DRIVER, text = "another conversation", timestamp = 2_000L,
                ),
            )
        }
        outcomeSeam(ModelPassOutcome.from(AgentResult.RateLimited))

        MemoryConsolidator.consolidatePending(context)

        assertEquals("three pending sessions, one call", 1, calls.size)
        val pass = checkNotNull(passRow())
        assertTrue("and the session it tried is set aside on the first hard failure", pass.isSetAside)
        assertTrue(pass.setAsideReason.contains("quota"))
        assertEquals(
            "every transcript is kept - nothing is lost by declining to spend",
            2,
            db().episodicTurnDao().forSession(sessionId).size,
        )
    }

    @Test
    fun `offline stays soft, so a tunnel does not park a transcript for good`() = runTest {
        seedTurns()
        outcomeSeam(ModelPassOutcome.from(AgentResult.Offline))

        MemoryConsolidator.consolidatePending(context)

        val pass = checkNotNull(passRow())
        assertTrue("a dead signal is not a dead key", !pass.isSetAside)
        assertEquals(1, pass.attempts)
    }
}
