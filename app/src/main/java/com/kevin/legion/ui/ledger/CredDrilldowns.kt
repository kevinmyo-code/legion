package com.kevin.legion.ui.ledger

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.kevin.legion.data.local.IngestState
import com.kevin.legion.data.local.IngestedFile
import com.kevin.legion.data.local.LedgerCurrency
import com.kevin.legion.data.local.LedgerTransaction
import com.kevin.legion.ledger.AccountBalance
import com.kevin.legion.ledger.BudgetVsActual
import com.kevin.legion.ledger.CategoryGuessResult
import com.kevin.legion.ledger.MonthSpend
import com.kevin.legion.ledger.UncategorizedMerchants
import com.kevin.legion.ledger.displayDescription
import com.kevin.legion.ledger.formatMoney
import com.kevin.legion.ui.common.DeckButton
import com.kevin.legion.ui.common.Hairline
import com.kevin.legion.ui.common.SectionHeader
import com.kevin.legion.ui.theme.LegionTheme
import com.kevin.legion.ui.theme.LegionType
import com.kevin.legion.ui.theme.LocalLegionSemantics
import com.kevin.legion.util.documentDateCompact
import java.time.YearMonth

/**
 * The three drilldowns mission-control ticket 16's CRED rebuild hangs off the new HALF tiles/hero
 * (`.scratch/mission-control/issues/16-build-surfaces.md`, `12-surface-inventories.md`'s own
 * `money` drilldown table: "spend trend, category drilldown, budget, quarantine, pantry"). CRED
 * root shed from seven always-visible sections to four (SPEND/BUDGET/BALANCES/RECENT ACTIVITY);
 * everything that used to sit inline below them is still reachable, just one tap deeper -
 * [ui.LedgerScreen]'s file doc records the "do not lose functionality" rule this file exists to
 * satisfy.
 *
 * Every screen here follows the same header shape every other in-screen drilldown in the app
 * already uses ([ui.BodyScreen]'s `DrilldownHeader`, [ExcludedOwnAccountMovementsScreen] above) - a
 * plain `TextButton("< BACK")`, never [DeckButton], so a driver's eye reads every back affordance in
 * the app identically. New ACTION buttons this file introduces ([CategorizeDrilldownScreen]'s RUN
 * CATEGORIZATION) do migrate to [DeckButton] per this ticket's binding; the rows/buttons REUSED
 * wholesale from elsewhere (RETRY, RETRY ALL, CONFIRM, REMOVE, ADD) are untouched - this ticket
 * moved where they live, not what they look like, and touching their internals was outside its
 * footprint.
 */

/**
 * `money/categorize` - merges the old always-visible `PENDING (LOGGED BY VOICE)` and
 * `CATEGORY GUESSES, NOT CONFIRMED` sections (ticket 12 section 3: "confirming a voice entry and
 * confirming a category guess are the same job"), plus a THIRD section this 2026-08-18 fix adds:
 * [uncategorized] / [uncategorizedTransfers], `category IS NULL` rows.
 *
 * **The bug this screen used to hide (Kevin, 2026-08-18: "it says nothing to categorize").** The
 * old empty-state check was `pending.isEmpty() && categoryGuesses.isEmpty()` - neither list is
 * "rows with no category" ([pending] is voice-logged entries, [categoryGuesses] is rows already
 * carrying a pending AI guess), so a driver whose statements imported with 44 genuinely
 * uncategorised rows and zero pending/guess rows saw an all-clear the screen never actually
 * checked, and never pressed RUN CATEGORIZATION - which would have fixed 11 of those 44 from
 * stored rules alone, for free. [LedgerCategoryResolver.categorizeEmptyStateSentence] is the fix:
 * it can only ever return a "nothing" sentence when [uncategorized] is ALSO empty.
 *
 * **RUN CATEGORIZATION split in two, matching what each step actually costs (2026-08-18).**
 * [onRunRules] is [com.kevin.legion.ledger.LedgerController.applyCategoryRules] - free, local,
 * no confirmation needed, fires immediately and reports [rulesFixedCount] the moment it lands.
 * [guessPool] is [com.kevin.legion.ledger.LedgerController.uncategorizedMerchants]'s own return,
 * loaded right after rules run so the driver sees exactly what is left BEFORE any money is spent -
 * [onConfirmGuesses] (a real Gemini call, on the driver's own key) sits behind [DeckButton]'s own
 * two-tap `confirming` pattern (see [DeckButton]'s doc comment / `PurgeLedgerRow`'s established
 * use of it) rather than firing on the first tap, per `LedgerController.applyCategoryGuesses`'s own
 * "only call after the caller has shown the estimate and gotten an explicit yes." [guessResult] is
 * that call's own [CategoryGuessResult], reported however it comes back - including the zero case
 * (a missing key or a network failure completes "successfully" with nothing guessed, per
 * `CategoryAgent.guessBatch` swallowing every exception into `null`), so the screen never looks
 * identical after a run that changed nothing.
 *
 * **Hand-setting a category on one uncategorised row.** [onSetRowCategory] is a direct pass-through
 * to [com.kevin.legion.ledger.LedgerController.recategorize] (`setCategoryConfirmed` under the
 * hood) - ONE row, unconditionally confirmed, the same write a driver picking a category directly
 * always gets (D19). This is deliberately NOT [com.kevin.legion.ledger.LedgerController.setCategory]
 * (merchant-substring, writes a standing [com.kevin.legion.data.local.CategoryRule]) - that panel
 * already exists one tap into a real category's own drill-down
 * ([com.kevin.legion.ui.ledger.LedgerCategoryDrilldown]'s `RecategorizePanel`) for a driver who
 * wants to move every future import too; this section's job is narrower: "I'm looking right at
 * this one row, put it somewhere." The picker itself reuses [DropdownMenu]/[DropdownMenuItem] over
 * [categoryNames] (D14's fixed list, never free text) - the SAME picker mechanics
 * `RecategorizePanel` already established, not a second interaction pattern.
 */
@Composable
fun CategorizeDrilldownScreen(
    pending: List<LedgerTransaction>,
    categoryGuesses: List<LedgerCategoryResolver.MerchantGuess>,
    uncategorized: List<LedgerTransaction>,
    uncategorizedTransfers: List<LedgerTransaction>,
    categoryNames: List<String>,
    hasGeminiKey: Boolean,
    rulesFixedCount: Int?,
    guessPool: UncategorizedMerchants?,
    guessResult: CategoryGuessResult?,
    onClearPending: (id: Long) -> Unit,
    onConfirmCategory: (merchantKey: String, category: String) -> Unit,
    onSetRowCategory: (transactionId: Long, category: String) -> Unit,
    onRunRules: () -> Unit,
    onConfirmGuesses: () -> Unit,
    onBack: () -> Unit,
) {
    val sem = LocalLegionSemantics.current
    // The GUESS CATEGORIES confirm control's own second-tap arm state - PurgeLedgerRow's exact
    // shape, local because it is purely "has this button been tapped once already", not a
    // persisted fact the caller's state holder needs to know about.
    var guessArmed by remember { mutableStateOf(false) }
    val emptySentence = LedgerCategoryResolver.categorizeEmptyStateSentence(
        pendingCount = pending.size,
        categoryGuessCount = categoryGuesses.size,
        uncategorizedRealCount = uncategorized.size,
        uncategorizedTransfersCount = uncategorizedTransfers.size,
    )
    Surface(modifier = Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onBack) {
                    Text("< BACK", style = LegionType.stamp, color = MaterialTheme.colorScheme.primary)
                }
            }
            Text("Categorize", style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp))
            Text(
                "Confirming a voice entry and confirming an AI-guessed category are the same job - both land here.",
                style = LegionType.stamp,
                color = sem.faint,
                modifier = Modifier.padding(horizontal = 12.dp),
            )
            Spacer(Modifier.height(8.dp))
            // Step 1: rules, free, no confirmation - fires immediately.
            DeckButton(
                text = "RUN CATEGORIZATION",
                onClick = onRunRules,
                modifier = Modifier.padding(horizontal = 12.dp),
            )
            if (rulesFixedCount != null) {
                Text(
                    LedgerCategoryResolver.rulesRunSentence(rulesFixedCount),
                    style = LegionType.stamp,
                    color = sem.faint,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp),
                )
            }
            // Step 2: guessing costs money - shown only once rules have actually run this visit
            // (guessPool loads right after onRunRules lands), gated behind an explicit second tap.
            if (guessPool != null) {
                Text(
                    LedgerCategoryResolver.guessPoolSentence(guessPool, hasGeminiKey),
                    style = LegionType.stamp,
                    color = sem.estimated,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp),
                )
                if (guessPool.keys.isNotEmpty() && hasGeminiKey) {
                    DeckButton(
                        text = if (guessArmed) "YES, GUESS WITH GEMINI" else "GUESS CATEGORIES",
                        onClick = { if (guessArmed) { onConfirmGuesses(); guessArmed = false } else guessArmed = true },
                        confirming = guessArmed,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                    )
                }
            }
            if (guessResult != null) {
                Text(
                    LedgerCategoryResolver.guessResultSentence(guessResult),
                    style = LegionType.stamp,
                    color = sem.faint,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp),
                )
            }
            Spacer(Modifier.height(4.dp))
            Hairline()
            if (emptySentence != null && uncategorizedTransfers.isEmpty()) {
                Text(
                    emptySentence,
                    style = MaterialTheme.typography.bodySmall,
                    color = sem.faint,
                    modifier = Modifier.padding(12.dp),
                )
            } else {
                LazyColumn(Modifier.fillMaxSize()) {
                    if (emptySentence != null) {
                        // Transfers still exist even though nothing needs a category - the sentence
                        // says so, and the list right underneath it must match (fix item 3: "the
                        // count the driver sees must match what the list shows").
                        item(key = "empty-with-transfers") {
                            Text(emptySentence, style = MaterialTheme.typography.bodySmall, color = sem.faint, modifier = Modifier.padding(12.dp))
                        }
                    }
                    if (pending.isNotEmpty()) {
                        item(key = "pending-header") { SectionHeader("PENDING (LOGGED BY VOICE)", pending.size.toString()) }
                        item(key = "pending") { PendingTransactionsSection(pending, onClearPending) }
                    }
                    if (categoryGuesses.isNotEmpty()) {
                        item(key = "guesses-header") { SectionHeader("CATEGORY GUESSES, NOT CONFIRMED", categoryGuesses.size.toString()) }
                        item(key = "guesses") { CategoryGuessesSection(categoryGuesses, onConfirmCategory) }
                    }
                    if (uncategorized.isNotEmpty()) {
                        item(key = "uncategorized-header") { SectionHeader("UNCATEGORISED", uncategorized.size.toString()) }
                        item(key = "uncategorized") { UncategorizedSection(uncategorized, categoryNames, onSetRowCategory) }
                    }
                    if (uncategorizedTransfers.isNotEmpty()) {
                        item(key = "transfers-header") { SectionHeader("TRANSFERS (EXCLUDED FROM SPEND)", uncategorizedTransfers.size.toString()) }
                        item(key = "transfers") { UncategorizedTransfersSection(uncategorizedTransfers) }
                    }
                    item(key = "bottom-spacer") { Spacer(Modifier.height(24.dp)) }
                }
            }
        }
    }
}

/**
 * `UNCATEGORISED` rows - the ones [LedgerCategoryResolver.categorizeEmptyStateSentence] used to
 * let hide behind a false "nothing to categorize". Each row grows a category picker
 * ([DropdownMenu] over [categoryNames], D14's fixed list) so a driver can set ONE row's category
 * by hand without leaving this screen - [onSetCategory] is a direct pass-through to
 * [com.kevin.legion.ledger.LedgerController.recategorize] (see [CategorizeDrilldownScreen]'s own
 * doc comment for why this is the single-row write, not the merchant-substring one).
 */
@Composable
fun UncategorizedSection(
    rows: List<LedgerTransaction>,
    categoryNames: List<String>,
    onSetCategory: (transactionId: Long, category: String) -> Unit,
) {
    var expandedTxnId by remember { mutableStateOf<Long?>(null) }
    Column(Modifier.fillMaxWidth()) {
        for (row in rows) {
            UncategorizedRow(
                txn = row,
                categoryNames = categoryNames,
                expanded = expandedTxnId == row.id,
                onToggleExpanded = { expandedTxnId = if (expandedTxnId == row.id) null else row.id },
                onSetCategory = { category ->
                    onSetCategory(row.id, category)
                    expandedTxnId = null
                },
            )
            Hairline()
        }
    }
}

@Composable
private fun UncategorizedRow(
    txn: LedgerTransaction,
    categoryNames: List<String>,
    expanded: Boolean,
    onToggleExpanded: () -> Unit,
    onSetCategory: (String) -> Unit,
) {
    val sem = LocalLegionSemantics.current
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp)) {
        Text(displayDescription(txn.description), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
        Spacer(Modifier.height(3.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(documentDateCompact(txn.txnDate), style = LegionType.stamp, color = sem.faint)
            Text(
                formatMoney(txn.amountCents, txn.currency),
                style = LegionType.amount,
                color = if (txn.amountCents > 0) sem.credit else sem.debit,
                modifier = Modifier.padding(start = 8.dp),
            )
            TextButton(onClick = onToggleExpanded) {
                Text(if (expanded) "CANCEL" else "SET CATEGORY", style = LegionType.stamp, color = MaterialTheme.colorScheme.primary)
            }
        }
        val note = LedgerCategoryResolver.rowNote(txn)
        if (note != null) {
            Spacer(Modifier.height(2.dp))
            Text(note, style = LegionType.stamp, color = sem.estimated)
        }
        if (expanded) {
            Spacer(Modifier.height(4.dp))
            var menuExpanded by remember(txn.id) { mutableStateOf(false) }
            Box {
                TextButton(onClick = { menuExpanded = true }) {
                    Text("Pick a category", style = LegionType.stamp, color = MaterialTheme.colorScheme.primary)
                }
                // D14: the fixed, Room-stored list only - never free text, matching the same
                // boundary `set_category`/`RecategorizePanel` are held to.
                DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                    categoryNames.forEach { name ->
                        DropdownMenuItem(
                            text = { Text(name) },
                            onClick = { menuExpanded = false; onSetCategory(name) },
                        )
                    }
                }
            }
        }
    }
}

/**
 * `TRANSFERS (EXCLUDED FROM SPEND)` - transfer-shaped `category IS NULL` rows
 * [com.kevin.legion.ledger.analyzeTransfers] correctly keeps out of the guesser's candidate pool.
 * Shown, not hidden (Kevin's own call, 2026-08-18: "hiding 22 of 44 rows behind an invisible
 * filter is how this bug happened in the first place") - but with no category picker, since
 * setting a category on a transfer would undo the correct exclusion. Read-only, words-only
 * disclosure ([LedgerCategoryResolver.transferRowNote], CLAUDE.md §4 rule 7).
 */
@Composable
fun UncategorizedTransfersSection(rows: List<LedgerTransaction>) {
    val sem = LocalLegionSemantics.current
    Column(Modifier.fillMaxWidth()) {
        for (row in rows) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp)) {
                Text(displayDescription(row.description), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
                Spacer(Modifier.height(3.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(documentDateCompact(row.txnDate), style = LegionType.stamp, color = sem.faint)
                    Text(
                        formatMoney(row.amountCents, row.currency),
                        style = LegionType.amount,
                        color = if (row.amountCents > 0) sem.credit else sem.debit,
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
                Spacer(Modifier.height(2.dp))
                Text(LedgerCategoryResolver.transferRowNote(), style = LegionType.stamp, color = sem.estimated)
            }
            Hairline()
        }
    }
}

/**
 * `money/quarantine` - the old always-visible `NEEDS ATTENTION` section (mission-control ticket 12's
 * ruling: a standing section duplicated what HOME's ALERTS pane and a row's own tier tag already
 * say, so it drops off the CRED root). The RETRY affordance itself is NOT dropped - ticket 12's own
 * drilldown table names `quarantine` explicitly - only its always-on placement is. No tap-through
 * from the root exists in the shipped app for a quarantined-DOCUMENT specifically (a quarantined
 * file never became rows, so there is no transaction row to tag or tap); this screen is reached
 * instead from the SPEND hero's own worded notice when [quarantined] is non-empty - see
 * `ui.LedgerScreen`'s `SpendPane` for that entry point.
 */
@Composable
fun QuarantineDrilldownScreen(
    quarantined: List<IngestedFile>,
    onRetry: (driveFileId: String) -> Unit,
    onRetryAll: () -> Unit,
    onBack: () -> Unit,
) {
    val sem = LocalLegionSemantics.current
    Surface(modifier = Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onBack) {
                    Text("< BACK", style = LegionType.stamp, color = MaterialTheme.colorScheme.primary)
                }
            }
            Text("Quarantine", style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp))
            Text(
                if (quarantined.isEmpty()) {
                    "Nothing quarantined."
                } else {
                    "${quarantined.size} document${if (quarantined.size == 1) "" else "s"} failed to reconcile " +
                        "against its own stated total (CLAUDE.md §4) - nothing from them was written."
                },
                style = LegionType.stamp,
                color = sem.faint,
                modifier = Modifier.padding(horizontal = 12.dp),
            )
            Hairline()
            if (quarantined.isEmpty()) {
                Text("All clear.", style = MaterialTheme.typography.bodySmall, color = sem.faint, modifier = Modifier.padding(12.dp))
            } else {
                RetryAllQuarantineRow(quarantined.size, onRetryAll)
                Hairline()
                LazyColumn(Modifier.fillMaxSize()) {
                    items(quarantined, key = { "q-${it.driveFileId}" }) { file ->
                        QuarantineRow(file, onRetry)
                        Hairline()
                    }
                }
            }
        }
    }
}

/**
 * `money/budget` - the full category-by-category breakdown [BudgetSection] already built (month
 * nav, the spend-trend sparkline and daily-spend bars, every [BudgetLine] row, the uncategorised
 * bucket, the coverage/own-account-movement disclosures), reused WHOLESALE rather than re-split -
 * only its placement moved, from always-inline on the CRED root to one tap in from the BUDGET HALF
 * tile. [AddCategoryRow] rides along directly underneath, matching its pre-ticket-16 position "at
 * the foot of the category list" (that composable's own doc comment).
 */
@Composable
fun BudgetDrilldownScreen(
    month: YearMonth,
    budget: BudgetVsActual?,
    spendTrend: List<MonthSpend>?,
    dailyTransactions: List<LedgerTransaction>,
    canGoPrevMonth: Boolean,
    canGoNextMonth: Boolean,
    onPrevMonth: () -> Unit,
    onNextMonth: () -> Unit,
    onOpenCategory: (String?) -> Unit,
    onOpenExcludedOwnAccountMovements: () -> Unit,
    onOpenTrend: () -> Unit,
    addCategoryError: String?,
    addCategorySuccessNonce: Int,
    onAddCategory: (String) -> Unit,
    onBack: () -> Unit,
) {
    Surface(modifier = Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onBack) {
                    Text("< BACK", style = LegionType.stamp, color = MaterialTheme.colorScheme.primary)
                }
            }
            Text("Budget", style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp))
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                BudgetSection(
                    month = month,
                    budget = budget,
                    spendTrend = spendTrend,
                    dailyTransactions = dailyTransactions,
                    canGoPrevMonth = canGoPrevMonth,
                    canGoNextMonth = canGoNextMonth,
                    onPrevMonth = onPrevMonth,
                    onNextMonth = onNextMonth,
                    onOpenCategory = onOpenCategory,
                    onOpenExcludedOwnAccountMovements = onOpenExcludedOwnAccountMovements,
                    onOpenTrend = onOpenTrend,
                )
                AddCategoryRow(errorText = addCategoryError, successNonce = addCategorySuccessNonce, onAdd = onAddCategory)
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

/**
 * `money/balances` - the full per-account [BalancesSection] (every currency, every account, the
 * "not combined, no exchange rate" disclosure, the provisional/pending notes), reused wholesale.
 * Not one of ticket 12's own named CRED drilldowns, but added here anyway: a HALF tile has room for
 * one account's own figure, and this app never combines currencies into one number (CLAUDE.md §4
 * rule 5 - inventing an FX rate is exactly the unstated-value problem that rule exists to prevent),
 * so a driver with more than one account needs a real place to see the rest. Dropping this would
 * have orphaned every account past the tile's own headline one.
 */
@Composable
fun BalancesDrilldownScreen(balances: List<AccountBalance>, onBack: () -> Unit) {
    Surface(modifier = Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onBack) {
                    Text("< BACK", style = LegionType.stamp, color = MaterialTheme.colorScheme.primary)
                }
            }
            Text("Balances", style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp))
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                BalancesSection(balances)
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

// ------------------------------------------------------------------------ previews

@Preview(name = "Categorize: pending + guesses + uncategorised + transfers", widthDp = 360, heightDp = 900)
@Composable
private fun PreviewCategorizeMixed() = LegionTheme {
    CategorizeDrilldownScreen(
        pending = listOf(
            LedgerTransaction(
                id = 1, sourceFile = "voice", accountId = "BOFA ****4471", currency = LedgerCurrency.USD,
                txnDate = System.currentTimeMillis(), description = "Coffee with Dana", amountCents = -650,
                lineRef = "1", ingestMethod = com.kevin.legion.data.local.IngestMethod.UNRECONCILED,
            ),
        ),
        categoryGuesses = listOf(
            LedgerCategoryResolver.MerchantGuess(merchantKey = "TRADER JOES", category = "Groceries", transactionCount = 4),
        ),
        uncategorized = listOf(
            LedgerTransaction(
                id = 2, sourceFile = "eStmt.pdf", accountId = "BOFA ****4471", currency = LedgerCurrency.USD,
                txnDate = System.currentTimeMillis(), description = "CHEVRON 0123456", amountCents = -4210,
                lineRef = "2", ingestMethod = com.kevin.legion.data.local.IngestMethod.DETERMINISTIC,
            ),
        ),
        uncategorizedTransfers = listOf(
            LedgerTransaction(
                id = 3, sourceFile = "eStmt.pdf", accountId = "BOFA ****4471", currency = LedgerCurrency.USD,
                txnDate = System.currentTimeMillis(), description = "ONLINE BANKING PAYMENT TO CRD", amountCents = -50000,
                lineRef = "3", ingestMethod = com.kevin.legion.data.local.IngestMethod.DETERMINISTIC,
            ),
        ),
        categoryNames = listOf("Groceries", "Gas", "Shopping"),
        hasGeminiKey = true,
        rulesFixedCount = 11,
        guessPool = UncategorizedMerchants(keys = listOf("CHEVRON"), transfersSkipped = 1),
        guessResult = null,
        onClearPending = {}, onConfirmCategory = { _, _ -> }, onSetRowCategory = { _, _ -> },
        onRunRules = {}, onConfirmGuesses = {}, onBack = {},
    )
}

@Preview(name = "Categorize: nothing to do", widthDp = 360, heightDp = 480)
@Composable
private fun PreviewCategorizeEmpty() = LegionTheme {
    CategorizeDrilldownScreen(
        pending = emptyList(), categoryGuesses = emptyList(),
        uncategorized = emptyList(), uncategorizedTransfers = emptyList(),
        categoryNames = listOf("Groceries", "Gas", "Shopping"),
        hasGeminiKey = true,
        rulesFixedCount = null, guessPool = null, guessResult = null,
        onClearPending = {}, onConfirmCategory = { _, _ -> }, onSetRowCategory = { _, _ -> },
        onRunRules = {}, onConfirmGuesses = {}, onBack = {},
    )
}

@Preview(name = "Categorize: nothing needs a category, transfers only", widthDp = 360, heightDp = 640)
@Composable
private fun PreviewCategorizeTransfersOnly() = LegionTheme {
    CategorizeDrilldownScreen(
        pending = emptyList(), categoryGuesses = emptyList(), uncategorized = emptyList(),
        uncategorizedTransfers = listOf(
            LedgerTransaction(
                id = 4, sourceFile = "eStmt.pdf", accountId = "BOFA ****4471", currency = LedgerCurrency.USD,
                txnDate = System.currentTimeMillis(), description = "MOBILE BANKING PAYMENT TO CRD", amountCents = -20000,
                lineRef = "4", ingestMethod = com.kevin.legion.data.local.IngestMethod.DETERMINISTIC,
            ),
        ),
        categoryNames = listOf("Groceries", "Gas", "Shopping"),
        hasGeminiKey = true,
        rulesFixedCount = null, guessPool = null, guessResult = null,
        onClearPending = {}, onConfirmCategory = { _, _ -> }, onSetRowCategory = { _, _ -> },
        onRunRules = {}, onConfirmGuesses = {}, onBack = {},
    )
}

@Preview(name = "Quarantine: two files", widthDp = 360, heightDp = 640)
@Composable
private fun PreviewQuarantineTwo() = LegionTheme {
    QuarantineDrilldownScreen(
        quarantined = listOf(
            IngestedFile(
                driveFileId = "abc123", treeUri = "content://tree/x", displayName = "eStmt_2025-11-05.pdf",
                sizeBytes = 40_000, lastModified = System.currentTimeMillis(), contentSha256 = null,
                state = IngestState.QUARANTINED, quarantineReason = "Lines summed to 4,182.19 but the statement says 4,180.00.",
                firstSeenAt = System.currentTimeMillis(), lastAttemptAt = System.currentTimeMillis(),
            ),
        ),
        onRetry = {}, onRetryAll = {}, onBack = {},
    )
}

@Preview(name = "Quarantine: none", widthDp = 360, heightDp = 480)
@Composable
private fun PreviewQuarantineEmpty() = LegionTheme {
    QuarantineDrilldownScreen(quarantined = emptyList(), onRetry = {}, onRetryAll = {}, onBack = {})
}

@Preview(name = "Balances: two accounts", widthDp = 360, heightDp = 480)
@Composable
private fun PreviewBalancesTwo() = LegionTheme {
    BalancesDrilldownScreen(
        balances = listOf(
            AccountBalance("BOFA ****4471", LedgerCurrency.USD, 119_80),
            AccountBalance("DBS ****8802", LedgerCurrency.SGD, 216_582),
        ),
        onBack = {},
    )
}
