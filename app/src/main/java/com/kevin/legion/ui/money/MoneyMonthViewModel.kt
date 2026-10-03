package com.kevin.legion.ui.money

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.kevin.legion.backend.LedgerMirrorStatus
import com.kevin.legion.ledger.AccountMonthSpend
import com.kevin.legion.ledger.LedgerController
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * The Money page's ViewModel (`AndroidViewModel`, no Hilt yet - same as [com.kevin.legion.ui.home.HomeViewModel]).
 * [refresh] runs on ON_RESUME and again when the ledger mirror says Room changed, because the
 * mirror lands rows after the screen has first read.
 *
 * **"This month" is the phone's own calendar month** ([YearMonth.now] in the system zone). The
 * ledger's budget-month boundary is UTC midnight ([com.kevin.legion.ledger.calendarDateOf]: a row's
 * date is its UTC date), so for a few hours around midnight local time the phone's month and the
 * ledger's can disagree about which month a boundary row is in. That only moves a row dated exactly
 * on the 1st or last day; every row still carries its stated date in the drilldown.
 */
class MoneyMonthViewModel(application: Application) : AndroidViewModel(application) {
    private val _state = MutableStateFlow(MoneyMonthUiState(monthTitle = monthTitleFor(currentMonth())))
    val state: StateFlow<MoneyMonthUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            LedgerMirrorStatus.changes.drop(1).collect { refresh() }
        }
    }

    // Any failed read lands on "Couldn't read", never a zero.
    @Suppress("TooGenericExceptionCaught", "SwallowedException") // reason in the line above
    fun refresh() {
        val month = currentMonth()
        viewModelScope.launch {
            try {
                val data = LedgerController.accountMonthResults(getApplication(), month)
                _state.update {
                    it.copy(
                        monthTitle = monthTitleFor(month), month = month, today = LocalDate.now(),
                        loading = false, readFailed = false, accounts = data.accounts,
                        newestOverall = data.newestOverall, newestByAccount = data.newestByAccount,
                    )
                }
            } catch (e: Exception) {
                _state.update { it.copy(monthTitle = monthTitleFor(month), loading = false, readFailed = true) }
            }
        }
    }

    @Suppress("TooGenericExceptionCaught", "SwallowedException") // a failed drilldown read says so in words
    fun openCategory(section: AccountMonthSpend, category: String?) {
        _state.update {
            it.copy(
                drilldown = MoneyDrilldownUiState(section.name, category, section.currency),
            )
        }
        viewModelScope.launch {
            val result = try {
                // The SAME rows the category's figure was summed from: operatingExpenses, narrowed
                // to this account's ids, over the budget month.
                drilldownRowsFrom(
                    LedgerController.categoryTransactions(
                        getApplication(), section.entity, section.month, category, section.accountIds,
                    ),
                )
            } catch (e: Exception) {
                null
            }
            _state.update { s ->
                val open = s.drilldown
                // Ignore a result for a drilldown the person has already left or replaced.
                if (open == null || open.accountName != section.name || open.category != category) s
                else s.copy(
                    drilldown = if (result == null) open.copy(loading = false, failed = true)
                    else open.copy(loading = false, rows = result),
                )
            }
        }
    }

    fun closeDrilldown() {
        _state.update { it.copy(drilldown = null) }
    }

    private companion object {
        fun currentMonth(): YearMonth = YearMonth.now(ZoneId.systemDefault())
    }
}
