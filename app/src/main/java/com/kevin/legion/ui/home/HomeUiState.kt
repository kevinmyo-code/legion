package com.kevin.legion.ui.home

import com.kevin.legion.R
import com.kevin.legion.ledger.BudgetVsActual
import com.kevin.legion.meals.DailyMealGap
import com.kevin.legion.ui.fleet.DueRowView

/**
 * Everything [HomeContent] renders, one [kotlinx.coroutines.flow.StateFlow] from [HomeViewModel]
 * (home-launcher ticket 03, CLAUDE.md sec 8's per-screen-ViewModel rule). `loading = true` is the
 * one state before the first [HomeViewModel.refresh] completes - [HomeContent] renders the grid
 * with every tile's honest empty/loading wording either way, never a blank screen, since ADR 0050
 * makes HOME the phone's only home screen and a blank frame there is a stranded user.
 */
data class HomeUiState(
    val loading: Boolean = true,

    // ---- today card
    val weekdayLabel: String = "",
    val dateLabel: String = "",
    val weatherText: String = "Weather not available yet - no location fix",
    val weatherIconRes: Int = R.drawable.ms_cloud_off,
    val areaAqiLine: String = "Checking the area...",
    val nextLine: String = "Nothing else on the calendar today",
    val chips: TodayChips = TodayChips(dueTodayText = "0 due today", overdueText = null, readFailed = false),

    // ---- tiles
    val checklistCount: Int = 0,
    val budget: BudgetVsActual? = null,
    val mealGap: DailyMealGap = DailyMealGap.NotLogged,
    val hasMealTarget: Boolean = false,
    val maintenanceRows: List<DueRowView> = emptyList(),
    val maintenanceUnknownCount: Int = 0,
    val voiceNotesCount: Int = 0,
)
