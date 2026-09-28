// This file's own doc comment explains why it holds every tile's status builder in one place
// (one-JUnit-testable-mapping-per-tile); that is also why it sits at 15 top-level functions
// against detekt's file-level ceiling of 11. Pre-existing (unrelated to the audit fixes that
// last touched this file - each `failed` parameter is an added default arg, not a new function),
// and splitting it would separate tiles that this file's own doc comment says belong together.
@file:Suppress("TooManyFunctions")

package com.kevin.legion.ui.home

import com.kevin.legion.R
import com.kevin.legion.ledger.BudgetVsActual
import com.kevin.legion.ledger.formatMoney
import com.kevin.legion.location.AirNow
import com.kevin.legion.meals.DailyMealGap
import com.kevin.legion.plan.TrustTier
import com.kevin.legion.service.LiveToolbox
import com.kevin.legion.ui.buildBudgetLineGapRowData
import com.kevin.legion.ui.buildFleetTile
import com.kevin.legion.ui.fleet.DueRowView
import com.kevin.legion.ui.notes.InboxRowView
import com.kevin.legion.ui.weatherLine
import com.kevin.legion.ui.world.aqiLine
import com.kevin.legion.ui.world.areaLine
import com.kevin.legion.ui.world.locationFailureMessage

/**
 * Every HOME tile/today-card status string as a pure function - `ui/HomeScreen.kt`'s thin Composable
 * layer renders these, never computes them inline, so the 2 x 4 grid's wording is one JUnit-testable
 * mapping per tile (home-launcher ticket 03, `HomeContent` = stateless renderer over [HomeUiState]).
 *
 * Trust disclosures (CLAUDE.md §4 rules 5/7) move WITH the figure they describe, as a caution-toned
 * second line - never folded into the hero string, never behind an expander (memory: "trust
 * disclosures are not furniture"). [moneyTileStatus]/[moneyDisclosureLine] and
 * [bodyTileStatus]/[bodyDisclosureLine] are deliberately separate functions for exactly that
 * reason: the composable renders the first unconditionally and the second only when non-null.
 */

/** One tile's rendered status. [alert] picks [com.kevin.legion.ui.theme.soft.SoftColors.alertContainer]/
 * `onAlert` at the call site rather than a plain [com.kevin.legion.ui.theme.soft.SoftColors.text2] -
 * the tile itself decides whether it is breaching, never a colour derived by re-parsing the string. */
data class TileStatus(val text: String, val alert: Boolean = false)

// ---------------------------------------------------------------- Calendar / today card

/** The today card's two chips, and the Calendar tile's own status line - built from the SAME
 * counts, so the two can never disagree (ticket's own instruction: "same due count as the card").
 * [readFailed] renders its own worded chip ("Couldn't read the calendar", `caution`), never a
 * silent "Nothing due" - the calendar-briefing failure (`calendar/OpenerCalendarBriefing.kt`)
 * restated for this card: an unreadable day and a genuinely clear one are different sentences.
 */
data class TodayChips(val dueTodayText: String, val overdueText: String?, val readFailed: Boolean)

fun buildTodayChips(dueTodayCount: Int, overdueCount: Int, calendarReadFailed: Boolean): TodayChips {
    if (calendarReadFailed) {
        return TodayChips(dueTodayText = "Couldn't read the calendar", overdueText = null, readFailed = true)
    }
    val due = if (dueTodayCount == 1) "1 due today" else "$dueTodayCount due today"
    val overdue = when {
        overdueCount <= 0 -> null
        overdueCount == 1 -> "1 overdue"
        else -> "$overdueCount overdue"
    }
    return TodayChips(due, overdue, readFailed = false)
}

/** The Calendar tile's status - the chips restated as one line, or the same failure sentence in
 * `caution` when the chips themselves are the failure state. */
fun calendarTileStatus(chips: TodayChips): TileStatus =
    if (chips.readFailed) {
        TileStatus(chips.dueTodayText, alert = true)
    } else {
        TileStatus(listOfNotNull(chips.dueTodayText, chips.overdueText).joinToString(", "))
    }

/**
 * The today card's "Next: ..." line. [scheduleToday] is the day's EVENT rows (time-ordered, the
 * same stream `CalendarScreen`'s own SCHEDULE section renders); [dueToday] is the day's open
 * TASK/reminder rows (the same [com.kevin.legion.ui.notes.buildInboxRows] stream that screen's own
 * YET TO DO section renders). Both windowed to today by the caller - reading through the identical
 * queries keeps the card and the calendar's day view from ever disagreeing (ticket's own
 * instruction: "built from the same reads the calendar's day view uses").
 *
 * An EVENT outranks a TASK/reminder even if the reminder is sooner - Kevin's calendar carries
 * classes and appointments as events, and "next thing on the calendar" reads as the next thing he
 * has to BE somewhere for, not the next checkbox. `null` `instantMs` never wins a comparison here -
 * every row this function is handed is already day-windowed and dated by construction.
 */
// Pre-existing (unchanged by the audit fixes that last touched this file): three ranked early
// returns - next event, next due task, else the plain "nothing" sentence - read more plainly as
// a fallback chain than folded into one nested expression.
@Suppress("ReturnCount")
fun nextThingLine(nowMs: Long, scheduleToday: List<InboxRowView>, dueToday: List<InboxRowView>): String {
    val nextEvent = scheduleToday
        .filter { (it.instantMs ?: Long.MAX_VALUE) >= nowMs }
        .minByOrNull { it.instantMs ?: Long.MAX_VALUE }
    if (nextEvent != null) {
        val time = nextEvent.dateLabel?.let { " - $it" }.orEmpty()
        return "Next: ${nextEvent.text}$time"
    }
    val nextDue = dueToday
        .filter { !it.done && (it.instantMs ?: Long.MAX_VALUE) >= nowMs }
        .minByOrNull { it.instantMs ?: Long.MAX_VALUE }
    if (nextDue != null) {
        val due = nextDue.dateLabel?.let { " due $it" }.orEmpty()
        return "Next: ${nextDue.text}$due"
    }
    return "Nothing else on the calendar today"
}

/** Which vendored `ms_*` drawable best matches a weather description - picked from the words
 * `Open-Meteo`'s own [com.kevin.legion.weather.WeatherController.describe] hands back, never a
 * second weather-code table. `null`/blank falls to the "no reading" glyph, same posture
 * [weatherLine] holds for the same input. */
fun weatherIconRes(description: String?): Int {
    val d = description?.lowercase().orEmpty()
    return when {
        d.isBlank() -> R.drawable.ms_cloud_off
        "rain" in d || "drizzle" in d || "storm" in d || "thunder" in d -> R.drawable.ms_rainy
        "cloud" in d || "fog" in d || "overcast" in d -> R.drawable.ms_partly_cloudy_day
        else -> R.drawable.ms_sunny
    }
}

/**
 * The today card's area/air-quality line - folds `ui/world/AreaCard.kt`'s three readers
 * ([areaLine], [aqiLine], [locationFailureMessage]) into one line (ticket's own instruction), since
 * the card has room for one line here, not a whole pane. A location failure states ONLY its own
 * message - there is no area to look air quality up for, same rule [AreaCard] itself already holds.
 * [aqi] null (still loading) states so in words rather than a blank second half.
 */
internal fun areaAqiLine(readout: LiveToolbox.LocationReadout, aqi: AirNow.Reading?): String = when (readout) {
    is LiveToolbox.LocationReadout.Available -> {
        val air = aqi?.let { aqiLine(it) } ?: "Checking air quality..."
        "${areaLine(readout)} - $air"
    }
    else -> locationFailureMessage(readout)
}

// ---------------------------------------------------------------- Lists

/** [failed] - the checklist-count read threw (audit finding 1, home-launcher tickets 03/04) - wins
 * over every count-based wording below. A thrown read and a genuinely-empty "No lists yet" are
 * different sentences (CLAUDE.md sec 1's "unreadable and empty"); collapsing them into the same
 * zero was the bug. Worded like [calendarTileStatus]'s own `readFailed` branch: `alert = true`,
 * the app's only existing precedent for a tile-level read failure. */
fun listsTileStatus(checklistCount: Int, failed: Boolean = false): TileStatus {
    if (failed) return TileStatus("Couldn't read lists", alert = true)
    return TileStatus(
        when (checklistCount) {
            0 -> "No lists yet"
            1 -> "1 list"
            else -> "$checklistCount lists"
        },
    )
}

// ---------------------------------------------------------------- Money

/**
 * The Money tile's hero status - over budget and Groceries-over both outrank the plain spend
 * figure (both are "Needs you" breaches restated for a tile, `ui/MeterReadings.kt`'s
 * [com.kevin.legion.ui.buildMeterBreaches] own two conditions, reworded here to the tile's own
 * copy rather than that pane's "over budget by $X" - the wording differs, the arithmetic does not).
 * [failed] (the budget read itself threw, audit finding 1) outranks everything below it - a thrown
 * read is never worded the same as "No spending yet".
 */
fun moneyTileStatus(budget: BudgetVsActual?, failed: Boolean = false): TileStatus {
    if (budget == null) return TileStatus(if (failed) "Couldn't read spending" else "No spending yet", alert = failed)
    val currency = budget.entity.currency
    val targetCents = budget.lines.sumOf { it.gap.target }
    val spentCents = budget.spentCents
    val groceriesLine = budget.lines.firstOrNull { it.category == "Groceries" }
    val groceriesOverCents = groceriesLine
        ?.takeIf { it.gap.target > 0 && it.gap.gap < 0 }
        ?.let { -it.gap.gap }
    return when {
        failed -> TileStatus("Couldn't read spending", alert = true)
        targetCents > 0 && spentCents > targetCents ->
            TileStatus("Over by ${formatMoney(spentCents - targetCents, currency)}", alert = true)
        groceriesOverCents != null ->
            TileStatus("Groceries over by ${formatMoney(groceriesOverCents, currency)}", alert = true)
        targetCents > 0 -> TileStatus("${formatMoney(spentCents, currency)} of ${formatMoney(targetCents, currency)}")
        spentCents > 0 -> TileStatus("${formatMoney(spentCents, currency)} this month")
        else -> TileStatus("No spending yet")
    }
}

/**
 * The Money tile's second line, `caution`-toned - whatever the retired `HomeMeterBands` Money pane
 * disclosed next to this same figure, restated in the tile's own single-line shape (CLAUDE.md §4
 * rules 5/7). Uncategorised spend is EXCLUDED from [BudgetVsActual.spentCents]
 * (`ledger/LedgerBudget.kt`'s own doc comment) - stated here whenever non-zero, never folded into
 * the hero. `unverified` fires whenever any row behind the figure above never cleared the
 * reconciliation gate (`IngestMethod.UNRECONCILED`, surfaced here as [BudgetLine.hasProvisionalRows]/
 * [UncategorizedSpend.hasProvisionalRows] - CLAUDE.md §4 rule 7 forbids a figure asserting a
 * reconciled fact it cannot back). `null` when there is nothing to disclose - a disclosure line
 * that always renders, even at "nothing to say", is furniture, not a disclosure.
 *
 * **"Excludes ... uncategorized", never "+ ... uncategorized" (audit finding 2).** The retired
 * `HomeMeterBands` Money pane said this figure "is NOT counted in spend" - the earlier "+ $X
 * uncategorized" wording here dropped that half of the sentence, leaving a reader to guess whether
 * the figure above already includes it. "Excludes" states the exclusion in the word itself.
 */
fun moneyDisclosureLine(budget: BudgetVsActual?): String? {
    if (budget == null) return null
    val currency = budget.entity.currency
    val parts = mutableListOf<String>()
    if (budget.uncategorized.spentCents > 0L) {
        parts += "Excludes ${formatMoney(budget.uncategorized.spentCents, currency)} uncategorized"
    }
    val unverified = budget.lines.any { it.hasProvisionalRows } || budget.uncategorized.hasProvisionalRows
    if (unverified) parts += "unverified"
    return parts.takeIf { it.isNotEmpty() }?.joinToString(" - ")
}

/** The Groceries line's own tier note (`ledger/LedgerBudget.kt`'s `hasProvisionalRows`/
 * `hasPendingCategoryGuesses`), read through the SAME [buildBudgetLineGapRowData] the retired
 * `HomeMeterBands` Money pane used for this exact sentence - `null` when the month has no
 * Groceries line at all, or that line carries nothing to disclose. */
fun groceriesDisclosureLine(budget: BudgetVsActual?): String? {
    val groceriesLine = budget?.lines?.firstOrNull { it.category == "Groceries" } ?: return null
    return buildBudgetLineGapRowData(groceriesLine, budget.entity.currency).tierNote
}

/** The Money tile has room for one caution line, and this ticket names three things that can each
 * want it (uncategorised present, unverified, the Groceries line's own tier note) - joined rather
 * than one silently winning over another, since all three are independently true facts CLAUDE.md
 * §4 rule 7 requires stated. */
fun moneyTileDisclosure(budget: BudgetVsActual?): String? =
    listOfNotNull(moneyDisclosureLine(budget), groceriesDisclosureLine(budget))
        .takeIf { it.isNotEmpty() }
        ?.joinToString("; ")

// ---------------------------------------------------------------- Body

/** [failed] (the meal-gap/target read itself threw, audit finding 1) outranks every other wording -
 * a thrown read must never collapse into "No calorie target", which reads as a genuinely unset
 * target rather than a read the app could not perform. */
fun bodyTileStatus(mealGap: DailyMealGap, hasMealTarget: Boolean, failed: Boolean = false): TileStatus {
    if (failed) return TileStatus("Couldn't read meals", alert = true)
    return when (mealGap) {
        DailyMealGap.NotLogged -> TileStatus(if (hasMealTarget) "Nothing logged today" else "No calorie target")
        is DailyMealGap.Logged -> {
            val actual = groupThousands(mealGap.gap.actual.caloriesKcal)
            val target = groupThousands(mealGap.gap.target.caloriesKcal)
            TileStatus("$actual of $target kcal")
        }
    }
}

/** A meal's macros are an LLM estimate from what was told to it, never a measurement a receipt or
 * a scale stated (CLAUDE.md §4 rule 5) - stated beside the figure whenever [DailyMealGap.Logged]'s
 * own [TrustTier] says so, `null` for an unlogged day (nothing to disclose about a day with
 * nothing measured or estimated). */
fun bodyDisclosureLine(mealGap: DailyMealGap): String? {
    val logged = mealGap as? DailyMealGap.Logged ?: return null
    return if (logged.gap.tier == TrustTier.REPORTED) "estimated, not measured" else null
}

/** Digits grouped in threes (thousands, millions, ...) - the western grouping [formatMoney]/every
 * kcal figure on this tile already uses. */
private const val THOUSANDS_GROUP_SIZE = 3

private fun groupThousands(value: Int): String =
    kotlin.math.abs(value).toString().reversed().chunked(THOUSANDS_GROUP_SIZE).joinToString(",").reversed()
        .let { if (value < 0) "-$it" else it }

// ---------------------------------------------------------------- Fleet

/**
 * The Fleet tile's status - overdue outranks everything (an alert), then whatever
 * [com.kevin.legion.ui.buildFleetTile] already computes for a schedule that exists, then the two
 * silent-schedule states named in the ticket's own table ("No maintenance schedule" /
 * "Mileage unknown") - [buildFleetTile] words the all-unknown-and-nothing-else case differently
 * ("0 due - N unknown"), so that one case is worded here instead of delegated. [failed] (the
 * maintenance-schedule read itself threw, audit finding 1) outranks all of it - a thrown read must
 * never collapse into "No maintenance schedule", which reads as a genuinely empty schedule.
 */
fun fleetTileStatus(rows: List<DueRowView>, unknownCount: Int, failed: Boolean = false): TileStatus {
    val overdueCount = rows.count { it.overdue }
    return when {
        failed -> TileStatus("Couldn't read maintenance", alert = true)
        overdueCount > 0 -> TileStatus(if (overdueCount == 1) "1 overdue" else "$overdueCount overdue", alert = true)
        rows.isNotEmpty() -> TileStatus(buildFleetTile(rows, unknownCount).caption)
        unknownCount > 0 -> TileStatus("Mileage unknown")
        else -> TileStatus("No maintenance schedule")
    }
}

// ---------------------------------------------------------------- Recordings

/** [failed] (the voice-notes count read itself threw, audit finding 1) outranks the count - a
 * thrown read must never collapse into "0 saved", which reads as a genuinely empty list. Recording
 * still wins over a failed COUNT read (the live recording-state flow is independent of this read,
 * [HomeScreen]'s own doc comment on the two separately-polled flows), so a mic that is actively
 * recording never hides behind a stale count failure. */
fun recordingsTileStatus(voiceNotesCount: Int, recording: Boolean, failed: Boolean = false): TileStatus = when {
    recording -> TileStatus("Recording", alert = true)
    failed -> TileStatus("Couldn't read recordings", alert = true)
    voiceNotesCount == 1 -> TileStatus("1 saved")
    else -> TileStatus("$voiceNotesCount saved")
}
