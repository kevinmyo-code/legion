package com.kevin.legion.ui.money

import com.kevin.legion.data.local.LedgerCurrency
import com.kevin.legion.data.local.LedgerTransaction
import com.kevin.legion.ledger.AccountMonthResult
import com.kevin.legion.ledger.AccountMonthSpend
import com.kevin.legion.ledger.calendarDateOf
import com.kevin.legion.ledger.displayDescription
import com.kevin.legion.data.local.IngestMethod
import java.time.YearMonth
import java.time.format.TextStyle
import java.util.Locale

/**
 * The Money page's one state (CLAUDE.md section 8: a ViewModel exposing one StateFlow).
 *
 * Three honest outcomes and they never share a rendering: [readFailed] (the read threw - "Couldn't
 * read", never an empty month), an [accounts] entry that is [AccountMonthResult.Unreadable] (one
 * account's figures threw), and a successful read with an empty [accounts] ("Nothing spent yet this
 * month", said only when the read succeeded).
 */
data class MoneyMonthUiState(
    val monthTitle: String,
    val loading: Boolean = true,
    val readFailed: Boolean = false,
    val accounts: List<AccountMonthResult> = emptyList(),
    val drilldown: MoneyDrilldownUiState? = null,
)

/** One category's transactions for one account this month, opened by tapping a category row. */
data class MoneyDrilldownUiState(
    val accountName: String,
    /** `null` is the uncategorised bucket. */
    val category: String?,
    val currency: LedgerCurrency,
    val loading: Boolean = true,
    val failed: Boolean = false,
    val rows: List<MoneyDrilldownRow> = emptyList(),
)

data class MoneyDrilldownRow(
    /** ISO date of the row as stated by the bank, never moved (budgetMonthOf moves the month, not the row). */
    val date: String,
    val description: String,
    /** Positive cents out. */
    val cents: Long,
    val unverified: Boolean,
)

/** "October so far" - the month named, and honest that it is not over. */
fun monthTitleFor(month: YearMonth): String =
    "${month.month.getDisplayName(TextStyle.FULL, Locale.ENGLISH)} so far"

/** Drilldown title: the category's name, or the words for the bucket nobody has classified. */
fun drilldownCategoryName(category: String?): String = category ?: UNCATEGORIZED_LABEL

const val UNCATEGORIZED_LABEL = "Uncategorized"

internal fun drilldownRowsFrom(rows: List<LedgerTransaction>): List<MoneyDrilldownRow> =
    rows.map {
        MoneyDrilldownRow(
            date = calendarDateOf(it).toString(),
            description = displayDescription(it.description),
            cents = -it.amountCents,
            unverified = it.ingestMethod == IngestMethod.UNRECONCILED,
        )
    }

/** Convenience for the screen: the spend sections only. */
internal val MoneyMonthUiState.spendSections: List<AccountMonthSpend>
    get() = accounts.filterIsInstance<AccountMonthResult.Spend>().map { it.spend }
