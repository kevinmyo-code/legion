package com.kevin.legion.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

/** Data Access Object for [CategoryRule]. */
@Dao
interface CategoryRuleDao {
    @Insert
    suspend fun insert(rule: CategoryRule): Long

    /** Every stored rule, oldest first - [com.kevin.legion.ledger.LedgerController.applyCategoryRules] applies them in this order so the earliest rule written for a merchant is the one that governs it, per [CategoryRule]'s doc comment. */
    @Query("SELECT * FROM category_rules ORDER BY createdAt ASC")
    suspend fun getAll(): List<CategoryRule>

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
     */
    @Query("DELETE FROM category_rules WHERE substring = :substring")
    suspend fun deleteBySubstring(substring: String): Int
}
