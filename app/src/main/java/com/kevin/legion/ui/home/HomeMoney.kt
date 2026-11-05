package com.kevin.legion.ui.home

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.kevin.legion.finance.BudgetsWire
import com.kevin.legion.finance.FinanceController
import com.kevin.legion.finance.FinanceRead
import com.kevin.legion.finance.FinanceText
import com.kevin.legion.finance.SafeToSpendWire
import com.kevin.legion.finance.noteOrNull
import com.kevin.legion.finance.valueOrNull
import com.kevin.legion.ui.money.BudgetBoardModel
import com.kevin.legion.ui.money.budgetBoard
import java.time.LocalDate
import java.time.YearMonth
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * What HOME's Money panel and the More sheet's Money card read: this month's budgets only (Kevin,
 * 2026-11-05: "lets manage our daily spending here first"). Safe to spend and net worth left HOME;
 * [safeLine] stays for the Money view's hidden household sections ([com.kevin.legion.ui.money.MoneySections]).
 */
data class HomeMoneyUiState(
    val loading: Boolean = true,
    val today: LocalDate = LocalDate.now(),
    val budgets: FinanceRead<BudgetsWire>? = null,
)

/** The safe-to-spend line: the figure, until when, the top-up when one is needed, and any note. */
data class SafeLineModel(
    /** "-$1,657"; null when there is no figure, and [note] says why. */
    val figure: String?,
    val negative: Boolean = false,
    /** "until Nov 20 pay, expected" or "for 30 days, to Dec 5". */
    val until: String? = null,
    /** "Top up ~$2,000 by Nov 11: below zero." */
    val topUp: String? = null,
    /** A saved copy's age, or why nothing could be read. */
    val note: String? = null,
)

/** The safe-to-spend line from whatever the read gave. Never blank, never a made-up zero. */
// One branch per thing the engine can say; splitting it would scatter one sentence's rules.
@Suppress("CyclomaticComplexMethod", "ReturnCount")
fun safeLine(read: FinanceRead<SafeToSpendWire>?, loading: Boolean): SafeLineModel {
    val body = read?.valueOrNull
    if (body == null) {
        val why = read?.noteOrNull
        return SafeLineModel(
            figure = null,
            note = when {
                why != null -> "Couldn't read safe to spend. $why"
                loading -> "Loading money."
                else -> "Couldn't read safe to spend."
            },
        )
    }
    val savedNote = (read as? FinanceRead.Saved)?.note
    if (!body.available || body.safeCents == null) {
        return SafeLineModel(
            figure = null,
            note = listOfNotNull(body.reason, savedNote).joinToString(" ").ifBlank { null },
        )
    }
    val currency = body.currency ?: "USD"
    val until = FinanceText.parseDay(body.until)?.let { day ->
        if (body.untilReason == "next_income") "until ${FinanceText.day(day)} pay, expected"
        else "for 30 days, to ${FinanceText.day(day)}"
    }
    val topUp = body.topUpNeededCents.takeIf { it > 0 }?.let { needed ->
        val by = FinanceText.parseDay(body.topUpBy)?.let { " by ${FinanceText.day(it)}" }.orEmpty()
        val why = when (body.topUpReason) {
            "below zero" -> ": below zero."
            "below the top-up trigger" -> ": under the 30-day bills trigger."
            else -> "."
        }
        "Top up ~${FinanceText.money(needed, currency)}$by$why"
    }
    return SafeLineModel(
        figure = FinanceText.money(body.safeCents, currency),
        negative = body.safeCents < 0,
        until = until,
        topUp = topUp,
        note = savedNote,
    )
}

/** HOME's panel and the More card: the month's total bar and every category, said the same way. */
fun homeMoneyPanel(state: HomeMoneyUiState): BudgetBoardModel =
    budgetBoard(state.budgets, state.today, loading = state.loading)

/**
 * HOME's money read: this month's budgets, through the one [FinanceController] the Money view and
 * Alfred's `budgets` tool share (ADR 0035), with its saved-copy fallback. `AndroidViewModel` with
 * a Factory like [HomeCalendarViewModel]: Hilt is not in the build yet (CLAUDE.md section 8's
 * migration order).
 */
class HomeMoneyViewModel(
    application: Application,
    private val finance: FinanceController = FinanceController.forApp(application),
) : AndroidViewModel(application) {
    private val _state = MutableStateFlow(HomeMoneyUiState())
    val state: StateFlow<HomeMoneyUiState> = _state.asStateFlow()

    fun refresh() {
        viewModelScope.launch {
            val budgets = finance.budgets(YearMonth.now())
            _state.value = HomeMoneyUiState(loading = false, today = LocalDate.now(), budgets = budgets)
        }
    }

    class Factory(context: Context) : ViewModelProvider.Factory {
        private val app = context.applicationContext as Application

        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = HomeMoneyViewModel(app) as T
    }
}
