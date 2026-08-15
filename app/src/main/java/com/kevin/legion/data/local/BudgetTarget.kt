package com.kevin.legion.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One monthly budget target, ticket 06 D9/D2: "set by voice AND screen"; "periods copy forward,
 * nothing is deleted... last month's grocery budget becomes this month's until changed."
 *
 * **This table does NOT hold one row per category per month.** Doing that would make "copy
 * forward" a literal duplication step run every month - a maintenance job that could be
 * forgotten, and a month with no explicit row would then mean something ambiguous (no budget at
 * all, or "same as last month"?). Instead a row is written only when the target CHANGES, dated
 * [effectiveFromMonthEpoch], and [com.kevin.legion.data.local.BudgetTargetDao.currentTargets]
 * reads "the most recent row on or before this month" - which IS "copy forward" with nothing
 * ever deleted, computed at read time instead of written at every month boundary. D2's "leftovers
 * do not roll over" is a separate rule about SPEND, not about this table - it means an unspent
 * 40 must never raise [amountCents] on its own; nothing here does that automatically, because
 * nothing here even looks at spend.
 *
 * [currency] (reasoned addition beyond the ticket's literal text): the ledger already splits
 * everything else - balances, the old P&L, this budget's own build function - per
 * [com.kevin.legion.ledger.LedgerEntity]/currency, on the explicit rule that SGD and USD are
 * never combined without an exchange rate nobody printed (CLAUDE.md §4 rule 5). Ticket 06 doesn't
 * name multi-entity budgets directly, but a USD "Groceries" target and an SGD "Groceries" target
 * are not the same number by any reading of the rest of the ledger's design, so this column
 * exists to keep that same discipline rather than silently assuming there is only one household.
 */
@Entity(
    tableName = "budget_targets",
    indices = [Index(value = ["category", "currency", "effectiveFromMonthEpoch"], unique = true)],
)
data class BudgetTarget(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val category: String,
    val currency: LedgerCurrency,
    val amountCents: Long,
    /** UTC month-start epoch millis (matches every parser's `atStartOfDay(ZoneOffset.UTC)` convention) - the first month this target applies to. */
    val effectiveFromMonthEpoch: Long,
    val updatedAt: Long,
)
