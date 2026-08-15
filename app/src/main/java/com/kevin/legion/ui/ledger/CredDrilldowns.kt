package com.kevin.legion.ui.ledger

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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import com.kevin.legion.ledger.MonthSpend
import com.kevin.legion.ui.common.DeckButton
import com.kevin.legion.ui.common.Hairline
import com.kevin.legion.ui.common.SectionHeader
import com.kevin.legion.ui.theme.LegionTheme
import com.kevin.legion.ui.theme.LegionType
import com.kevin.legion.ui.theme.LocalLegionSemantics
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
 * confirming a category guess are the same job"). [onRunCategorize] is the SAME action the CRED
 * root's old header CATEGORIZE button used to fire silently on tap (`LedgerController.applyCategoryRules`
 * then `applyCategoryGuesses` for whatever's still uncategorised) - it now lives here, as an
 * explicit button, because this screen is where its own results land.
 */
@Composable
fun CategorizeDrilldownScreen(
    pending: List<LedgerTransaction>,
    categoryGuesses: List<LedgerCategoryResolver.MerchantGuess>,
    onClearPending: (id: Long) -> Unit,
    onConfirmCategory: (merchantKey: String, category: String) -> Unit,
    onRunCategorize: () -> Unit,
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
            Text("Categorize", style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp))
            Text(
                "Confirming a voice entry and confirming an AI-guessed category are the same job - both land here.",
                style = LegionType.stamp,
                color = sem.faint,
                modifier = Modifier.padding(horizontal = 12.dp),
            )
            Spacer(Modifier.height(8.dp))
            DeckButton(
                text = "RUN CATEGORIZATION",
                onClick = onRunCategorize,
                modifier = Modifier.padding(horizontal = 12.dp),
            )
            Spacer(Modifier.height(4.dp))
            Hairline()
            if (pending.isEmpty() && categoryGuesses.isEmpty()) {
                Text(
                    "Nothing to categorize right now.",
                    style = MaterialTheme.typography.bodySmall,
                    color = sem.faint,
                    modifier = Modifier.padding(12.dp),
                )
            } else {
                LazyColumn(Modifier.fillMaxSize()) {
                    if (pending.isNotEmpty()) {
                        item(key = "pending-header") { SectionHeader("PENDING (LOGGED BY VOICE)", pending.size.toString()) }
                        item(key = "pending") { PendingTransactionsSection(pending, onClearPending) }
                    }
                    if (categoryGuesses.isNotEmpty()) {
                        item(key = "guesses-header") { SectionHeader("CATEGORY GUESSES, NOT CONFIRMED", categoryGuesses.size.toString()) }
                        item(key = "guesses") { CategoryGuessesSection(categoryGuesses, onConfirmCategory) }
                    }
                    item(key = "bottom-spacer") { Spacer(Modifier.height(24.dp)) }
                }
            }
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

@Preview(name = "Categorize: pending + guesses", widthDp = 360, heightDp = 720)
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
        onClearPending = {}, onConfirmCategory = { _, _ -> }, onRunCategorize = {}, onBack = {},
    )
}

@Preview(name = "Categorize: nothing to do", widthDp = 360, heightDp = 480)
@Composable
private fun PreviewCategorizeEmpty() = LegionTheme {
    CategorizeDrilldownScreen(
        pending = emptyList(), categoryGuesses = emptyList(),
        onClearPending = {}, onConfirmCategory = { _, _ -> }, onRunCategorize = {}, onBack = {},
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
