package com.kevin.legion.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update

/** Data Access Object for [CategoryRule]. */
@Dao
interface CategoryRuleDao {
    @Insert
    suspend fun insert(rule: CategoryRule): Long

    /** Every ACTIVE stored rule, oldest first (`deleted = 0` added ledger-config-supabase ticket) -
     * [com.kevin.legion.ledger.LedgerController.applyCategoryRules] applies them in this order so
     * the earliest rule written for a merchant is the one that governs it, per [CategoryRule]'s doc
     * comment. A rule tombstoned by a remote delete must not still fire against future imports. */
    @Query("SELECT * FROM category_rules WHERE deleted = 0 ORDER BY createdAt ASC")
    suspend fun getAll(): List<CategoryRule>

    /** Every row, active AND soft-deleted - [com.kevin.legion.backend.LedgerConfigSync.pull]'s own
     * local match scan, same "getAll(), not getAllActive()" reasoning [MemoryDao.getAll]'s own doc
     * comment gives. */
    @Query("SELECT * FROM category_rules")
    suspend fun getAllIncludingDeleted(): List<CategoryRule>

    /** Every ACTIVE rule for [substring] - [com.kevin.legion.backend.LedgerConfigWriteThrough]'s
     * own lookup before soft-deleting, so each matched row can be tombstoned and pushed
     * individually by its own [CategoryRule.guid] rather than the bare local `DELETE`
     * [deleteBySubstring] performs. In practice at most one row, per [CategoryRule]'s own doc
     * comment on why a merchant never accumulates two rules, but this returns every match rather
     * than assuming that invariant holds. */
    @Query("SELECT * FROM category_rules WHERE substring = :substring AND deleted = 0")
    suspend fun getActiveBySubstring(substring: String): List<CategoryRule>

    /** Whole-row update - [com.kevin.legion.backend.LedgerConfigSync.pull]'s merge write, and
     * [com.kevin.legion.backend.LedgerConfigWriteThrough]'s local soft-delete. */
    @Update
    suspend fun update(rule: CategoryRule)

    /**
     * Removes every rule for [substring] (audit fix, 2026-08-07). **A rule was
     * unremovable before this existed**, and that was the sharp end of a
     * genuinely dangerous bug: `set_category` writes a rule from a free-text
     * merchant name spoken at a half-duplex voice pipe, matches it as a bare
     * `LIKE '%...%'` substring, and replays it against every future import
     * forever. One garbled short utterance could permanently mis-file a growing
     * share of the ledger with no path back through the app.
     *
     * Two callers, both load-bearing:
     * - [com.kevin.legion.ledger.LedgerController.setCategory] calls it BEFORE
     *   inserting, so correcting the same merchant twice replaces the rule
     *   instead of stacking a second one. `@Insert` carries no conflict
     *   strategy and there is no unique index on `substring`, so without this
     *   every correction accumulated another row that
     *   [getAll] would go on applying in `createdAt` order forever.
     * - [com.kevin.legion.ledger.LedgerController.clearCategoryRules] is the
     *   undo path itself.
     *
     * **ledger-config-supabase ticket: this is now the UNCONFIGURED-INSTALL fallback only.**
     * Both callers above route through [com.kevin.legion.backend.LedgerConfigWriteThrough]
     * instead of this DAO directly - on a configured install it soft-deletes and pushes a
     * tombstone per matched row (via [getActiveBySubstring] + [update]) so the deletion survives
     * a merge pull rather than being silently resurrected; this raw hard `DELETE` is what runs
     * when there is no backend to tell, same "unconfigured install falls back to the original
     * bare deleteAll()" shape [MemoryWriteThrough.deleteAllMemoryEntries]'s own doc comment
     * describes.
     */
    @Query("DELETE FROM category_rules WHERE substring = :substring")
    suspend fun deleteBySubstring(substring: String): Int
}
