package com.kevin.legion.ui.money

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.kevin.legion.data.local.IngestMethod
import com.kevin.legion.finance.BudgetsWire
import com.kevin.legion.finance.CashFlowWire
import com.kevin.legion.finance.FinanceAccountWire
import com.kevin.legion.finance.FinanceController
import com.kevin.legion.finance.FinanceRead
import com.kevin.legion.finance.NetWorthWire
import com.kevin.legion.finance.SafeToSpendWire
import com.kevin.legion.ledger.LedgerController
import com.kevin.legion.ledger.LedgerEntity
import com.kevin.legion.ledger.calendarDateOf
import com.kevin.legion.ledger.displayDescription
import java.time.LocalDate
import java.time.YearMonth
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** One spend row of a month in the Money view's transactions section. */
data class MoneyTxnRow(
    val id: Long,
    val date: LocalDate,
    val description: String,
    /** Signed cents as the bank states them (spend negative). */
    val cents: Long,
    val currency: String,
    val category: String?,
    /** Logged by voice, not yet on a statement: said "pending". */
    val pending: Boolean,
    /** UNRECONCILED: said "current period" (CLAUDE.md section 4 rule 7). */
    val currentPeriod: Boolean,
)

/** One month's page: its budgets and cash flow from the engine, its spend rows from the phone. */
data class MoneyMonthPage(
    val budgets: FinanceRead<BudgetsWire>? = null,
    val cashFlow: FinanceRead<CashFlowWire>? = null,
    /** Null while loading. */
    val transactions: List<MoneyTxnRow>? = null,
    val transactionsFailed: Boolean = false,
)

/**
 * The Money view's state (household-finance ticket 14). The figures that are about TODAY (safe to
 * spend, net worth, the account list) are read once; the figures that are about a MONTH are read per
 * page as the pager reaches it.
 */
data class MoneyViewUiState(
    val today: LocalDate = LocalDate.now(),
    val safe: FinanceRead<SafeToSpendWire>? = null,
    val netWorth: FinanceRead<NetWorthWire>? = null,
    val accounts: FinanceRead<List<FinanceAccountWire>>? = null,
    val pages: Map<YearMonth, MoneyMonthPage> = emptyMap(),
    /** The This month card's view currency: USD, or SGD at the engine's stated rate. */
    val currency: String = "USD",
)

/**
 * The Money view's engine reads, through the one [FinanceController] HOME and Alfred's tools share
 * (ADR 0035). `AndroidViewModel` + Factory: Hilt is not in the build yet.
 */
class MoneyViewModel(
    application: Application,
    private val finance: FinanceController = FinanceController.forApp(application),
) : AndroidViewModel(application) {
    private val _state = MutableStateFlow(MoneyViewUiState())
    val state: StateFlow<MoneyViewUiState> = _state.asStateFlow()

    /** Today's figures, and every month already on screen again. */
    fun refresh() {
        // Hidden household sections ([MoneySections]) read nothing: their figures are not on screen.
        if (MoneySections.SHOW_HOUSEHOLD) viewModelScope.launch {
            val safe = async { finance.safeToSpend() }
            val worth = async { finance.netWorth() }
            val accounts = async { finance.accounts() }
            val s = safe.await()
            val w = worth.await()
            val a = accounts.await()
            _state.update { it.copy(today = LocalDate.now(), safe = s, netWorth = w, accounts = a) }
        }
        _state.value.pages.keys.forEach { loadMonth(it, force = true) }
    }

    /** [month]'s page, read once unless [force]. A page change reads the month it landed on. */
    fun loadMonth(month: YearMonth, force: Boolean = false) {
        if (!force && _state.value.pages[month] != null) return
        _state.update { it.copy(pages = it.pages + (month to (it.pages[month] ?: MoneyMonthPage()))) }
        viewModelScope.launch {
            val budgets = async { finance.budgets(month) }
            val cash = async { if (MoneySections.SHOW_HOUSEHOLD) finance.cashFlow(month) else null }
            val rows = async { runCatching { transactions(month) }.getOrNull() }
            val txns = rows.await()
            val page = MoneyMonthPage(budgets.await(), cash.await(), txns, transactionsFailed = txns == null)
            _state.update { it.copy(pages = it.pages + (month to page)) }
        }
    }

    fun setCurrency(currency: String) {
        _state.update { it.copy(currency = currency) }
    }

    private suspend fun transactions(month: YearMonth): List<MoneyTxnRow> {
        val app = getApplication<Application>()
        val aliases = LedgerController.merchantAliases(app)
        return LedgerController.monthOperatingExpenses(app, LedgerEntity.US, month)
            .sortedByDescending { it.txnDate }
            .map {
                MoneyTxnRow(
                    id = it.id,
                    date = calendarDateOf(it),
                    description = displayDescription(it.description, aliases),
                    cents = it.amountCents,
                    currency = it.currency.name,
                    category = it.category,
                    pending = it.pendingLoggedAt != null,
                    currentPeriod = it.ingestMethod == IngestMethod.UNRECONCILED,
                )
            }
    }

    class Factory(context: Context) : ViewModelProvider.Factory {
        private val app = context.applicationContext as Application

        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = MoneyViewModel(app) as T
    }
}

/** Whether the person has swiped the Money view once; until then it says "Swipe for other months". */
object MoneySwipeHint {
    private const val PREFS = "money_view"
    private const val KEY = "swipe_hint_seen"

    fun seen(context: Context): Boolean =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY, false)

    fun markSeen(context: Context) {
        context.applicationContext.getSharedPreferences(
            PREFS,
            Context.MODE_PRIVATE,
        ).edit().putBoolean(KEY, true).apply()
    }
}
