package com.kevin.legion.ui.money

import com.kevin.legion.finance.CashFlowWire
import com.kevin.legion.finance.FinanceAccountWire
import com.kevin.legion.finance.FinanceText
import com.kevin.legion.finance.NetWorthLineWire
import com.kevin.legion.finance.NetWorthWire
import com.kevin.legion.finance.SafeToSpendWire
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.TextStyle
import java.util.Locale

/**
 * The Money view's words, pure so each is pinned by a test. Every estimate, every partial figure and
 * every pending amount is said in words (CLAUDE.md section 4 rules 5 and 7), never by colour alone.
 */

/** One "label  value" line, with an optional tag said after the value ("estimate"). */
data class KvRow(val label: String, val value: String, val tag: String? = null, val good: Boolean? = null)

data class ThisMonthModel(val rows: List<KvRow>, val footnotes: List<String>, val sgdAvailable: Boolean)

fun monthName(month: YearMonth): String = month.month.getDisplayName(TextStyle.FULL, Locale.ENGLISH)

/**
 * The This month card. USD as the engine sends it; SGD converted on the phone at the engine's own
 * stated rate (the rate and its day are said), never at a guessed one: with no rate, SGD is off.
 */
fun thisMonthModel(cash: CashFlowWire, currency: String, labels: Map<String, String> = emptyMap()): ThisMonthModel {
    val rate = cash.rate
    val sgd = currency == "SGD" && rate != null
    fun m(cents: Long) = if (sgd) FinanceText.money(FinanceText.usdToSgd(cents, rate!!.rateMicros), "SGD")
    else FinanceText.money(cents, "USD")
    val rows = listOf(
        KvRow(
            "Money in, planned",
            m(cash.incomePlannedCents),
            tag = "estimate".takeIf { cash.incomePlannedIsEstimate },
        ),
        KvRow("Money in, on file", m(cash.incomeOnFileCents)),
        KvRow("Spent", m(cash.spendCents)),
        KvRow("Mortgage", m(cash.mortgageCents)),
        KvRow(
            if (cash.netCents >= 0) "Net so far" else "Net so far, short",
            (if (cash.netCents > 0) "+" else "") + m(cash.netCents),
            tag = "estimate".takeIf { cash.incomePlannedIsEstimate },
            good = cash.netCents >= 0,
        ),
    )
    val notes = buildList {
        add("Card payments and transfers between you two are not counted.")
        if (rate != null) {
            val day = FinanceText.parseDay(rate.rateDate)?.let { ", ${FinanceText.day(it)}" }.orEmpty()
            add("Rate ${FinanceText.rate(rate.rateMicros)}$day.")
        } else {
            add("No USD/SGD rate is on file, so there is no SGD view.")
        }
        if (cash.accountsWithoutRows.isNotEmpty()) {
            val names = cash.accountsWithoutRows.joinToString(", ") { labels[it] ?: it }
            add("No rows yet this month from $names: their spend is not in it.")
        }
    }
    return ThisMonthModel(rows, notes, sgdAvailable = rate != null)
}

/** The safe-to-spend card on this month's page: the figure's parts, each in words. */
fun safeParts(safe: SafeToSpendWire, today: LocalDate): List<KvRow> {
    val parts = safe.parts ?: return emptyList()
    val ccy = safe.currency ?: "USD"
    val checkingAsOf = FinanceText.parseDay(parts.checkingAsOf)?.let { "as of ${FinanceText.asOf(it, today)}" }
    return buildList {
        add(KvRow("Checking", FinanceText.money(parts.checkingCents, ccy), checkingAsOf))
        parts.checkingPendingCents?.takeIf { it != 0L }?.let {
            add(KvRow("Checking pending, not in it", FinanceText.money(it, ccy), "pending"))
        }
        add(KvRow("Owed on the card", FinanceText.money(-parts.cardOwedCents, ccy), "includes pending"))
        add(
            KvRow(
                "Bills before then",
                FinanceText.money(-parts.billsCents, ccy),
                "estimate, from repeat charges".takeIf { parts.billsDetected },
            ),
        )
    }
}

data class LayerRow(val name: String, val locked: Boolean, val value: String, val tags: List<String>)

data class NetWorthModel(
    val partialLine: String?,
    val layers: List<LayerRow>,
    val total: KvRow,
    val notes: List<String>,
)

private val LAYER_NAMES = mapOf(
    "liquid" to "Liquid", "investments" to "Investments", "cpf" to "CPF", "property" to "Property", "other" to "Other",
)
private val LOCKED = setOf("cpf", "property")

/** Net worth by layer. "partial" when the engine lists accounts that are in no total. */
fun netWorthModel(nw: NetWorthWire, labels: Map<String, String> = emptyMap()): NetWorthModel {
    val left = nw.missing.size + nw.unconverted.size
    val partial = if (left > 0) "partial: $left account${if (left == 1) "" else "s"} not in it" else null
    val layers = nw.layers.map { layer ->
        val empty = layer.accounts.isEmpty()
        LayerRow(
            name = LAYER_NAMES[layer.layer] ?: layer.layer.replaceFirstChar { it.uppercase() },
            locked = layer.layer in LOCKED,
            value = if (empty) "nothing on file" else FinanceText.money(layer.totalCents, nw.currency),
            tags = listOfNotNull(
                "estimate".takeIf { layer.isEstimate },
                "as of an old day".takeIf { layer.accounts.any { it.stale } },
            ).takeIf { !empty }.orEmpty(),
        )
    }
    val notes = buildList {
        if (nw.missing.isNotEmpty()) add("No balance yet: ${nw.missing.joinToString(", ") { labels[it] ?: it }}.")
        if (nw.unconverted.isNotEmpty()) {
            add("Left out for want of a USD/SGD rate: ${nw.unconverted.joinToString(", ") { labels[it] ?: it }}.")
        }
    }
    return NetWorthModel(
        partialLine = partial,
        layers = layers,
        total = KvRow("Total", FinanceText.money(nw.totalCents, nw.currency), "estimate".takeIf { nw.totalIsEstimate }),
        notes = notes,
    )
}

/** Account key to the household's own label, for sentences that name accounts. */
fun accountLabels(accounts: List<FinanceAccountWire>?): Map<String, String> =
    accounts.orEmpty().associate { it.key to it.label.ifBlank { it.key } }

/** One read-only account line: name (one line, ellipsised), how it is known, and its balance. */
data class AccountRow(val name: String, val sub: String, val balance: String)

private fun linesByKey(nw: NetWorthWire?): Map<String, NetWorthLineWire> =
    nw?.layers?.flatMap { it.accounts }?.associateBy { it.account }.orEmpty()

private fun sourceWord(source: String): String = when (source) {
    "plaid" -> "live"
    "statement" -> "statement"
    "ibkr_flex" -> "brokerage report"
    "computed" -> "worked out, estimate"
    "manual" -> "you set"
    else -> source
}

/** The accounts list: every active account, its balance from the net-worth lines when there is one. */
fun accountRows(accounts: List<FinanceAccountWire>, nw: NetWorthWire?, today: LocalDate): List<AccountRow> {
    val lines = linesByKey(nw)
    return accounts.filter { it.active }.map { account ->
        val line = lines[account.key]
        val name = listOfNotNull(account.label.ifBlank { account.key }, account.last4).joinToString(" ")
        val asOf = FinanceText.parseDay(line?.asOf)?.let { FinanceText.asOf(it, today) }
        val sub = when {
            line == null -> "${sourceWord(account.source)}, none yet"
            line.isEstimate && account.source != "computed" -> "${sourceWord(account.source)}, estimate, as of $asOf"
            else -> "${sourceWord(account.source)}, as of $asOf"
        } + if ((line?.pendingCents ?: 0L) != 0L) ", ${FinanceText.money(
            line!!.pendingCents!!,
            line.currency,
        )} pending" else ""
        AccountRow(name, sub, line?.let { FinanceText.exact(it.amountCents, it.currency) } ?: "-")
    }
}

data class StatementRow(val name: String, val status: String, val onFile: Boolean)

/**
 * Statements present or missing, per account, for [month]'s page. The month a statement is owed for
 * is the page's month once it has ended, else the month before. **Read from each account's newest
 * balance day**: a statement balance dated in or after the owed month counts as on file. The engine
 * has no per-statement check yet (household-finance ticket 09), and this says so.
 */
fun statementRows(
    accounts: List<FinanceAccountWire>,
    nw: NetWorthWire?,
    month: YearMonth,
    today: LocalDate,
): List<StatementRow> {
    val lines = linesByKey(nw)
    val owed = if (month.atEndOfMonth().isBefore(today)) month else month.minusMonths(1)
    return accounts.filter { it.active && (it.source == "statement" || it.source == "plaid") }.map { account ->
        val name = account.label.ifBlank { account.key }
        if (account.source == "plaid") return@map StatementRow(name, "live, no statement needed", onFile = true)
        val asOf = FinanceText.parseDay(lines[account.key]?.asOf)
        when {
            asOf == null -> StatementRow(name, "none yet", onFile = false)
            !asOf.isBefore(owed.atDay(1)) -> StatementRow(name, "${monthName(owed)} on file", onFile = true)
            else -> StatementRow(name, "${monthName(owed)} missing", onFile = false)
        }
    }
}
