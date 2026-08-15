package com.kevin.legion.data.local

import com.kevin.legion.testutil.RoomTestReset
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * The clone-and-run regression guard (Kevin 2026-08-07, CLAUDE.md §2). Before
 * [CarDatabase.getDatabase]'s `RoomDatabase.Callback` existed, a genuinely FRESH install got ZERO
 * categories: [MIGRATION_5_6]'s `starterCategories` insert only ever ran for an install that
 * upgraded THROUGH v5->v6, and Room does not replay migrations against a database it is creating
 * for the first time - it builds the schema straight from the `@Entity` set instead. Kevin only
 * ever had all 15 (now 16) categories because his own install upgraded through every version;
 * a stranger cloning the repo and sideloading it (CLAUDE.md §2's actual requirement) got a
 * `categories` table that existed but was completely empty, so `set_category` had a fixed list of
 * nothing to validate against and categorisation had nothing to assign.
 *
 * Robolectric, not a plain JUnit test, because this has to go through the REAL
 * `Room.databaseBuilder(...).build()` path in [CarDatabase.getDatabase] - a fake/hand-rolled
 * database would not exercise the callback wiring this test exists to pin. [RoomTestReset] is
 * required (see its own doc comment) because [CarDatabase]'s `INSTANCE` is a process-static
 * singleton that otherwise survives across this class's two `@Test` methods within one Robolectric
 * run, each of which gets its OWN native SQLite shadow layer.
 */
@RunWith(RobolectricTestRunner::class)
class CarDatabaseFreshInstallTest {
    private val context = RuntimeEnvironment.getApplication()

    @Before
    fun clearState() {
        RoomTestReset.resetCarDatabaseSingleton()
    }

    @Test
    fun `a fresh database has the full starter category list, including Pets`() = runBlocking {
        // No prior file, no prior migration chain - Robolectric's shadow SQLite starts genuinely
        // empty each test method (see RoomTestReset's doc comment), so this is exactly the
        // "stranger clones, sideloads, opens the app for the first time" case.
        val names = CarDatabase.getDatabase(context).categoryDao().allNames()

        assertEquals(CategorySeed.starter.size, names.size)
        for ((name, _) in CategorySeed.starter) {
            assertTrue("expected '$name' among a fresh install's categories, got $names", name in names)
        }
        assertTrue("expected 'Pets' specifically among a fresh install's categories", "Pets" in names)
    }

    @Test
    fun `a fresh database's food categories are flagged exactly as CategorySeed says`() = runBlocking {
        val all = CarDatabase.getDatabase(context).categoryDao().getAll()
        val byName = all.associateBy { it.name }

        for ((name, isFood) in CategorySeed.starter) {
            val row = byName[name]
            assertTrue("expected a row for '$name'", row != null)
            assertEquals("isFoodCategory mismatch for '$name'", isFood, row!!.isFoodCategory)
        }
    }
}
