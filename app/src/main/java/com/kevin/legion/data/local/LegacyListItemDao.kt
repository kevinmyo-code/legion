package com.kevin.legion.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

/**
 * Data Access Object for [LegacyListItem], the frozen `list_items` table.
 *
 * **Two methods, because one caller is all that is left** (lists redesign slice 1a, 2026-11-24).
 * This interface carried twenty-four: an insert, an `@Update`, every targeted column UPDATE the
 * notes screens and voice tools once made, and the agenda, alarm, place-trigger and missed-item
 * reads. `notes/NotesController.kt` repointed all of them onto `events` in backend-erp ticket 15
 * step 4, and the legacy Supabase sync that still pushed and pulled the table retired in this same
 * slice. The two left are what
 * [com.kevin.legion.engine.migration.EngineDataMigrationWave1] reads on a stale install: every
 * surviving row for its one-time notes copy, and one row by id for its `ListItemSkip` rekey.
 *
 * **[insert] has no caller in `app/src/main` on purpose, and it is not dead.** Nothing in the app
 * writes this table any more; what seeds a legacy row is
 * `EngineDataMigrationWave1Test`/`EngineDataMigrationWave1CutoverTest`, whose whole subject is what
 * that migrator does to rows a real install already holds, and there is no other door into the
 * table to write them through. The twenty-two targeted column UPDATEs this interface used to carry
 * are gone; adding one back would be the first step toward the split brain [ListItem]'s own doc
 * comment describes.
 */
@Dao
interface LegacyListItemDao {
    /** See this interface's own doc comment: a test-fixture seam, not a live write path. */
    @Insert
    suspend fun insert(item: LegacyListItem): Long

    /** One surviving row by id - [com.kevin.legion.engine.migration.EngineDataMigrationWave1]'s
     * skip rekey resolves a [ListItemSkip.itemId] through here to reach that row's `syncId`. */
    @Query("SELECT * FROM list_items WHERE id = :id AND deleted = 0")
    suspend fun getById(id: Long): LegacyListItem?

    /**
     * Every non-tombstoned row, across every legacy [ItemList] -
     * [com.kevin.legion.engine.migration.EngineDataMigrationWave1]'s one-time notes copy reads this
     * once per install and then never again (its own SharedPreferences completion flag short-
     * circuits the call entirely).
     *
     * Includes DONE rows: the copy carries the `done`/`doneAt` pair onto the engine record rather
     * than dropping a completed item's history. Deliberately unordered beyond a stable tiebreak;
     * nothing downstream depends on the order.
     */
    @Query("SELECT * FROM list_items WHERE deleted = 0 ORDER BY sortOrder ASC, createdAt ASC")
    suspend fun allActive(): List<LegacyListItem>
}
