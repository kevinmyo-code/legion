package com.kevin.legion.service

import com.kevin.legion.advisor.AdvisorAnswer
import com.kevin.legion.advisor.AdvisorResult
import com.kevin.legion.data.local.AdvisorAdvice
import com.kevin.legion.data.local.CarDatabase
import com.kevin.legion.meals.dayStartEpoch
import com.kevin.legion.testutil.RoomTestReset
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * Ticket 18's own verification list, exercised against [LiveToolbox.dispatch]'s `accept_proposal`
 * (and [LiveToolbox.mapAdvisorResult] for `ask_advisor`'s degrade path) - same Robolectric-plus-
 * Room shape as [LiveToolboxVehicleScopingTest]. Every test here seeds an `advisor_advice` row
 * directly (never calls the real [com.kevin.legion.advisor.AdvisorAgent], which would need a live
 * Gemini key and network) - `accept_proposal`'s whole point is that it only ever reads what is
 * already STORED, so seeding the row directly is the honest way to test it, not a shortcut around it.
 */
@RunWith(RobolectricTestRunner::class)
class LiveToolboxAdvisorTest {
    private val context = RuntimeEnvironment.getApplication()

    @Before
    fun clearState() {
        RoomTestReset.resetCarDatabaseSingleton()
    }

    private suspend fun seedAdvice(
        aspect: String,
        proposalJson: String?,
        outcome: String = "pending",
        createdAt: Long = System.currentTimeMillis(),
    ): Long {
        return CarDatabase.getDatabase(context).advisorAdviceDao().insert(
            AdvisorAdvice(
                aspect = aspect,
                questionText = "test question",
                gist = "test gist",
                adviceText = "test advice text",
                proposalJson = proposalJson,
                outcome = outcome,
                createdAt = createdAt,
            ),
        )
    }

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


    // --- Acceptance writes EXACTLY the stored proposal, never a re-supplied one -----------------

    @Test
    fun `accept_proposal writes the exact stored BIO meal-target proposal`() = runBlocking {
        val id = seedAdvice(
            aspect = "bio",
            proposalJson = """{"op":"set_meal_target","caloriesKcal":2200,"proteinG":180.5,"carbsG":220.0,"fatG":70.0}""",
        )

        val result = LiveToolbox.dispatch(context, "accept_proposal", JSONObject().put("id", id))!!

        assertTrue(result.getBoolean("success"))
        val target = CarDatabase.getDatabase(context).mealTargetDao().currentTarget(dayStartEpoch(System.currentTimeMillis()))!!
        assertEquals(2200, target.caloriesKcal)
        assertEquals(180.5, target.proteinG, 0.0001)
        assertEquals(220.0, target.carbsG, 0.0001)
        assertEquals(70.0, target.fatG, 0.0001)

        val row = CarDatabase.getDatabase(context).advisorAdviceDao().pending(id)!!
        assertEquals("accepted", row.outcome)
    }

    @Test
    fun `accept_proposal never lets accept_proposal's own arguments override the stored proposal`() = runBlocking {
        // accept_proposal's only declared parameter is `id` - passing anything else through
        // dispatch (as a hostile/confused live-model call might) must not change what gets written.
        val id = seedAdvice(aspect = "bio", proposalJson = """{"op":"set_meal_target","caloriesKcal":1800,"proteinG":150.0,"carbsG":180.0,"fatG":60.0}""")

        val result = LiveToolbox.dispatch(
            context, "accept_proposal",
            JSONObject().put("id", id).put("caloriesKcal", 9999).put("proteinG", 1.0),
        )!!

        assertTrue(result.getBoolean("success"))
        val target = CarDatabase.getDatabase(context).mealTargetDao().currentTarget(dayStartEpoch(System.currentTimeMillis()))!!
        assertEquals("the stored proposal's number must win, never a same-call argument", 1800, target.caloriesKcal)
    }

    // --- Out-of-allowlist operations are refused ---------------------------------------------------

    @Test
    fun `a BIO proposal trying to write a budget is refused`() = runBlocking {
        val id = seedAdvice(aspect = "bio", proposalJson = """{"op":"set_budget","category":"Groceries","amountCents":40000}""")

        val result = LiveToolbox.dispatch(context, "accept_proposal", JSONObject().put("id", id))!!

        assertFalse(result.getBoolean("success"))
        val budgets = CarDatabase.getDatabase(context).budgetTargetDao().currentTargets(
            com.kevin.legion.data.local.LedgerCurrency.USD,
            java.time.YearMonth.now(java.time.ZoneId.systemDefault()).atDay(1)
                .atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli(),
        )
        assertTrue("a BIO proposal must never reach the budget table", budgets.isEmpty())
    }

    @Test
    fun `any proposal trying to log an actual is refused`() = runBlocking {
        // log_bodyweight/log_meal/log_workout_set/log_sleep/log_service are all ACTUALS - claims
        // about what already happened - and are never on any brief's writableOps allowlist, so
        // this must be refused the same way an unrecognised op is, not silently routed anywhere.
        val id = seedAdvice(aspect = "bio", proposalJson = """{"op":"log_bodyweight","weight":180,"weight_unit":"lbs"}""")

        val result = LiveToolbox.dispatch(context, "accept_proposal", JSONObject().put("id", id))!!

        assertFalse(result.getBoolean("success"))
        assertNull(
            "no bodyweight row should ever be written by an advisor proposal",
            CarDatabase.getDatabase(context).bodyweightLogDao().mostRecent(),
        )
    }

    @Test
    fun `a CRED proposal trying to write a goal for a different aspect still lands under CRED's own aspect`() = runBlocking {
        // set_goal is on CRED's own allowlist, but the proposal's own "aspect" field (if a model
        // ever included one) must never be trusted over the brief that authored it.
        val id = seedAdvice(aspect = "cred", proposalJson = """{"op":"set_goal","aspect":"bio","statement":"Save 10k"}""")

        val result = LiveToolbox.dispatch(context, "accept_proposal", JSONObject().put("id", id))!!

        assertTrue(result.getBoolean("success"))
        val credGoals = CarDatabase.getDatabase(context).goalDao().currentGoals("cred")
        val bioGoals = CarDatabase.getDatabase(context).goalDao().currentGoals("bio")
        assertEquals(1, credGoals.size)
        assertTrue(bioGoals.isEmpty())
    }

    // --- Expiry ---------------------------------------------------------------------------------

    @Test
    fun `an expired proposal refuses with wording and marks the row expired`() = runBlocking {
        val staleCreatedAt = System.currentTimeMillis() - (25L * 60 * 60 * 1000) // 25h, past the 24h TTL
        val id = seedAdvice(
            aspect = "bio",
            proposalJson = """{"op":"set_sleep_target","targetHours":8.0}""",
            createdAt = staleCreatedAt,
        )

        val result = LiveToolbox.dispatch(context, "accept_proposal", JSONObject().put("id", id))!!

        assertFalse(result.getBoolean("success"))
        assertTrue(
            "refusal must be worded, never silent",
            result.getString("message").isNotBlank(),
        )
        val row = CarDatabase.getDatabase(context).advisorAdviceDao().pending(id)!!
        assertEquals("expired", row.outcome)
        assertNull(
            "an expired proposal must never actually write",
            CarDatabase.getDatabase(context).sleepTargetDao().currentTarget(System.currentTimeMillis()),
        )
    }

    @Test
    fun `a proposal well inside the TTL is not treated as expired`() = runBlocking {
        val recentCreatedAt = System.currentTimeMillis() - (60L * 60 * 1000) // 1h ago
        val id = seedAdvice(aspect = "bio", proposalJson = """{"op":"set_sleep_target","targetHours":8.0}""", createdAt = recentCreatedAt)

        val result = LiveToolbox.dispatch(context, "accept_proposal", JSONObject().put("id", id))!!

        assertTrue(result.getBoolean("success"))
    }

    // --- HOME has no writable ops -----------------------------------------------------------------

    @Test
    fun `HOME has no writable ops - even a set_goal proposal under home is refused`() = runBlocking {
        val id = seedAdvice(aspect = "home", proposalJson = """{"op":"set_goal","statement":"Get everything on track"}""")

        val result = LiveToolbox.dispatch(context, "accept_proposal", JSONObject().put("id", id))!!

        assertFalse(result.getBoolean("success"))
        assertTrue(CarDatabase.getDatabase(context).goalDao().allCurrentGoals().isEmpty())
    }

    // --- Defect 1: a verified-not-written proposal must never read as accepted -------------------

    @Test
    fun `an invalid sleep-target proposal reports failure and leaves the row pending for a retry`() = runBlocking {
        val id = seedAdvice(aspect = "bio", proposalJson = """{"op":"set_sleep_target","targetHours":26.0}""")

        val result = LiveToolbox.dispatch(context, "accept_proposal", JSONObject().put("id", id))!!

        assertFalse("SleepController.setTarget's own failure string must not read as success", result.getBoolean("success"))
        assertNull(
            "nothing should have been written",
            CarDatabase.getDatabase(context).sleepTargetDao().currentTarget(System.currentTimeMillis()),
        )
        val row = CarDatabase.getDatabase(context).advisorAdviceDao().pending(id)!!
        assertEquals(
            "a write that never landed must leave the row pending, not permanently accepted",
            "pending", row.outcome,
        )
    }

    @Test
    fun `a retry after a WriteFailed can still succeed on the same row once it holds a valid target`() = runBlocking {
        val db = CarDatabase.getDatabase(context)
        val id = db.advisorAdviceDao().insert(
            com.kevin.legion.data.local.AdvisorAdvice(
                aspect = "bio", questionText = "q", gist = "g", adviceText = "a",
                proposalJson = """{"op":"set_sleep_target","targetHours":26.0}""",
            ),
        )
        val firstAttempt = LiveToolbox.dispatch(context, "accept_proposal", JSONObject().put("id", id))!!
        assertFalse(firstAttempt.getBoolean("success"))

        // A corrected proposal replaces the bad JSON on the SAME row - what a re-ask (ticket 03
        // answer call 2, "yes but..." goes back as a follow-up returning a new stored proposal)
        // would persist in the real flow; done here with a raw statement since the DAO has no
        // "replace this row's proposal" method of its own (nor should it need one for this test).
        db.openHelper.writableDatabase.execSQL(
            "UPDATE advisor_advice SET proposalJson = '{\"op\":\"set_sleep_target\",\"targetHours\":8.0}' WHERE id = $id",
        )

        val secondAttempt = LiveToolbox.dispatch(context, "accept_proposal", JSONObject().put("id", id))!!
        assertTrue("a retry with a corrected proposal on the same id must be able to succeed", secondAttempt.getBoolean("success"))
        assertEquals("accepted", db.advisorAdviceDao().pending(id)!!.outcome)
    }

    // --- Defect 2: an already-claimed row is refused, not executed a second time -----------------

    @Test
    fun `a row another caller already claimed is refused, never executed a second time`() = runBlocking {
        // Simulates the losing side of the race: a concurrent claimIfPending already flipped the
        // row to "accepting" before this call's own claim attempt runs.
        val id = seedAdvice(aspect = "bio", proposalJson = """{"op":"set_sleep_target","targetHours":8.0}""")
        CarDatabase.getDatabase(context).advisorAdviceDao().claimIfPending(id, "accepting", System.currentTimeMillis())

        val result = LiveToolbox.dispatch(context, "accept_proposal", JSONObject().put("id", id))!!

        assertFalse("a row already claimed by another caller must be refused", result.getBoolean("success"))
        assertNull(
            "the second caller must never execute the write",
            CarDatabase.getDatabase(context).sleepTargetDao().currentTarget(System.currentTimeMillis()),
        )
    }

    @Test
    fun `two accept_proposal calls for the same id never both write - only the first lands`() = runBlocking {
        val id = seedAdvice(aspect = "bio", proposalJson = """{"op":"set_sleep_target","targetHours":8.0}""")

        val first = LiveToolbox.dispatch(context, "accept_proposal", JSONObject().put("id", id))!!
        val second = LiveToolbox.dispatch(context, "accept_proposal", JSONObject().put("id", id))!!

        assertTrue("the first call executes normally", first.getBoolean("success"))
        assertFalse("the row is already accepted, so a second call must refuse rather than re-run", second.getBoolean("success"))
        val row = CarDatabase.getDatabase(context).advisorAdviceDao().pending(id)!!
        assertEquals("accepted", row.outcome)
    }

    // --- Already-resolved / unknown / unparseable proposals ------------------------------------

    @Test
    fun `accepting an already-accepted proposal a second time is refused, not re-run`() = runBlocking {
        val id = seedAdvice(aspect = "bio", proposalJson = """{"op":"set_sleep_target","targetHours":8.0}""", outcome = "accepted")

        val result = LiveToolbox.dispatch(context, "accept_proposal", JSONObject().put("id", id))!!

        assertFalse(result.getBoolean("success"))
    }

    @Test
    fun `an unknown proposal id refuses in words`() = runBlocking {
        val result = LiveToolbox.dispatch(context, "accept_proposal", JSONObject().put("id", 999999L))!!
        assertFalse(result.getBoolean("success"))
        assertTrue(result.getString("message").isNotBlank())
    }

    @Test
    fun `a proposal whose JSON does not parse is refused rather than crashing`() = runBlocking {
        val id = seedAdvice(aspect = "bio", proposalJson = "not json at all")
        val result = LiveToolbox.dispatch(context, "accept_proposal", JSONObject().put("id", id))!!
        assertFalse(result.getBoolean("success"))
    }

    // --- ask_advisor: a ParseFailed answer still yields spoken advice, not silence --------------

    @Test
    fun `a ParseFailed advisor answer still produces a spoken result, not a silent drop`() {
        val response = LiveToolbox.mapAdvisorResult(AdvisorResult.ParseFailed(""))

        assertTrue(
            "a parse failure must still speak something rather than the tool call reading as silence",
            response.getBoolean("success"),
        )
        assertTrue(response.getString("message").isNotBlank())
    }

    @Test
    fun `a ParseFailed answer RELAYS the model's prose rather than discarding it`() {
        val prose = "Two sessions against a plan of four. The week is not lost - move leg day to Thursday."
        val response = LiveToolbox.mapAdvisorResult(AdvisorResult.ParseFailed(prose))

        assertTrue(response.getBoolean("success"))
        assertTrue(
            "the coaching prose is good even when its JSON envelope failed; discarding it would " +
                "turn a formatting problem into a silent loss of the advice",
            response.getString("message").contains(prose),
        )
        assertTrue(
            "the driver must be told there is nothing to accept, not left assuming a normal answer",
            response.getString("message").contains("nothing to accept"),
        )
        assertFalse(
            "a parse failure has no proposal, so it must never advertise one",
            response.has("hasProposal"),
        )
    }

    @Test
    fun `a successful advisor answer carries adviceId so accept_proposal always has a real id to name`() {
        val answer = AdvisorAnswer(spoken = "You're on track.", proposal = """{"op":"set_goal"}""")
        val response = LiveToolbox.mapAdvisorResult(AdvisorResult.Success(answer, adviceId = 42L))

        assertTrue(response.getBoolean("success"))
        assertEquals(42L, response.getLong("adviceId"))
        assertTrue(response.getBoolean("hasProposal"))
    }
}
