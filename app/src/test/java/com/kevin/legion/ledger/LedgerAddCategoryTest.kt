package com.kevin.legion.ledger

import com.kevin.legion.data.local.CarDatabase
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
 * [LedgerController.addCategory] end to end against a real Room database (Kevin 2026-08-07, "let
 * me add a category, without letting the model invent one"). [CategoryValidationTest] already
 * covers [validateNewCategoryName] itself in isolation; this pins that the CONTROLLER validates
 * against the LIVE stored list (which now starts non-empty thanks to the fresh-install seeding fix
 * - see [com.kevin.legion.data.local.CarDatabaseFreshInstallTest]) and that a confirmed write is
 * actually visible to [com.kevin.legion.data.local.CategoryDao.allNames] afterward, the exact list
 * `set_category`'s tool-boundary validation reads (D14).
 */
@RunWith(RobolectricTestRunner::class)
class LedgerAddCategoryTest {
    private val context = RuntimeEnvironment.getApplication()

    @Before
    fun clearState() {
        RoomTestReset.resetCarDatabaseSingleton()
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


    @Test
    fun `a new category is written and immediately visible to allNames`() = runBlocking {
        val result = LedgerController.addCategory(context, "Hobbies")

        assertTrue(result is NewCategoryValidation.Valid)
        val names = CarDatabase.getDatabase(context).categoryDao().allNames()
        assertTrue("expected 'Hobbies' among the stored categories, got $names", "Hobbies" in names)
    }

    @Test
    fun `adding a category that collides case-insensitively with a seeded one is refused and not duplicated`() = runBlocking {
        // "Pets" is already seeded by the fresh-install callback (CategorySeed.starter).
        val before = CarDatabase.getDatabase(context).categoryDao().allNames().size

        val result = LedgerController.addCategory(context, "pets")

        assertTrue(result is NewCategoryValidation.Invalid)
        assertEquals("\"pets\" already exists.", (result as NewCategoryValidation.Invalid).reason)
        val after = CarDatabase.getDatabase(context).categoryDao().allNames()
        assertEquals("expected no new row from a refused duplicate", before, after.size)
    }

    @Test
    fun `set_category's validation list grows once Kevin adds a category`() = runBlocking {
        val beforeNames = CarDatabase.getDatabase(context).categoryDao().allNames()
        assertTrue("Ferrets" !in beforeNames)

        LedgerController.addCategory(context, "Ferrets")

        val afterNames = CarDatabase.getDatabase(context).categoryDao().allNames()
        assertTrue("Ferrets" in afterNames)
        assertEquals(beforeNames.size + 1, afterNames.size)
    }
}
