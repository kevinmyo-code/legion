package com.kevin.legion.checklists

import com.kevin.legion.data.local.CarDatabase
import com.kevin.legion.data.local.Checklist
import com.kevin.legion.testutil.RoomTestReset
import java.time.LocalDate
import java.time.ZoneOffset
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * Exercises [ChecklistController] - the one write/read path this ticket requires. Robolectric
 * through the real [CarDatabase.getDatabase] path, same shape as `GoalControllerTest` - see
 * [RoomTestReset]'s own doc comment for why the singleton needs resetting per method.
 *
 * Every day used in these tests is expressed via [LocalDate.toEpochDay] pinned to
 * [ZoneOffset.UTC] rather than [ChecklistController.today]'s device-zone default, so the test
 * itself never depends on the machine's local timezone.
 */
@RunWith(RobolectricTestRunner::class)
class ChecklistControllerTest {
    private val context = RuntimeEnvironment.getApplication()

    @Before
    fun clearState() {
        RoomTestReset.resetCarDatabaseSingleton()
    }

    @After
    fun drainRoomInvalidationTracker() {
        // Same failure mode GoalControllerTest's own doc comment explains - see RoomTestReset's
        // class doc for the full account.
        RoomTestReset.drainArchDiskIoPool()
    }

    private fun day(year: Int, month: Int, dayOfMonth: Int): Int =
        LocalDate.of(year, month, dayOfMonth).toEpochDay().toInt()

    private fun epochMs(year: Int, month: Int, dayOfMonth: Int): Long =
        LocalDate.of(year, month, dayOfMonth).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

    /** Inserts a [Checklist] directly, with a caller-chosen [createdAt] far enough in the past
     * that trap 1's own gate never interferes with a test that is deliberately exercising some
     * OTHER behaviour (a retroactive tick, or a soft-deleted item's history). Trap 1 itself has
     * its own dedicated tests above; this bypasses [ChecklistController.createChecklist]'s
     * real-"now" default only so those other tests are not accidentally gated by it too.
     * [recursDaily] is DEPRECATED and no longer read by the controller (see [Checklist.recursDaily]'s
     * own doc comment) - kept as a parameter here only so existing call sites still compile;
     * [scheduleKind]/[scheduleEvery] are what actually pick per-day vs any-day tick semantics now. */
    private suspend fun backdatedChecklist(
        name: String,
        createdAt: Long,
        recursDaily: Boolean = true,
        scheduleKind: String? = null,
        scheduleEvery: Int? = null,
    ): Checklist {
        val checklist = Checklist(name = name, recursDaily = recursDaily, createdAt = createdAt, scheduleKind = scheduleKind, scheduleEvery = scheduleEvery)
        val id = CarDatabase.getDatabase(context).checklistDao().insert(checklist)
        return checklist.copy(id = id)
    }

    /** Unwraps a [ChecklistController.ChecklistItemsResult], failing loudly (rather than a
     * `ClassCastException` with no context) if a test that expects a successful read got
     * [ChecklistController.ChecklistItemsResult.Failed] instead - dedicated tests below exercise
     * [ChecklistController.ChecklistItemsResult.Failed] itself. */
    private fun ChecklistController.ChecklistItemsResult.loaded(): List<ChecklistController.ItemState> =
        when (this) {
            is ChecklistController.ChecklistItemsResult.Loaded -> items
            is ChecklistController.ChecklistItemsResult.Failed -> throw AssertionError("expected Loaded, got Failed($reason)")
        }

    // ---- tick / untick round trip ---------------------------------------------------------------

    @Test
    fun `a tick and untick round trip`() = runBlocking {
        val checklist = ChecklistController.createChecklist(context, "bio")
        val item = ChecklistController.addItem(context, checklist.id, "goblet squats")
        val today = day(2026, 9, 4)

        var state = ChecklistController.itemsWithTickState(context, checklist.id, today).loaded()
        assertFalse("not ticked yet", state.single().ticked)

        ChecklistController.tick(context, item.id, today)
        state = ChecklistController.itemsWithTickState(context, checklist.id, today).loaded()
        assertTrue("ticked after tick()", state.single().ticked)

        ChecklistController.untick(context, item.id, today)
        state = ChecklistController.itemsWithTickState(context, checklist.id, today).loaded()
        assertFalse("unticked after untick()", state.single().ticked)
    }

    @Test
    fun `re-ticking after an untick on the same day revives the tick with a fresh tickedAt`() = runBlocking {
        val checklist = ChecklistController.createChecklist(context, "bio")
        val item = ChecklistController.addItem(context, checklist.id, "goblet squats")
        val today = day(2026, 9, 4)

        ChecklistController.tick(context, item.id, today, at = 1_000L)
        ChecklistController.untick(context, item.id, today, at = 2_000L)
        // Without the revival path, this second tick would silently do nothing (the tombstoned
        // row already occupies the (itemId, day) unique slot) and the item would stay unticked.
        ChecklistController.tick(context, item.id, today, at = 3_000L)

        val state = ChecklistController.itemsWithTickState(context, checklist.id, today).loaded()
        assertTrue("tick revived", state.single().ticked)
        assertEquals(3_000L, state.single().tickedAt)
    }

    @Test
    fun `double-ticking the same item on the same day is idempotent and keeps the original tickedAt`() = runBlocking {
        val checklist = ChecklistController.createChecklist(context, "bio")
        val item = ChecklistController.addItem(context, checklist.id, "goblet squats")
        val today = day(2026, 9, 4)

        ChecklistController.tick(context, item.id, today, at = 1_000L)
        ChecklistController.tick(context, item.id, today, at = 9_999L) // second tap, later time

        val state = ChecklistController.itemsWithTickState(context, checklist.id, today).loaded()
        assertTrue(state.single().ticked)
        assertEquals("the FIRST tap's time survives, not the second", 1_000L, state.single().tickedAt)
    }

    @Test
    fun `a retroactive tick lands on the requested day but keeps the real tap time`() = runBlocking {
        // A DAILY schedule, deliberately - this test is exercising decision 3 (day vs tickedAt),
        // which only has a "today's own row is untouched" question to ask on a PER-DAY checklist;
        // a no-schedule (scheduleKind = null) list is done the moment any tick exists at all
        // (decision 4, covered by its own tests below), so it would read this test's second
        // assertion as false for the wrong reason.
        val checklist = backdatedChecklist("bio", createdAt = epochMs(2026, 1, 1), scheduleKind = "DAILY", scheduleEvery = 1)
        val item = ChecklistController.addItem(context, checklist.id, "goblet squats")
        val yesterday = day(2026, 9, 3)
        val today = day(2026, 9, 4)
        val realTapTime = epochMs(2026, 9, 4) // tapped this morning...

        ChecklistController.tick(context, item.id, day = yesterday, at = realTapTime) // ...for yesterday's row

        val yesterdayState = ChecklistController.itemsWithTickState(context, checklist.id, yesterday).loaded()
        assertTrue("counts for yesterday", yesterdayState.single().ticked)
        assertEquals("but the tap time is the real one", realTapTime, yesterdayState.single().tickedAt)

        val todayState = ChecklistController.itemsWithTickState(context, checklist.id, today).loaded()
        assertFalse("today's own row is untouched", todayState.single().ticked)
    }

    // ---- trap 1: a day before the checklist existed shows nothing -------------------------------

    @Test
    fun `trap 1 - a day before the checklist was created returns no items, not unticked ones`() = runBlocking {
        val checklist = ChecklistController.createChecklist(context, "bio")
        // createdAt defaults to "now" (real current time), which is after any fixed historical
        // day this test picks - so any day far in the past is guaranteed to precede it.
        ChecklistController.addItem(context, checklist.id, "goblet squats")

        val longAgo = day(2020, 1, 1)
        val state = ChecklistController.itemsWithTickState(context, checklist.id, longAgo).loaded()
        assertTrue("empty, not a wall of unticked items", state.isEmpty())
    }

    @Test
    fun `trap 1 - checklistsForDay excludes a checklist created after that day`() = runBlocking {
        ChecklistController.createChecklist(context, "bio") // createdAt = now

        val longAgo = day(2020, 1, 1)
        val checklists = ChecklistController.checklistsForDay(context, longAgo)
        assertTrue("the checklist did not exist yet on that day", checklists.isEmpty())
    }

    @Test
    fun `trap 1 - checklistHistory clamps its range to the checklist's own creation day`() = runBlocking {
        val checklist = ChecklistController.createChecklist(context, "bio")
        val item = ChecklistController.addItem(context, checklist.id, "goblet squats")
        val today = ChecklistController.today()

        ChecklistController.tick(context, item.id, today)

        // Ask for a range starting long before the checklist existed.
        val history = ChecklistController.checklistHistory(context, checklist.id, day(2020, 1, 1), today)
        assertEquals("only today's real tick, nothing manufactured for the earlier days", 1, history.size)
        assertEquals(today, history.single().day)
    }

    // ---- trap 2: editing/deleting an item must not rewrite the past -----------------------------

    @Test
    fun `trap 2 - a soft-deleted item still resolves its text in history`() = runBlocking {
        val checklist = backdatedChecklist("bio", createdAt = epochMs(2026, 1, 1))
        val item = ChecklistController.addItem(context, checklist.id, "goblet squats")
        val lastWeek = ChecklistController.today() - 7

        ChecklistController.tick(context, item.id, lastWeek)
        ChecklistController.deleteItem(context, item.id) // "drop goblet squats next month"

        val history = ChecklistController.checklistHistory(context, checklist.id, lastWeek, lastWeek)
        assertEquals(1, history.size)
        assertEquals("goblet squats", history.single().item.text)
        assertTrue(history.single().ticked)
    }

    @Test
    fun `trap 2 - a soft-deleted item no longer appears in the live per-day read`() = runBlocking {
        val checklist = ChecklistController.createChecklist(context, "bio")
        val item = ChecklistController.addItem(context, checklist.id, "goblet squats")
        val today = ChecklistController.today()
        ChecklistController.tick(context, item.id, today)

        ChecklistController.deleteItem(context, item.id)

        val state = ChecklistController.itemsWithTickState(context, checklist.id, today).loaded()
        assertTrue("dropped from the live view", state.isEmpty())
    }

    // ---- one tick model: non-recurring "done" is any tick on any day ----------------------------

    @Test
    fun `a non-recurring checklist item is done once any tick exists, on any day`() = runBlocking {
        val checklist = ChecklistController.createChecklist(context, "one-off packing list", recursDaily = false)
        val item = ChecklistController.addItem(context, checklist.id, "passport")
        val yesterday = ChecklistController.today() - 1
        val today = ChecklistController.today()

        ChecklistController.tick(context, item.id, yesterday)

        val todayState = ChecklistController.itemsWithTickState(context, checklist.id, today).loaded()
        assertTrue("done because it was ticked at all, not because it was ticked TODAY", todayState.single().ticked)
    }

    // ---- create / rename / archive plumbing ------------------------------------------------------

    @Test
    fun `create rename archive and delete a checklist`() = runBlocking {
        val checklist = ChecklistController.createChecklist(context, "bio")
        ChecklistController.renameChecklist(context, checklist.id, "biology")
        var loaded = ChecklistController.getChecklist(context, checklist.id)
        assertEquals("biology", loaded?.name)

        ChecklistController.archiveChecklist(context, checklist.id)
        assertTrue(ChecklistController.allChecklists(context, includeArchived = true).any { it.id == checklist.id })
        assertFalse(ChecklistController.allChecklists(context, includeArchived = false).any { it.id == checklist.id })

        ChecklistController.unarchiveChecklist(context, checklist.id)
        assertTrue(ChecklistController.allChecklists(context, includeArchived = false).any { it.id == checklist.id })

        ChecklistController.deleteChecklist(context, checklist.id)
        loaded = ChecklistController.getChecklist(context, checklist.id)
        assertNull("soft-deleted, no longer resolvable by getChecklist", loaded)
    }

    @Test
    fun `add edit reorder and delete an item`() = runBlocking {
        val checklist = ChecklistController.createChecklist(context, "bio")
        val item = ChecklistController.addItem(context, checklist.id, "goblet squats", sortOrder = 0)

        ChecklistController.editItem(context, item.id, "goblet squats x3")
        ChecklistController.reorderItem(context, item.id, sortOrder = 5)

        val state = ChecklistController.itemsWithTickState(context, checklist.id).loaded()
        assertEquals("goblet squats x3", state.single().item.text)
        assertEquals(5, state.single().item.sortOrder)

        ChecklistController.deleteItem(context, item.id)
        val afterDelete = ChecklistController.itemsWithTickState(context, checklist.id).loaded()
        assertTrue(afterDelete.isEmpty())
    }

    @Test
    fun `checklistHistory returns no line for a day nothing was ticked`() = runBlocking {
        val checklist = ChecklistController.createChecklist(context, "bio")
        ChecklistController.addItem(context, checklist.id, "goblet squats")
        val today = ChecklistController.today()

        val history = ChecklistController.checklistHistory(context, checklist.id, today, today)
        assertTrue("no row means not done, never an explicit false line", history.isEmpty())
    }

    // ---- measured items ---------------------------------------------------------------------------

    @Test
    fun `a measured tick stores its value and source`() = runBlocking {
        val checklist = ChecklistController.createChecklist(context, "bio")
        val item = ChecklistController.addItem(
            context, checklist.id, "walk 10k steps",
            measureUnit = "steps", measureTarget = 10000.0, measureDirection = "AT_LEAST",
        )
        val today = day(2026, 9, 4)

        val outcome = ChecklistController.tick(context, item.id, today, value = 8400.0)
        assertTrue("a valid measured tick is accepted", outcome is ChecklistController.TickOutcome.Ticked)

        val state = ChecklistController.itemsWithTickState(context, checklist.id, today).loaded()
        assertTrue(state.single().ticked)
        assertEquals(8400.0, state.single().value)
    }

    @Test
    fun `a valueless tick on a measured item is refused and writes nothing`() = runBlocking {
        val checklist = ChecklistController.createChecklist(context, "bio")
        val item = ChecklistController.addItem(context, checklist.id, "walk 10k steps", measureUnit = "steps")
        val today = day(2026, 9, 4)

        val outcome = ChecklistController.tick(context, item.id, today)
        assertTrue("no number, no tick - a number is the point", outcome is ChecklistController.TickOutcome.Refused)

        val state = ChecklistController.itemsWithTickState(context, checklist.id, today).loaded()
        assertFalse("nothing was written by the refused tick", state.single().ticked)
    }

    @Test
    fun `a valueless tick on a binary item still works`() = runBlocking {
        val checklist = ChecklistController.createChecklist(context, "bio")
        val item = ChecklistController.addItem(context, checklist.id, "goblet squats")
        val today = day(2026, 9, 4)

        val outcome = ChecklistController.tick(context, item.id, today)
        assertTrue(outcome is ChecklistController.TickOutcome.Ticked)

        val state = ChecklistController.itemsWithTickState(context, checklist.id, today).loaded()
        assertTrue(state.single().ticked)
    }

    // ---- schedules ----------------------------------------------------------------------------------

    @Test
    fun `a WEEKLY MON,WED,FRI list is absent on Tuesday and present on Wednesday`() = runBlocking {
        val checklist = ChecklistController.createChecklist(
            context, "lifting",
            scheduleKind = "WEEKLY", scheduleEvery = 1, scheduleDaysOfWeek = "MONDAY,WEDNESDAY,FRIDAY",
        )
        // 2026-09-07 is a Monday, so 2026-09-08 is Tuesday and 2026-09-09 is Wednesday.
        val tuesday = day(2026, 9, 8)
        val wednesday = day(2026, 9, 9)

        val onTuesday = ChecklistController.checklistsForDay(context, tuesday)
        val onWednesday = ChecklistController.checklistsForDay(context, wednesday)

        assertTrue("WEEKLY MON,WED,FRI does not apply on a Tuesday", onTuesday.none { it.id == checklist.id })
        assertTrue("WEEKLY MON,WED,FRI applies on a Wednesday", onWednesday.any { it.id == checklist.id })
    }

    @Test
    fun `a no-schedule list appears every day after creation`() = runBlocking {
        val checklist = backdatedChecklist("groceries", createdAt = epochMs(2026, 1, 1))
        val farFuture = day(2027, 1, 1)

        val checklists = ChecklistController.checklistsForDay(context, farFuture)
        assertTrue("a plain todo list with no schedule applies every day", checklists.any { it.id == checklist.id })
    }

    // ---- decision 4: completion semantics come from the schedule, not the deprecated recursDaily -

    @Test
    fun `a no-schedule list's line ticked on Monday reads done on Wednesday`() = runBlocking {
        val checklist = backdatedChecklist("groceries", createdAt = epochMs(2026, 1, 1)) // scheduleKind null
        val item = ChecklistController.addItem(context, checklist.id, "milk")
        val monday = day(2026, 9, 7)
        val wednesday = day(2026, 9, 9)

        ChecklistController.tick(context, item.id, monday)

        val wednesdayState = ChecklistController.itemsWithTickState(context, checklist.id, wednesday).loaded()
        assertTrue("a plain todo list is done once, ever - not just on the day it was ticked", wednesdayState.single().ticked)
    }

    @Test
    fun `a DAILY list's line ticked Monday reads NOT done on Wednesday`() = runBlocking {
        val checklist = backdatedChecklist("bio", createdAt = epochMs(2026, 1, 1), scheduleKind = "DAILY", scheduleEvery = 1)
        val item = ChecklistController.addItem(context, checklist.id, "goblet squats")
        val monday = day(2026, 9, 7)
        val wednesday = day(2026, 9, 9)

        ChecklistController.tick(context, item.id, monday)

        val wednesdayState = ChecklistController.itemsWithTickState(context, checklist.id, wednesday).loaded()
        assertFalse("a DAILY list is tracked per day - Monday's tick is not Wednesday's", wednesdayState.single().ticked)
    }

    @Test
    fun `a WEEKLY list's line ticked Monday reads NOT done on Wednesday`() = runBlocking {
        val checklist = backdatedChecklist(
            "lifting", createdAt = epochMs(2026, 1, 1),
            scheduleKind = "WEEKLY", scheduleEvery = 1,
        )
        val item = ChecklistController.addItem(context, checklist.id, "deadlifts")
        val monday = day(2026, 9, 7)
        val wednesday = day(2026, 9, 9)

        ChecklistController.tick(context, item.id, monday)

        val wednesdayState = ChecklistController.itemsWithTickState(context, checklist.id, wednesday).loaded()
        assertFalse("a WEEKLY list is tracked per day too, same as DAILY", wednesdayState.single().ticked)
    }

    @Test
    fun `history for a no-schedule list still shows the day it was actually ticked`() = runBlocking {
        val checklist = backdatedChecklist("groceries", createdAt = epochMs(2026, 1, 1)) // scheduleKind null
        val item = ChecklistController.addItem(context, checklist.id, "milk")
        val monday = day(2026, 9, 7)
        val wednesday = day(2026, 9, 9)

        ChecklistController.tick(context, item.id, monday)

        // itemsWithTickState says "done" on every later day (decision 4), but checklistHistory is
        // never derived from that - it reports the actual (item, day) tick rows, so the history
        // still shows Monday as the day it happened, not a line manufactured for Wednesday too.
        val history = ChecklistController.checklistHistory(context, checklist.id, monday, wednesday)
        assertEquals("only the real tick on Monday, nothing manufactured for Wednesday", 1, history.size)
        assertEquals(monday, history.single().day)
    }

    // ---- itemsWithTickState carries its own failure signal ---------------------------------------

    @Test
    fun `itemsWithTickState returns Failed when the DAO throws`() = runBlocking {
        val checklist = ChecklistController.createChecklist(context, "bio")
        ChecklistController.addItem(context, checklist.id, "goblet squats")

        // Closing the CACHED CarDatabase instance directly (never through
        // RoomTestReset.resetCarDatabaseSingleton, which also nulls the singleton field) is this
        // suite's own way of making a real DAO throw: writing garbage bytes over the on-disk file
        // was tried first and does NOT work - Android's framework SQLiteDatabase detects the
        // corruption on open and silently deletes-and-recreates an empty database rather than
        // throwing, so [ChecklistController.getChecklist] just finds nothing and this reads as a
        // quiet [ChecklistController.ChecklistItemsResult.Loaded] instead of the failure being
        // tested for. Closing the instance Room itself is still holding a reference to (rather
        // than rebuilding a fresh one against the same file) cancels the coroutine scope Room's
        // suspend DAO functions run on, so the very next DAO call throws a real
        // `CancellationException` - still an exception the [runCatching] this function's own doc
        // comment describes must catch, whatever its exact class.
        CarDatabase.getDatabase(context).close()

        val result = ChecklistController.itemsWithTickState(context, checklist.id)
        assertTrue("a thrown DAO read must not read as a quiet empty checklist", result is ChecklistController.ChecklistItemsResult.Failed)
    }
}
