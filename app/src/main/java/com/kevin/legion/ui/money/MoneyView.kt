@file:Suppress("FunctionNaming", "MagicNumber", "LongParameterList", "TooManyFunctions", "LongMethod")
// FunctionNaming: @Composable convention is PascalCase. MagicNumber: layout dp from the approved
// prototype. LongParameterList: one callback per tap. TooManyFunctions: one composable per section.
// LongMethod: a section is one card.

package com.kevin.legion.ui.money

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kevin.legion.R
import com.kevin.legion.finance.BudgetsWire
import com.kevin.legion.finance.FinanceRead
import com.kevin.legion.finance.FinanceText
import com.kevin.legion.finance.noteOrNull
import com.kevin.legion.finance.valueOrNull
import com.kevin.legion.ui.home.safeLine
import com.kevin.legion.ui.ledger.MoneyTitleRow
import com.kevin.legion.ui.theme.soft.MsIcon
import com.kevin.legion.ui.theme.soft.SoftColors
import java.time.LocalDate
import java.time.YearMonth
import kotlinx.coroutines.launch

/** Every tap the Money view hands back to its host ([com.kevin.legion.ui.LedgerScreen]). */
data class MoneyViewCallbacks(
    val onBack: () -> Unit = {},
    val onOpenGroceries: () -> Unit = {},
    val onOpenCategorize: () -> Unit = {},
    /** A budget bar or a transaction chip's category; null is the uncategorised bucket. */
    val onOpenCategory: (String?) -> Unit = {},
    /** Budget details: add a category, the trend, own-account movements. */
    val onOpenBudget: () -> Unit = {},
    val onOpenQuarantine: () -> Unit = {},
    val onSetCurrency: (String) -> Unit = {},
    /** The pager settled on a month: the host moves its picked month, which reloads that month. */
    val onPageMonth: (YearMonth) -> Unit = {},
)

/** What the host knows about the page that is not the engine's: the ledger side. */
data class MoneyViewHost(
    val months: List<YearMonth>,
    val pickedMonth: YearMonth?,
    val toCategorizeCount: Int = 0,
    val quarantinedCount: Int = 0,
    /** The ledger mirror's own line when the engine's ledger could not be read. */
    val syncLine: String? = null,
    /** "swipe for other months", until the person has swiped once. */
    val showSwipeHint: Boolean = false,
    val loading: Boolean = false,
)

private val CARD = RoundedCornerShape(18.dp)

/**
 * Which of the Money view's sections show. Kevin, 2026-11-05: "lets manage our daily spending here
 * first" - "lets not care about singapore and money transfer and cash flow". So the household
 * sections (safe to spend, this month's cash flow with its USD/SGD toggle, net worth, accounts,
 * statements) are hidden, not deleted, and their engine reads are skipped while they are. Flip this
 * to bring them back.
 */
object MoneySections {
    const val SHOW_HOUSEHOLD = false
}

/**
 * The Money view (household-finance ticket 14, Kevin's approved prototype screen 3): ONE screen that
 * swipes between months. It replaces More > Money, Money details and the statements screen; every
 * route that reached them lands here. Each month's page is the budget first (Kevin, 2026-11-05): the
 * total budget bar, every category bar (a tap opens its drilldown), the fixed categories, then the
 * month's transactions with category chips. The household sections sit behind [MoneySections].
 */
@Composable
fun MoneyViewContent(
    state: MoneyViewUiState,
    host: MoneyViewHost,
    callbacks: MoneyViewCallbacks,
    /** Under every page: the host's Room-backed money goals panel. Empty in tests. */
    footer: @Composable () -> Unit = {},
) {
    Column(Modifier.fillMaxSize().background(SoftColors.ground)) {
        MoneyTitleRow(host.toCategorizeCount, callbacks.onOpenGroceries, callbacks.onOpenCategorize, callbacks.onBack)
        host.syncLine?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = SoftColors.caution,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
        }
        if (host.quarantinedCount > 0) {
            Text(
                quarantineLine(host.quarantinedCount),
                style = MaterialTheme.typography.bodySmall,
                color = SoftColors.caution,
                modifier = Modifier.fillMaxWidth().clickable(onClick = callbacks.onOpenQuarantine)
                    .padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }
        val months = host.months
        if (months.isEmpty()) {
            Text(
                if (host.loading) "Loading." else "No bank data yet.",
                style = MaterialTheme.typography.bodyLarge,
                color = SoftColors.text2,
                modifier = Modifier.padding(16.dp),
            )
            return@Column
        }
        // Keyed on the month list so a list that grows (the mirror landed) opens on the right page.
        key(months) {
            val start = months.indexOf(host.pickedMonth).takeIf { it >= 0 } ?: months.lastIndex
            val pager = rememberPagerState(initialPage = start) { months.size }
            LaunchedEffect(pager) {
                snapshotFlow { pager.settledPage }.collect { callbacks.onPageMonth(months[it]) }
            }
            MonthHeader(months, pager, host.showSwipeHint)
            HorizontalPager(state = pager, modifier = Modifier.weight(1f).fillMaxWidth()) { index ->
                MonthPage(months[index], state, callbacks, footer)
            }
        }
    }
}

@Composable
private fun MonthHeader(months: List<YearMonth>, pager: PagerState, showSwipeHint: Boolean) {
    val scope = rememberCoroutineScope()
    val page = pager.currentPage
    val month = months[page]
    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        StepButton("Previous month", enabled = page > 0, left = true) {
            scope.launch { pager.animateScrollToPage(page - 1) }
        }
        Text(
            "${monthName(month)} ${month.year}",
            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
            color = SoftColors.text,
            modifier = Modifier.weight(1f),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
        StepButton("Next month", enabled = page < months.lastIndex, left = false) {
            scope.launch { pager.animateScrollToPage(page + 1) }
        }
    }
    if (months.size <= MAX_DOTS) {
        Row(Modifier.fillMaxWidth().padding(bottom = 4.dp), horizontalArrangement = Arrangement.Center) {
            months.indices.forEach { i ->
                Box(
                    Modifier.padding(horizontal = 2.5.dp).size(5.dp)
                        .background(if (i == page) SoftColors.text else SoftColors.outline, CircleShape),
                )
            }
        }
    } else {
        Text(
            "${page + 1} of ${months.size} months",
            style = MaterialTheme.typography.labelSmall,
            color = SoftColors.text3,
            modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
    }
    if (showSwipeHint) {
        Text(
            "Swipe for other months",
            style = MaterialTheme.typography.labelSmall,
            color = SoftColors.text3,
            modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
    }
}

private const val MAX_DOTS = 12

@Composable
private fun StepButton(description: String, enabled: Boolean, left: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .size(44.dp)
            .clip(CircleShape)
            .let { if (enabled) it.clickable(role = Role.Button, onClick = onClick) else it }
            .semantics { contentDescription = if (enabled) description else "$description, none" },
        contentAlignment = Alignment.Center,
    ) {
        MsIcon(
            R.drawable.ms_arrow_back,
            contentDescription = null,
            tint = if (enabled) SoftColors.text2 else SoftColors.outline,
            modifier = if (left) Modifier else Modifier.rotate(180f),
        )
    }
}

@Composable
private fun MonthPage(
    month: YearMonth,
    state: MoneyViewUiState,
    callbacks: MoneyViewCallbacks,
    footer: @Composable () -> Unit,
) {
    val page = state.pages[month]
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 14.dp, vertical = 4.dp),
    ) {
        if (MoneySections.SHOW_HOUSEHOLD) {
            if (month == YearMonth.from(state.today)) SafeSection(state)
            ThisMonthSection(
                page?.cashFlow,
                state.currency,
                accountLabels(state.accounts?.valueOrNull),
                callbacks.onSetCurrency,
            )
        }
        BudgetSection(page?.budgets, state.today, callbacks)
        if (MoneySections.SHOW_HOUSEHOLD) {
            NetWorthSection(state)
            AccountsSection(state)
            StatementsSection(state, month)
        }
        TransactionsSection(page, callbacks.onOpenCategory)
        footer()
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun Card(content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(bottom = 10.dp).background(SoftColors.card, CARD).padding(14.dp),
    ) { content() }
}

@Composable
private fun SectionTitle(title: String, tag: String? = null) {
    Row(Modifier.padding(top = 8.dp, bottom = 8.dp, start = 2.dp), verticalAlignment = Alignment.Bottom) {
        Text(
            title,
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
            color = SoftColors.text,
        )
        tag?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = SoftColors.text3,
                modifier = Modifier.padding(start = 8.dp),
            )
        }
    }
}

@Composable
private fun Note(text: String, caution: Boolean = false) {
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        color = if (caution) SoftColors.caution else SoftColors.text3,
        modifier = Modifier.padding(top = 6.dp),
    )
}

/** A read that gave no value: its sentence, in a card, never a blank section. */
@Composable
private fun ReadNote(read: FinanceRead<*>?, what: String) {
    Card {
        Text(
            if (read == null) "Loading $what." else "Couldn't read $what. ${read.noteOrNull.orEmpty()}".trim(),
            style = MaterialTheme.typography.bodyMedium,
            color = SoftColors.text2,
        )
    }
}

@Composable
private fun Kv(row: KvRow, first: Boolean = false) {
    if (!first) Box(Modifier.fillMaxWidth().height(1.dp).background(SoftColors.barRule))
    Row(Modifier.fillMaxWidth().padding(vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            row.label,
            style = MaterialTheme.typography.bodyMedium,
            color = SoftColors.text2,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Text(
            row.value,
            style = MaterialTheme.typography.bodyMedium,
            color = when (row.good) {
                true -> SoftColors.good
                false -> SoftColors.onAlert
                null -> SoftColors.text
            },
            maxLines = 1,
            softWrap = false,
        )
        row.tag?.let { Tag(it) }
    }
}

@Composable
private fun Tag(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        color = SoftColors.text3,
        maxLines = 1,
        softWrap = false,
        modifier = Modifier.padding(start = 6.dp).border(1.dp, SoftColors.outline, RoundedCornerShape(6.dp))
            .padding(horizontal = 5.dp, vertical = 1.dp),
    )
}

@Composable
private fun SafeSection(state: MoneyViewUiState) {
    val safe = state.safe
    val body = safe?.valueOrNull
    val line = safeLine(safe, loading = safe == null)
    SectionTitle("Safe to spend")
    Card {
        if (line.figure != null) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    line.figure,
                    style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold),
                    color = if (line.negative) SoftColors.onAlert else SoftColors.text,
                )
                line.until?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = SoftColors.text3,
                        modifier = Modifier.padding(start = 10.dp, bottom = 5.dp),
                    )
                }
            }
            body?.let { b -> safeParts(b, state.today).forEachIndexed { i, row -> Kv(row, first = i == 0) } }
            if (body?.untilIsEstimate == true && body.untilReason == "next_income") {
                Note("The pay date is the income plan's estimate.")
            }
        }
        line.note?.let { Note(it, caution = line.figure != null) }
        line.topUp?.let { com.kevin.legion.ui.home.AlertLine(it, Modifier.padding(top = 8.dp)) }
    }
}

@Composable
private fun ThisMonthSection(
    read: FinanceRead<com.kevin.legion.finance.CashFlowWire>?,
    currency: String,
    labels: Map<String, String>,
    onSetCurrency: (String) -> Unit,
) {
    val cash = read?.valueOrNull
    if (cash == null) {
        if (read is FinanceRead.Refused) {
            Card { Text(read.sentence, style = MaterialTheme.typography.bodyMedium, color = SoftColors.text2) }
        } else {
            ReadNote(read, "this month's cash flow")
        }
        return
    }
    val model = thisMonthModel(cash, currency, labels)
    Card {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                "This month",
                style = MaterialTheme.typography.labelMedium,
                color = SoftColors.text3,
                modifier = Modifier.weight(1f),
            )
            if (model.sgdAvailable) CurrencyToggle(currency, onSetCurrency)
        }
        model.rows.forEachIndexed { i, row -> Kv(row, first = i == 0) }
        model.footnotes.forEach { Note(it) }
        (read as? FinanceRead.Saved)?.let { Note(it.note, caution = true) }
    }
}

@Composable
private fun CurrencyToggle(currency: String, onSetCurrency: (String) -> Unit) {
    Row(Modifier.clip(RoundedCornerShape(50)).background(SoftColors.cardHighest).padding(2.dp)) {
        listOf("USD", "SGD").forEach { ccy ->
            val on = ccy == currency
            Text(
                ccy,
                style = MaterialTheme.typography.labelMedium,
                color = if (on) SoftColors.text else SoftColors.text2,
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(if (on) SoftColors.outline else SoftColors.cardHighest)
                    .clickable(role = Role.Tab, onClickLabel = "Show $ccy") { onSetCurrency(ccy) }
                    .heightIn(min = 32.dp)
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            )
        }
    }
}

@Composable
private fun BudgetSection(read: FinanceRead<BudgetsWire>?, today: LocalDate, callbacks: MoneyViewCallbacks) {
    SectionTitle("Budget")
    val budgets = read?.valueOrNull ?: return ReadNote(read, "budgets")
    val board = budgetBoard(read, today)
    Card {
        board.total?.let { TotalBudgetBar(it) }
        board.bars.forEach { bar ->
            BudgetBar(bar, Modifier.padding(top = 6.dp)) { callbacks.onOpenCategory(bar.category) }
        }
        val fixedNames = board.fixed.map { it.category }.toSet()
        budgets.categories
            .filter { (it.capCents == null || it.capCents <= 0) && it.category !in fixedNames }
            .forEach { line ->
                Text(
                    "${line.category}: ${FinanceText.money(line.spentCents)}, no cap" +
                        if (line.pendingCents > 0) ", includes ${FinanceText.money(line.pendingCents)} pending" else "",
                    style = MaterialTheme.typography.bodySmall,
                    color = SoftColors.text2,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { callbacks.onOpenCategory(line.category) }
                        .padding(top = 6.dp),
                )
            }
        if (board.fixed.isNotEmpty()) {
            FixedLabel()
            board.fixed.forEach { line -> FixedRow(line) { callbacks.onOpenCategory(line.category) } }
        }
        if (budgets.uncategorisedCents > 0) {
            Text(
                "${FinanceText.money(budgets.uncategorisedCents)} uncategorised, in no category above.",
                style = MaterialTheme.typography.labelSmall,
                color = SoftColors.caution,
                modifier = Modifier.clickable { callbacks.onOpenCategory(null) }.padding(top = 6.dp),
            )
        }
        Note("Tap a category for its transactions.")
        board.note?.let { Note(it, caution = board.noteIsCaution) }
        Text(
            "Budget details, trend and new categories ›",
            style = MaterialTheme.typography.bodyMedium,
            color = SoftColors.primary,
            modifier = Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = callbacks.onOpenBudget)
                .padding(top = 10.dp, bottom = 2.dp),
        )
    }
}

@Composable
private fun NetWorthSection(state: MoneyViewUiState) {
    val nw = state.netWorth?.valueOrNull
    if (nw == null) {
        SectionTitle("Net worth")
        ReadNote(state.netWorth, "net worth")
        return
    }
    val model = netWorthModel(nw, accountLabels(state.accounts?.valueOrNull))
    SectionTitle("Net worth today", model.partialLine)
    Card {
        model.layers.forEachIndexed { i, layer ->
            if (i > 0) Box(Modifier.fillMaxWidth().height(1.dp).background(SoftColors.barRule))
            Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(layer.name, style = MaterialTheme.typography.bodyMedium, color = SoftColors.text)
                if (layer.locked) Text(
                    "locked",
                    style = MaterialTheme.typography.labelSmall,
                    color = SoftColors.text3,
                    modifier = Modifier.padding(start = 4.dp),
                )
                Spacer(Modifier.weight(1f))
                Text(
                    layer.value,
                    style = MaterialTheme.typography.bodyMedium,
                    color = SoftColors.text,
                    maxLines = 1,
                    softWrap = false,
                )
                layer.tags.forEach { Tag(it) }
            }
        }
        Kv(model.total)
        model.notes.forEach { Note(it) }
        (state.netWorth as? FinanceRead.Saved)?.let { Note(it.note, caution = true) }
    }
}

@Composable
private fun AccountsSection(state: MoneyViewUiState) {
    SectionTitle("Accounts")
    val accounts = state.accounts?.valueOrNull ?: return ReadNote(state.accounts, "the account list")
    val rows = accountRows(accounts, state.netWorth?.valueOrNull, state.today)
    Card {
        if (rows.isEmpty()) Text(
            "No accounts on file.",
            style = MaterialTheme.typography.bodyMedium,
            color = SoftColors.text2,
        )
        rows.forEachIndexed { i, row -> AccountLine(row, first = i == 0) }
    }
}

/** One account: never clickable, every text one line and ellipsised (Kevin: "its getting wrapped"). */
@Composable
internal fun AccountLine(row: AccountRow, first: Boolean) {
    if (!first) Box(Modifier.fillMaxWidth().height(1.dp).background(SoftColors.barRule))
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(
                row.name,
                style = MaterialTheme.typography.bodyMedium,
                color = SoftColors.text,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                row.sub,
                style = MaterialTheme.typography.labelSmall,
                color = SoftColors.text3,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(10.dp))
        Text(
            row.balance,
            style = MaterialTheme.typography.bodyMedium,
            color = SoftColors.text,
            maxLines = 1,
            softWrap = false,
        )
    }
}

@Composable
private fun StatementsSection(state: MoneyViewUiState, month: YearMonth) {
    SectionTitle("Statements")
    val accounts = state.accounts?.valueOrNull ?: return ReadNote(state.accounts, "the statement list")
    val rows = statementRows(accounts, state.netWorth?.valueOrNull, month, state.today)
    Card {
        if (rows.isEmpty()) Text(
            "No account takes a statement.",
            style = MaterialTheme.typography.bodyMedium,
            color = SoftColors.text2,
        )
        rows.forEachIndexed { i, row ->
            if (i > 0) Box(Modifier.fillMaxWidth().height(1.dp).background(SoftColors.barRule))
            Row(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                Text(
                    row.name,
                    style = MaterialTheme.typography.bodyMedium,
                    color = SoftColors.text,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    row.status,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (row.onFile) SoftColors.good else SoftColors.caution,
                    maxLines = 1,
                    softWrap = false,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
        }
        Note("Read from each account's newest balance day; the engine does not check statements one by one yet.")
    }
}

@Composable
@Suppress("LongMethod", "CyclomaticComplexMethod") // chips and rows share the chip state
private fun TransactionsSection(page: MoneyMonthPage?, onOpenCategory: (String?) -> Unit) {
    SectionTitle("Transactions")
    val rows = page?.transactions
    if (rows == null) {
        Card {
            Text(
                if (page?.transactionsFailed == true) "Couldn't read this month's transactions." else "Loading.",
                style = MaterialTheme.typography.bodyMedium,
                color = SoftColors.text2,
            )
        }
        return
    }
    var chip by remember(rows) { mutableStateOf<String?>(ALL) }
    val categories = rows.map { it.category }.distinct().sortedBy { it ?: "~" }
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(bottom = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        (listOf<String?>(ALL) + categories).forEach { c ->
            val on = c == chip
            Text(
                when (c) {
                    ALL -> "All"
                    null -> "Uncategorized"
                    else -> c
                },
                style = MaterialTheme.typography.labelMedium,
                color = if (on) SoftColors.text else SoftColors.text2,
                maxLines = 1,
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(if (on) SoftColors.cardHighest else SoftColors.ground)
                    .border(1.dp, SoftColors.outline, RoundedCornerShape(50))
                    .clickable(role = Role.Tab) { chip = c }
                    .heightIn(min = 32.dp)
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            )
        }
    }
    val shown = if (chip == ALL) rows else rows.filter { it.category == chip }
    Card {
        if (shown.isEmpty()) Text(
            "No spend this month.",
            style = MaterialTheme.typography.bodyMedium,
            color = SoftColors.text2,
        )
        shown.forEachIndexed { i, row ->
            if (i > 0) Box(Modifier.fillMaxWidth().height(1.dp).background(SoftColors.barRule))
            Row(
                Modifier.fillMaxWidth().clickable { onOpenCategory(row.category) }.padding(vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        row.description,
                        style = MaterialTheme.typography.bodyMedium,
                        color = SoftColors.text,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        txnSubLine(row),
                        style = MaterialTheme.typography.labelSmall,
                        color = SoftColors.text3,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Text(
                    FinanceText.exact(row.cents, row.currency).let { if (row.cents < 0) it else "+$it" },
                    style = MaterialTheme.typography.bodyMedium,
                    color = SoftColors.text,
                    maxLines = 1,
                    softWrap = false,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
        }
    }
}

private const val ALL = "\u0000all"

/** "Nov 4, Groceries, pending". */
internal fun txnSubLine(row: MoneyTxnRow): String = listOfNotNull(
    FinanceText.day(row.date),
    row.category ?: "Uncategorized",
    "pending".takeIf { row.pending },
    "current period".takeIf { row.currentPeriod },
).joinToString(", ")

/** "2 statement files quarantined. Tap to review." */
internal fun quarantineLine(count: Int): String =
    "$count statement file${if (count == 1) "" else "s"} quarantined. Tap to review."
