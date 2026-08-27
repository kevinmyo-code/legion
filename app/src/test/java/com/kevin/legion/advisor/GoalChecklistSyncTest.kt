package com.kevin.legion.advisor

import com.kevin.legion.data.local.CarDatabase
import com.kevin.legion.data.local.ListItem
import com.kevin.legion.data.local.MealTarget
import com.kevin.legion.data.local.RecordProvenance
import com.kevin.legion.data.local.WorkoutPlan
import com.kevin.legion.data.local.WorkoutPlanItem
import com.kevin.legion.engine.RecordStore
import com.kevin.legion.engine.notes.NotesAspectSeeder
import com.kevin.legion.meals.dayStartEpoch
import com.kevin.legion.notes.NotesController
import com.kevin.legion.testutil.RoomTestReset
import com.kevin.legion.workouts.WorkoutController
import com.kevin.legion.workouts.weekStartEpoch
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
 * [GoalChecklistSync] (ticket 06, `goal-plans`) - Robolectric-plus-Room, same shape as
 * [com.kevin.legion.service.LiveToolboxGoalPlanTest], because [GoalChecklistSync.materializeToday]
 * touches Room through [NotesController] and this domain's own reconciliation ([GoalChecklistTest])
 * is already covered pure and separately.
 *
 * **Cutover 1** (`docs/architecture/cutover1-2026-08-24.md`): [NotesController] is now
 * engine-backed, so every fixture below that used to insert a backdated/pre-ticked `ListItem`
 * straight into `ListItemDao` now goes through [insertEngineItem] instead - the SAME
 * [RecordStore] door `NotesController` itself writes through, just with an explicit `now` so a
 * fixture can still look like an item that genuinely aged into the past. Every read-back that used
 * to call `db.listItemDao().getById` now calls [NotesController.itemById].
 *
 * Three things this ticket's own verification names explicitly, each with its own test below:
 * idempotence across repeated same-day runs, that a materialized line is a genuinely tickable
 * one-off item (not refused by [NotesController.tick]'s recurring-item guard), and that the
 * retention trim removes ticked and un-ticked plan items together.
 */
@RunWith(RobolectricTestRunner::class)
class GoalChecklistSyncTest {
    private val context = RuntimeEnvironment.getApplication()

    @Before
    fun clearState() {
        RoomTestReset.resetCarDatabaseSingleton()
    }

    private suspend fun givenAMealTarget(now: Long) {
        CarDatabase.getDatabase(context).mealTargetDao().upsert(
            MealTarget(
                caloriesKcal = 2300, proteinG = 180.0, carbsG = 220.0, fatG = 70.0,
                effectiveFromDateEpoch = dayStartEpoch(now), updatedAt = now,
            ),
        )
    }

    /** Writes one engine-backed plan-shaped item directly through [RecordStore] - the same door
     * [NotesController] itself uses - so a fixture can carry an arbitrary `createdAt`/`done`/
     * `doneAt`/`repeatKind` combination [NotesController.addItem] itself has no parameter for
     * (it always stamps real wall-clock "now"). Returns the new record's id. */
    private suspend fun insertEngineItem(
        text: String,
        done: Boolean = false,
        doneAt: Long? = null,
        sortOrder: Int = 0,
        createdAt: Long,
        repeatKind: String? = null,
    ): Long {
        val db = CarDatabase.getDatabase(context)
        val schema = NotesAspectSeeder.ensureSeeded(context)
        val store = RecordStore(db.engineRecordDao(), db.fieldDefDao(), db.recordTypeDao())
        val values = mapOf(
            schema.fieldIds.getValue(NotesAspectSeeder.FIELD_TEXT) to text,
            schema.fieldIds.getValue(NotesAspectSeeder.FIELD_DONE) to done,
            schema.fieldIds.getValue(NotesAspectSeeder.FIELD_DONE_AT) to doneAt,
            schema.fieldIds.getValue(NotesAspectSeeder.FIELD_SORT_ORDER) to sortOrder,
            schema.fieldIds.getValue(NotesAspectSeeder.FIELD_REPEAT_KIND) to repeatKind,
        )
        val result = store.create(schema.recordTypeId, values, RecordProvenance.USER, now = createdAt)
        return (result as RecordStore.WriteResult.Success).recordId
    }

    /** Every non-deleted [ListItem] this object has ever written, on the one list. */
    private suspend fun planItems(): List<ListItem> {
        val list = NotesController.theList(context)
        return NotesController.allItems(context).filter { it.listId == list.id && it.text.startsWith(GoalChecklistSync.ITEM_PREFIX) }
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


    // --- idempotence: opening the app five times in one day must not produce five copies --------

    @Test
    fun `materializing the same day repeatedly does not duplicate the line`() = runBlocking {
        val now = System.currentTimeMillis()
        givenAMealTarget(now)

        // Five calls, same as five app opens in one day - the ticket's own worded scenario.
        repeat(5) { GoalChecklistSync.materializeToday(context, now) }

        val items = planItems()
        assertEquals("one call's worth of lines, not five", 1, items.size)
        assertTrue(items.single().text.contains("2300 kcal"))
    }

    @Test
    fun `materializing again after a target changes replaces the old line, still once`() = runBlocking {
        val now = System.currentTimeMillis()
        givenAMealTarget(now)
        GoalChecklistSync.materializeToday(context, now)

        // The target changes mid-day - a later acceptance writes a new MealTarget row, and a
        // second materialize call in the SAME day's window should retire the stale line rather
        // than pile a second one alongside it.
        CarDatabase.getDatabase(context).mealTargetDao().upsert(
            MealTarget(
                caloriesKcal = 2500, proteinG = 190.0, carbsG = 230.0, fatG = 75.0,
                effectiveFromDateEpoch = dayStartEpoch(now), updatedAt = now + 1,
            ),
        )
        GoalChecklistSync.materializeToday(context, now)

        val items = planItems()
        assertEquals(1, items.size)
        assertTrue("the superseded 2300 line must be gone, not left stale", items.single().text.contains("2500 kcal"))
    }

    // --- a materialized plan line is genuinely tickable, not refused ------------------------------

    @Test
    fun `a materialized plan line is a one-off item and NotesController tick accepts it`() = runBlocking {
        val now = System.currentTimeMillis()
        givenAMealTarget(now)
        GoalChecklistSync.materializeToday(context, now)

        val item = planItems().single()
        assertNull(
            "repeatKind must stay null - this is the entire fix ticket 06 exists to make; a " +
                "non-null repeatKind here would mean NotesController.tick refuses this item exactly " +
                "the way it refused ticket 04's recurring version",
            item.repeatKind,
        )

        val ticked = NotesController.tick(context, item)
        assertTrue("a one-off item must be accepted by NotesController.tick's guard, not refused", ticked)

        val reread = NotesController.itemById(context, item.id)
        assertNotNull(reread)
        assertTrue(reread!!.done)
        assertNotNull("a real done timestamp must exist for currentItems to read back", reread.doneAt)
    }

    @Test
    fun `currentItems reflects a real done state after ticking, not a skip proxy`() = runBlocking {
        val now = System.currentTimeMillis()
        givenAMealTarget(now)
        GoalChecklistSync.materializeToday(context, now)
        val item = planItems().single()
        NotesController.tick(context, item)

        val view = GoalChecklistSync.currentItems(context, now).single()
        assertTrue(view.done)
        assertNotNull(view.doneAt)
    }

    @Test
    fun `no plan yet reads as no items, never a zero-progress row`() = runBlocking {
        val now = System.currentTimeMillis()
        // No target of any kind written - materializeToday must not invent an item to represent
        // "nothing accepted", mirroring GoalChecklist.forToday's own hasPlan=false/items=empty rule.
        GoalChecklistSync.materializeToday(context, now)

        assertTrue(planItems().isEmpty())
        assertTrue(GoalChecklistSync.currentItems(context, now).isEmpty())
    }

    // --- ticket 07: the UI tick path (GoalChecklistSync.toggle) and the voice tick path
    // (NotesController.tick, called directly by manage_item) reach the SAME function -------------

    @Test
    fun `toggle refuses a recurring item exactly like NotesController tick does`() = runBlocking {
        // A second, duplicate ticking implementation could easily skip NotesController.tick's
        // recurring-item guard. This only stays refused if GoalChecklistSync.toggle really calls
        // NotesController.tick itself rather than reimplementing "flip done" on its own - proof
        // the UI path and the voice path are the SAME function, not two that happen to agree today.
        val now = System.currentTimeMillis()
        val recurringId = insertEngineItem(
            text = GoalChecklistSync.ITEM_PREFIX + "Recurring artifact",
            repeatKind = "WEEKLY", createdAt = now,
        )

        GoalChecklistSync.toggle(context, recurringId)

        val after = NotesController.itemById(context, recurringId)
        assertNotNull(after)
        assertFalse("a recurring item must stay un-ticked - NotesController.tick's own guard, not a checkbox-side one", after!!.done)
    }

    @Test
    fun `toggle ticks then unticks a one-off plan item, a real round trip through NotesController`() = runBlocking {
        val now = System.currentTimeMillis()
        givenAMealTarget(now)
        GoalChecklistSync.materializeToday(context, now)
        val item = planItems().single()

        GoalChecklistSync.toggle(context, item.id)
        val ticked = NotesController.itemById(context, item.id)
        assertNotNull(ticked)
        assertTrue(ticked!!.done)
        assertNotNull("a real doneAt, matching what NotesController.tick itself stamps", ticked.doneAt)

        GoalChecklistSync.toggle(context, item.id)
        val unticked = NotesController.itemById(context, item.id)
        assertNotNull(unticked)
        assertFalse(unticked!!.done)
    }

    @Test
    fun `toggle on an id that does not exist is a silent no-op, not a crash`() = runBlocking {
        GoalChecklistSync.toggle(context, 999_999L)
    }

    // --- ticket 07: a plan change made TODAY never reaches back and untick a PAST day's item ------

    @Test
    fun `a plan change today does not touch or untick an already-ticked plan item from a past day`() = runBlocking {
        val now = System.currentTimeMillis()
        val yesterday = now - 24 * 60 * 60 * 1000

        // A row materialized "yesterday" and ticked that day, under whatever plan was in effect
        // then - inserted directly with a backdated createdAt, the same pattern the retention
        // tests above use to look like a row that genuinely aged out of today's window, since
        // NotesController.addItem always stamps the real wall-clock "now" and cannot itself be
        // backdated.
        val yesterdayItemId = insertEngineItem(
            text = GoalChecklistSync.ITEM_PREFIX + "Squat: 9 sets this week",
            done = true, doneAt = yesterday, createdAt = yesterday,
        )

        // Today: the plan changes (a meal target now exists where none did before) and
        // materializeToday runs for TODAY only.
        givenAMealTarget(now)
        GoalChecklistSync.materializeToday(context, now)

        val reread = NotesController.itemById(context, yesterdayItemId)
        assertNotNull(reread)
        assertTrue(
            "a past day's ticked item must survive a plan change made today - materializeToday's " +
                "own \"already materialized today\" scoping never reads or writes a row outside " +
                "today's createdAt window, so a completed session is a fact about the past a later " +
                "plan change has no way to reach back and un-happen",
            reread!!.done,
        )
    }

    // --- retention: ticked and un-ticked plan items are removed TOGETHER outside the window -------

    @Test
    fun `plan items older than the retention window are trimmed, ticked and un-ticked alike`() = runBlocking {
        val now = System.currentTimeMillis()
        val longAgo = now - (GoalChecklistSync.RETENTION_DAYS + 1) * 24 * 60 * 60 * 1000

        // Two plan items materialized "long ago" and never trimmed since - one ticked that day,
        // one left open, both directly inserted with a backdated createdAt the way an item that
        // genuinely aged past the window would look, since NotesController.addItem always stamps
        // "now".
        val doneOldId = insertEngineItem(
            text = GoalChecklistSync.ITEM_PREFIX + "Sleep 8h",
            done = true, doneAt = longAgo, createdAt = longAgo,
        )
        val openOldId = insertEngineItem(
            text = GoalChecklistSync.ITEM_PREFIX + "Hit 2300 kcal / 180g protein",
            sortOrder = 1, createdAt = longAgo,
        )

        // A materialize call today is what triggers the trim (trim-on-write, matching
        // ConversationAuditDao.record's own convention) - a plan with no target today still runs
        // the trim half of materializeToday even though it derives zero wanted lines.
        GoalChecklistSync.materializeToday(context, now)

        assertNull("the ticked old item must be gone too - the denominator, not just the numerator, is removed", NotesController.itemById(context, doneOldId))
        assertNull("the un-ticked old item must be gone", NotesController.itemById(context, openOldId))
    }

    @Test
    fun `a plan item inside the retention window survives a materialize call`() = runBlocking {
        val now = System.currentTimeMillis()
        val recent = now - (GoalChecklistSync.RETENTION_DAYS - 1) * 24 * 60 * 60 * 1000

        val recentId = insertEngineItem(
            text = GoalChecklistSync.ITEM_PREFIX + "Sleep 8h",
            done = true, doneAt = recent, createdAt = recent,
        )

        GoalChecklistSync.materializeToday(context, now)

        assertNotNull("an item still inside the window must not be swept up by the same trim pass", NotesController.itemById(context, recentId))
    }

    // --- ticket 08: the lazy end-of-day auto-log sweep --------------------------------------------

    /** A whole-plan `WorkoutPlan` + one `WorkoutPlanItem`, effective for [weekStart] - the minimum
     * a sweep candidate needs behind it so [GoalChecklist.workoutLinesForDay] can regenerate the
     * exact line the sweep will try to match. */
    private suspend fun givenAWorkoutPlan(weekStart: Long, at: Long, exercise: String = "Kettlebell swing", targetSets: Int = 12, sessionsPerWeek: Int = 7) {
        val db = CarDatabase.getDatabase(context)
        db.workoutPlanDao().upsert(WorkoutPlan(sessionsPerWeek = sessionsPerWeek, effectiveFromWeekEpoch = weekStart, updatedAt = at))
        db.workoutPlanItemDao().upsert(WorkoutPlanItem(exercise = exercise, targetSetsPerWeek = targetSets, effectiveFromWeekEpoch = weekStart, updatedAt = at))
    }

    @Test
    fun `a ticked past-day workout line auto-logs exactly once across three materialization runs`() = runBlocking {
        val now = System.currentTimeMillis()
        val yesterday = now - 24 * 60 * 60 * 1000
        val yesterdayDow = java.time.Instant.ofEpochMilli(yesterday).atZone(java.time.ZoneId.systemDefault()).dayOfWeek
        val weekStart = weekStartEpoch(yesterday)
        // sessionsPerWeek = 7 ("train every day") so the test does not need to reason about WHICH
        // of the week's days assignedDays(N) actually lands on - every day qualifies, so whatever
        // day "yesterday" happens to be is always an assigned one.
        givenAWorkoutPlan(weekStart, yesterday, sessionsPerWeek = 7)

        // The exact line GoalChecklist.workoutLinesForDay would have derived for that day under
        // that plan - built from the SAME function the sweep itself calls, never hand-typed, so
        // this test cannot drift from a formatting change the production code also picked up.
        val line = GoalChecklist.workoutLinesForDay(
            listOf(WorkoutPlanItem(exercise = "Kettlebell swing", targetSetsPerWeek = 12, effectiveFromWeekEpoch = weekStart, updatedAt = yesterday)),
            7,
            yesterdayDow,
        ).single()

        val itemId = insertEngineItem(
            text = GoalChecklistSync.ITEM_PREFIX + line.text,
            done = true, doneAt = yesterday, createdAt = yesterday,
        )

        // Three materialize calls, same as three app opens - idempotence is the entire point.
        val db = CarDatabase.getDatabase(context)
        repeat(3) { GoalChecklistSync.materializeToday(context, now) }

        val logs = db.workoutSetLogDao().getRecent(10)
        assertEquals("exactly one log across three materialization runs, not three", 1, logs.size)
        val log = logs.single()
        assertEquals("Kettlebell swing", log.exercise)
        assertEquals(line.sets, log.sets)
        assertEquals(
            "the logged row must carry the ITEM's own day, not the sweep's day",
            yesterday, log.loggedAt,
        )

        val reread = NotesController.itemById(context, itemId)
        assertNotNull(reread)
        assertTrue("the tick itself must survive the sweep - adherence is not deleted", reread!!.done)
        assertNotNull("loggedAt must now be set - this is the whole idempotence anchor", reread.loggedAt)
    }

    @Test
    fun `a ticked past-day meal or sleep line logs nothing`() = runBlocking {
        val now = System.currentTimeMillis()
        val yesterday = now - 24 * 60 * 60 * 1000
        val mealItemId = insertEngineItem(
            text = GoalChecklistSync.ITEM_PREFIX + "Hit 2300 kcal / 180g protein",
            done = true, doneAt = yesterday, createdAt = yesterday,
        )
        val sleepItemId = insertEngineItem(
            text = GoalChecklistSync.ITEM_PREFIX + "Sleep 8h",
            done = true, doneAt = yesterday, sortOrder = 1, createdAt = yesterday,
        )

        GoalChecklistSync.materializeToday(context, now)

        val db = CarDatabase.getDatabase(context)
        assertTrue(
            "a meal/sleep tick must never invent a workout log - it is not a workout line",
            db.workoutSetLogDao().getRecent(10).isEmpty(),
        )
        assertNull(NotesController.itemById(context, mealItemId)!!.loggedAt)
        assertNull(NotesController.itemById(context, sleepItemId)!!.loggedAt)
    }

    @Test
    fun `an un-ticked past-day workout line is never swept`() = runBlocking {
        val now = System.currentTimeMillis()
        val yesterday = now - 24 * 60 * 60 * 1000
        val yesterdayDow = java.time.Instant.ofEpochMilli(yesterday).atZone(java.time.ZoneId.systemDefault()).dayOfWeek
        val weekStart = weekStartEpoch(yesterday)
        givenAWorkoutPlan(weekStart, yesterday, sessionsPerWeek = 7)
        val line = GoalChecklist.workoutLinesForDay(
            listOf(WorkoutPlanItem(exercise = "Kettlebell swing", targetSetsPerWeek = 12, effectiveFromWeekEpoch = weekStart, updatedAt = yesterday)),
            7,
            yesterdayDow,
        ).single()

        insertEngineItem(text = GoalChecklistSync.ITEM_PREFIX + line.text, done = false, createdAt = yesterday)

        GoalChecklistSync.materializeToday(context, now)

        val db = CarDatabase.getDatabase(context)
        assertTrue("nothing was ticked, so nothing should have been reported logged", db.workoutSetLogDao().getRecent(10).isEmpty())
    }

    @Test
    fun `a ticked line whose text no longer matches any current plan derivation is skipped, never guessed`() = runBlocking {
        val now = System.currentTimeMillis()
        val yesterday = now - 24 * 60 * 60 * 1000
        // No WorkoutPlan/WorkoutPlanItem on file for that week at all - a line whose plan has since
        // changed (or was never a real plan-derived line) has nothing to structurally match against.
        val itemId = insertEngineItem(
            text = GoalChecklistSync.ITEM_PREFIX + "3 sets - Kettlebell swing",
            done = true, doneAt = yesterday, createdAt = yesterday,
        )

        GoalChecklistSync.materializeToday(context, now)

        val db = CarDatabase.getDatabase(context)
        assertTrue(
            "no anchor to match against means no claim - never fabricate exercise/sets for an unmatched line",
            db.workoutSetLogDao().getRecent(10).isEmpty(),
        )
        assertNull("an unmatched item stays un-swept, available for a later materialize if the plan is restored", NotesController.itemById(context, itemId)!!.loggedAt)
    }

    // --- ticket 09: "a ticked workout is one act, not two rows" ---------------------------------

    @Test
    fun `untick after a sweep removes the log and clears the anchor`() = runBlocking {
        val now = System.currentTimeMillis()
        val yesterday = now - 24 * 60 * 60 * 1000
        val yesterdayDow = java.time.Instant.ofEpochMilli(yesterday).atZone(java.time.ZoneId.systemDefault()).dayOfWeek
        val weekStart = weekStartEpoch(yesterday)
        givenAWorkoutPlan(weekStart, yesterday, sessionsPerWeek = 7)
        val line = GoalChecklist.workoutLinesForDay(
            listOf(WorkoutPlanItem(exercise = "Kettlebell swing", targetSetsPerWeek = 12, effectiveFromWeekEpoch = weekStart, updatedAt = yesterday)),
            7,
            yesterdayDow,
        ).single()

        val itemId = insertEngineItem(
            text = GoalChecklistSync.ITEM_PREFIX + line.text,
            done = true, doneAt = yesterday, createdAt = yesterday,
        )

        GoalChecklistSync.materializeToday(context, now)
        val db = CarDatabase.getDatabase(context)
        assertEquals("the sweep must have written a log to undo", 1, db.workoutSetLogDao().getRecent(10).size)
        val sweptItem = NotesController.itemById(context, itemId)!!
        assertNotNull("loggedAt must be stamped before the untick", sweptItem.loggedAt)

        NotesController.untick(context, sweptItem)

        assertTrue(
            "the phantom set defect: unticking must delete the log the sweep wrote, not leave it behind forever",
            db.workoutSetLogDao().getRecent(10).isEmpty(),
        )
        val untickedItem = NotesController.itemById(context, itemId)!!
        assertFalse(untickedItem.done)
        assertNull("loggedAt must clear too, so a re-tick is eligible for a fresh sweep", untickedItem.loggedAt)
    }

    @Test
    fun `a manual log then a tick produces exactly ONE row`() = runBlocking {
        val now = System.currentTimeMillis()
        val yesterday = now - 24 * 60 * 60 * 1000
        val yesterdayDow = java.time.Instant.ofEpochMilli(yesterday).atZone(java.time.ZoneId.systemDefault()).dayOfWeek
        val weekStart = weekStartEpoch(yesterday)
        givenAWorkoutPlan(weekStart, yesterday, sessionsPerWeek = 7)
        val line = GoalChecklist.workoutLinesForDay(
            listOf(WorkoutPlanItem(exercise = "Kettlebell swing", targetSetsPerWeek = 12, effectiveFromWeekEpoch = weekStart, updatedAt = yesterday)),
            7,
            yesterdayDow,
        ).single()

        // The hand/voice log lands FIRST, same day, same exercise, exactly as WorkoutController.logSet
        // is called everywhere except the sweep - sourceListItemId stays null.
        val outcome = WorkoutController.logSet(
            context = context, exercise = "Kettlebell swing", sets = 3, reps = 10,
            weightValue = null, weightUnit = null, loggedAt = yesterday,
        )
        assertTrue(outcome.success)

        val itemId = insertEngineItem(
            text = GoalChecklistSync.ITEM_PREFIX + line.text,
            done = true, doneAt = yesterday, createdAt = yesterday,
        )

        GoalChecklistSync.materializeToday(context, now)

        val db = CarDatabase.getDatabase(context)
        val logs = db.workoutSetLogDao().getRecent(10)
        assertEquals(
            "a driver who logged it by hand AND ticked the line did one workout, not two",
            1, logs.size,
        )
        assertNull("the surviving row is the manual one, not a swept duplicate", logs.single().sourceListItemId)
        assertNotNull(
            "the tick still counts as adherence even though nothing new was written",
            NotesController.itemById(context, itemId)!!.loggedAt,
        )
    }

    @Test
    fun `a tick alone with no manual log still produces exactly one row`() = runBlocking {
        val now = System.currentTimeMillis()
        val yesterday = now - 24 * 60 * 60 * 1000
        val yesterdayDow = java.time.Instant.ofEpochMilli(yesterday).atZone(java.time.ZoneId.systemDefault()).dayOfWeek
        val weekStart = weekStartEpoch(yesterday)
        givenAWorkoutPlan(weekStart, yesterday, sessionsPerWeek = 7)
        val line = GoalChecklist.workoutLinesForDay(
            listOf(WorkoutPlanItem(exercise = "Kettlebell swing", targetSetsPerWeek = 12, effectiveFromWeekEpoch = weekStart, updatedAt = yesterday)),
            7,
            yesterdayDow,
        ).single()

        insertEngineItem(
            text = GoalChecklistSync.ITEM_PREFIX + line.text,
            done = true, doneAt = yesterday, createdAt = yesterday,
        )

        GoalChecklistSync.materializeToday(context, now)

        val db = CarDatabase.getDatabase(context)
        assertEquals(1, db.workoutSetLogDao().getRecent(10).size)
    }

    @Test
    fun `the linked column is null for hand and voice logs`() = runBlocking {
        val outcome = WorkoutController.logSet(
            context = context, exercise = "Bench press", sets = 4, reps = 8,
            weightValue = 135.0, weightUnit = "lbs",
        )
        assertTrue(outcome.success)

        val db = CarDatabase.getDatabase(context)
        val log = db.workoutSetLogDao().getRecent(1).single()
        assertNull(
            "WorkoutController.logSet's default is null for every existing caller (voice, the dialog) - only the sweep passes a real id",
            log.sourceListItemId,
        )
    }
}
