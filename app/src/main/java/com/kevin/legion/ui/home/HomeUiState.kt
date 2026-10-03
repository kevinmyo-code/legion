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
 *
 * **Every tile that can THROW carries its own `*Failed` flag (audit finding 1).** Before this, a
 * thrown read for the checklist count, budget, meal gap/target, maintenance schedule or
 * voice-notes count was caught and silently replaced with the same value a genuinely empty state
 * produces (0, `null`, [DailyMealGap.NotLogged], an empty list) - "Couldn't read lists" and "No
 * lists yet" collapsed into one sentence, exactly the "unreadable and empty are different
 * sentences" mistake CLAUDE.md sec 1 already names for the calendar chip. [HomeTileReadingsTest]'s
 * `*TileStatus` functions read these flags first, ahead of every count-based wording.
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
    val listsFailed: Boolean = false,
    val budget: BudgetVsActual? = null,
    val moneyFailed: Boolean = false,
    /** This month per category, combined across accounts ([moneyTileModel]); null until read or on a failed read. */
    val moneyMonth: com.kevin.legion.ledger.CombinedMonthSpend? = null,
    /** [com.kevin.legion.backend.LedgerMirrorStatus.line]: non-null when the last read of the
     * engine's ledger failed, so the figure above is the phone's copy (CLAUDE.md section 7). */
    val moneySyncLine: String? = null,
    val mealGap: DailyMealGap = DailyMealGap.NotLogged,
    val hasMealTarget: Boolean = false,
    val bodyFailed: Boolean = false,
    val maintenanceRows: List<DueRowView> = emptyList(),
    val maintenanceUnknownCount: Int = 0,
    val fleetFailed: Boolean = false,
    val voiceNotesCount: Int = 0,
    val recordingsFailed: Boolean = false,
)
