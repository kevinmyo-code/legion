package com.kevin.legion.data.local

import androidx.room.Room
import com.kevin.legion.advisor.GoalPlanAgent
import com.kevin.legion.testutil.RoomTestReset
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * [CompanionMemoryDao.byCategoryPrefixed] - the read behind goal-plans ticket 03's whole promise:
 * "having said once that he has no gym, he should not have to say it again." Same Robolectric-
 * plus-Room shape as [CompanionMemoryRecallScopeTest], which this class deliberately does not
 * duplicate - [getRecallScan]'s own cross-vehicle behaviour is that file's job; this one is
 * scoped to the narrower prefix filter [GoalPlanAgent.withConstraints]'s caller relies on.
 */
@RunWith(RobolectricTestRunner::class)
class CompanionMemoryConstraintTest {

    private lateinit var db: CarDatabase
    private lateinit var dao: CompanionMemoryDao

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), CarDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = db.companionMemoryDao()
    }

    @After
    fun tearDown() {
        // This test builds its OWN CarDatabase (Room.inMemoryDatabaseBuilder above) rather than
        // going through CarDatabase.getDatabase, but Room's InvalidationTracker refresh still runs
        // on ArchTaskExecutor's process-wide disk-IO pool - draining before close() is the same
        // fix RoomTestReset applies to the singleton path, needed here for the same reason. See
        // RoomTestReset's class doc comment for the full account
        // (.scratch/hardening/issues/13-the-suite-is-green-by-luck.md).
        RoomTestReset.drainArchDiskIoPool()
        db.close()
    }

    private fun row(
        text: String,
        category: String = CompanionMemory.Category.DRIVER,
        vehicleId: String = "car-a",
    ) = CompanionMemory(
        vehicleId = vehicleId,
        text = text,
        category = category,
        source = CompanionMemory.Source.STATED,
        createdAt = System.currentTimeMillis(),
    )

    @Test
    fun `finds only rows carrying the constraint prefix, not every driver-category row`() = runBlocking {
        dao.insert(row(GoalPlanAgent.CONSTRAINT_PREFIX + "no gym access"))
        // An ordinary driver fact with no prefix - must not be mistaken for a plan constraint.
        dao.insert(row("Listens to Attack on Titan opening themes"))

        val found = dao.byCategoryPrefixed(CompanionMemory.Category.DRIVER, GoalPlanAgent.CONSTRAINT_PREFIX)

        assertEquals(1, found.size)
        assertTrue(found.first().text.contains("no gym access"))
    }

    @Test
    fun `is cross-vehicle - constraints from every car come back together`() = runBlocking {
        dao.insert(row(GoalPlanAgent.CONSTRAINT_PREFIX + "vegetarian", vehicleId = "car-a"))
        dao.insert(row(GoalPlanAgent.CONSTRAINT_PREFIX + "no gym access", vehicleId = "car-b"))

        val found = dao.byCategoryPrefixed(CompanionMemory.Category.DRIVER, GoalPlanAgent.CONSTRAINT_PREFIX)

        assertEquals(
            "a stated fitness constraint outlives whichever car happens to be connected",
            2, found.size,
        )
    }

    @Test
    fun `a car_anchored row with a matching prefix by coincidence is never returned`() = runBlocking {
        dao.insert(
            row(
                GoalPlanAgent.CONSTRAINT_PREFIX + "not actually a fitness constraint",
                category = CompanionMemory.Category.CAR_ANCHORED,
            ),
        )

        assertTrue(
            "the category filter must hold even if a car-anchored row's text happened to start " +
                "with the same marker",
            dao.byCategoryPrefixed(CompanionMemory.Category.DRIVER, GoalPlanAgent.CONSTRAINT_PREFIX).isEmpty(),
        )
    }

    @Test
    fun `returns constraints oldest first, the order they were actually stated`() = runBlocking {
        dao.insert(row(GoalPlanAgent.CONSTRAINT_PREFIX + "first stated"))
        Thread.sleep(2)
        dao.insert(row(GoalPlanAgent.CONSTRAINT_PREFIX + "second stated"))

        val found = dao.byCategoryPrefixed(CompanionMemory.Category.DRIVER, GoalPlanAgent.CONSTRAINT_PREFIX)

        assertEquals(2, found.size)
        assertTrue(found[0].text.contains("first stated"))
        assertTrue(found[1].text.contains("second stated"))
    }
}
