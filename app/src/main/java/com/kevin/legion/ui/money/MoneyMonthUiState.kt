package com.kevin.legion.ui.money

import com.kevin.legion.data.local.LedgerCurrency
import com.kevin.legion.data.local.LedgerTransaction
import com.kevin.legion.ledger.AccountMonthResult
import com.kevin.legion.ledger.AccountMonthSpend
import com.kevin.legion.ledger.calendarDateOf
import com.kevin.legion.ledger.formatMoney
import com.kevin.legion.ledger.displayDescription
import com.kevin.legion.data.local.IngestMethod
import java.time.LocalDate
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
    val month: YearMonth = YearMonth.now(),
    /** Newest bank row over all accounts, any month; null when there are none. See [emptyMonthMessage]. */
    val newestOverall: LocalDate? = null,
    val newestByAccount: Map<String, LocalDate> = emptyMap(),
    val today: LocalDate = LocalDate.now(),
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

internal fun drilldownRowsFrom(
    rows: List<LedgerTransaction>,
    aliases: List<com.kevin.legion.data.local.MerchantAlias> = emptyList(),
): List<MoneyDrilldownRow> =
    rows.map {
        MoneyDrilldownRow(
            date = calendarDateOf(it).toString(),
            description = displayDescription(it.description, aliases),
            cents = -it.amountCents,
            unverified = it.ingestMethod == IngestMethod.UNRECONCILED,
        )
    }

/** Convenience for the screen: the spend sections only. */
internal val MoneyMonthUiState.spendSections: List<AccountMonthSpend>
    get() = accounts.filterIsInstance<AccountMonthResult.Spend>().map { it.spend }

/** "Sep 26", with the year only when it is not [today]'s year ("Sep 26, 2025"). */
fun freshnessDate(date: LocalDate, today: LocalDate): String {
    val md = "${date.month.getDisplayName(TextStyle.SHORT, Locale.ENGLISH)} ${date.dayOfMonth}"
    return if (date.year == today.year) md else "$md, ${date.year}"
}

/** The line under the month: how far the bank data actually reaches. */
fun dataThroughLine(newest: LocalDate, today: LocalDate): String = "Bank data through ${freshnessDate(newest, today)}"

/** The per-account line, shown only when accounts disagree. */
fun accountDataThroughLine(newest: LocalDate, today: LocalDate): String = "Data through ${freshnessDate(newest, today)}"

/** Per-account lines are worth showing only when the accounts are not all equally fresh. */
fun accountsDifferInFreshness(newestByAccount: Map<String, LocalDate>): Boolean =
    newestByAccount.values.toSet().size > 1

/**
 * What the EMPTY month says (the read succeeded and no account has a row this month). Three
 * different truths, three sentences: no data at all; data that stops before this month began (stale,
 * NOT "you spent nothing"); and data that reaches into the month ("Nothing spent yet", e.g. only
 * credits).
 */
fun emptyMonthMessage(month: YearMonth, newestOverall: LocalDate?, today: LocalDate): String = when {
    newestOverall == null -> "No bank data yet."
    newestOverall.isBefore(month.atDay(1)) ->
        "No ${month.month.getDisplayName(TextStyle.FULL, Locale.ENGLISH)} transactions yet. " +
            "The newest bank data is from ${freshnessDate(newestOverall, today)}."
    else -> "Nothing spent yet this month."
}

/** The bank's name for the sentence: "BofA" only when the account is named so, else "the bank". */
private fun bankWord(accountName: String): String =
    if (accountName.startsWith("BofA", ignoreCase = true)) "BofA" else "the bank"

/**
 * The quiet line under an account's total (Kevin, 2026-10-02, ruling "a": no amber). Rows of the
 * current period are UNRECONCILED until the bank closes the period; this says so in words in the
 * normal muted colour - still CLAUDE.md section 4 rule 7's "said in words", only quieter.
 * All spend unverified, some of it, or none (no line).
 */
fun currentPeriodLine(spend: AccountMonthSpend): String? {
    val unverified = spend.unverifiedTotalCents
    val bank = bankWord(spend.name)
    return when {
        unverified <= 0L -> null
        unverified >= spend.totalCents -> "Current period, settles when $bank closes it."
        else ->
            "${formatMoney(unverified, spend.currency)} is from the current period and settles when $bank closes it."
    }
}
