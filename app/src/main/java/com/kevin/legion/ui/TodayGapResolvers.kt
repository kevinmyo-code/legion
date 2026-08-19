package com.kevin.legion.ui

import com.kevin.legion.advisor.AdvisorAspect
import com.kevin.legion.data.local.BodyweightLog
import com.kevin.legion.data.local.DailyDriveLog
import com.kevin.legion.data.local.Goal
import com.kevin.legion.data.local.IngestedFile
import com.kevin.legion.data.local.LedgerCurrency
import com.kevin.legion.ledger.AccountBalance
import com.kevin.legion.ledger.BudgetLine
import com.kevin.legion.ledger.BudgetVsActual
import com.kevin.legion.ledger.UncategorizedSpend
import com.kevin.legion.ledger.formatMoney
import com.kevin.legion.ledger.maskedAccountLabel
import com.kevin.legion.meals.DailyMealGap
import com.kevin.legion.meals.MacroTotals
import com.kevin.legion.meals.dayEndEpoch
import com.kevin.legion.meals.dayStartEpoch
import com.kevin.legion.plan.PlanGap
import com.kevin.legion.plan.TrustTier
import com.kevin.legion.sleep.SleepGap
import com.kevin.legion.sleep.formatMinutesAsHours
import com.kevin.legion.ui.common.GapRowData
import com.kevin.legion.ui.common.GapSign
import com.kevin.legion.ui.fleet.DriveSummaryView
import com.kevin.legion.ui.fleet.DueRowView
import com.kevin.legion.util.compactDate
import com.kevin.legion.util.documentDateCompact
import java.time.YearMonth
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.abs

/**
 * Pure `PlanGap`/domain-model -> [GapRowData] mappers for [TodayScreen] and [BodyScreen]. Kept
 * Compose-free on purpose - same "pure builder, thin Composable wrapper" split
 * [com.kevin.legion.ledger.buildBudgetVsActual]/[com.kevin.legion.workouts.buildWeeklyWorkoutGap]
 * already use - so the sign/caption/tier-note branching below is a plain JUnit test
 * (`TodayGapResolversTest`), never a Robolectric one.
 *
 * Money stays `Long` cents all the way to [formatMoney] (CLAUDE.md §4 rule 3); nothing here does
 * arithmetic on a `Double`.
 */

/** D10: budget minus spent, plain subtraction. `gap.gap` positive means room left, negative means over. */
fun buildBudgetLineGapRowData(line: BudgetLine, currency: LedgerCurrency): GapRowData {
    val remaining = line.gap.gap >= 0
    return GapRowData(
        label = line.category,
        actualOverTarget = "${formatMoney(line.gap.actual, currency)} of ${formatMoney(line.gap.target, currency)}",
        gapValue = formatMoney(line.gap.gap, currency),
        gapCaption = if (remaining) "remaining" else "over",
        sign = if (remaining) GapSign.GOOD else GapSign.BAD,
        tier = line.gap.tier,
        tierNote = budgetLineTierNote(line.hasProvisionalRows, line.hasPendingCategoryGuesses),
    )
}

/** Mirrors [com.kevin.legion.ui.ledger.BudgetSection]'s `provisionalLabel` wording exactly, so the two screens never disagree about why a figure is REPORTED. */
private fun budgetLineTierNote(hasProvisionalRows: Boolean, hasPendingCategoryGuesses: Boolean): String? = when {
    hasProvisionalRows && hasPendingCategoryGuesses ->
        "includes pending transactions not yet on a statement, and a category LEGION guessed, not yet confirmed"
    hasProvisionalRows -> "includes pending transactions not yet on a statement"
    hasPendingCategoryGuesses -> "includes a category LEGION guessed, not yet confirmed"
    else -> null
}

/**
 * D11: uncategorised spend's own loud bucket, rendered through the same [GapRowData] shape rather
 * than a bespoke row - there is no target here (nothing to be "over" or "under"), so [GapSign] is
 * always [GapSign.NEUTRAL] and [GapRowData.gapCaption] says why in words instead of implying one.
 */
fun buildUncategorizedGapRowData(uncategorized: UncategorizedSpend, currency: LedgerCurrency): GapRowData =
    GapRowData(
        label = "Uncategorised",
        actualOverTarget = "not assigned to a category - not counted in spend, and in no budget line above",
        gapValue = formatMoney(uncategorized.spentCents, currency),
        gapCaption = "spent",
        sign = GapSign.NEUTRAL,
        tier = if (uncategorized.hasProvisionalRows) TrustTier.REPORTED else TrustTier.PROVEN,
        tierNote = if (uncategorized.hasProvisionalRows) "includes pending transactions not yet on a statement" else null,
    )

/** D24: sessions done versus sessions planned, this week. `gap.gap` positive means behind, negative/zero means caught up or ahead. */
fun buildWeeklyWorkoutGapRowData(gap: PlanGap<Int>): GapRowData {
    val behind = gap.gap > 0
    return GapRowData(
        label = "Workouts this week",
        actualOverTarget = "${gap.actual} of ${gap.target} sessions",
        gapValue = abs(gap.gap).toString(),
        gapCaption = if (behind) "sessions behind" else "sessions ahead",
        sign = if (behind) GapSign.BAD else GapSign.GOOD,
        tier = gap.tier,
        tierNote = if (gap.tier == TrustTier.REPORTED) "you logged these, not independently verified" else null,
    )
}

/**
 * D27/D28: only ever called for a LOGGED day - [com.kevin.legion.meals.DailyMealGap.NotLogged]
 * is a completely separate render path ([TodayScreen]'s [com.kevin.legion.ui.common.GapEmptyRow])
 * so a day with nothing logged can never reach this function and be coerced into a zero-actual row.
 * Calories only, as the one headline figure - GapRow is a one-line-per-thing panel (this file's
 * doc comment), and the target's own macro breakdown is what [BodyScreen]'s recent-meals list is
 * for, not this row.
 */
fun buildDailyMealGapRowData(gap: PlanGap<MacroTotals>): GapRowData {
    val remainingKcal = gap.gap.caloriesKcal
    val over = remainingKcal < 0
    return GapRowData(
        label = "Calories today",
        actualOverTarget = "${gap.actual.caloriesKcal} of ${gap.target.caloriesKcal} kcal",
        gapValue = abs(remainingKcal).toString(),
        gapCaption = if (over) "kcal over" else "kcal left",
        sign = if (over) GapSign.BAD else GapSign.GOOD,
        tier = gap.tier,
        tierNote = if (gap.tier == TrustTier.REPORTED) "estimated from what you told me, not measured" else null,
    )
}

/**
 * Sleep (Kevin, 2026-08-07): last night's minutes against the nightly target, `gap.gap` positive
 * means short of target, negative/zero means met or exceeded it. Mirrors [buildWeeklyWorkoutGapRowData]'s
 * shape exactly - same REPORTED-always tier note, since nothing external ever verifies sleep (see
 * [com.kevin.legion.sleep.SleepGap]'s own doc comment).
 */
fun buildSleepGapRowData(gap: PlanGap<Int>): GapRowData {
    val short = gap.gap > 0
    return GapRowData(
        label = "Sleep last night",
        actualOverTarget = "${formatMinutesAsHours(gap.actual)} of ${formatMinutesAsHours(gap.target)}",
        gapValue = formatMinutesAsHours(abs(gap.gap)),
        gapCaption = if (short) "short of target" else "over target",
        sign = if (short) GapSign.BAD else GapSign.GOOD,
        tier = gap.tier,
        tierNote = if (gap.tier == TrustTier.REPORTED) "you logged this, not independently verified" else null,
    )
}

/**
 * D29-D32: [DueRowView] already resolved the whichever-comes-first-miles-or-date gap and its own
 * OVERDUE/upcoming wording ([com.kevin.legion.ui.fleet.buildDueRows]) - this only re-shapes those
 * already-formatted strings into [GapRowData]'s four slots, it does not recompute anything.
 * `tier` stays [TrustTier.PROVEN] with no note: [com.kevin.legion.data.local.MaintenanceItem]
 * carries no [TrustTier] column of its own (see that entity's doc comment) and inventing a claim
 * this file cannot verify would be worse than saying nothing - a REASONED simplification, not a
 * traced fact about the schedule's provenance.
 */
/**
 * The Notes summary line on [TodayScreen] (ticket 07: "today's timed items and anything due, as a
 * summary that links into Notes. It does not become a second list editor... the same item never
 * renders in two different shapes") - counts only, never the items themselves, which is what keeps
 * this a summary rather than a second agenda. [dueTodayCount] is today's timed items (one-off,
 * already in the day's window, plus any recurring occurrence landing today); [missedCount] is
 * ticket 12's "reported, never silent" MISSED backlog, which is arguably MORE urgent than anything
 * still due today, so it is named first when both are non-zero.
 */
fun notesSummaryMessage(dueTodayCount: Int, missedCount: Int): String = when {
    missedCount > 0 && dueTodayCount > 0 -> "$missedCount missed, $dueTodayCount due today"
    missedCount > 0 -> if (missedCount == 1) "1 missed reminder" else "$missedCount missed reminders"
    dueTodayCount > 0 -> if (dueTodayCount == 1) "1 due today" else "$dueTodayCount due today"
    else -> "Nothing due today"
}

fun buildMaintenanceGapRowData(row: DueRowView): GapRowData =
    GapRowData(
        label = row.label,
        actualOverTarget = row.sub,
        gapValue = row.value,
        // No caption. `row.value` already reads "in 7,400 miles", so "away"
        // added nothing and, being right-aligned under a long value, wrapped to
        // its own orphaned line on device (observed 2026-08-07). The word "in"
        // carries the future tense on its own.
        gapCaption = "",
        sign = if (row.overdue) GapSign.BAD else GapSign.NEUTRAL,
        tier = TrustTier.PROVEN,
    )

// ------------------------------------------------------------ ticket 15: HOME

/**
 * One timed item landing today, deck home's AGENDA pane (ticket 15/06 answer #2). Built by
 * [TodayScreen]'s `LaunchedEffect` from the SAME two [com.kevin.legion.notes.NotesController]
 * reads its old NOTES-row count already made (`timedItemsInWindow` for one-offs,
 * `allRecurringItems` + `skippedDates` + [com.kevin.legion.notes.Recurrence.occurrencesInWindow]
 * for recurrences) - the count used to be discarded, this keeps the item text and the resolved
 * occurrence instant instead of collapsing straight to a number. No new query.
 *
 * [source] joined at ticket 13 (`.scratch/google-account-integration/issues/13-calendar-read.md`):
 * a second producer, [com.kevin.legion.ui.notes.mergeAgenda], now appends
 * [com.kevin.legion.calendar.CalendarProvider.GoogleCalendarEvent] rows over the same window,
 * tagged [AgendaSource.GOOGLE]. Defaults to [AgendaSource.LOCAL] so every pre-existing call site
 * that only ever produced local rows keeps compiling and keeps reading as local.
 */
data class AgendaEntry(val label: String, val timeMs: Long, val allDay: Boolean, val source: AgendaSource = AgendaSource.LOCAL)

/**
 * Where one [AgendaEntry] came from - ticket 13 point 4: "a Google event must be distinguishable
 * from a LEGION reminder in WORDS, not by colour alone." [GOOGLE] rows get an explicit `CAL` tag
 * (`TodayScreen.kt`'s `AgendaRow`); [LOCAL] rows stay tagless, the pane's existing silent-is-strong
 * posture. Deliberately NOT a third "overdue/needs you" red state here: `DeckTagStyle`'s own doc
 * comment reserves red exclusively for [com.kevin.legion.ui.common.QuarantineTag] in the ALERTS
 * pane, and this screen's AGENDA pane has never used it either (see the AGENDA summary line's own
 * comment on this file) - ticket 13's "red stays reserved for a LEGION reminder that needs Kevin"
 * is read here as "IF this pane ever turns red, it may only mean that", not as a mandate to
 * introduce red into a pane that has deliberately never carried it.
 */
enum class AgendaSource { LOCAL, GOOGLE }

/**
 * Fraction of the local day already elapsed at [nowMs] - the [com.kevin.legion.ui.common.DeckMeter]
 * `paceFraction` tick on the INTAKE hero (ticket 06 answer #1: "meter with pace tick"). Reuses
 * [dayStartEpoch]/[dayEndEpoch] rather than a fixed 24h divisor, so a DST-shift day (23h or 25h
 * locally) still reads correctly instead of the tick landing a few minutes off true local noon -
 * same reasoning [dayEndEpoch]'s own doc comment gives for computing by calendar date, not by
 * adding milliseconds.
 */
fun dayElapsedFraction(nowMs: Long): Float {
    val start = dayStartEpoch(nowMs)
    val end = dayEndEpoch(nowMs)
    if (end <= start) return 0f
    return ((nowMs - start).toFloat() / (end - start).toFloat()).coerceIn(0f, 1f)
}

// ------------------------------------------------------- mission-control ticket 16: HALF tiles

/**
 * **SYSTEMS SWEEP is dissolved (mission-control ticket 11/16).** Its four per-domain [DeckRow]s
 * (SLEEP / TRAINING WK / LEDGER / FLEET) are gone, and with them `DeckSweepRow` and every
 * `build*SweepRow` function - grep-confirmed nothing outside this file and `TodayScreen.kt` ever
 * read them. What replaces the pane is four [com.kevin.legion.ui.common.DeckPane] HALF tiles, one
 * per non-HOME aspect (BIO/CRED/FLEET/LOG), matching [com.kevin.legion.advisor.AdvisorAspect]'s own
 * four-aspect vocabulary rather than the old sweep's ad hoc SLEEP/TRAINING split. **BIO's tile is
 * bodyweight, not sleep or training** - ticket 11's inventory picked "one figure, one qualifier",
 * the exact half-tile shape, and mass is that figure; SLEEP and TRAINING WK are not shown anywhere
 * on HOME any more (still live in `BodyScreen`'s own drilldown, unaffected by this ticket).
 *
 * Every `build*Tile` function below returns a plain `hero`/`caption` pair, never a Composable - the
 * same pure-builder/thin-wrapper split every function above already follows, so a half tile's
 * content is one more JUnit-testable mapping, not new logic wedged into `TodayScreen.kt`. `hero` is
 * checked against ticket 05's 7-character half-tile limit at the CALL SITE
 * (`TodayScreen.kt`'s `HalfTileHero`), because the limit is a rendering-size decision, not a string
 * fact these pure functions should own.
 */

/**
 * BIO tile: latest bodyweight in kg plus a compact UP/DOWN/FLAT trend word. Mirrors
 * [com.kevin.legion.advisor.digest.HomeDigestBuilder.bioHeadline]'s own kg-normalisation and
 * 4-week lookback comparison (same 0.2kg flat threshold as that function's own
 * `BODYWEIGHT_FLAT_THRESHOLD_KG`, restated here as [BIO_TREND_FLAT_THRESHOLD_KG] rather than
 * imported because that function is `internal` to the `advisor.digest` package and returns a
 * full advisor sentence, not a hero/caption pair a HALF tile can render at 30sp) - never a second
 * DAO shape, the same [BodyweightLog.mostRecent]/`forWindow` reads [TodayScreen]'s `LaunchedEffect`
 * already makes for this tile.
 */
data class BioTileData(val hero: String, val caption: String)

private const val BIO_TREND_FLAT_THRESHOLD_KG = 0.2

fun buildBioTile(latest: BodyweightLog?, lookback: List<BodyweightLog>): BioTileData {
    if (latest == null) return BioTileData(hero = "NOT LOGGED", caption = "no weigh-ins yet")
    val latestKg = toKgForTile(latest.weightValue, latest.weightUnit)
    val priorAvgKg = lookback.takeIf { it.isNotEmpty() }?.map { toKgForTile(it.weightValue, it.weightUnit) }?.average()
    val trend = when {
        priorAvgKg == null -> ""
        abs(latestKg - priorAvgKg) < BIO_TREND_FLAT_THRESHOLD_KG -> "FLAT"
        latestKg < priorAvgKg -> "DOWN"
        else -> "UP"
    }
    val hero = "%.1f".format(latestKg)
    val caption = if (trend.isEmpty()) "KG" else "KG - $trend 4WK"
    return BioTileData(hero = hero, caption = caption)
}

private fun toKgForTile(value: Double, unit: String): Double = if (unit.equals("lbs", ignoreCase = true)) value * 0.45359237 else value

/**
 * A whole-unit money figure for a HALF-tile hero - `formatMoney`'s own `"USD 2,418.00"` is both too
 * long (10-12 characters against the 7-character limit) and carries no `$`/`S$` glyph a glance
 * reads faster than a currency code. Rounds to the nearest whole unit (never truncates down, which
 * would silently under-report spend by up to 99 cents) - this is a DISPLAY rounding for the hero
 * glyph only, the underlying figure everywhere else in the app stays exact `Long` cents (CLAUDE.md
 * §4 rule 3), this function never feeds back into a stored or reconciled value.
 */
fun compactMoneyHero(cents: Long, currency: LedgerCurrency): String {
    val symbol = when (currency) {
        LedgerCurrency.USD -> "$"
        LedgerCurrency.SGD -> "S$"
    }
    val roundedWhole = (abs(cents) + 50) / 100
    val sign = if (cents < 0) "-" else ""
    val grouped = roundedWhole.toString().reversed().chunked(3).joinToString(",").reversed()
    return "$sign$symbol$grouped"
}

/**
 * CRED tile: this month's spend ([BudgetVsActual.spentCents] - categorised lines only, the
 * uncategorised bucket excluded per Kevin 2026-08-15, see [UncategorizedSpend]'s own doc comment),
 * against its target when one is set. Silent (no budget AND no spend at all this month) reads
 * `NOT LOGGED`, mirroring [com.kevin.legion.advisor.digest.HomeDigestBuilder.credHeadline]'s own
 * silent branch - and that emptiness test reads [BudgetVsActual.allOperatingSpendCents], not
 * [BudgetVsActual.spentCents], so a month holding only uncategorised rows reads an honest `$0`
 * with the exclusion stated beside it rather than the much stronger "nothing was imported".
 *
 * **The exclusion's own words are NOT in [caption]** - a HALF tile's caption is one ellipsised
 * line, so a sentence appended there would be silently truncated, which is worse than not stating
 * it. [com.kevin.legion.ui.TodayScreen]'s CRED tile renders
 * [com.kevin.legion.ledger.uncategorizedExcludedSentence] in the tile's own `extra` slot instead,
 * and [com.kevin.legion.ui.LedgerScreen]'s full-width SPEND pane states it under the chart.
 *
 * **The incomplete-month caption is a DATE, not a warning (2026-08-18, Kevin).** It used to read
 * `COVERAGE GAP` for any `!budget.isComplete` month - true, but not what he was asking. He already
 * knows the month is not over; what he wants off a HALF tile is how much he's used SO FAR, as of
 * when he actually extracted the statements. `"THROUGH AUG 12"` answers that directly: it is the
 * minimum, across every account in [budget.coverage], of that account's own
 * [com.kevin.legion.ledger.AccountCoverage.coveredThroughMs] - the MINIMUM because the figure above
 * it is a SUM across accounts, and a sum is only good through the point every one of its addends
 * is, the same reasoning [coverageSentence][com.kevin.legion.ui.ledger.coverageSentence] already
 * applies per-account, one level up. When any account's `coveredThroughMs` is null (it never even
 * reaches the month's own start - the empty-[budget.coverage] case included, since `mapNotNull`
 * over nothing is nothing) there is no honest date to print, so the caption says so in words
 * instead (`NOT COVERED YET`) rather than silently falling back to a date that would read as
 * current. This is a REWORDING, not a loosening: `!budget.isComplete` still gates the branch,
 * still CLAUDE.md §4 rule 7 disclosure, just phrased as the answer to "how much so far" instead of
 * a bare warning he'd already parsed.
 */
data class CredTileData(val hero: String, val caption: String)

fun buildCredTile(budget: BudgetVsActual?, monthLabel: String): CredTileData {
    if (budget == null) return CredTileData(hero = "...", caption = "loading")
    if (budget.lines.isEmpty() && budget.allOperatingSpendCents == 0L) {
        return CredTileData(hero = "NOT LOGGED", caption = "no spend $monthLabel")
    }
    val spentCents = budget.spentCents
    val targetCents = budget.lines.sumOf { it.gap.target }
    val hero = compactMoneyHero(spentCents, budget.entity.currency)
    // **One caption, always** (Kevin, 2026-08-18): "i dont need to know not covered yet. remove
    // that line... 2 figures, 2 subtitles: $847 spent so far, $1208 in debit account. thats it."
    //
    // The THROUGH <date> / NOT COVERED YET wording this replaces lasted a matter of hours, and it
    // was not wrong - it was redundant HERE. He knows the month is not over. The coverage dates
    // still reach him where there is room to explain them: LedgerScreen's SPEND pane
    // (coverageSentence, per-account), and CredDigestBuilder's voice line. This tile is a glance
    // surface beside BIO, and a glance surface that spends a line restating what its owner already
    // knows has spent the only line it had.
    //
    // [targetCents] is deliberately unused now - the "OF $X AUG" budget comparison went with the
    // same instruction. Left computed above so the diff is honest about what was dropped rather
    // than quietly deleting the concept; delete it if a second surface never wants it back.
    return CredTileData(hero = hero, caption = "spent so far")
}

/**
 * The `THROUGH <DATE>` / `NOT COVERED YET` half of [buildCredTile] - split out so
 * [TodayGapResolversTest] can exercise the minimum-across-accounts and no-honest-date branches
 * directly. See [buildCredTile]'s own doc comment for the reasoning; this is only the mechanics.
 */
private fun coverageThroughCaption(budget: BudgetVsActual): String {
    val throughs = budget.coverage.map { it.coveredThroughMs }
    // Every account must have a real through-date, or the minimum across them would be a lie -
    // one account that never reaches the month's own start means the SUM above is missing that
    // account entirely from day one, and no date can honestly describe that.
    if (throughs.isEmpty() || throughs.any { it == null }) return "NOT COVERED YET"
    val throughMs = throughs.filterNotNull().min()
    return "THROUGH ${documentDateCompact(throughMs).uppercase(Locale.ENGLISH)}"
}

/**
 * CRED tile's balance line (2026-08-18, Kevin: "no need for the line graph. just how much I've
 * used so far and what's the balance"). LEGION cannot tell a cash account from a card, so rather
 * than guess it renders exactly ONE account - whichever [nominatedAccountId] names
 * ([LedgerNominatedAccountPreferences]) - and its own [AccountBalance.asOfMs] date, never a
 * silently-picked default and never a fallback to a different account when the nominated one goes
 * missing (CLAUDE.md §4 rule 7: a disclosure gap is stated in words, never left blank).
 *
 * [balances] is expected to already be [com.kevin.legion.ledger.groupAccountBalances]'d by the
 * caller - same "grouping is a render-site concern" discipline [groupAccountBalances]'s own doc
 * comment states, restated here rather than grouping a second time inside this function.
 *
 * Four distinct silent/partial states, each its own sentence, never collapsed:
 *  - [nominatedAccountId] null/blank -> nothing picked yet, says so and where to fix it.
 *  - not present in [balances] (renamed folder, purged rows) -> says so plainly, never falls back
 *    to another account in the list.
 *  - present but [AccountBalance.balanceCents] null -> the exact words
 *    [com.kevin.legion.ui.ledger.LedgerRows]' `BalancesSection` already uses for this state
 *    (Bank of America's card layout, which never prints a running balance at all) - one phrasing,
 *    reused, never invented twice.
 *  - present, a real balance, but [AccountBalance.asOfMs] null -> [secondary] is null; the tile
 *    never prints "as of" against a date it does not have.
 *
 * [isAdvisory] is `true` for every branch except the normal figure-plus-date one, so the thin
 * Composable wrapper can pick a colour off this field directly rather than re-deriving "is this a
 * problem" from the string it's about to print.
 */
data class CredBalanceLine(val primary: String, val secondary: String?, val isAdvisory: Boolean)

fun buildCredBalanceLine(balances: List<AccountBalance>, nominatedAccountId: String?): CredBalanceLine {
    if (nominatedAccountId.isNullOrBlank()) {
        return CredBalanceLine(
            primary = "NO ACCOUNT NOMINATED",
            secondary = "set one in Money",
            isAdvisory = true,
        )
    }
    val balance = balances.firstOrNull { it.accountId == nominatedAccountId }
    if (balance == null) {
        return CredBalanceLine(
            primary = "${maskedAccountLabel(nominatedAccountId)} NOT FOUND",
            secondary = "renamed or no longer seen - pick a different account in Money",
            isAdvisory = true,
        )
    }
    val label = maskedAccountLabel(balance.accountId)
    val balanceCents = balance.balanceCents
    if (balanceCents == null) {
        // Same words BalancesSection already uses for this exact state - see this
        // function's own doc comment for why that reuse is deliberate, not laziness.
        return CredBalanceLine(primary = label, secondary = "no balance ever printed for this account", isAdvisory = true)
    }
    val amount = compactMoneyHero(balanceCents, balance.currency)
    // "$1208" / "in debit account" - Kevin's own words for the shape he wants, with the account
    // moved off the figure line and into the subtitle so the two heroes on this tile read as a
    // matched pair.
    //
    // **The `as of <date>` line is deliberately NOT here**, and this is the one place that costs
    // something: he asked for it three hours earlier ("and if the account balance is stale") and
    // then asked for exactly two subtitles. Both are his calls and the second is the later one, so
    // it wins. The staleness disclosure still ships in full on Money's own balance row
    // (`ui/ledger/LedgerRows.kt`, "as of Aug 12" / "no balance ever printed for this account"),
    // which is the surface that exists to answer that question. Flagged to him rather than
    // resolved silently - if a stale figure reading as current on HOME turns out to bite, the fix
    // is to fold the date into this subtitle, not to add a third line.
    return CredBalanceLine(primary = amount, secondary = "in $label account", isAdvisory = false)
}

/**
 * FLEET tile: [rows] is [com.kevin.legion.ui.fleet.buildDueRows]'s already-sorted (overdue-first)
 * output, re-shaped rather than recomputed, same posture [buildMaintenanceGapRowData] states for
 * its own row above. `NO LINK` (no maintenance schedule at all) is the silent-domain wording the
 * old FLEET sweep row used, carried over unchanged - now gated on [unknownCount] too, since a car
 * whose entire schedule is unknown-anchor still HAS a schedule, just nothing anchored yet.
 *
 * **Ticket 09's fix: the tile must stop saying `OK` while unknowns exist.** On Kevin's real Jeep
 * this used to read `OK / NEXT BRAKE FLUID -` with seven of ten items unanchored and the "next"
 * row an orphan with no interval at all - not true, and it was the surface he saw most.
 *
 * [unknownCount] comes from [com.kevin.legion.vehicle.VehicleController.unknownItems] - a genuinely
 * separate count from [rows], which [buildDueRows]'s own doc says never contains an unknown-anchor
 * item at all (they are excluded, not merely unsorted). `OK` is reserved for the one state that is
 * actually true: nothing overdue AND nothing unknown. Any other state names the overdue count as the
 * hero - honestly `"0 DUE"` when every unknown item happens to sit alongside zero overdue ones,
 * never `OK` - and the caption states the unknown count in words (`"3 due - 7 unknown"`) rather than
 * a colour or a glyph (CLAUDE.md §4 rule 7). Seven characters of hero max (mission-control ticket 05)
 * - `"N DUE"` fits at any single or double-digit N.
 */
data class FleetTileData(val hero: String, val caption: String)

fun buildFleetTile(rows: List<DueRowView>, unknownCount: Int): FleetTileData {
    if (rows.isEmpty() && unknownCount == 0) return FleetTileData(hero = "NO LINK", caption = "no maintenance schedule")
    val overdueCount = rows.count { it.overdue }
    if (overdueCount == 0 && unknownCount == 0) {
        val next = rows.first()
        return FleetTileData(hero = "OK", caption = "next ${next.label} ${next.value}")
    }
    val caption = if (unknownCount > 0) "$overdueCount due - $unknownCount unknown" else "overdue - see fleet"
    return FleetTileData(hero = "$overdueCount DUE", caption = caption)
}

// -------------------------------------------------- mission-control ticket 16: BIO/FLEET surfaces

/**
 * **BIO's INTAKE/SLEEP HALF tiles (mission-control ticket 16's BIO build).** BIO already had four
 * FULL [com.kevin.legion.ui.common.DeckPane]s (MASS/INTAKE/SLEEP/TRAINING, all pre-tiling); this
 * ticket's inventory (`.scratch/mission-control/issues/12-surface-inventories.md`) drops INTAKE and
 * SLEEP to HALF, sharing one row via [com.kevin.legion.ui.common.EqualHeightRow] the same way HOME's
 * BIO/CRED/FLEET/LOG row already does. MASS stays FULL (it is the surface's hero) and TRAINING stays
 * FULL (a set list is rows, not a figure - ticket 12's own reasoning, unchanged).
 *
 * [buildIntakeTile] restates [buildDailyMealGapRowData]'s NotLogged/Logged split as a hero/caption
 * pair instead of a [GapRowData] row, same "pure PlanGap mapper, second shape for a second slot"
 * posture every tile builder in this file already follows - never a second read of
 * [com.kevin.legion.meals.DailyMealGap], just a second RENDER of the one [BodyUiState] already
 * loaded. `actual.caloriesKcal` as the hero (never the remaining gap) matches what a driver expects
 * a calorie tile to lead with - "how much did I eat" is the more frequent question than "how much is
 * left", which the caption still answers.
 */
data class IntakeTileData(val hero: String, val caption: String)

fun buildIntakeTile(mealGap: DailyMealGap, hasMealTarget: Boolean): IntakeTileData = when (mealGap) {
    DailyMealGap.NotLogged -> IntakeTileData(
        hero = if (hasMealTarget) "NOT LOGGED" else "NO TARGET",
        caption = if (hasMealTarget) "say \"log a meal\"" else "no target set",
    )
    is DailyMealGap.Logged -> IntakeTileData(
        hero = "${mealGap.gap.actual.caloriesKcal}",
        caption = "OF ${mealGap.gap.target.caloriesKcal} KCAL",
    )
}

/**
 * [buildSleepTile]: same split as [buildIntakeTile], mirrored onto [SleepGap]. `formatMinutesAsHours`
 * ("7h 30m") clears the 7-character half-tile limit at every real value up to 23h59m - checked, not
 * assumed: the longest possible string ("23h 59m") is exactly 7 characters, so
 * [com.kevin.legion.ui.common.HalfTile]'s own step-down never has to fire for a real sleep duration,
 * only for the "NOT LOGGED"/"NO TARGET" empty-state words, exactly like every other tile in this file.
 */
data class SleepTileData(val hero: String, val caption: String)

fun buildSleepTile(gap: SleepGap, hasSleepTarget: Boolean): SleepTileData = when (gap) {
    SleepGap.NotLogged -> SleepTileData(
        hero = if (hasSleepTarget) "NOT LOGGED" else "NO TARGET",
        caption = if (hasSleepTarget) "say how long you slept" else "no target set",
    )
    is SleepGap.Logged -> SleepTileData(
        hero = formatMinutesAsHours(gap.gap.actual),
        caption = "OF ${formatMinutesAsHours(gap.gap.target)}",
    )
}

/**
 * **FLEET's MAINTENANCE/DRIVES HALF tiles (mission-control ticket 16's FLEET build).** MAINTENANCE
 * reuses [buildFleetTile] wholesale - it is the exact same "overdue count, or next due item" reading
 * HOME's own FLEET tile already renders off the identical [DueRowView] list, so there is no second
 * builder to write. [buildDrivesTile] is the DRIVES tile's own builder, new here: [DriveSummaryView]
 * carries a headline like `"214 mi · 26.8 mpg"`, too long for a HALF hero at either character count
 * or intent (two figures crammed into one string), so this reshapes it into a single-figure hero -
 * the SAME most-recent-drive day [summary] itself already headlines, never a second, differently-
 * scoped reading.
 *
 * **[recentLogsNewestFirst]'s own `driveCount > 0` filter is deliberately duplicated from
 * [com.kevin.legion.ui.fleet.buildLastDriveSummary] rather than shared** - that function is
 * `internal` to `ui.fleet` and returns only the already-formatted [DriveSummaryView], which does
 * not carry the raw mile figure this hero needs. A first attempt read
 * [com.kevin.legion.ui.fleet.buildMilesSparkline]'s own last element instead, on the assumption
 * that "most recent day in the 14-day window" and "the last day something actually drove" were the
 * same day - **wrong, caught on-device**: that series has one entry per CALENDAR day including
 * undriven ones (see that function's own doc: `milesDriven` is a real, non-gap `0.0` on a day
 * nothing drove), so on a car parked for two weeks the sparkline's last entry is today's `0.0`
 * while [summary]'s own caption still correctly says "1 drive · 15 days ago" - a tile reading
 * `"0 MI"` above a caption naming a real drive from two weeks back. Re-deriving off the same
 * `driveCount > 0` filter [buildLastDriveSummary] uses keeps the hero and the caption describing
 * the identical drive.
 */
data class DrivesTileData(val hero: String, val caption: String)

fun buildDrivesTile(summary: DriveSummaryView, recentLogsNewestFirst: List<DailyDriveLog>): DrivesTileData {
    if (!summary.hasData) return DrivesTileData(hero = "NO DRIVES", caption = summary.sub)
    val lastDriveMiles = recentLogsNewestFirst.firstOrNull { it.driveCount > 0 }?.milesDriven
    val hero = lastDriveMiles?.let { "${it.toInt()} MI" } ?: "—"
    return DrivesTileData(hero = hero, caption = summary.sub)
}

/**
 * LOG tile: open-task count (undone, unscheduled, non-recurring items - the same "task" shape
 * [com.kevin.legion.advisor.digest.HomeDigestBuilder.logHeadline] computes, restated here for the
 * same "pure hero/caption pair, not an advisor sentence" reason [buildBioTile]'s doc gives).
 * `hasAnyItems = false` (no [com.kevin.legion.data.local.ListItem] row has EVER existed, not merely
 * zero open right now) is the one truly silent LOG state; a caught-up inbox with real, computed
 * zero open tasks reads its own honest `0 NEW`, same "a real zero is not silence" precedent
 * [buildFleetTile]'s "on track" branch already sets for FLEET.
 */
data class LogTileData(val hero: String, val caption: String)

fun buildLogTile(openTaskCount: Int, missedCount: Int, hasAnyItems: Boolean): LogTileData {
    if (!hasAnyItems) return LogTileData(hero = "NOT LOGGED", caption = "no items yet")
    return if (missedCount > 0) {
        LogTileData(hero = "$missedCount MISSED", caption = "$openTaskCount open task(s)")
    } else {
        LogTileData(hero = "$openTaskCount NEW", caption = "open tasks")
    }
}

// --------------------------------------------------------- mission-control ticket 16: ALERTS

/**
 * ALERTS' two tiers (mission-control ticket 04's answer, section 1) - `DESTRUCTIVE` is not a third
 * member here because nothing this pane renders is a control, only states.
 */
enum class AlertTier { ALARM, ADVISORY }

/** Which callback an [AlertRowData] taps through to - resolved to a real navigation lambda only at
 * the [com.kevin.legion.ui.common.DeckRow] call site in `TodayScreen.kt`, so this stays a plain
 * enum a pure function can return. [NONE] exists for a row this file cannot route anywhere sane
 * (see [alertTargetForAspect]'s doc) rather than guessing. */
enum class AlertTarget { CRED, KEY, BIO, LOG, FLEET, NONE }

/** One row of the ALERTS pane, before [com.kevin.legion.ui.common.DeckRow] renders it. [tagText] is
 * always non-null here (unlike [DeckSweepRow]'s optional tag in the old sweep) - ticket 11 section 3
 * is explicit that every row in this pane "carries its tier's tag", there is no silent row. */
data class AlertRowData(val label: String, val value: String, val tier: AlertTier, val tagText: String, val target: AlertTarget)

/** [visible] is already capped to five (mission-control ticket 11 section 3); [overflowCount] feeds
 * the worded `AND N MORE` line, never a bare count badge alone. */
data class AlertsSummary(val visible: List<AlertRowData>, val overflowCount: Int)

/**
 * Builds every row ALERTS can show today, **ALARM always first** (ticket 11 section 3) - the two
 * `+` concatenations below ARE that ordering rule; nothing here sorts within a tier, so arrival
 * order (quarantine rows in [quarantined]'s own order, then the key advisory, then goals in
 * [overdueGoals]'s own order) is preserved and never silently reshuffled.
 *
 * **Only wires advisory sources that already exist** (ticket 16's binding) - a missing Gemini key
 * ([hasGeminiKey], read via [com.kevin.legion.ai.GeminiKeyProvider.hasKey]) and an overdue active
 * [Goal] (already computed by [com.kevin.legion.advisor.digest.HomeDigestBuilder.exceptionsLine] for
 * the advisor digest, filtered the identical way here: `deadlineEpoch != null && deadlineEpoch < now`
 * - see `TodayScreen.kt`'s `LaunchedEffect`). A sync failure / expired Drive credential is ticket
 * 04's third ADVISORY example but **has no readable state anywhere in the app today** (traced: no
 * `SyncStatus`/`syncError` field exists in `sync/`) - not wired, because wiring it would be
 * inventing state this ticket's binding explicitly forbids, not reading an existing one. An active
 * vehicle fault (DTC) is ticket 04's other ALARM example and has the same gap: `vehicle/`'s DTC
 * reads are a LIVE OBD scan, not a cached/persisted state HOME can cheaply poll, so it is absent
 * here too, for the same reason.
 */
fun buildAlertRows(quarantined: List<IngestedFile>, hasGeminiKey: Boolean, overdueGoals: List<Goal>): List<AlertRowData> {
    val alarms = quarantined.map { file ->
        AlertRowData(label = file.displayName, value = "FAILED THE GATE", tier = AlertTier.ALARM, tagText = "QUARANTINED", target = AlertTarget.CRED)
    }
    val advisories = buildList {
        if (!hasGeminiKey) {
            add(AlertRowData(label = "Assistant", value = "NO GEMINI KEY", tier = AlertTier.ADVISORY, tagText = "NO KEY", target = AlertTarget.KEY))
        }
        overdueGoals.forEach { goal ->
            add(
                AlertRowData(
                    label = goal.statement,
                    value = "WAS DUE ${compactDate(goal.deadlineEpoch!!)}",
                    tier = AlertTier.ADVISORY,
                    tagText = "OVERDUE",
                    target = alertTargetForAspect(AdvisorAspect.fromKey(goal.aspect)),
                ),
            )
        }
    }
    return alarms + advisories
}

/**
 * Whether [rows] holds at least one [AlertTier.ALARM] entry - mission-control ticket 04 build,
 * "the ALERTS pane on TodayScreen passes `alarm = true` when it holds any ALARM row." Pulled out
 * of `TodayScreen.kt`'s `DeckPane(header = "Alerts", alarm = ...)` call site as its own named,
 * pure function rather than left inline specifically so it is unit-testable without Compose - see
 * [TodayGapResolversTest]'s coverage. [buildAlertRows] already puts ALARM rows first (ticket 11
 * section 3), but this checks the whole list rather than relying on that ordering, so it stays
 * correct even if a future caller changes it.
 */
fun alertRowsHaveAlarm(rows: List<AlertRowData>): Boolean = rows.any { it.tier == AlertTier.ALARM }

/** Routes a [Goal.aspect] to the tile/tab that owns it. [AdvisorAspect.HOME] and an unrecognised
 * key both fall to [AlertTarget.NONE] rather than a guessed destination - no write path ever mints a
 * `home`-aspect goal ([com.kevin.legion.advisor.AdvisorBriefs.HOME]'s `writableOps = emptySet()`),
 * so this branch is defensive, not expected to fire; an unrecognised `aspect` string is the same
 * "data hygiene to investigate, never crash" case [AdvisorAspect.fromKey]'s own doc names. */
fun alertTargetForAspect(aspect: AdvisorAspect?): AlertTarget = when (aspect) {
    AdvisorAspect.BIO -> AlertTarget.BIO
    AdvisorAspect.LOG -> AlertTarget.LOG
    AdvisorAspect.FLEET -> AlertTarget.FLEET
    AdvisorAspect.CRED -> AlertTarget.CRED
    AdvisorAspect.HOME, null -> AlertTarget.NONE
}

/** Caps at [max] (five, ticket 11 section 3) with a worded overflow count - never silently drops
 * rows past the cap without saying so, same "reported, never silent" posture ticket 12's MISSED
 * backlog already set for this screen's AGENDA pane. */
fun capAlertRows(rows: List<AlertRowData>, max: Int = 5): AlertsSummary =
    if (rows.size <= max) AlertsSummary(rows, 0) else AlertsSummary(rows.take(max), rows.size - max)

/** The short month abbreviation for the CRED tile's caption, e.g. "AUG" - fixed-width three letters regardless of locale. */
fun ledgerSweepMonthLabel(month: YearMonth): String =
    month.month.getDisplayName(TextStyle.SHORT, Locale.ENGLISH).uppercase(Locale.ENGLISH)
