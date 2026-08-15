package com.kevin.legion.data.local

import com.kevin.legion.testutil.RoomTestReset
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * DAO-level regression for [AdvisorAdviceDao.recent] - the window that rides each digest (answer
 * call 7): newest-first, capped at [limit], scoped to one aspect. Same Robolectric shape as
 * [GoalDaoTest]/[CarDatabaseFreshInstallTest].
 */
@RunWith(RobolectricTestRunner::class)
class AdvisorAdviceDaoTest {
    private val context = RuntimeEnvironment.getApplication()
    private val dao get() = CarDatabase.getDatabase(context).advisorAdviceDao()

    @Before
    fun clearState() {
        RoomTestReset.resetCarDatabaseSingleton()
    }

    @Test
    fun `recent returns an aspect's advice newest-first, capped at limit`() = runBlocking {
        dao.insert(
            AdvisorAdvice(
                aspect = "log", questionText = "q1", gist = "gist-1", adviceText = "a1", createdAt = 1000,
            )
        )
        dao.insert(
            AdvisorAdvice(
                aspect = "log", questionText = "q2", gist = "gist-2", adviceText = "a2", createdAt = 3000,
            )
        )
        dao.insert(
            AdvisorAdvice(
                aspect = "log", questionText = "q3", gist = "gist-3", adviceText = "a3", createdAt = 2000,
            )
        )
        // A different aspect's advice must never leak into "log"'s window.
        dao.insert(
            AdvisorAdvice(
                aspect = "fleet", questionText = "q4", gist = "gist-4", adviceText = "a4", createdAt = 5000,
            )
        )

        val recent = dao.recent("log", limit = 2)

        assertEquals("limit caps the window even though 3 rows exist for this aspect", 2, recent.size)
        assertEquals("newest first", "gist-2", recent[0].gist)
        assertEquals("second-newest next", "gist-3", recent[1].gist)
    }

    @Test
    fun `claimIfPending succeeds once and then refuses a second claim on the same id`() = runBlocking {
        // The atomic claim closes accept_proposal's read-check-execute-write race: two concurrent
        // callers for the same id (double-tap, or a retry racing the original past TOOL_TIMEOUT_MS)
        // must never both see rows-affected == 1.
        val id = dao.insert(
            AdvisorAdvice(
                aspect = "bio", questionText = "q", gist = "g", adviceText = "a",
                proposalJson = """{"op":"set_sleep_target","targetHours":8.0}""", createdAt = 1000,
            )
        )

        val firstClaim = dao.claimIfPending(id, "accepting", 2000)
        val secondClaim = dao.claimIfPending(id, "accepting", 3000)

        assertEquals("the first caller must win the claim", 1, firstClaim)
        assertEquals("a second claim on an already-claimed row must affect nothing", 0, secondClaim)
        assertEquals("accepting", dao.pending(id)!!.outcome)
    }

    @Test
    fun `claimIfPending refuses a row that is not pending at all`() = runBlocking {
        val id = dao.insert(
            AdvisorAdvice(
                aspect = "bio", questionText = "q", gist = "g", adviceText = "a",
                proposalJson = """{"op":"set_sleep_target","targetHours":8.0}""",
                outcome = "accepted", createdAt = 1000,
            )
        )

        val claim = dao.claimIfPending(id, "accepting", 2000)

        assertEquals(0, claim)
    }

    @Test
    fun `revertToPending restores a claimed row to pending with resolvedAt cleared`() = runBlocking {
        val id = dao.insert(
            AdvisorAdvice(
                aspect = "bio", questionText = "q", gist = "g", adviceText = "a",
                proposalJson = """{"op":"set_sleep_target","targetHours":8.0}""", createdAt = 1000,
            )
        )
        dao.claimIfPending(id, "accepting", 2000)

        dao.revertToPending(id)

        val row = dao.pending(id)!!
        assertEquals("pending", row.outcome)
        assertEquals(null, row.resolvedAt)
    }

    @Test
    fun `markOutcome resolves a pending proposal in place`() = runBlocking {
        val id = dao.insert(
            AdvisorAdvice(
                aspect = "cred",
                questionText = "raise the savings goal?",
                gist = "on track, raise it",
                adviceText = "You are ahead of pace, consider raising the target.",
                proposalJson = """{"newTargetValue":35000}""",
                createdAt = 1000,
            )
        )

        dao.markOutcome(id, "accepted", 2000)

        val resolved = dao.pending(id)
        assertEquals("accepted", resolved!!.outcome)
        assertEquals(2000L, resolved.resolvedAt)
    }
}
