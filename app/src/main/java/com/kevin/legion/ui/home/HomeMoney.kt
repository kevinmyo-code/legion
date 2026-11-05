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
import com.kevin.legion.finance.NetWorthWire
import com.kevin.legion.finance.SafeToSpendWire
import com.kevin.legion.finance.noteOrNull
import com.kevin.legion.finance.valueOrNull
import com.kevin.legion.ui.money.BarTone
import com.kevin.legion.ui.money.BudgetBarModel
import com.kevin.legion.ui.money.closestToCap
import com.kevin.legion.ui.money.fraction
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.TextStyle
import java.util.Locale
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** What HOME's Money panel and the More sheet's Money card read (household-finance 13 and 14). */
data class HomeMoneyUiState(
    val loading: Boolean = true,
    val today: LocalDate = LocalDate.now(),
    val safe: FinanceRead<SafeToSpendWire>? = null,
    val budgets: FinanceRead<BudgetsWire>? = null,
    val netWorth: FinanceRead<NetWorthWire>? = null,
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

data class HomeMoneyPanelModel(
    val safe: SafeLineModel,
    /** "November: $1,402 of $2,830"; null with no budget read. */
    val monthLine: String?,
    /** "50%", or "no caps set". */
    val monthPercent: String?,
    /** Every capped category, closest to its cap first; the panel shows as many as fit. */
    val bars: List<BudgetBarModel>,
    /** A saved budget copy's age, or why no budget could be read. */
    val budgetNote: String?,
    /** The whole month's spent / cap and pace / cap, for the More card's single bar. */
    val monthFill: Float = 0f,
    val monthPace: Float? = null,
    val monthTone: BarTone = BarTone.NORMAL,
    /** "November budget, 50% used on day 5". */
    val monthUsedLine: String? = null,
)

private fun monthName(month: String?, today: LocalDate): String {
    val ym = month?.let { runCatching { YearMonth.parse(it) }.getOrNull() } ?: YearMonth.from(today)
    return ym.month.getDisplayName(TextStyle.FULL, Locale.ENGLISH)
}

/** The safe-to-spend line from whatever the read gave. Never blank, never a made-up zero. */
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
        return SafeLineModel(figure = null, note = listOfNotNull(body.reason, savedNote).joinToString(" ").ifBlank { null })
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

fun homeMoneyPanel(state: HomeMoneyUiState): HomeMoneyPanelModel {
    val safe = safeLine(state.safe, state.loading)
    val budgets = state.budgets?.valueOrNull
    val budgetNote = when {
        budgets == null && state.budgets != null -> "Couldn't read budgets. ${state.budgets.noteOrNull.orEmpty()}".trim()
        budgets == null -> null
        else -> state.budgets.noteOrNull?.takeIf { it != safe.note }
    }
    if (budgets == null) return HomeMoneyPanelModel(safe, null, null, emptyList(), budgetNote)
    val name = monthName(budgets.month, state.today)
    val total = budgets.total
    val currency = budgets.currency
    val percent = total.percent
    val monthTone = when {
        total.capCents > 0 && total.spentCents > total.capCents -> BarTone.OVER
        percent != null && percent >= NEAR_PERCENT -> BarTone.NEAR
        else -> BarTone.NORMAL
    }
    return HomeMoneyPanelModel(
        safe = safe,
        monthLine = "$name: ${FinanceText.money(total.spentCents, currency)} of ${FinanceText.money(total.capCents, currency)}",
        monthPercent = percent?.let { "$it%" } ?: "no caps set",
        bars = closestToCap(budgets.categories, currency),
        budgetNote = budgetNote,
        monthFill = fraction(total.spentCents, total.capCents),
        monthPace = total.paceCents?.let { fraction(it, total.capCents) }?.takeIf { total.capCents > 0 },
        monthTone = monthTone,
        monthUsedLine = percent?.let { "$name budget, $it% used on day ${budgets.daysElapsed}" }
            ?: "$name budget: no caps set yet",
    )
}

private const val NEAR_PERCENT = 80

/** The More card's net worth: the figure and, in words, whether it is partial or an estimate. */
data class NetWorthGlance(val figure: String?, val tag: String?)

fun netWorthGlance(read: FinanceRead<NetWorthWire>?): NetWorthGlance {
    val body = read?.valueOrNull ?: return NetWorthGlance(null, read?.noteOrNull?.let { "couldn't read" })
    val left = body.missing.size + body.unconverted.size
    val tag = when {
        left > 0 -> "partial: $left account${if (left == 1) "" else "s"} not in it"
        body.totalIsEstimate -> "includes estimates"
        else -> null
    }
    return NetWorthGlance(FinanceText.compact(body.totalCents, body.currency), tag)
}

/**
 * HOME's money reads: safe to spend, this month's budgets and net worth, through the one
 * [FinanceController] the Money view and Alfred's tools share (ADR 0035). `AndroidViewModel` with
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
            val month = YearMonth.now()
            val safe = async { finance.safeToSpend() }
            val budgets = async { finance.budgets(month) }
            val worth = async { finance.netWorth() }
            _state.value = HomeMoneyUiState(
                loading = false,
                today = LocalDate.now(),
                safe = safe.await(),
                budgets = budgets.await(),
                netWorth = worth.await(),
            )
        }
    }

    class Factory(context: Context) : ViewModelProvider.Factory {
        private val app = context.applicationContext as Application

        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = HomeMoneyViewModel(app) as T
    }
}
