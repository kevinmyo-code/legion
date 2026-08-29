package com.kevin.legion.ui

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kevin.legion.ai.GeminiKeyProvider
import com.kevin.legion.data.local.IngestState
import com.kevin.legion.data.local.IngestedFile
import com.kevin.legion.data.local.LedgerCurrency
import com.kevin.legion.data.local.LedgerTransaction
import com.kevin.legion.ledger.AccountBalance
import com.kevin.legion.ledger.LedgerNominatedAccountPreferences
import com.kevin.legion.ledger.LedgerController
import com.kevin.legion.ledger.LedgerEntity
import com.kevin.legion.ledger.BudgetVsActual
import com.kevin.legion.ledger.groupAccountBalances
import com.kevin.legion.ledger.uncategorizedExcludedSentence
import com.kevin.legion.ui.common.DeckPane
import com.kevin.legion.ui.common.DeckRadio
import com.kevin.legion.ui.common.EqualHeightRow
import com.kevin.legion.ui.common.HalfTile
import com.kevin.legion.ui.common.Hairline
import com.kevin.legion.ui.common.SectionHeader
import com.kevin.legion.ui.ledger.NominatedAccountSection
import com.kevin.legion.ui.ledger.BalancesDrilldownScreen
import com.kevin.legion.ui.ledger.BudgetDrilldownScreen
import com.kevin.legion.ui.ledger.CategorizeDrilldownScreen
import com.kevin.legion.ui.ledger.CategoryDrilldownScreen
import com.kevin.legion.ui.ledger.CategorySpendChart
import com.kevin.legion.ui.ledger.categorySpendBars
import com.kevin.legion.ui.ledger.categorySpendChartData
import com.kevin.legion.ui.ledger.dollarsParseErrorMessage
import com.kevin.legion.ui.ledger.ExcludedOwnAccountMovementsScreen
import com.kevin.legion.ui.ledger.monthLabel
import com.kevin.legion.ui.ledger.parseDollarsToCents
import com.kevin.legion.ui.ledger.LedgerCategoryResolver
import com.kevin.legion.ui.ledger.LedgerEmptyCopy
import com.kevin.legion.ui.ledger.LedgerEmptyState
import com.kevin.legion.ui.ledger.LedgerTransactionRow
import com.kevin.legion.ui.ledger.QuarantineDrilldownScreen
import com.kevin.legion.ui.theme.LegionTheme
import com.kevin.legion.ui.theme.LegionType
import com.kevin.legion.ui.theme.LocalLegionSemantics
import java.time.YearMonth
import kotlinx.coroutines.launch
import com.kevin.legion.ledger.maskedAccountLabel
import com.kevin.legion.ledger.sameCard

/**
 * `ledger` tab. Ticket 08 Part 5 built the read surfaces (resolution items
 * 4-7): the transaction stream (variant B "Stream"), per-currency balances,
 * the quarantine list, and (partially - see below) the three empty states.
 * Part 6, this revision, adds items 1-3 and finishes item 7: folder
 * connection, scan progress against [ScanState], and the ticket 06 spend
 * gate, plus wiring the two empty states Part 5 could build but not reach.
 *
 * **How the scan is driven.** `AriaForegroundService` used to own the
 * [IngestScanner] this screen binds to (ticket 05 resolution §1's plan), but
 * that service's `onCreate()` unconditionally boots the entire voice
 * assistant - see [com.kevin.legion.service.AriaForegroundService]'s doc
 * comment where `ingestScanner` used to be declared. Opening the Ledger tab
 * must never do that (`AssistantIgnition` promises "ledger... unaffected",
 * off by default). This screen instead binds to
 * [com.kevin.legion.service.LedgerIngestService], a small `dataSync`-only
 * service that owns nothing but the scanner - see [rememberIngestScanner]
 * and that service's own doc comment for the full reasoning. The bind is the
 * shape ticket 05 anticipated ("binding the Activity to the service"); only
 * WHICH service changed.
 *
 * Split per the repo's vendored `compose-state-holder-ui-split` skill:
 * [LedgerScreen] is the state holder (talks to [LedgerController]/
 * [LedgerFolderPreferences]/the bound [IngestScanner], owns every side
 * effect), [LedgerContent] is plain UI state plus callbacks and is what the
 * `@Preview`s below exercise.
 */
/**
 * The category drill-down's OPEN/CLOSED state (Kevin, 2026-08-07 item 3). `null` (the containing
 * `var`, not this class) means "not open"; `CategoryDrilldownSelection(null)` means "open on the
 * `(uncategorised)` bucket" - a plain `String?` alone cannot tell those two apart, which is exactly
 * why this wrapper exists rather than reusing `String?` directly for the `var` itself.
 */
private data class CategoryDrilldownSelection(val category: String?)

data class LedgerUiState(
    val loading: Boolean = true,
    // Backend-erp phase 3 (`.scratch/backend-erp/issues/05-migration-path.md`): what this screen
    // knows about its own last read, and the words it owes the user because of it. Additive next
    // to `loading` above - every existing render branch keyed off `loading` is untouched.
    val read: ReadState = ReadState(),
    val transactions: List<LedgerTransaction> = emptyList(),
    val balances: List<AccountBalance> = emptyList(),
    val quarantined: List<IngestedFile> = emptyList(),
    /** Which account HOME's CRED tile shows a balance for (2026-08-18) - null means never picked.
     * See [com.kevin.legion.ledger.LedgerNominatedAccountPreferences]'s own doc comment. */
    val nominatedAccountId: String? = null,
    // Ticket 06 (`.scratch/legion-shape/issues/06-budget-versus-actual.md`):
    // US-entity monthly budget-versus-actual, replacing the old P&L.
    // `pnlMonthsWithData` bounds the month picker (never page past what
    // could have data); `budgetVsActual` is null while that month's figures
    // are still loading. SG is never surfaced here, only the US entity -
    // same scope limit the old P&L carried.
    val pnlMonthsWithData: List<YearMonth> = emptyList(),
    val pnlMonth: YearMonth? = null,
    val budgetVsActual: BudgetVsActual? = null,
    // Backend-erp phase 3, item 4: the month effect below never touched `loading`, so
    // `budgetVsActual == null` used to mean BOTH "this month is reloading" and "no data at all" -
    // indistinguishable to buildCredTile/buildBudgetTile. True for the duration of that one effect,
    // false once it lands (success OR failure - a failed reload is not a loading state, it is a
    // failed one, and `read.failure` carries that instead).
    val monthLoading: Boolean = false,
    // quant-viz ticket 10: the Money tab's two always-on hero graphics. `spendTrend` is the SAME
    // list `SpendTrendDrilldown` renders (loaded eagerly here, not lazily behind opening that
    // drilldown, since the sparkline needs it visible on the tab face) - see the reload effect's
    // own comment for why loading it here also lets the drilldown reuse this field instead of a
    // second fetch. `monthDailyExpenses` is `pnlMonth`'s own ALL-category operating-expense rows
    // ([com.kevin.legion.ledger.LedgerController.monthOperatingExpenses]), reloaded alongside
    // `budgetVsActual` in the SAME effect since both are keyed on the same picked month.
    val spendTrend: List<com.kevin.legion.ledger.MonthSpend>? = null,
    val monthDailyExpenses: List<LedgerTransaction> = emptyList(),
    // Voice-logged pending transactions - the driver's own report, never a
    // file. See LedgerTransaction.pendingLoggedAt's doc comment.
    val pending: List<LedgerTransaction> = emptyList(),
    // Ticket B2 (2026-08-07): pending AI-guessed categories, up for confirm
    // or correction - CategoryAgent/LedgerController.applyCategoryGuesses
    // existed since ticket 07 with no caller anywhere until this pass wired
    // it. See LedgerController.pendingCategoryGuesses's doc comment.
    val pendingCategoryGuesses: List<LedgerTransaction> = emptyList(),
    // Command-center ticket 11: `accept_proposal` by hand, CRED aspect only - see
    // `ui/ledger/CredDrilldowns.kt`'s `AdvisorProposalsSection` doc comment.
    val pendingProposals: List<com.kevin.legion.data.local.AdvisorAdvice> = emptyList(),
    // 2026-08-18 fix ("it says nothing to categorize" while 44 real rows sat uncategorised):
    // `category IS NULL` rows, split the same way `LedgerController.uncategorizedMerchants`
    // already splits its own candidate pool - `uncategorized` genuinely needs a category,
    // `uncategorizedTransfers` is `analyzeTransfers`-excluded but shown anyway rather than hidden
    // behind an invisible filter. See `ui.ledger.CategorizeDrilldownScreen`'s own doc comment.
    val uncategorized: List<LedgerTransaction> = emptyList(),
    val uncategorizedTransfers: List<LedgerTransaction> = emptyList(),
    // The fixed, Room-stored category list (D14) the new UNCATEGORISED section's hand-set picker
    // offers - loaded alongside the reload above rather than only inside the category drill-down
    // effect (`drilldownCategoryNames`), since CategorizeDrilldownScreen can be opened without ever
    // opening that drill-down.
    val categoryNames: List<String> = emptyList(),
    // The add-category affordance (Kevin 2026-08-07) - a live signal merged into `fullState` each
    // recomposition, same split `folder`/`scanState` already use, not part of the async DB load
    // above. `addCategoryError` is null until a refusal; `addCategorySuccessNonce` only bumps on a
    // confirmed write, so AddCategoryRow's own LaunchedEffect can tell "cleared because it worked"
    // apart from "still showing what the driver typed while a refusal sits underneath it".
    val addCategoryError: String? = null,
    val addCategorySuccessNonce: Int = 0,
    // The SPEND surface's per-account toggle (Kevin, 2026-08-18) - the SELECTED cluster's
    // representative `accountId`, `null` for ALL. A live signal merged into `fullState` each
    // recomposition, same split every other selector on this screen (`folder`/`scanState`/
    // `addCategoryError`) already uses - see `ui.LedgerScreen`'s own `moneyAccountFilterId` `var`
    // for why it is not part of the async DB load or persisted.
    val moneyAccountFilterId: String? = null,
)

@Composable
fun LedgerScreen(
    onOpenGroceries: () -> Unit,
    // Today's category drill-down link (Kevin, 2026-08-07): [MainActivity]'s `LegionShell` holds a
    // pending category + nonce ABOVE the NavHost, the same "state lives above the destination that
    // needs to survive a fresh mount" shape [openItemId]/[openItemNonce] use to deliver a
    // notification tap into `NotesScreen`. It differs in one way, on purpose: `openCategory == null`
    // is itself a real, meaningful request (the uncategorised bucket - see
    // `CategoryDrilldownSelection`'s own doc comment), so unlike the Notes case this can't rely on
    // "null payload -> no-op" to tell "no request" apart from "requested the uncategorised bucket".
    // [openCategoryNonce] carries that instead (`0` = never requested), and [onCategoryDrilldownConsumed]
    // resets it back to `0` in the parent the moment it's acted on - without that reset, merely
    // leaving Money and tapping back into the tab (a fresh mount, since `LegionBottomBar`'s
    // `popUpTo(...){ saveState = false }` disposes this composable's `remember`ed state) would replay
    // the same nonce and silently reopen a drill-down the driver never asked for this time.
    openCategory: String? = null,
    openCategoryNonce: Int = 0,
    onCategoryDrilldownConsumed: () -> Unit = {},
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var state by remember { mutableStateOf(LedgerUiState()) }
    // Bumped after a retry commits, or after a scan finishes, to key the
    // reload LaunchedEffect below - a plain boolean/Unit key can't
    // distinguish "reload once" from "reload again", the same shape as
    // MainActivity's deepLinkNonce.
    var reloadNonce by remember { mutableStateOf(0) }

    // The CATEGORIZE drilldown's own guess-step Gemini gate (unrelated to statement ingestion,
    // which is gone - ticket 25) - CategorizeDrilldownScreen reads this local var directly, never
    // through `state`, so it survived the folder/scan-state deletion below untouched.
    var hasGeminiKey by remember { mutableStateOf(GeminiKeyProvider.hasKey()) }
    val nominatedAccountId by LedgerNominatedAccountPreferences.nominatedAccountId.collectAsStateWithLifecycle()

    // The add-category affordance (Kevin 2026-08-07) - live signals, same "outside `state`,
    // merged into `fullState` each recomposition" split `hasGeminiKey` above already uses.
    var addCategoryError by remember { mutableStateOf<String?>(null) }
    var addCategorySuccessNonce by remember { mutableStateOf(0) }

    // The SET TARGET affordance (quant-viz ticket 09, Kevin 2026-08-13) - the SAME "live signal
    // outside `state`" split as `addCategoryError`/`addCategorySuccessNonce` right above, not folded
    // into `LedgerUiState` for the identical reason: a rejected parse must survive a recomposition
    // rather than fight the async DB reload below over which one owns the field.
    var setTargetErrorText by remember { mutableStateOf<String?>(null) }
    var setTargetSuccessNonce by remember { mutableStateOf(0) }

    // ticket 25 deleted the folder-connect/scan machinery this used to sit next to
    // (`LedgerFolderPreferences`/`service/IngestScanner`) - a Gemini key saved from `settings/key`
    // (this screen was never told) is the one remaining reason to re-check on resume.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        hasGeminiKey = GeminiKeyProvider.hasKey()
    }

    // The P&L's own picked month - null means "not resolved yet", which the
    // reload effect below turns into "the most recent month with data" the
    // first time months become known (ticket resolution §3's default).
    // Deliberately separate from `state` (not folded into LedgerUiState
    // directly) so paging the picker doesn't have to fight the async DB load
    // below over who owns the field - same split `pnlMonth`/`pnlMonthsWithData`
    // being read back OUT of `state` uses for display.
    var pnlMonth by remember { mutableStateOf<YearMonth?>(null) }

    // The SPEND surface's per-account toggle (Kevin, 2026-08-18: "spending... toggleable between
    // the credit card and the debit card"). Deliberately plain Compose state, not part of
    // `LedgerUiState` and not persisted (the task's own item 5: "not persisted... unless an existing
    // preferences object obviously fits" - nothing here does; every other Money-tab selection this
    // screen holds outside `state`, `pnlMonth` included, resets on a fresh mount the same way).
    // `null` means ALL - the untouched-install default that keeps today's behaviour unchanged.
    // Holds the SELECTED cluster's representative `accountId` (see `groupAccountBalances`'s own doc
    // comment for what "representative" means) - never a raw typed string, so there is nothing here
    // for a stale id to drift from what `moneyAccountOptions` below actually offers.
    var moneyAccountFilterId by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(reloadNonce) {
        // Backend-erp phase 3: this whole body used to have no try/catch at all, so a Room or
        // asset-IO throw propagated straight out of the LaunchedEffect and crashed the tab. Wrapped
        // in runCatching rather than a try/catch around the assignment, so the AWAIT-FIRST/COPY-ONCE
        // discipline the comment below still describes is unchanged: every suspend call still
        // resolves into a local val, and the state.copy(...) that reads them is still the single
        // last expression of the block, now just also the runCatching block's return value.
        runCatching {
            val months = LedgerController.monthsWithData(context, LedgerEntity.US)
            // Resolve the default once months become known, and re-resolve if a
            // purge or a scan made the currently-picked month stop existing -
            // never leave the picker parked on a month with nothing to page to.
            if (pnlMonth == null || pnlMonth !in months) pnlMonth = months.lastOrNull()
            // BUG FOUND ON DEVICE (Kevin's US BUDGET section rendering as nothing despite 144 real
            // transactions, 2026-08-07): Kotlin evaluates `state.copy(...)`'s RECEIVER (`state`) before
            // its arguments, so a suspend call inside the argument list suspends AFTER `state` is
            // already captured. This effect and the `pnlMonth`/`reloadNonce` effect right below it are
            // both keyed to fire together, and whichever one's suspend calls finish LAST wins, silently
            // clobbering whatever the other had just written into the SAME `state` var (here,
            // `pnlMonthsWithData`, which the budget section's render gate needs non-empty). Await every
            // suspend call into a local val FIRST, then do exactly one non-suspending `state = state.copy(...)`.
            // The next field added to this copy call MUST follow the same shape, or this bug returns.
            val transactions = LedgerController.recentTransactions(context)
            val balances = LedgerController.accountBalances(context)
            val quarantined = LedgerController.quarantinedFiles(context)
            val pending = LedgerController.pendingTransactions(context)
            val pendingCategoryGuesses = LedgerController.pendingCategoryGuesses(context)
            // Command-center ticket 11: pending advisor proposals for the CRED aspect only - this
            // screen has no reach into BIO/LOG/FLEET/HOME's own proposals, each of which belongs on
            // its own aspect screen.
            val pendingProposals = com.kevin.legion.advisor.AdvisorProposalHandPath.pendingProposals(
                context, com.kevin.legion.advisor.AdvisorAspect.CRED,
            )
            // quant-viz ticket 10: loaded HERE, eagerly, rather than lazily behind `showSpendTrend` -
            // the Money tab face's sparkline needs it visible without opening the drilldown.
            // `SpendTrendDrilldown` below reuses this SAME field (its default 24-month range, unchanged
            // from before this ticket) rather than re-fetching - one load, not two. The tab-face
            // sparkline itself narrows to the ticket's own "up-to-12 months" at its OWN call site
            // (`BudgetSection`'s `spendTrend.takeLast(12)`), not by shrinking what this reload keeps.
            val spendTrend = LedgerController.monthlySpendTrend(context, LedgerEntity.US)
            // 2026-08-18 fix: the third CATEGORIZE list - see `LedgerUiState.uncategorized`'s own
            // comment. Loaded eagerly here (not lazily behind opening the CATEGORIZE drilldown) so
            // HOME/CRED-level counts and the drilldown's own list can never observe two different
            // moments in time.
            val uncategorizedSplit = LedgerController.uncategorizedTransactionsSplit(context)
            val categoryNames = LedgerController.allCategories(context).map { it.name }
            state.copy(
                loading = false,
                transactions = transactions,
                balances = balances,
                quarantined = quarantined,
                pnlMonthsWithData = months,
                pending = pending,
                pendingCategoryGuesses = pendingCategoryGuesses,
                pendingProposals = pendingProposals,
                spendTrend = spendTrend,
                uncategorized = uncategorizedSplit.real,
                uncategorizedTransfers = uncategorizedSplit.transfers,
                categoryNames = categoryNames,
                read = ReadState(loading = false, loadedAtMs = System.currentTimeMillis(), failure = null),
            )
        }.onSuccess { updated ->
            state = updated
        }.onFailure { t ->
            com.kevin.legion.MidnightEvents.appStartWorkFailed("ledger_load", t)
            // Kevin's ruling, 2026-08-26: keep whatever data is already on screen, say the refresh
            // failed alongside it. Never touch a data field here - only `read` changes.
            state = state.copy(
                loading = false,
                read = state.read.copy(loading = false, failure = failureReason(t)),
            )
        }
    }

    // Separate from the reload effect above so paging the month picker
    // doesn't have to re-run the transactions/balances/quarantine reload -
    // only the P&L figures depend on `pnlMonth`. Still keyed on `reloadNonce`
    // too (ticket resolution §5: "re-keyed on reloadNonce so purge and import
    // both refresh it") so a purge clears last month's figures rather than
    // leaving a stale P&L on screen next to an emptied transaction list.
    LaunchedEffect(pnlMonth, reloadNonce, moneyAccountFilterId) {
        val month = pnlMonth
        // Backend-erp phase 3, item 4: without this, `budgetVsActual == null` meant BOTH "this
        // effect is mid-flight" and "genuinely nothing to show", and buildCredTile/buildBudgetTile
        // could not tell those apart. Set true for the duration of this effect only.
        state = state.copy(monthLoading = true)
        // moneyAccountFilterId is the SELECTED cluster's representative accountId; sameCard (via
        // LedgerController.budgetVsActual -> buildBudgetVsActual -> matchesAccountFilter) is what
        // actually resolves it against every stored variant of that physical account, so passing
        // just the one representative id here is enough - see matchesAccountFilter's own doc
        // comment. `null` means ALL, unchanged from before this toggle existed.
        val accountFilter = moneyAccountFilterId?.let { setOf(it) }
        runCatching {
        // See the reload effect above's comment: await first, copy once, non-suspending.
        val budget = if (month != null) LedgerController.budgetVsActual(context, LedgerEntity.US, month, accountFilter) else null
        // quant-viz ticket 10: the daily-bars hero graphic's own load, keyed the SAME as `budget`
        // since both are per-picked-month figures - `monthOperatingExpenses` is the unfiltered
        // sibling of `categoryTransactions` (see that function's own doc comment), never a second
        // aggregate that could drift from `budget`'s own category lines.
        val monthExpenses = if (month != null) LedgerController.monthOperatingExpenses(context, LedgerEntity.US, month, accountFilter) else emptyList()
        state = state.copy(
            budgetVsActual = budget,
            pnlMonth = month,
            monthDailyExpenses = monthExpenses,
            monthLoading = false,
        )
        }.onFailure { t ->
            // This effect was left bare when the reload effect above was wrapped, which made
            // `monthLoading` - introduced in the same change - a flag that could stick true
            // forever and pin the money tiles at "loading". A throw here also crashed the tab,
            // the same defect phase 3 exists to remove. Clear the flag, keep the figures already
            // on screen, and say the refresh failed through the same one banner.
            com.kevin.legion.MidnightEvents.appStartWorkFailed("ledger_month_load", t)
            state = state.copy(
                monthLoading = false,
                read = state.read.copy(failure = failureReason(t)),
            )
        }
    }

    // ticket 25: the folder-scan reload trigger this used to be (a finished scan writing straight
    // to Room via `service/IngestScanner`) is gone along with the scanner itself - a retry-quarantine
    // commit and a category correction already bump `reloadNonce` at their own call sites below.

    // Category drill-down (Kevin, 2026-08-07 item 3: "I want to be able to drill down into a
    // category and see the transactions in there"). Internal Compose state, no nav-graph route -
    // `LegionRoute` deliberately carries no argument routes - matching `ui.NotesScreen`'s own
    // list-drill-down pattern. Kept OUTSIDE `state`/`fullState` (same split `pnlMonth` already
    // uses) so opening/closing it never fights the async DB reload above over ownership.
    var drilldownCategory by remember { mutableStateOf<CategoryDrilldownSelection?>(null) }
    var drilldownTransactions by remember { mutableStateOf<List<LedgerTransaction>>(emptyList()) }
    var drilldownLoading by remember { mutableStateOf(false) }
    // Bumped after a hand recategorise commits FROM INSIDE the drill-down (Kevin, 2026-08-07:
    // "a row that no longer belongs to the open category must not sit there looking unchanged") -
    // `drilldownCategory`/`pnlMonth` themselves don't change when a row moves, so without this the
    // reload effect right below would never re-fire and a Petco row moved out of Shopping would
    // keep sitting in the Shopping drill-down until the screen was left and reopened.
    var drilldownReloadNonce by remember { mutableStateOf(0) }
    // The fixed, Room-stored category list (D14) the recategorise panel's dropdown offers - loaded
    // once per month change alongside the drill-down transactions themselves, same lifetime as
    // `drilldownTransactions`, never a stale list this composable might otherwise be holding.
    var drilldownCategoryNames by remember { mutableStateOf<List<String>>(emptyList()) }
    // ticket 09: the open category's own explicit target, read fresh in the same effect below so a
    // confirmed SET TARGET write (which bumps `setTargetSuccessNonce`, in this effect's key list)
    // shows its own new value without the driver leaving and reopening the drill-down.
    var drilldownTargetCents by remember { mutableStateOf<Long?>(null) }

    // Today's category link (see this function's [openCategory] doc comment above). Guarded on
    // `openCategoryNonce > 0`, not just keyed by it - `0` is this parameter's own default, so a
    // plain visit to the Money tab that never came through Today's drill-down link (nonce still at
    // its initial `0`) must never open anything, the same "no request" reading `deepLinkRoute == null`
    // gets in `LegionShell`'s own effect.
    LaunchedEffect(openCategoryNonce) {
        if (openCategoryNonce > 0) {
            drilldownCategory = CategoryDrilldownSelection(openCategory)
            onCategoryDrilldownConsumed()
        }
    }

    LaunchedEffect(drilldownCategory, pnlMonth, drilldownReloadNonce, setTargetSuccessNonce, moneyAccountFilterId) {
        val selection = drilldownCategory
        val month = pnlMonth
        if (selection == null || month == null) return@LaunchedEffect
        drilldownLoading = true
        // Await both into locals first, one non-suspending assignment each after - matches the
        // reload effect above's own rule (a suspend call inside `state.copy(...)`'s argument list
        // suspends AFTER the receiver is already captured, and this pair fires on the same
        // recomposition whenever a hand recategorise bumps `drilldownReloadNonce`).
        //
        // Same filter the SPEND toggle threads into `budgetVsActual` above - a category tapped from
        // a filtered chart bar must drill into exactly the rows that made THAT bar's total, never
        // every account's rows for a total that only counted one (Kevin, 2026-08-18).
        val transactions = LedgerController.categoryTransactions(
            context, LedgerEntity.US, month, selection.category,
            accountFilter = moneyAccountFilterId?.let { setOf(it) },
        )
        val names = LedgerController.allCategories(context).map { it.name }
        // ticket 09: null for the uncategorised bucket (no target concept there, D11) - never a
        // wasted DAO read for a category that can never show the affordance anyway.
        val targetCents = selection.category?.let {
            LedgerController.currentTargetCents(context, LedgerEntity.US, it, month)
        }
        drilldownTransactions = transactions
        drilldownCategoryNames = names
        drilldownTargetCents = targetCents
        drilldownLoading = false
    }

    // Own-account-movements drill-down (Kevin, 2026-08-13) - a plain boolean, not a nonce/selection
    // wrapper like `CategoryDrilldownSelection`, because there is only ever ONE thing to disclose
    // per month (unlike a category name that varies), and it reads straight off the ALREADY-LOADED
    // `state.budgetVsActual` rather than triggering its own fetch - see
    // `LedgerController.excludedOwnAccountMovements`'s doc comment for why that duplicate read path
    // still exists (the voice tool needs it independently), but this screen has no reason to use it
    // when the figure is already sitting in `state`.
    var showExcludedOwnAccountMovements by remember { mutableStateOf(false) }

    // Spend trend drill-down (quant-viz ticket 04) - a plain boolean like
    // `showExcludedOwnAccountMovements` (only ever ONE thing to open, no selection to carry).
    // ticket 10 (2026-08-13): no longer triggers its OWN fetch - `state.spendTrend` is now loaded
    // eagerly by the main reload effect (the Money tab face's sparkline needs it even when this
    // drilldown is never opened), so this screen reuses that SAME field rather than a second load
    // that could observe a different moment in time.
    var showSpendTrend by remember { mutableStateOf(false) }

    // Mission-control ticket 16's CRED rebuild - the three NEW drilldowns the root's shed sections
    // moved into (CATEGORIZE, QUARANTINE, BUDGET) plus BALANCES (not one of ticket 12's own named
    // drilldowns, but added for the same "do not lose functionality" reason - see
    // `ui.ledger.BalancesDrilldownScreen`'s own doc comment). Same "plain boolean, only ever ONE
    // thing to open" shape `showExcludedOwnAccountMovements`/`showSpendTrend` already use above.
    var showCategorize by remember { mutableStateOf(false) }
    var showQuarantine by remember { mutableStateOf(false) }
    var showBudget by remember { mutableStateOf(false) }
    var showBalances by remember { mutableStateOf(false) }

    // RUN CATEGORIZATION's own run-state (2026-08-18 fix) - live signals outside `state`, same
    // split `addCategoryError`/`addCategorySuccessNonce` already use above, since a mid-run result
    // must survive a recomposition without fighting the async DB reload over ownership.
    // `categorizeRulesFixedCount` null = rules have not been run yet this time the screen is open;
    // `categorizeGuessPool` null = not yet checked what (if anything) is left to guess;
    // `categorizeGuessResult` null = no guess call has completed yet this time the screen is open.
    var categorizeRulesFixedCount by remember { mutableStateOf<Int?>(null) }
    var categorizeGuessPool by remember { mutableStateOf<com.kevin.legion.ledger.UncategorizedMerchants?>(null) }
    var categorizeGuessResult by remember { mutableStateOf<com.kevin.legion.ledger.CategoryGuessResult?>(null) }

    // Hoisted out of the LedgerContent(...) call site below (mission-control ticket 16) so the NEW
    // BUDGET drilldown can page months with the IDENTICAL logic the CRED root's own SPEND hero and
    // the old inline BudgetSection both already relied on - one definition of "page, never past what
    // `pnlMonthsWithData` bounds", never two independently-typed copies drifting apart.
    val onPrevPnlMonth: () -> Unit = {
        val months = state.pnlMonthsWithData
        val index = pnlMonth?.let(months::indexOf) ?: -1
        if (index > 0) pnlMonth = months[index - 1]
    }
    val onNextPnlMonth: () -> Unit = {
        val months = state.pnlMonthsWithData
        val index = pnlMonth?.let(months::indexOf) ?: -1
        if (index in 0 until months.lastIndex) pnlMonth = months[index + 1]
    }

    val currentDrilldown = drilldownCategory
    if (currentDrilldown != null) {
        CategoryDrilldownScreen(
            category = currentDrilldown.category,
            entity = LedgerEntity.US,
            transactions = drilldownTransactions,
            loading = drilldownLoading,
            categoryNames = drilldownCategoryNames,
            // ticket 03 (quant-viz): the daily-spend bars read `pnlMonth`/`state.budgetVsActual.coverage` -
            // the SAME month and coverage the budget section above already loaded, no new DB read.
            // `pnlMonth` cannot be null here: opening a drill-down (`onOpenCategory`) is only ever
            // wired from a row BudgetSection rendered, which itself only renders once `pnlMonth` is
            // non-null - falling back to `YearMonth.now()` is a defensive no-op, never actually hit.
            month = pnlMonth ?: YearMonth.now(),
            coverage = state.budgetVsActual?.coverage ?: emptyList(),
            // 2026-08-18 fix: the uncategorised bucket's own chart is the REAL-category breakdown
            // now, not a per-day total of the bucket alone - see CategoryDrilldownScreen's own doc
            // comment. Same `state.budgetVsActual` the BUDGET drilldown already reads, no new load.
            budget = state.budgetVsActual,
            currentTargetCents = drilldownTargetCents,
            setTargetErrorText = setTargetErrorText,
            setTargetSuccessNonce = setTargetSuccessNonce,
            // ticket 09: parses the typed dollars text (no controller reference inside the content
            // composable, matching onSetCategory below) and writes via LedgerController.setBudget for
            // the drilldown's OWN open month - "copy forward from where you're looking", per the
            // ticket's own resolution. `reloadNonce++` refreshes `state.budgetVsActual` so the meter
            // this write unlocks (BudgetLineRow's `target > 0L` guard) appears without leaving the
            // screen; `setTargetSuccessNonce++` both clears SetTargetRow's typed text (its own
            // LaunchedEffect) AND re-fires the drill-down load effect above to pick up the new
            // `drilldownTargetCents` for the words line.
            onSetTarget = { text ->
                val category = currentDrilldown.category
                if (category != null) {
                    val cents = parseDollarsToCents(text)
                    if (cents == null) {
                        setTargetErrorText = dollarsParseErrorMessage()
                    } else {
                        val month = pnlMonth ?: YearMonth.now()
                        scope.launch {
                            LedgerController.setBudget(context, LedgerEntity.US, category, month, cents)
                            setTargetErrorText = null
                            setTargetSuccessNonce++
                            reloadNonce++
                        }
                    }
                }
            },
            onPreviewRecategorizeCount = { merchantKey -> LedgerController.previewRecategorizeCount(context, merchantKey) },
            onSetCategory = { merchantKey, category ->
                val result = LedgerController.setCategory(context, merchantKey, category)
                // Refresh BOTH the open drill-down (so a row that moved out of this category
                // stops sitting here looking unchanged) and the rest of the screen (budget lines,
                // the uncategorised bucket, pending-guess counts - all of which can shift under a
                // recategorise the same way a voice `set_category` call already does via its own
                // `reloadNonce++`).
                if (result.rowsTouched > 0) {
                    drilldownReloadNonce++
                    reloadNonce++
                }
                result
            },
            // A bar tapped on the uncategorised bucket's own category chart (2026-08-18) - swap
            // this drilldown for that category's, rather than stacking a second screen, so BACK
            // still returns to Money in one press.
            onOpenCategory = { category ->
                drilldownCategory = CategoryDrilldownSelection(category)
                setTargetErrorText = null
            },
            // ticket 09: clears a lingering SET TARGET rejection so leaving this category and
            // opening a different one never shows category B underneath category A's stale parse
            // error - `setTargetSuccessNonce` is deliberately NOT bumped here (that signal means "a
            // write just landed", which did not happen on a plain back-out).
            onBack = { drilldownCategory = null; setTargetErrorText = null },
        )
        return
    }

    if (showExcludedOwnAccountMovements) {
        val budget = state.budgetVsActual
        ExcludedOwnAccountMovementsScreen(
            entity = LedgerEntity.US,
            excluded = budget?.excludedOwnAccountMovements ?: com.kevin.legion.ledger.ExcludedOwnAccountMovements(0, 0L, emptyList()),
            onBack = { showExcludedOwnAccountMovements = false },
        )
        return
    }

    if (showSpendTrend) {
        com.kevin.legion.ui.ledger.SpendTrendDrilldown(
            entity = LedgerEntity.US,
            trend = state.spendTrend,
            onBack = { showSpendTrend = false },
        )
        return
    }

    // Mission-control ticket 16: CATEGORIZE - merges the old PENDING/CATEGORY GUESSES sections,
    // plus (2026-08-18 fix) the UNCATEGORISED/TRANSFERS sections `state.uncategorized`/
    // `state.uncategorizedTransfers` now carry. RUN CATEGORIZATION used to fire rules-then-guesses
    // as one silent, unconfirmed action discarding its own `CategoryGuessResult` - split in two
    // below (`onRunRules`/`onConfirmGuesses`) per `CategorizeDrilldownScreen`'s own doc comment:
    // rules are free and fire immediately, guessing spends the driver's own Gemini key and sits
    // behind an explicit second tap.
    if (showCategorize) {
        // Reset every fresh open, not just every fresh app start - a driver who runs
        // categorisation, backs out, and comes back later must not see a stale "rules fixed 11
        // rows" from last time sitting over this visit's (possibly different) numbers.
        LaunchedEffect(showCategorize) {
            categorizeRulesFixedCount = null
            categorizeGuessPool = null
            categorizeGuessResult = null
        }
        CategorizeDrilldownScreen(
            pending = state.pending,
            categoryGuesses = LedgerCategoryResolver.groupPendingGuesses(state.pendingCategoryGuesses),
            uncategorized = state.uncategorized,
            uncategorizedTransfers = state.uncategorizedTransfers,
            categoryNames = state.categoryNames,
            hasGeminiKey = hasGeminiKey,
            rulesFixedCount = categorizeRulesFixedCount,
            guessPool = categorizeGuessPool,
            guessResult = categorizeGuessResult,
            onClearPending = { id ->
                scope.launch {
                    LedgerController.clearPendingTransaction(context, id)
                    reloadNonce++
                }
            },
            onConfirmCategory = { merchant, category ->
                scope.launch {
                    LedgerController.setCategory(context, merchant, category)
                    reloadNonce++
                }
            },
            onSetRowCategory = { transactionId, category ->
                scope.launch {
                    LedgerController.recategorize(context, transactionId, category)
                    reloadNonce++
                }
            },
            // Step 1: free, local, no confirmation - CLAUDE.md §7's "Gemini call? cheap one-shot
            // sub-agent where possible" cuts the other way here too: a rule match is a plain SQL
            // UPDATE, so there is nothing to gate.
            onRunRules = {
                scope.launch {
                    val fixed = LedgerController.applyCategoryRules(context)
                    categorizeRulesFixedCount = fixed
                    reloadNonce++
                    // Loaded from the FRESH pool (after rules just ran, and after reloadNonce's
                    // uncategorizedTransactionsSplit reload lands) - showing a pre-rules pool size
                    // here would double-count rows the rules step just fixed.
                    categorizeGuessPool = LedgerController.uncategorizedMerchants(context)
                }
            },
            onConfirmGuesses = {
                val pool = categorizeGuessPool
                if (pool != null && pool.keys.isNotEmpty()) {
                    scope.launch {
                        categorizeGuessResult = LedgerController.applyCategoryGuesses(context, pool.keys)
                        reloadNonce++
                        // The pool is now stale (every key it named either got a guess or the model
                        // skipped it) - re-check what's ACTUALLY left rather than leave the old
                        // count's GUESS CATEGORIES button sitting there re-armable on spent keys.
                        categorizeGuessPool = LedgerController.uncategorizedMerchants(context)
                    }
                }
            },
            onBack = { showCategorize = false },
            // Command-center ticket 11: `log_pending_transaction` by hand. `state.balances` is the
            // SAME list already loaded for BALANCES above - never a second fetch.
            accounts = state.balances,
            onPendingAdded = { reloadNonce++ },
            // Command-center ticket 11: `accept_proposal` by hand, CRED aspect.
            proposals = state.pendingProposals,
            onAcceptProposal = { id -> com.kevin.legion.advisor.AdvisorProposalHandPath.acceptPendingProposal(context, id) },
            onDismissProposal = { id -> com.kevin.legion.advisor.AdvisorProposalHandPath.dismissPendingProposal(context, id) },
            onProposalActed = { reloadNonce++ },
        )
        return
    }

    // Mission-control ticket 16: QUARANTINE - the old always-on NEEDS ATTENTION section, now a
    // drilldown (ticket 12's own CRED drilldown table names it) reached from the SPEND hero's own
    // worded notice rather than a standing section - see `SpendPane`'s doc comment below.
    if (showQuarantine) {
        QuarantineDrilldownScreen(
            quarantined = state.quarantined,
            onRetry = { driveFileId ->
                scope.launch {
                    LedgerController.retryQuarantined(context, driveFileId)
                    reloadNonce++
                }
            },
            onRetryAll = {
                scope.launch {
                    LedgerController.retryAllQuarantined(context)
                    reloadNonce++
                }
            },
            onBack = { showQuarantine = false },
        )
        return
    }

    // Mission-control ticket 16: BUDGET - the full category-by-category breakdown, unchanged in
    // content from the old always-inline BudgetSection, now reached one tap in from the BUDGET tile.
    if (showBudget) {
        val month = pnlMonth
        if (month != null) {
            val index = state.pnlMonthsWithData.indexOf(month)
            BudgetDrilldownScreen(
                month = month,
                budget = state.budgetVsActual,
                dailyTransactions = state.monthDailyExpenses,
                canGoPrevMonth = index > 0,
                canGoNextMonth = index in 0 until state.pnlMonthsWithData.lastIndex,
                onPrevMonth = onPrevPnlMonth,
                onNextMonth = onNextPnlMonth,
                onOpenCategory = { category ->
                    showBudget = false
                    drilldownCategory = CategoryDrilldownSelection(category)
                },
                onOpenExcludedOwnAccountMovements = { showExcludedOwnAccountMovements = true },
                onOpenTrend = { showSpendTrend = true },
                addCategoryError = addCategoryError,
                addCategorySuccessNonce = addCategorySuccessNonce,
                onAddCategory = { name ->
                    scope.launch {
                        when (val result = LedgerController.addCategory(context, name)) {
                            is com.kevin.legion.ledger.NewCategoryValidation.Valid -> {
                                addCategoryError = null
                                addCategorySuccessNonce++
                            }
                            is com.kevin.legion.ledger.NewCategoryValidation.Invalid -> addCategoryError = result.reason
                        }
                    }
                },
                onBack = { showBudget = false },
            )
        }
        return
    }

    // Mission-control ticket 16: BALANCES - every account, reused wholesale from the old
    // always-inline BalancesSection, now reached one tap in from the BALANCES tile. Grouped HERE,
    // at this call site, same as the old root's own comment on why `groupAccountBalances` is never
    // called inside `LedgerController.accountBalances` itself - `state.balances` stays ungrouped so
    // any OTHER reader of it (the AccountMappingSection `knownAccountIds` above) still sees every
    // real accountId, including ones this grouping would cluster together for display only.
    if (showBalances) {
        BalancesDrilldownScreen(
            balances = groupAccountBalances(state.balances),
            onBack = { showBalances = false },
        )
        return
    }

    // Folder/scan/key/mapping are live signals, not part of the async DB
    // load above - merged into a fresh value each recomposition rather than
    // folded back into the remembered `state` var, so this never risks a
    // self-triggered recompose loop over fields that already have their own
    // source of truth.
    val fullState = state.copy(
        nominatedAccountId = nominatedAccountId,
        addCategoryError = addCategoryError, addCategorySuccessNonce = addCategorySuccessNonce,
        moneyAccountFilterId = moneyAccountFilterId,
    )

    LedgerContent(
        state = fullState,
        onOpenGroceries = onOpenGroceries,
        // Never let the picker step past what `pnlMonthsWithData` actually
        // bounds (ticket resolution §5: "never let the user page into months
        // that cannot have data") - a null current month or an index at
        // either end makes both a safe no-op rather than paging off the list.
        onPrevPnlMonth = onPrevPnlMonth,
        onNextPnlMonth = onNextPnlMonth,
        onNominateAccount = { accountId ->
            LedgerNominatedAccountPreferences.setNominated(context, accountId)
        },
        // Mission-control ticket 16's CRED rebuild - the four new nav targets the shed sections
        // moved into. See the `showCategorize`/`showQuarantine`/`showBudget`/`showBalances` blocks
        // above for what each actually renders.
        onOpenCategorize = { showCategorize = true },
        onOpenQuarantine = { showQuarantine = true },
        onOpenBudget = { showBudget = true },
        onOpenBalances = { showBalances = true },
        onOpenTrend = { showSpendTrend = true },
        // The SPEND pane's uncategorised-excluded disclosure taps straight into the uncategorised
        // bucket's own drilldown - the SAME CategoryDrilldownSelection(null) the BUDGET drilldown's
        // uncategorised row already opens (see that wrapper class's own doc comment for why `null`
        // here is a real request, not "nothing requested").
        onOpenUncategorized = { drilldownCategory = CategoryDrilldownSelection(null) },
        // A tap on a SPEND-chart bar (2026-08-18) - same CategoryDrilldownSelection the BUDGET
        // drilldown's own rows open, so the chart and the list reach one destination rather than
        // two that can drift.
        onOpenCategory = { category -> drilldownCategory = CategoryDrilldownSelection(category) },
        // The SPEND toggle itself (Kevin, 2026-08-18) - a plain assignment into the `var` above,
        // which the reload effects keyed on `moneyAccountFilterId` above pick up.
        onSelectAccountFilter = { accountId -> moneyAccountFilterId = accountId },
    )
}

/** Plain UI: [state] plus callbacks, no controller/service reference - see the file doc comment. */
@Composable
fun LedgerContent(
    state: LedgerUiState,
    onOpenGroceries: () -> Unit,
    // The nomination picker (2026-08-18) - see NominatedAccountSection's own doc comment.
    onNominateAccount: (accountId: String?) -> Unit,
    onPrevPnlMonth: () -> Unit,
    onNextPnlMonth: () -> Unit,
    // Mission-control ticket 16's CRED rebuild: the four nav targets the shed sections moved into -
    // see `ui.LedgerScreen`'s own `showCategorize`/`showQuarantine`/`showBudget`/`showBalances`
    // blocks for what each opens.
    onOpenCategorize: () -> Unit,
    onOpenQuarantine: () -> Unit,
    onOpenBudget: () -> Unit,
    onOpenBalances: () -> Unit,
    onOpenTrend: () -> Unit,
    // The SPEND pane's uncategorised-excluded disclosure (2026-08-15) - opens the uncategorised
    // bucket's own drilldown, so the exclusion is inspectable, never merely asserted.
    onOpenUncategorized: () -> Unit = {},
    // 2026-08-18: the SPEND pane's category chart is tappable now, and a bar opens that category's
    // own drilldown - the same destination BudgetSection's rows already reach.
    onOpenCategory: (String?) -> Unit = {},
    // The SPEND surface's per-account toggle (Kevin, 2026-08-18) - `null` selects ALL.
    onSelectAccountFilter: (String?) -> Unit = {},
) {
    val sem = LocalLegionSemantics.current
    // 2026-08-18 regression fix: this used to be a plain `Column(fillMaxSize())` holding the title
    // row, folder connection/scan/account-mapping rows (all deleted, ticket 25 - statement
    // ingestion left the phone) etc. as FIXED (non-scrolling)
    // content, with `LedgerListing`'s own LazyColumn as the only scroll surface, sized to whatever
    // height was left over. Adding NominatedAccountSection to that fixed region (commit 77a4fbf) grew
    // it past the viewport on-device: a plain Column's non-weighted children never yield space back,
    // so the leftover height for the LazyColumn collapsed to ~0 and the whole tab stopped scrolling.
    // Same "ONE scroll surface" constraint `ui.notes.InboxScreen`'s own LazyColumn comment documents -
    // everything below the MONEY title now lives as items in a SINGLE LazyColumn, this one, so growth
    // in any section (this one, or the next) degrades into "scroll further", never "stop scrolling".
    Surface(modifier = Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize()) {
            item(key = "money-title-row") {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("MONEY", style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onSurface)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        // A grocery receipt is a purchase (2026-08-07 brief) -
                        // pantry's read screen lives under Money now, reached
                        // from here rather than its own tab.
                        TextButton(onClick = onOpenGroceries) {
                            Text("GROCERIES", style = LegionType.stamp, color = MaterialTheme.colorScheme.primary)
                        }
                        // Mission-control ticket 16: now opens the CATEGORIZE drilldown rather than
                        // instant-firing the categorise action - that action lives INSIDE the drilldown
                        // now (an explicit RUN CATEGORIZATION button), because this screen is where its
                        // own results land. The count said in words, not a bare badge (CLAUDE.md §4) -
                        // same convention SectionHeader's own right-hand count already used pre-ticket-16.
                        val toCategorizeCount = state.pending.size + LedgerCategoryResolver.groupPendingGuesses(state.pendingCategoryGuesses).size
                        TextButton(onClick = onOpenCategorize) {
                            Text(
                                if (toCategorizeCount > 0) "CATEGORIZE ($toCategorizeCount)" else "CATEGORIZE",
                                style = LegionType.stamp,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                }
            }

            // Backend-erp phase 3: a stale/failed-read notice, placed right under the title, never
            // below the fold - see readStateLine's own doc for why silence is correct otherwise.
            item(key = "money-read-state") {
                ReadStateBanner(state.read, modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp))
            }

            // The nomination picker (2026-08-18) - an account can carry balances from an ingested
            // statement with no phone-side folder concept at all (ticket 25 deleted that entirely),
            // and it's still nominatable. Renders nothing itself when state.balances is empty -
            // groupAccountBalances, not the raw list, same render-site grouping discipline every
            // other reader of state.balances on this screen follows (see that function's own doc
            // comment).
            item(key = "money-nominated-account") {
                NominatedAccountSection(
                    balances = groupAccountBalances(state.balances),
                    nominatedAccountId = state.nominatedAccountId,
                    onNominate = onNominateAccount,
                )
            }
            item(key = "money-hairline") { Hairline() }

            when {
                state.loading -> item(key = "money-loading") {
                    Text(
                        "Loading...",
                        style = LegionType.stamp,
                        color = sem.ghost,
                        modifier = Modifier.padding(12.dp),
                    )
                }
                state.transactions.isEmpty() && state.quarantined.isEmpty() ->
                    item(key = "money-empty") { LedgerEmptySection() }
                else -> ledgerListingItems(
                    state, onPrevPnlMonth, onNextPnlMonth, onOpenCategorize, onOpenQuarantine,
                    onOpenBudget, onOpenBalances, onOpenTrend, onOpenUncategorized, onOpenCategory,
                    onSelectAccountFilter,
                )
            }
        }
    }
}

/**
 * **AMENDED 2026-08-29, backend-erp ticket 25.** This used to resolve one of three copies
 * (`LedgerEmptyStateResolver`) depending on a folder/scan signal - that whole mechanism is gone
 * along with phone-side statement ingestion, so there is only one state left to say: no
 * transactions have landed here yet. Statement ingestion moved to the web app; nothing on the
 * phone can trigger one to appear, so there is no action button here any more either.
 */
@Composable
private fun LedgerEmptySection() {
    LedgerEmptyState(
        title = LedgerEmptyCopy.NO_STATEMENTS_TITLE,
        body = LedgerEmptyCopy.NO_STATEMENTS_BODY,
    )
}

/**
 * CRED root, rebuilt to mission-control ticket 16/ticket 12's inventory: SPEND (FULL, hero) then a
 * BUDGET/BALANCES HALF-tile row then RECENT ACTIVITY (FULL, list) - four panels/lists where seven
 * always-visible sections stood before. Every dropped section is still reachable: CATEGORIZE and
 * QUARANTINE via drilldowns this file's caller wires, BUDGET's full category breakdown and
 * BALANCES' full account list one tap in from their own tiles, START OVER moved to Setup entirely
 * (`ui.SettingsScreen`'s own `PurgeLedgerRow` now).
 */
/**
 * `LazyListScope` extension, not a `@Composable` with its own `LazyColumn` - see the 2026-08-18
 * regression comment on [LedgerContent]'s call site. This function used to own a second, nested
 * `LazyColumn`; it now just appends `item`/`items` calls onto the CALLER's single LazyColumn, so
 * the whole MONEY tab (title, folder setup, account pickers, and this listing) shares one scroll
 * surface instead of this one silently competing with a fixed-height header region above it for
 * the viewport.
 */
private fun LazyListScope.ledgerListingItems(
    state: LedgerUiState,
    onPrevPnlMonth: () -> Unit,
    onNextPnlMonth: () -> Unit,
    onOpenCategorize: () -> Unit,
    onOpenQuarantine: () -> Unit,
    onOpenBudget: () -> Unit,
    onOpenBalances: () -> Unit,
    onOpenTrend: () -> Unit,
    onOpenUncategorized: () -> Unit,
    onOpenCategory: (String?) -> Unit,
    onSelectAccountFilter: (String?) -> Unit,
) {
    // Ticket 19's GOALS panel - CRED aspect (personal-finance advisor's own key, see
    // com.kevin.legion.advisor.playbooks.CredPlaybook's doc comment). Sits above every other
    // panel, unchanged by this ticket's tiling - see GoalsPanel's own doc comment for why it is
    // self-contained rather than folded into LedgerUiState.
    item(key = "goals") {
        com.kevin.legion.ui.goals.GoalsPanel(aspect = "cred")
        Hairline()
    }

    // ------------------------------------------------------------ SPEND (FULL, hero)
    // Only once the US entity has at least one month of data to show - `state.pnlMonth` stays
    // null until `monthsWithData` resolves to something real, matching the old US BUDGET
    // section's own gate (a fresh install with zero transactions never renders a month picker
    // with nothing to page through).
    val pnlMonth = state.pnlMonth
    if (state.pnlMonthsWithData.isNotEmpty() && pnlMonth != null) {
        item(key = "spend-pane") {
            val index = state.pnlMonthsWithData.indexOf(pnlMonth)
            SpendPane(
                state = state,
                canGoPrevMonth = index > 0,
                canGoNextMonth = index in 0 until state.pnlMonthsWithData.lastIndex,
                onPrevMonth = onPrevPnlMonth,
                onNextMonth = onNextPnlMonth,
                onOpenTrend = onOpenTrend,
                onOpenQuarantine = onOpenQuarantine,
                onOpenUncategorized = onOpenUncategorized,
                onOpenCategory = onOpenCategory,
                onOpenBudget = onOpenBudget,
                onSelectAccountFilter = onSelectAccountFilter,
            )
        }

        // ---------------------------------------------------- BUDGET / BALANCES (HALF tiles)
        // Same EqualHeightRow/HalfTile shell HOME's own BIO/CRED/FLEET/LOG row, BodyScreen's
        // INTAKE/SLEEP row, and FleetScreen's MAINTENANCE/DRIVES row all already use - see
        // `ui.common.DeckTiles.kt`'s own doc comment for why a bare `Row(IntrinsicSize.Min)`
        // cannot be substituted (it crashes on-device against a DeckPane child).
        item(key = "tile-row-budget-balances") {
            val budgetTile = buildBudgetTile(state.budgetVsActual, state.monthLoading)
            val groupedBalances = groupAccountBalances(state.balances)
            val balancesTile = buildBalancesTile(groupedBalances)
            EqualHeightRow(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalGap = 9.dp) {
                HalfTile(
                    header = "Budget",
                    hero = budgetTile.hero,
                    caption = budgetTile.caption,
                    modifier = Modifier.clickable(onClick = onOpenBudget),
                )
                HalfTile(
                    header = "Balances",
                    hero = balancesTile.hero,
                    caption = balancesTile.caption,
                    modifier = Modifier.clickable(onClick = onOpenBalances),
                )
            }
        }
        item(key = "spend-row-spacer") { Spacer(Modifier.height(14.dp)) }
    }

    // ------------------------------------------------------------ RECENT ACTIVITY (FULL, list)
    if (state.transactions.isNotEmpty()) {
        item(key = "activity-header") { SectionHeader("RECENT ACTIVITY") }
        items(state.transactions, key = { "t-${it.id}" }) { txn ->
            LedgerTransactionRow(txn)
            Hairline()
        }
    }
    item(key = "bottom-spacer") { Spacer(Modifier.height(24.dp)) }
}

/**
 * SPEND - CRED's FULL hero pane (mission-control ticket 16's root rebuild): month spend against
 * target ([buildCredTile], the SAME resolver HOME's own CRED tile already uses) over a bar chart of
 * this month's categories.
 *
 * **The hero visual is [com.kevin.legion.ui.ledger.categorySpendBars] (Kevin, 2026-08-15).** It
 * replaces the month-to-date cumulative sparkline this pane carried since ticket 16 - one hero
 * visual, and the question the pane answers is "where did the money go", which a running total
 * cannot show. **HOME's own CRED tile dropped its sparkline entirely on 2026-08-18** (Kevin: "no
 * need for the line graph") in favour of the nominated account's own balance -
 * [TodayGapResolvers.buildCredBalanceLine] - so this pane's chart is now the only place a spend
 * TREND renders at all; the month-over-month trend is one tap in via [onOpenTrend]. Uncategorised
 * spend is in neither the hero figure nor the chart, and the sentence
 * under the chart says so in words whenever there is any - see
 * [com.kevin.legion.ledger.UncategorizedSpend]'s own doc comment for why that disclosure is what
 * makes the exclusion honest rather than hidden.
 *
 * The month nav (`< AUGUST 2026 >`) that used to sit atop
 * the always-inline `BudgetSection` lives here instead - the root's own natural home for "what
 * month am I looking at", now that the full category-by-category breakdown moved one tap in to the
 * BUDGET tile.
 *
 * Tapping the pane opens [com.kevin.legion.ui.ledger.SpendTrendDrilldown] ([onOpenTrend]) - ticket
 * 12's own CRED drilldown table names "spend trend", and this is the SAME destination the old
 * `BudgetSection`'s sparkline/month-label already tapped into, unchanged. A quarantined-document
 * notice renders underneath, in words (never colour alone, CLAUDE.md §4), only when
 * `state.quarantined` is non-empty, and carries its OWN nested clickable into [onOpenQuarantine] -
 * same "inner click wins" shape [FleetScreen]'s `DriveModeOfferRow` already relies on inside its
 * own clickable parent pane.
 */
@Composable
private fun SpendPane(
    state: LedgerUiState,
    canGoPrevMonth: Boolean,
    canGoNextMonth: Boolean,
    onPrevMonth: () -> Unit,
    onNextMonth: () -> Unit,
    onOpenTrend: () -> Unit,
    onOpenQuarantine: () -> Unit,
    onOpenUncategorized: () -> Unit,
    onOpenCategory: (String?) -> Unit,
    onOpenBudget: () -> Unit,
    // The account toggle (Kevin, 2026-08-18) - `null` selects ALL, matching `state.moneyAccountFilterId`.
    onSelectAccountFilter: (String?) -> Unit = {},
) {
    val sem = LocalLegionSemantics.current
    val month = state.pnlMonth
    val credTile = buildCredTile(state.budgetVsActual, month?.let(::ledgerSweepMonthLabel).orEmpty(), state.monthLoading)
    DeckPane(header = "Spend", modifier = Modifier.clickable(onClick = onOpenTrend)) {
        if (month != null) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onPrevMonth, enabled = canGoPrevMonth) {
                    Text("<", style = LegionType.stamp, color = if (canGoPrevMonth) MaterialTheme.colorScheme.primary else sem.ghost)
                }
                Text(monthLabel(month), style = LegionType.stamp, color = sem.faint)
                TextButton(onClick = onNextMonth, enabled = canGoNextMonth) {
                    Text(">", style = LegionType.stamp, color = if (canGoNextMonth) MaterialTheme.colorScheme.primary else sem.ghost)
                }
            }
        }
        // The per-account toggle (Kevin, 2026-08-18: "spending... toggleable between the credit
        // card and the debit card") - a segmented DeckRadio row, the SAME horizontal-row-of-
        // DeckRadio shape `ui.SettingsRows`' temperature-unit picker already uses, in place of
        // inventing a new control (CLAUDE.md's "look at what ui/common/ already offers" rule).
        // Only rendered once there is more than one physical account to choose between - a single-
        // account entity has nothing to toggle, and an ALL-vs-ALL control would be a lie about
        // there being a choice. Reuses `groupAccountBalances` (never plain-equality on `accountId`)
        // for the exact reason that function's own doc comment gives: Kevin's one physical card is
        // stored under two different strings, and a toggle keyed on the raw string would show his
        // card twice and split its own spend between the two rows.
        val accountOptions = groupAccountBalances(state.balances.filter { it.currency == LedgerEntity.US.currency })
        if (accountOptions.size > 1) {
            AccountFilterRow(
                options = accountOptions,
                selectedAccountId = state.moneyAccountFilterId,
                onSelect = onSelectAccountFilter,
            )
        }
        Text(
            credTile.hero,
            style = MaterialTheme.typography.displayLarge,
            // A month's own spend is a VALUE, mint like every other reading in the app (ticket 01's
            // "mint is every value, amber is every highlight") - the same `sem.data` HOME's CRED
            // tile already reads this identical figure with.
            color = sem.data,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
        )
        Text(credTile.caption, style = LegionType.stamp, color = sem.faint, modifier = Modifier.padding(horizontal = 12.dp))
        val budget = state.budgetVsActual
        if (budget != null) {
            val chartData = categorySpendChartData(budget)
            val bars = chartData.bars
            if (bars.isEmpty()) {
                Text(
                    "no categorised spend this month",
                    style = LegionType.stamp,
                    color = sem.faint,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                )
            } else {
                // Tapping a bar opens that category's own transactions (Kevin, 2026-08-18) - the
                // same destination BudgetLineRow's tap already reaches, now one tap from the chart
                // he is already looking at instead of two taps in behind the BUDGET tile. An inner
                // click wins over this pane's own onOpenTrend, the same nesting the quarantine
                // notice below already relies on.
                //
                // The folded `OTHER n` bar carries a null category - it is several categories
                // added together and names none of them - so it opens the full BUDGET breakdown,
                // which is exactly what that bar is a summary of. Drilling on its LABEL would
                // query for a category called "OTHER 3" and honestly find nothing.
                CategorySpendChart(
                    bars,
                    modifier = Modifier.padding(vertical = 4.dp),
                    onBarTap = { index ->
                        val category = chartData.categories.getOrNull(index)
                        if (category != null) onOpenCategory(category) else onOpenBudget()
                    },
                )
            }
            // The exclusion, in words, directly under the figure and the chart it describes
            // (CLAUDE.md §4 rule 7's disclosure posture, and the condition that makes excluding the
            // bucket honest at all - see UncategorizedSpend's own doc comment). Tapping it opens the
            // uncategorised bucket's own drilldown, the same "inner click wins" nesting the
            // quarantine notice below already uses inside this clickable pane.
            if (budget.uncategorized.spentCents > 0L) {
                Text(
                    uncategorizedExcludedSentence(budget.uncategorized, budget.entity.currency),
                    style = LegionType.stamp,
                    color = sem.estimated,
                    modifier = Modifier
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                        .clickable(onClick = onOpenUncategorized),
                )
            }
        }
        if (state.quarantined.isNotEmpty()) {
            Text(
                "${state.quarantined.size} DOCUMENT${if (state.quarantined.size == 1) "" else "S"} QUARANTINED - TAP TO REVIEW",
                style = LegionType.stamp,
                color = sem.quarantined,
                modifier = Modifier
                    .padding(horizontal = 12.dp, vertical = 6.dp)
                    .clickable(onClick = onOpenQuarantine),
            )
        }
    }
}

/**
 * The SPEND surface's per-account segmented row (Kevin, 2026-08-18) - `ALL` plus one [DeckRadio]
 * per grouped account (already [groupAccountBalances]'d by the caller, so a card stored under two
 * strings shows once), labelled via [maskedAccountLabel] so a raw 16-digit PAN never reaches this
 * screen (see that function's own doc comment for the on-device incident this rule closes). The
 * whole row is one `selectableGroup()`, matching [ui.SettingsRows]' temperature-unit picker - the
 * same horizontal-DeckRadio-row shape, not a new control, per this repo's "look at what ui/common/
 * already offers" convention.
 */
@Composable
private fun AccountFilterRow(
    options: List<AccountBalance>,
    selectedAccountId: String?,
    onSelect: (String?) -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 2.dp)
            .selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        DeckRadio(selected = selectedAccountId == null, onClick = { onSelect(null) }, label = "All")
        options.forEach { balance ->
            DeckRadio(
                selected = selectedAccountId != null && sameCard(selectedAccountId, balance.accountId),
                onClick = { onSelect(balance.accountId) },
                label = maskedAccountLabel(balance.accountId),
            )
        }
    }
}

/**
 * BUDGET tile: how many categories are over their own target this month, off the SAME
 * [BudgetVsActual.lines] the BUDGET drilldown renders in full - re-shaped, never recomputed, same
 * posture [buildFleetTile]/[buildCredTile] already follow for their own tiles. A zero-target line
 * (spend with no budget set, `buildBudgetVsActual`'s own "$0 budgeted, $42 spent" case) never counts
 * as "over" - there is nothing to be over.
 */
private data class BudgetTileData(val hero: String, val caption: String)

private fun buildBudgetTile(budget: BudgetVsActual?, monthLoading: Boolean = false): BudgetTileData {
    // Backend-erp phase 3, item 4: same split as buildCredTile above - null means either
    // "reloading" or "genuinely nothing to show", and only monthLoading can tell them apart.
    if (budget == null) {
        return if (monthLoading) {
            BudgetTileData(hero = "...", caption = "loading")
        } else {
            BudgetTileData(hero = "NO DATA", caption = "nothing to show yet")
        }
    }
    if (budget.lines.isEmpty()) return BudgetTileData(hero = "NONE", caption = "no categories yet - see budget")
    val overCount = budget.lines.count { it.gap.target > 0L && it.gap.gap < 0L }
    return if (overCount > 0) {
        BudgetTileData(hero = "$overCount OVER", caption = "${budget.lines.size} categories - see budget")
    } else {
        BudgetTileData(hero = "OK", caption = "${budget.lines.size} categories, on track")
    }
}

/**
 * BALANCES tile: the first account's own figure ([AccountBalance.availableCents], never a total
 * across currencies - CLAUDE.md §4 rule 5, this app never invents an exchange rate) plus how many
 * more sit behind it in the full [BalancesDrilldownScreen]. [grouped] is the SAME
 * `groupAccountBalances(...)` output the drilldown itself renders, not a second grouping pass.
 */
private data class BalancesTileData(val hero: String, val caption: String)

private fun buildBalancesTile(grouped: List<AccountBalance>): BalancesTileData {
    if (grouped.isEmpty()) return BalancesTileData(hero = "NONE", caption = "no accounts yet")
    val primary = grouped.first()
    val hero = if (primary.hasAnyFigure) compactMoneyHero(primary.availableCents, primary.currency) else "N/A"
    val extra = grouped.size - 1
    val label = maskedAccountLabel(primary.accountId)
    val caption = if (extra > 0) "$label +$extra more" else label
    return BalancesTileData(hero, caption)
}


// ------------------------------------------------------------------------ previews

@Preview(name = "Ledger: loading", widthDp = 360, heightDp = 720)
@Composable
private fun PreviewLedgerLoading() = LegionTheme {
    LedgerContent(
        LedgerUiState(loading = true),
        onOpenGroceries = {}, onNominateAccount = {}, onPrevPnlMonth = {}, onNextPnlMonth = {},
        onOpenCategorize = {}, onOpenQuarantine = {}, onOpenBudget = {}, onOpenBalances = {}, onOpenTrend = {},
    )
}

@Preview(name = "Ledger empty: no statements yet", widthDp = 360, heightDp = 720)
@Composable
private fun PreviewLedgerEmptyNoStatements() = LegionTheme {
    LedgerContent(
        LedgerUiState(loading = false),
        onOpenGroceries = {}, onNominateAccount = {}, onPrevPnlMonth = {}, onNextPnlMonth = {},
        onOpenCategorize = {}, onOpenQuarantine = {}, onOpenBudget = {}, onOpenBalances = {}, onOpenTrend = {},
    )
}

@Preview(name = "Ledger: balances + quarantine + stream", widthDp = 360, heightDp = 900)
@Composable
private fun PreviewLedgerPopulated() = LegionTheme {
    LedgerContent(
        state = LedgerUiState(
            loading = false,
            balances = listOf(
                AccountBalance("BOFA ****4471", LedgerCurrency.USD, 119_80),
                AccountBalance("DBS ****8802", LedgerCurrency.SGD, 216_582),
                // Ticket 12: mid-cycle card CSV rows counted into a
                // provisional balance, clearly marked - see
                // ui/ledger/LedgerRows.kt's own provisional-balance preview.
                AccountBalance(
                    accountId = "7823",
                    currency = LedgerCurrency.USD,
                    balanceCents = null,
                    provisionalDeltaCents = -7500,
                    isProvisional = true,
                ),
            ),
            quarantined = listOf(
                IngestedFile(
                    driveFileId = "abc123",
                    treeUri = "content://tree/x",
                    displayName = "eStmt_2025-11-05.pdf",
                    sizeBytes = 40_000,
                    lastModified = System.currentTimeMillis(),
                    contentSha256 = null,
                    state = IngestState.QUARANTINED,
                    quarantineReason = "Lines summed to 4,182.19 but the statement says 4,180.00.",
                    firstSeenAt = System.currentTimeMillis(),
                    lastAttemptAt = System.currentTimeMillis(),
                ),
            ),
            transactions = listOf(
                LedgerTransaction(
                    id = 1,
                    sourceFile = "eStmt_2026-07.pdf",
                    accountId = "BOFA ****4471",
                    currency = LedgerCurrency.USD,
                    txnDate = System.currentTimeMillis(),
                    description = "CHECKCARD 0701 TRADER JOES #452 SAN JOSE CA",
                    amountCents = -8734,
                    balanceCents = 412_09,
                    lineRef = "1",
                    ingestMethod = com.kevin.legion.data.local.IngestMethod.DETERMINISTIC,
                ),
                LedgerTransaction(
                    id = 2,
                    sourceFile = "eStmt_2026-07.pdf",
                    accountId = "BOFA ****4471",
                    currency = LedgerCurrency.USD,
                    txnDate = System.currentTimeMillis(),
                    description = "PAYROLL DES:DIRECT DEP ID:9928471 INDN:K MYO",
                    amountCents = 384_512,
                    balanceCents = 588_87,
                    lineRef = "2",
                    ingestMethod = com.kevin.legion.data.local.IngestMethod.LLM_RECONCILED,
                ),
                LedgerTransaction(
                    id = 3,
                    sourceFile = "currentTransaction_7823.csv",
                    accountId = "7823",
                    currency = LedgerCurrency.USD,
                    txnDate = System.currentTimeMillis(),
                    description = "NORTHWIND OUTFITTERS 07/13 PURCHASE SEATTLE WA",
                    amountCents = -6000,
                    balanceCents = null,
                    lineRef = "3",
                    ingestMethod = com.kevin.legion.data.local.IngestMethod.UNRECONCILED,
                ),
            ),
        ),
        onOpenGroceries = {}, onNominateAccount = {}, onPrevPnlMonth = {}, onNextPnlMonth = {},
        onOpenCategorize = {}, onOpenQuarantine = {}, onOpenBudget = {}, onOpenBalances = {}, onOpenTrend = {},
    )
}
