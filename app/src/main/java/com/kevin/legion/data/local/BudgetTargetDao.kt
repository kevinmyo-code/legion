package com.kevin.legion.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

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

    /**
     * Every category's currently-effective target for [currency] as of [monthStartMs] - one row
     * per category, the latest whose [BudgetTarget.effectiveFromMonthEpoch] is on or before the
     * month being asked about. This IS [BudgetTarget]'s "copy forward" - see its doc comment.
     */
    @Query(
        "SELECT * FROM budget_targets bt WHERE currency = :currency AND effectiveFromMonthEpoch = (" +
            "SELECT MAX(effectiveFromMonthEpoch) FROM budget_targets bt2 " +
            "WHERE bt2.category = bt.category AND bt2.currency = :currency " +
            "AND bt2.effectiveFromMonthEpoch <= :monthStartMs" +
            ") AND effectiveFromMonthEpoch <= :monthStartMs"
    )
    suspend fun currentTargets(currency: LedgerCurrency, monthStartMs: Long): List<BudgetTarget>
}
