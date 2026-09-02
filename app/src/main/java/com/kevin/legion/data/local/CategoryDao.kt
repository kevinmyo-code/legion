package com.kevin.legion.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update

/**
 * Data Access Object for [Category]. D14's "fixed list" (ticket 07) means fixed FROM THE MODEL'S
 * SIDE - `set_category`/`CategoryAgent` may only ever validate against the stored list, never add
 * to it (see [insert]'s own doc comment). Kevin adding a category through the ledger screen
 * (2026-08-07) is a different actor at a different boundary, and this DAO is what makes that
 * possible without a schema migration per category - see [Category]'s doc comment, "Room-backed
 * rather than a hardcoded enum so the set can be edited later without a schema migration."
 */
@Dao
interface CategoryDao {
    /** Active categories only (`deleted = 0` added ledger-config-supabase ticket) - every app-facing
     * reader ([com.kevin.legion.ledger.LedgerController.allCategories], the category-guess prompt)
     * must never offer or display a category tombstoned by a remote delete. */
    @Query("SELECT * FROM categories WHERE deleted = 0 ORDER BY name ASC")
    suspend fun getAll(): List<Category>

    /** Every row, active AND soft-deleted - [com.kevin.legion.backend.LedgerConfigSync.pull]'s own
     * local match scan, same "getAll(), not getAllActive()" reasoning [MemoryDao.getAll]'s own doc
     * comment gives. */
    @Query("SELECT * FROM categories")
    suspend fun getAllIncludingDeleted(): List<Category>

    /** Every category's name, in the exact spelling [CategoryAgent]'s prompt must offer the model and validate its answer against - D14's "fixed list" enforced at the boundary of an LLM call. `deleted = 0` added ledger-config-supabase ticket, same reasoning as [getAll]. */
    @Query("SELECT name FROM categories WHERE deleted = 0 ORDER BY name ASC")
    suspend fun allNames(): List<String>

    /**
     * True if [name] already exists under any case - the case-insensitive duplicate check the
     * add-category affordance needs (`Pets` vs `pets`) that a plain `UNIQUE INDEX` on `name` alone
     * cannot enforce, because SQLite's default text comparison is case-sensitive/byte-wise. `COLLATE
     * NOCASE` is an ASCII-only fold (Room/SQLite ship no ICU collation on-device), a deliberately
     * accepted limit - good enough for the plain-English category names this table has ever held.
     * `deleted = 0` added ledger-config-supabase ticket - a tombstoned category's name is free to
     * reuse.
     */
    @Query("SELECT EXISTS(SELECT 1 FROM categories WHERE name = :name COLLATE NOCASE AND deleted = 0)")
    suspend fun existsByNameIgnoreCase(name: String): Boolean

    /**
     * Adds one driver-typed category (Kevin 2026-08-07: "let me add a category, without letting the
     * model invent one" - this is the Kevin-only door D14 doesn't touch). `IGNORE`, not `ABORT`,
     * so a race against [existsByNameIgnoreCase]'s own check (there is no transaction wrapping the
     * two together - Room's default dispatcher is single-writer per database, but this is
     * conflict-safe either way) silently no-ops on the UNIQUE `name` index rather than throwing -
     * the caller has already told the driver why via [existsByNameIgnoreCase], so a second, silent
     * insert-side rejection is not a new user-facing failure mode, just a safety net.
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(category: Category)

    /** Whole-row update - [com.kevin.legion.backend.LedgerConfigSync.pull]'s merge write. */
    @Update
    suspend fun update(category: Category)
}
