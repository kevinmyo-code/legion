package com.kevin.legion.ui

import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kevin.legion.ai.GeminiKeyProvider
import com.kevin.legion.data.local.IngestedFile
import com.kevin.legion.data.local.LedgerTransaction
import com.kevin.legion.ledger.AccountBalance
import com.kevin.legion.ledger.LedgerController
import com.kevin.legion.ledger.LedgerEntity
import com.kevin.legion.ledger.BudgetVsActual
import com.kevin.legion.ui.ledger.BudgetDrilldownScreen
import com.kevin.legion.ui.ledger.CategorizeDrilldownScreen
import com.kevin.legion.ui.ledger.CategoryDrilldownScreen
import com.kevin.legion.ui.ledger.dollarsParseErrorMessage
import com.kevin.legion.ui.ledger.ExcludedOwnAccountMovementsScreen
import com.kevin.legion.ui.ledger.parseDollarsToCents
import com.kevin.legion.ui.ledger.LedgerCategoryResolver
import com.kevin.legion.ui.ledger.QuarantineDrilldownScreen
import com.kevin.legion.ui.theme.soft.SoftTheme
import java.time.YearMonth
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
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
 * `LedgerIngestService` (DELETED, backend-erp ticket 25; the manifest entry went 2026-10-14), a small `dataSync`-only
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
    /** Non-null when the last read of the ENGINE's ledger failed - the figures are the phone's
     * copy ([com.kevin.legion.backend.LedgerMirrorStatus.line]). A different fact from [read]. */
    val syncLine: String? = null,
    val transactions: List<LedgerTransaction> = emptyList(),
    val balances: List<AccountBalance> = emptyList(),
    val quarantined: List<IngestedFile> = emptyList(),
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
)

@Composable
fun LedgerScreen(
    onOpenGroceries: () -> Unit,
    onBack: () -> Unit = {},
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
    // Household-finance ticket 14: the Money view's engine figures (safe to spend, budgets, net
    // worth, accounts, cash flow), through the one FinanceController HOME and Alfred share.
    val moneyViewModel: com.kevin.legion.ui.money.MoneyViewModel =
        androidx.lifecycle.viewmodel.compose.viewModel(factory = com.kevin.legion.ui.money.MoneyViewModel.Factory(context))
    val money by moneyViewModel.state.collectAsStateWithLifecycle()
    var swipeHintSeen by remember { mutableStateOf(com.kevin.legion.ui.money.MoneySwipeHint.seen(context)) }

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
        moneyViewModel.refresh()
    }

    // The P&L's own picked month - null means "not resolved yet", which the
    // reload effect below turns into "the most recent month with data" the
    // first time months become known (ticket resolution §3's default).
    // Deliberately separate from `state` (not folded into LedgerUiState
    // directly) so paging the picker doesn't have to fight the async DB load
    // below over who owns the field - same split `pnlMonth`/`pnlMonthsWithData`
    // being read back OUT of `state` uses for display.
    var pnlMonth by remember { mutableStateOf<YearMonth?>(null) }

    // The SPEND surface's per-account toggle (accountFilter chips, 2026-08-18) RETIRED 2026-11-05,
    // household-finance ticket 14 (Kevin: the accounts "no need to be selectable"). Every figure on
    // this screen is all accounts now; [moneyAccountFilterId] stays null so the controller calls
    // below keep their signature.
    val moneyAccountFilterId: String? = null

    // backend-etl ticket 14: the engine mirror is fire-and-forget from the app's resume, so its rows
    // (and its "not synced" status) can land after this screen loaded. Reload when it says so.
    LaunchedEffect(Unit) {
        com.kevin.legion.backend.LedgerMirrorStatus.changes.drop(1).collect { reloadNonce++ }
    }

    LaunchedEffect(reloadNonce) {
        // Backend-erp phase 3: this whole body used to have no try/catch at all, so a Room or
        // asset-IO throw propagated straight out of the LaunchedEffect and crashed the tab. Wrapped
        // in runCatching rather than a try/catch around the assignment, so the AWAIT-FIRST/COPY-ONCE
        // discipline the comment below still describes is unchanged: every suspend call still
        // resolves into a local val, and the state.copy(...) that reads them is still the single
        // last expression of the block, now just also the runCatching block's return value.
        runCatching {
            // Household-finance ticket 14: the pager pages over these, newest on the right, and opens
            // on the current month even before it has a row (the engine's figures for it exist).
            val months = (LedgerController.monthsWithData(context, LedgerEntity.US) + YearMonth.now())
                .distinct().sorted()
            // Resolve the default once months become known, and re-resolve if a
            // purge or a scan made the currently-picked month stop existing -
            // never leave the picker parked on a month with nothing to page to.
            if (pnlMonth == null || pnlMonth !in months) pnlMonth = months.lastOrNull()
            pnlMonth?.let(moneyViewModel::loadMonth)
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
            // backend-etl ticket 14: whether the ENGINE's ledger could be read on the last try. The
            // `read` banner below is this screen's own Room read, a different fact.
            // Option 2 of the same ticket: a hand-set category still queued for the server is said
            // here too, in words, until it lands - the same line, since both are "this screen is
            // ahead of or behind the server".
            val syncLine = listOfNotNull(
                com.kevin.legion.backend.LedgerMirrorStatus.line(context),
                com.kevin.legion.backend.LedgerTransactionCategoryWriteThrough.pendingSentence(context),
            ).takeIf { it.isNotEmpty() }?.joinToString(" ")
            state.copy(
                syncLine = syncLine,
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
        SoftTheme {
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
        }
        return
    }

    if (showExcludedOwnAccountMovements) {
        val budget = state.budgetVsActual
        SoftTheme {
            ExcludedOwnAccountMovementsScreen(
                entity = LedgerEntity.US,
                excluded = budget?.excludedOwnAccountMovements ?: com.kevin.legion.ledger.ExcludedOwnAccountMovements(0, 0L, emptyList()),
                onBack = { showExcludedOwnAccountMovements = false },
            )
        }
        return
    }

    if (showSpendTrend) {
        SoftTheme {
            com.kevin.legion.ui.ledger.SpendTrendDrilldown(
                entity = LedgerEntity.US,
                trend = state.spendTrend,
                onBack = { showSpendTrend = false },
            )
        }
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
        SoftTheme {
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
        }
        return
    }

    // Mission-control ticket 16: QUARANTINE - the old always-on NEEDS ATTENTION section, now a
    // drilldown (ticket 12's own CRED drilldown table names it) reached from the SPEND hero's own
    // worded notice rather than a standing section - see `SpendPane`'s doc comment below.
    if (showQuarantine) {
        SoftTheme {
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
        }
        return
    }

    // Mission-control ticket 16: BUDGET - the full category-by-category breakdown, unchanged in
    // content from the old always-inline BudgetSection, now reached one tap in from the BUDGET tile.
    if (showBudget) {
        val month = pnlMonth
        if (month != null) {
            val index = state.pnlMonthsWithData.indexOf(month)
            SoftTheme {
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
                                is com.kevin.legion.ledger.NewCategoryValidation.Invalid ->
                                    addCategoryError = result.reason
                            }
                        }
                    },
                    onBack = { showBudget = false },
                )
            }
        }
        return
    }

    // BALANCES drilldown RETIRED 2026-11-05 (household-finance ticket 14): the Money view's own
    // Accounts section is the account list now, read-only, one line each.

    // Folder/scan/key/mapping are live signals, not part of the async DB
    // load above - merged into a fresh value each recomposition rather than
    // folded back into the remembered `state` var, so this never risks a
    // self-triggered recompose loop over fields that already have their own
    // source of truth.
    SoftTheme {
        val toCategorizeCount = state.pending.size +
            LedgerCategoryResolver.groupPendingGuesses(state.pendingCategoryGuesses).size
        com.kevin.legion.ui.money.MoneyViewContent(
            state = money,
            host = com.kevin.legion.ui.money.MoneyViewHost(
                months = state.pnlMonthsWithData,
                pickedMonth = pnlMonth,
                toCategorizeCount = toCategorizeCount,
                quarantinedCount = state.quarantined.size,
                syncLine = listOfNotNull(readStateLine(state.read, System.currentTimeMillis())?.text, state.syncLine).joinToString(" ").ifBlank { null },
                showSwipeHint = !swipeHintSeen,
                loading = state.loading,
            ),
            callbacks = com.kevin.legion.ui.money.MoneyViewCallbacks(
                onBack = onBack,
                onOpenGroceries = onOpenGroceries,
                onOpenCategorize = { showCategorize = true },
                onOpenCategory = { category -> drilldownCategory = CategoryDrilldownSelection(category) },
                onOpenBudget = { showBudget = true },
                onOpenQuarantine = { showQuarantine = true },
                onSetCurrency = moneyViewModel::setCurrency,
                // A page change moves the picked month, so the month effect above reloads it (the
                // category drilldown and the budget details read the same month), and the engine
                // figures for that month are read.
                onPageMonth = { month ->
                    if (pnlMonth != null && month != pnlMonth && !swipeHintSeen) {
                        swipeHintSeen = true
                        com.kevin.legion.ui.money.MoneySwipeHint.markSeen(context)
                    }
                    pnlMonth = month
                    moneyViewModel.loadMonth(month)
                },
            ),
        )
    }
}
