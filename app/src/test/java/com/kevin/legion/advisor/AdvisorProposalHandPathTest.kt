package com.kevin.legion.advisor

import com.kevin.legion.data.local.AdvisorAdvice
import com.kevin.legion.data.local.CarDatabase
import com.kevin.legion.testutil.RoomTestReset
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * Command-center ticket 11: `accept_proposal` by hand, CRED aspect (ADR 0035).
 * [AdvisorProposalHandPath] re-states `LiveToolbox.acceptProposalTool`'s orchestration (that
 * function is `private` inside a file this agent's territory brief held READ-ONLY - see
 * [AdvisorProposalHandPath]'s own class doc for the full trace) but calls the exact same
 * [AdvisorProposalExecutor.execute] write. This mirrors [com.kevin.legion.service.LiveToolboxAdvisorTest]'s
 * own fixture shape and re-proves the SAME behaviours against the hand path directly, so the two
 * orchestration copies are shown to agree rather than merely asserted to.
 */
@RunWith(RobolectricTestRunner::class)
class AdvisorProposalHandPathTest {
    private val context = RuntimeEnvironment.getApplication()

    @Before
    fun clearState() {
        RoomTestReset.resetCarDatabaseSingleton()
    }

    private suspend fun seedAdvice(
        aspect: String = "cred",
        proposalJson: String?,
        outcome: String = "pending",
        createdAt: Long = System.currentTimeMillis(),
    ): Long = CarDatabase.getDatabase(context).advisorAdviceDao().insert(
        AdvisorAdvice(
            aspect = aspect, questionText = "test question", gist = "test gist",
            adviceText = "test advice text", proposalJson = proposalJson, outcome = outcome, createdAt = createdAt,
        ),
    )

    @After
    fun drainRoomInvalidationTracker() {
        // A DAO write anywhere in this test can schedule a Room InvalidationTracker refresh
        // on ArchTaskExecutor's disk-IO pool. If that refresh is still queued or running when
        // this test method returns, it races Robolectric's own per-@Test-METHOD native SQLite
        // reset and throws "Illegal connection pointer" on a background thread - uncaught, and
        // blamed by kotlinx-coroutines-test on whatever runTest starts next, not on this class.
        // Draining here, before Robolectric ever gets a chance to reset, is the fix - see
        // RoomTestReset's own class doc comment
        // (.scratch/hardening/issues/13-the-suite-is-green-by-luck.md) for the full account.
        RoomTestReset.drainArchDiskIoPool()
    }


    @Test
    fun `pendingProposals lists only pending rows for the requested aspect`() = runBlocking {
        seedAdvice(aspect = "cred", proposalJson = """{"op":"set_budget"}""")
        seedAdvice(aspect = "cred", proposalJson = """{"op":"set_budget"}""", outcome = "accepted")
        seedAdvice(aspect = "bio", proposalJson = """{"op":"set_goal"}""")

        val proposals = AdvisorProposalHandPath.pendingProposals(context, AdvisorAspect.CRED)

        assertEquals("only the one still-pending CRED row", 1, proposals.size)
        assertEquals("cred", proposals.single().aspect)
    }

    @Test
    fun `accept writes the exact stored CRED budget proposal, same as the voice path`() = runBlocking {
        val id = seedAdvice(proposalJson = """{"op":"set_budget","category":"Groceries","amountCents":40000}""")

        val outcome = AdvisorProposalHandPath.acceptPendingProposal(context, id)

        assertTrue(outcome.success)
        val row = CarDatabase.getDatabase(context).advisorAdviceDao().pending(id)!!
        assertEquals("accepted", row.outcome)
    }

    @Test
    fun `an expired proposal refuses and marks the row expired, never writes`() = runBlocking {
        val stale = System.currentTimeMillis() - (25L * 60 * 60 * 1000)
        val id = seedAdvice(proposalJson = """{"op":"set_goal","statement":"Save 10k"}""", createdAt = stale)

        val outcome = AdvisorProposalHandPath.acceptPendingProposal(context, id)

        assertFalse(outcome.success)
        assertTrue(outcome.message.isNotBlank())
        val row = CarDatabase.getDatabase(context).advisorAdviceDao().pending(id)!!
        assertEquals("expired", row.outcome)
    }

    @Test
    fun `two accepts for the same id never both write - only the first lands`() = runBlocking {
        val id = seedAdvice(proposalJson = """{"op":"set_goal","statement":"Save 10k"}""")

        val first = AdvisorProposalHandPath.acceptPendingProposal(context, id)
        val second = AdvisorProposalHandPath.acceptPendingProposal(context, id)

        assertTrue(first.success)
        assertFalse("the row is already accepted, so a second call must refuse", second.success)
    }

    @Test
    fun `dismiss marks the row rejected without touching the underlying data`() = runBlocking {
        val id = seedAdvice(proposalJson = """{"op":"set_goal","statement":"Save 10k"}""")

        AdvisorProposalHandPath.dismissPendingProposal(context, id)

        val row = CarDatabase.getDatabase(context).advisorAdviceDao().pending(id)!!
        assertEquals("rejected", row.outcome)
        assertTrue(
            "a dismissed proposal must never have run its write",
            CarDatabase.getDatabase(context).goalDao().currentGoals("cred").isEmpty(),
        )
    }

    @Test
    fun `an unknown id refuses in words`() = runBlocking {
        val outcome = AdvisorProposalHandPath.acceptPendingProposal(context, 999999L)
        assertFalse(outcome.success)
        assertTrue(outcome.message.isNotBlank())
    }
}
