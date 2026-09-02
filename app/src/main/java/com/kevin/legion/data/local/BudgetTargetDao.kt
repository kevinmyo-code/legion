package com.kevin.legion.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update

/** Data Access Object for [BudgetTarget]. */
@Dao
interface BudgetTargetDao {
    /**
     * Sets [category]'s budget from [target.effectiveFromMonthEpoch] onward. `REPLACE` on the
     * unique `(category, currency, effectiveFromMonthEpoch)` index - setting the SAME month's
     * target twice (e.g. correcting a typo just entered) overwrites rather than duplicating,
     * which the unique index would otherwise reject outright.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(target: BudgetTarget)

    /** Whole-row update - [com.kevin.legion.backend.LedgerConfigSync.pull]'s merge write, same
     * shape as [MealTargetDao.update]: [upsert]'s `REPLACE` is for local writes going through the
     * same `(category, currency, effectiveFromMonthEpoch)` unique conflict; a sync merge writes an
     * already-existing row in place by primary key instead. */
    @Update
    suspend fun update(target: BudgetTarget)

    /**
     * The EXACT row already effective from [category]/[currency]/[effectiveFromMonthEpoch], if one
     * exists - ledger-config-supabase ticket, same reasoning as [MealTargetDao.getByEffectiveDate]'s
     * own doc comment: [com.kevin.legion.backend.LedgerConfigWriteThrough.setBudgetTarget]'s one
     * caller ([com.kevin.legion.ledger.LedgerController.setBudget]) reads this FIRST and reuses
     * that row's own [BudgetTarget.guid] on [upsert]'s `REPLACE` rather than minting a fresh one -
     * `REPLACE` deletes and reinserts on the unique index conflict, so setting the SAME month's
     * target twice with two different guids would upsert two DIFFERENT server rows and orphan the
     * first one forever.
     */
    @Query(
        "SELECT * FROM budget_targets WHERE category = :category AND currency = :currency " +
            "AND effectiveFromMonthEpoch = :effectiveFromMonthEpoch LIMIT 1",
    )
    suspend fun getByKey(category: String, currency: LedgerCurrency, effectiveFromMonthEpoch: Long): BudgetTarget?

    /** Every row, active AND soft-deleted - [com.kevin.legion.backend.LedgerConfigSync.pull]'s own
     * local match scan, same "getAll(), not getAllActive()" reasoning [MemoryDao.getAll]'s own doc
     * comment gives. */
    @Query("SELECT * FROM budget_targets")
    suspend fun getAll(): List<BudgetTarget>

    /**
     * Every category's currently-effective target for [currency] as of [monthStartMs] - one row
     * per category, the latest whose [BudgetTarget.effectiveFromMonthEpoch] is on or before the
     * month being asked about. This IS [BudgetTarget]'s "copy forward" - see its doc comment.
     * `deleted = 0` added ledger-config-supabase ticket - a target tombstoned by a remote delete
     * must not still read as the current one.
     */
    @Query(
        "SELECT * FROM budget_targets bt WHERE currency = :currency AND deleted = 0 AND effectiveFromMonthEpoch = (" +
            "SELECT MAX(effectiveFromMonthEpoch) FROM budget_targets bt2 " +
            "WHERE bt2.category = bt.category AND bt2.currency = :currency AND bt2.deleted = 0 " +
            "AND bt2.effectiveFromMonthEpoch <= :monthStartMs" +
            ") AND effectiveFromMonthEpoch <= :monthStartMs"
    )
    suspend fun currentTargets(currency: LedgerCurrency, monthStartMs: Long): List<BudgetTarget>
}
