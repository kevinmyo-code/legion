package com.kevin.legion.ledger

import com.kevin.legion.data.local.IngestMethod
import com.kevin.legion.data.local.LedgerCurrency
import com.kevin.legion.data.local.LedgerTransaction
import java.time.YearMonth

/**
 * The Money page's per-account, this-month aggregation (Kevin, 2026-10-02: "how much ive used so
 * far and on what ... this calendar month for both accs, separated by acc"). **Pure** - rows in,
 * sections out - so it is unit-tested without Room.
 *
 * **It computes nothing of its own about what spending is.** Every figure is read from the SAME
 * functions the older Money screen, the HOME tile and the voice path read ([buildBudgetVsActual],
 * [operatingExpenses], [budgetMonthRows]), narrowed to one account by `accountFilter`:
 * - outflows only (`amountCents < 0`); a refund or credit is EXCLUDED, not netted - that is what
 *   [operatingExpenses] has always done and this page matches it;
 * - a card payment and its matching leg (own-account movement) is excluded by [analyzeTransfers],
 *   which runs over EVERY account first and is narrowed afterwards, so paying the card from
 *   checking is neither checking spend nor a second count of the card's;
 * - rows in a category flagged excludedFromSpend (Transfers) are excluded and disclosed;
 * - the month is the BUDGET month ([budgetMonthOf]): rent paid in the last 3 days of a month counts
 *   in the next one, disclosed by [earlyChargeSentences];
 * - the total is [BudgetVsActual.spentCents], which by Kevin's 2026-08-15 ruling does NOT include
 *   the uncategorised bucket. That bucket is its own row and [uncategorizedExcludedSentence] states
 *   it beside the total.
 *
 * **Accounts are not typed.** Nothing in the data says checking or credit card (Kevin, 2026-10-02:
 * no heuristic), so every account with activity this month gets its own section titled with its
 * own name, ordered by name. Ids [sameCard] treats as one card (a bare last-4 and a full PAN)
 * merge into one section, within one currency.
 */
data class CategorySpend(
    /** `null` is the uncategorised bucket, never a category named "Uncategorized". */
    val category: String?,
    val cents: Long,
    /** The part of [cents] that came from UNRECONCILED rows (CLAUDE.md section 4 rule 7). */
    val unverifiedCents: Long,
    /** True while any contributing row's category is an unconfirmed guess. */
    val hasPendingGuess: Boolean,
)

data class AccountMonthSpend(
    /** What the section is titled: the account's own label, digit runs masked. */
    val name: String,
    /** Every stored accountId this section merges, the `accountFilter` for the drilldown. */
    val accountIds: Set<String>,
    val entity: LedgerEntity,
    val month: YearMonth,
    /** [BudgetVsActual.spentCents]: categorised outflows only. */
    val totalCents: Long,
    /** Unverified cents INSIDE [totalCents] (categorised UNRECONCILED rows). */
    val unverifiedTotalCents: Long,
    /** Categorised rows, largest first. */
    val categories: List<CategorySpend>,
    /** The uncategorised bucket, shown as its own row and NOT in [totalCents]. Null when no rows. */
    val uncategorized: CategorySpend?,
    /** Disclosures stated beside the figure, in words. */
    val disclosures: List<String>,
) {
    val currency: LedgerCurrency get() = entity.currency

    /** True when nothing left this account this month at all, categorised or not. */
    val hasNoSpend: Boolean get() = categories.isEmpty() && uncategorized == null
}

/** One account's section: either its figures or an honest "could not read". Never a zero. */
sealed interface AccountMonthResult {
    val name: String

    data class Spend(val spend: AccountMonthSpend) : AccountMonthResult {
        override val name: String get() = spend.name
    }

    data class Unreadable(override val name: String) : AccountMonthResult
}

/** One cluster of stored ids that are the same account, with its currency. */
private data class AccountCluster(val currency: LedgerCurrency, val ids: Set<String>) {
    /** The longest id reads best (a full number over a bare last-4), matching [groupAccountBalances]. */
    val name: String get() = maskedAccountLabel(ids.maxWith(compareBy({ it.length }, { it })))
}

private fun clusterAccounts(rows: List<LedgerTransaction>): List<AccountCluster> {
    val clusters = mutableListOf<Pair<LedgerCurrency, MutableSet<String>>>()
    for ((currency, id) in rows.map { it.currency to it.accountId }.distinct().sortedBy { it.second }) {
        // Same currency guard as groupAccountBalances: a suffix match across currencies is a
        // coincidence of naming ("BOFA-CHECKING" and "DBS-CHECKING"), not one account.
        val existing = clusters.firstOrNull { (c, ids) -> c == currency && ids.any { sameCard(it, id) } }
        if (existing != null) existing.second.add(id) else clusters.add(currency to mutableSetOf(id))
    }
    return clusters.map { (currency, ids) -> AccountCluster(currency, ids) }
}

/**
 * Sections for every account with a row in [month]'s budget month, ordered by name
 * (case-insensitive). An account whose figures throw is returned as
 * [AccountMonthResult.Unreadable] rather than dropped or zeroed.
 *
 * [rows] is every ledger row ([LedgerController]'s one read), [notSpending] the names of the
 * categories flagged excludedFromSpend.
 */
fun buildAccountMonthResults(
    rows: List<LedgerTransaction>,
    month: YearMonth,
    notSpending: Set<String>,
): List<AccountMonthResult> =
    clusterAccounts(rows).mapNotNull { cluster ->
        val entity = LedgerEntity.of(cluster.currency)
        // The same fetch the controller's budgetVsActual makes, so the period is the budget month.
        val (pairingWindow, inPeriod) =
            budgetMonthRows(rows, entity.currency, month, LedgerController.PAIRING_WINDOW_DAYS)
        val active = inPeriod.any { row -> cluster.ids.any { sameCard(it, row.accountId) } }
        if (!active) return@mapNotNull null
        // One account fails alone, named Unreadable; the others still render.
        @Suppress("TooGenericExceptionCaught", "SwallowedException") // reason in the line above
        try {
            AccountMonthResult.Spend(accountSpend(cluster, entity, month, inPeriod, pairingWindow, rows, notSpending))
        } catch (e: RuntimeException) {
            AccountMonthResult.Unreadable(cluster.name)
        }
    }.sortedBy { it.name.lowercase() }

private fun accountSpend(
    cluster: AccountCluster,
    entity: LedgerEntity,
    month: YearMonth,
    inPeriod: List<LedgerTransaction>,
    pairingWindow: List<LedgerTransaction>,
    allRows: List<LedgerTransaction>,
    notSpending: Set<String>,
): AccountMonthSpend {
    val ownAccountIds = allRows.filter { it.currency == entity.currency }.map { it.accountId }.toSet()
    val budget = buildBudgetVsActual(
        entity, month, inPeriod, pairingWindow, targets = emptyMap(), coverage = emptyList(),
        ownAccountIds = ownAccountIds, accountFilter = cluster.ids, notSpending = notSpending,
    )
    // Same call buildBudgetVsActual makes for its own lines - used only to say how much of each
    // figure is UNRECONCILED, which BudgetLine does not carry as an amount.
    val expenses = operatingExpenses(
        entity, inPeriod, pairingWindow, ownAccountIds = ownAccountIds, accountFilter = cluster.ids,
        notSpending = notSpending,
    )
    fun spendFor(category: String?, rows: List<LedgerTransaction>) = CategorySpend(
        category = category,
        cents = -rows.sumOf { it.amountCents },
        unverifiedCents = -rows.filter { it.ingestMethod == IngestMethod.UNRECONCILED }.sumOf { it.amountCents },
        hasPendingGuess = rows.any { it.categoryPending },
    )
    val byCategory = expenses.filter { it.category != null }.groupBy { it.category!! }
    val categories = budget.lines
        .filter { it.gap.actual != 0L }
        .map { line ->
            // The line's own actual is the figure; the rows only apportion its unverified part.
            spendFor(line.category, byCategory[line.category].orEmpty()).copy(cents = line.gap.actual)
        }
        .sortedWith(compareByDescending<CategorySpend> { it.cents }.thenBy { it.category })
    val uncategorizedRows = expenses.filter { it.category == null }
    val uncategorized = if (uncategorizedRows.isEmpty()) null else
        spendFor(null, uncategorizedRows).copy(cents = budget.uncategorized.spentCents)

    val disclosures = buildList {
        if (budget.uncategorized.spentCents != 0L) {
            add(uncategorizedExcludedSentence(budget.uncategorized, entity.currency))
        }
        if (!budget.notSpendingExcluded.isEmpty) {
            add(notSpendingExcludedSentence(budget.notSpendingExcluded, entity.currency))
        }
        if (!budget.excludedOwnAccountMovements.isEmpty) {
            add(excludedOwnAccountMovementsSentence(budget.excludedOwnAccountMovements, entity.currency))
        }
        addAll(earlyChargeSentences(budget.earlyChargesMoved, month, entity.currency))
    }
    return AccountMonthSpend(
        name = cluster.name,
        accountIds = cluster.ids,
        entity = entity,
        month = month,
        totalCents = budget.spentCents,
        unverifiedTotalCents = categories.sumOf { it.unverifiedCents },
        categories = categories,
        uncategorized = uncategorized,
        disclosures = disclosures,
    )
}
