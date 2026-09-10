package com.kevin.legion.advisor

import com.kevin.legion.checklists.ChecklistController
import com.kevin.legion.testutil.RoomTestReset
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * `create_checklist` - the one-home ticket 05 op that lets an advisor write the day's workout list
 * (Kevin: *"the advisor would tell me what would be a good daily todo list for workouts > and
 * populate it that way"*).
 *
 * **The centrepiece is idempotence, because that is the defect ticket 05 names in advance:** *"a
 * handler that duplicates the list every morning is the defect this bullet exists to prevent."* The
 * advisor runs again tomorrow, and the day after. The old mechanism this replaces
 * (`GoalChecklistSync`) found its own rows by scanning display text for a `"Plan: "` prefix; ticket
 * 04 retired that and required the replacement to *"identify the checklist it owns by a real key,
 * not by matching text"*. [AdvisorProposalExecutor.BIO_CHECKLIST_SOURCE_KEY], stored in
 * `checklists.sourceKey`, is that key, and the tests below are what stop it regressing into a
 * second `"Plan: "`.
 *
 * The `GoalChecklistSyncTest` these replace covered 20 cases against the retired mechanism -
 * materialisation, prefix matching, day sweeps. None of that survives the mechanism it tested, so
 * they were deleted rather than ported: what is worth keeping is the BEHAVIOUR, and that is what is
 * re-pinned here against the new store.
 */
@RunWith(RobolectricTestRunner::class)
class AdvisorCreateChecklistTest {
    private val context = RuntimeEnvironment.getApplication()
    private val brief = AdvisorBriefs.forAspect(AdvisorAspect.BIO)

    @Before
    fun clearState() {
        RoomTestReset.resetCarDatabaseSingleton()
    }

    @After
    fun drainRoomInvalidationTracker() {
        // See AdvisorProposalExecutorTest's own comment: a queued Room invalidation refresh races
        // Robolectric's per-method native SQLite reset and is blamed on whatever runs next.
        RoomTestReset.drainArchDiskIoPool()
    }

    private fun proposal(name: String, vararg items: String): String {
        val itemsJson = items.joinToString(",") { "\"$it\"" }
        return """{"op":"create_checklist","name":"$name","items":[$itemsJson]}"""
    }

    private suspend fun ownedChecklist() = ChecklistController.getChecklistBySourceKey(
        context,
        AdvisorProposalExecutor.BIO_CHECKLIST_SOURCE_KEY,
    )

    private suspend fun ownedItemTexts(): List<String> {
        val list = ownedChecklist() ?: return emptyList()
        return ChecklistController.itemsFor(context, list.id).map { it.text }
    }

    // --- the write lands at all -----------------------------------------------------------------

    @Test
    fun `a proposal creates a recurring checklist stamped with the source key`() = runBlocking {
        val outcome = AdvisorProposalExecutor.execute(
            context, brief, proposal("Today's training", "3 sets x 10 - Goblet squat", "20 min walk"),
        )

        assertTrue("expected Ok, got $outcome", outcome is AdvisorProposalExecutor.ExecuteResult.Ok)
        val list = assertNotNull("no checklist was written", ownedChecklist()).let { ownedChecklist()!! }
        assertEquals("Today's training", list.name)
        // Recurring, per ticket 04 consequence 2 - a DAILY schedule is what gives ChecklistTick a
        // per-day row to record, which is the "end of day it records and resets" half of Kevin's
        // 2026-09-04 quote that the old list_items mechanism structurally could not do.
        assertEquals("DAILY", list.scheduleKind)
        assertEquals(2, ownedItemTexts().size)
    }

    @Test
    fun `every stored line carries the estimate label, in the text itself`() = runBlocking {
        // CLAUDE.md §7: estimates are labelled as estimates. A set/rep count an advisor composed is
        // a model's opinion, not something Kevin measured, and the label lives in the STORED text
        // rather than in a renderer - a second surface rendering the same row must not be able to
        // present it as a measurement by forgetting to add the word.
        AdvisorProposalExecutor.execute(context, brief, proposal("Training", "3 sets x 10 - Goblet squat"))

        val text = ownedItemTexts().single()
        assertTrue("stored line must say it is an estimate, got: $text", text.contains("estimate"))
    }

    // --- idempotence: the defect the ticket names ------------------------------------------------

    @Test
    fun `running the same proposal twice does not produce two checklists or two of each line`() = runBlocking {
        val p = proposal("Today's training", "3 sets x 10 - Goblet squat", "20 min walk")

        AdvisorProposalExecutor.execute(context, brief, p)
        val firstId = ownedChecklist()!!.id
        val firstTexts = ownedItemTexts().sorted()

        AdvisorProposalExecutor.execute(context, brief, p)

        assertEquals("a second run must reuse the SAME checklist, not create another", firstId, ownedChecklist()!!.id)
        assertEquals("a second run must not duplicate the lines", firstTexts, ownedItemTexts().sorted())
        assertEquals("exactly one checklist carries the source key", 1, allOwnedCount())
    }

    @Test
    fun `running it fourteen times is the same as running it once`() = runBlocking {
        // The literal shape of ticket 05's warning: "fourteen copies of the same list". A handler
        // that appended rather than reconciled would pass a two-run test and still fail here, which
        // is why this is a separate case and not a louder version of the one above.
        val p = proposal("Training", "3 sets x 10 - Goblet squat")
        repeat(14) { AdvisorProposalExecutor.execute(context, brief, p) }

        assertEquals(1, allOwnedCount())
        assertEquals(1, ownedItemTexts().size)
    }

    @Test
    fun `tomorrow's different proposal replaces the lines rather than piling onto them`() = runBlocking {
        AdvisorProposalExecutor.execute(context, brief, proposal("Training", "Squat day", "20 min walk"))
        AdvisorProposalExecutor.execute(context, brief, proposal("Training", "Deadlift day"))

        val texts = ownedItemTexts()
        assertEquals("the new list replaces the old one", 1, texts.size)
        assertTrue("the surviving line is the new one, got $texts", texts.single().contains("Deadlift"))
        assertTrue("yesterday's line must be gone", texts.none { it.contains("Squat day") })
        assertEquals(1, allOwnedCount())
    }

    @Test
    fun `a renamed proposal renames the same checklist instead of founding a rival`() = runBlocking {
        AdvisorProposalExecutor.execute(context, brief, proposal("Training", "Squat day"))
        val id = ownedChecklist()!!.id

        AdvisorProposalExecutor.execute(context, brief, proposal("Today's plan", "Squat day"))

        assertEquals("the name changed, the row did not", id, ownedChecklist()!!.id)
        assertEquals("Today's plan", ownedChecklist()!!.name)
        assertEquals(1, allOwnedCount())
    }

    @Test
    fun `the key identifies the checklist, not its name - a hand-made list of the same name is untouched`() = runBlocking {
        // The property that makes this a real key rather than a prefix by another spelling. A
        // checklist Kevin made himself carries a null sourceKey, and the advisor must neither adopt
        // it nor overwrite it, no matter what it is called.
        val mine = ChecklistController.createChecklist(context, name = "Training")
        ChecklistController.addItem(context, mine.id, "something I typed myself")

        AdvisorProposalExecutor.execute(context, brief, proposal("Training", "Squat day"))

        assertNull("a hand-made checklist must not be adopted", ChecklistController.getChecklist(context, mine.id)?.sourceKey)
        assertEquals(
            "the hand-made list's own items must be untouched",
            listOf("something I typed myself"),
            ChecklistController.itemsFor(context, mine.id).map { it.text },
        )
        assertTrue("the advisor made its own row", ownedChecklist()!!.id != mine.id)
    }

    // --- refusals say, in words, what did not happen ---------------------------------------------

    @Test
    fun `a proposal with no name is refused and writes nothing`() = runBlocking {
        val outcome = AdvisorProposalExecutor.execute(
            context, brief, """{"op":"create_checklist","items":["Squat day"]}""",
        )

        assertTrue("expected Refused, got $outcome", outcome is AdvisorProposalExecutor.ExecuteResult.Refused)
        assertNull("nothing may be written on a refusal", ownedChecklist())
    }

    @Test
    fun `a proposal with no items is refused and writes nothing`() = runBlocking {
        val outcome = AdvisorProposalExecutor.execute(
            context, brief, """{"op":"create_checklist","name":"Training","items":[]}""",
        )

        assertTrue("expected Refused, got $outcome", outcome is AdvisorProposalExecutor.ExecuteResult.Refused)
        assertNull(ownedChecklist())
    }

    @Test
    fun `a proposal whose items are all blank is refused rather than writing an empty list`() = runBlocking {
        // Not the same as "no items". §4 rule 6's shape: a handler that accepted this would write a
        // checklist with nothing on it and report success, which reads to the user as a plan.
        val outcome = AdvisorProposalExecutor.execute(
            context, brief, """{"op":"create_checklist","name":"Training","items":["","  "]}""",
        )

        assertTrue("expected Refused, got $outcome", outcome is AdvisorProposalExecutor.ExecuteResult.Refused)
        assertNull(ownedChecklist())
    }

    @Test
    fun `an unlisted op is still refused - the allowlist did not widen`() = runBlocking {
        val outcome = AdvisorProposalExecutor.execute(
            context, brief, """{"op":"drop_every_checklist","name":"Training"}""",
        )

        assertTrue(
            "adding a handler must not have turned the allowlist into a passthrough, got $outcome",
            outcome is AdvisorProposalExecutor.ExecuteResult.Refused,
        )
    }

    private suspend fun allOwnedCount(): Int =
        ChecklistController.allChecklists(context, includeArchived = true)
            .count { it.sourceKey == AdvisorProposalExecutor.BIO_CHECKLIST_SOURCE_KEY }
}
