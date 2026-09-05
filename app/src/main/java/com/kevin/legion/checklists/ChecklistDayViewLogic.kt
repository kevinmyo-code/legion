package com.kevin.legion.checklists

import com.kevin.legion.data.local.Checklist
import com.kevin.legion.data.local.ChecklistItem
import com.kevin.legion.data.local.MeasureDirection
import com.kevin.legion.notes.parseWeekdays
import java.text.DecimalFormat
import java.time.DayOfWeek
import java.time.format.TextStyle
import java.util.Locale

/**
 * Pure helpers `ui/CalendarScreen.kt`'s day view and `ui/checklists/ChecklistsScreen.kt`'s history
 * screen call into, split out of both composables the same way `ui/phone/PhoneDialLogic.kt` keeps
 * its own pure logic testable without Compose or Robolectric.
 */

/**
 * The day-view section header for one checklist - name plus that day's own progress, e.g.
 * "BIO (2/5)" (`DeckSectionRule` uppercases whatever it is handed, so the case here does not
 * matter). **Never a percentage, never a streak** - CLAUDE.md §7's compulsion ban; a plain
 * done/total count is the same kind of fact `GoalChecklistPanel`'s own "N TODAY" accent states,
 * not a score. A checklist with zero items renders "(0/0)" here - the caller still shows its own
 * "No items yet." row underneath, this label alone does not need to special-case that.
 *
 * [loadFailed], added one-today ticket 09's third build (collapsed calendar sections): a collapsed
 * header must stay honest when the day's own [ChecklistController.itemsWithTickState] read threw -
 * a `0/0` there would read as "nothing on this list today" when the truth is "could not be read",
 * exactly the empty-vs-unreadable conflation CLAUDE.md's proactive-raise rule names for a calendar
 * briefing. `true` short-circuits the count entirely; [items] is ignored in that case rather than
 * being trusted to already be empty.
 */
fun checklistSectionLabel(
    checklist: Checklist,
    items: List<ChecklistController.ItemState>,
    loadFailed: Boolean = false,
): String {
    if (loadFailed) return "${checklist.name} - couldn't load today's items"
    val done = items.count { it.ticked }
    return "${checklist.name} (${done}/${items.size})"
}

/**
 * Groups [ChecklistController.checklistHistory]'s flat `(item, day)` lines by [ChecklistController.ChecklistHistoryLine.day],
 * most recent day first - the "look back and see what i did" read (`ui/checklists/ChecklistsScreen.kt`'s
 * history view) wants one heading per day, not one row per line. **Shown, never scored** (the
 * ticket's own instruction, CLAUDE.md §7): this only groups and orders, it computes no streak, no
 * percentage, no "N of M days" figure - a caller wanting a count of lines on a day gets it from
 * `.size` on the list it is handed, this function never precomputes one.
 *
 * Within a day, lines stay in [ChecklistController.checklistHistory]'s own order (sorted by
 * `item.sortOrder`, per that function's own doc comment) - not re-sorted here, so a caller reading
 * across days sees each day's lines in the same order the checklist itself lists them in.
 */
fun historyGroupedByDayDescending(
    lines: List<ChecklistController.ChecklistHistoryLine>,
): List<Pair<Int, List<ChecklistController.ChecklistHistoryLine>>> =
    lines.groupBy { it.day }.toSortedMap(compareByDescending { it }).map { (day, dayLines) -> day to dayLines }

// -------------------------------------------------------------------- measured items (ticket 09, third build)

/** "8,400", "22.5" - grouped thousands, no trailing ".0" on a whole number. One formatter for every
 * measured-value display in this file so "8400.0" and "8,400" never appear side by side on the
 * same screen. Not currency - [ChecklistTick.value] is a plain measurement, never money, so this
 * deliberately does not go anywhere near CLAUDE.md §4's `Long`-cents rule. */
private val MEASURE_NUMBER_FORMAT = DecimalFormat("#,##0.##")
fun formatMeasureNumber(value: Double): String = MEASURE_NUMBER_FORMAT.format(value)

/**
 * A ticked measured item's value against its target - Kevin's own worked example, verbatim from
 * the ticket: "8,400 / 10,000 steps". Falls back to "8,400 steps" when [ChecklistItem.measureTarget]
 * is null (a unit with no target means "just record it" - [ChecklistItem]'s own doc comment), and
 * to the bare number when [ChecklistItem.measureUnit] itself is null - which should never happen
 * for a genuinely measured item, but this function never throws on a malformed row; it degrades to
 * showing what it has rather than crashing a day view over one bad item.
 */
fun measureValueDisplay(item: ChecklistItem, value: Double): String {
    val unit = item.measureUnit
    val target = item.measureTarget
    val formattedValue = formatMeasureNumber(value)
    return when {
        unit != null && target != null -> "$formattedValue / ${formatMeasureNumber(target)} $unit"
        unit != null -> "$formattedValue $unit"
        else -> formattedValue
    }
}

/** Shown beside the number-entry field when ticking a measured item - "target: at least 10,000
 * steps" when [ChecklistItem.measureTarget]/[ChecklistItem.measureDirection] are both set, "in
 * steps" when only [ChecklistItem.measureUnit] is (the "just record it" case), null for a plain
 * binary item ([ChecklistItem.measureUnit] null). Never states a target this item does not
 * actually carry - a malformed row (target with no direction, or vice versa) falls back to the
 * bare "in <unit>" phrasing rather than guessing a direction. */
fun measurePromptLabel(item: ChecklistItem): String? {
    val unit = item.measureUnit ?: return null
    val target = item.measureTarget
    val direction = item.measureDirection?.let { runCatching { MeasureDirection.valueOf(it) }.getOrNull() }
    return if (target != null && direction != null) {
        val directionWord = if (direction == MeasureDirection.AT_LEAST) "at least" else "at most"
        "target: $directionWord ${formatMeasureNumber(target)} $unit"
    } else {
        "in $unit"
    }
}

/** Whether a measured tick's [value] met [ChecklistItem.measureTarget] in [ChecklistItem.measureDirection] -
 * null when the item carries no target (or a malformed one), meaning there is nothing to compare
 * against and no hit/miss should be rendered at all. */
enum class MeasureTargetResult { MET, MISSED }

fun measureTargetResult(item: ChecklistItem, value: Double): MeasureTargetResult? {
    val target = item.measureTarget ?: return null
    val direction = item.measureDirection?.let { runCatching { MeasureDirection.valueOf(it) }.getOrNull() } ?: return null
    return when (direction) {
        MeasureDirection.AT_LEAST -> if (value >= target) MeasureTargetResult.MET else MeasureTargetResult.MISSED
        MeasureDirection.AT_MOST -> if (value <= target) MeasureTargetResult.MET else MeasureTargetResult.MISSED
    }
}

/** [MeasureTargetResult] spelled out in words, glyph included - never colour alone (the same rule
 * an UNRECONCILED ledger row follows), so a caller may safely tint this text without the tint being
 * the ONLY signal. */
fun measureTargetResultLabel(result: MeasureTargetResult): String = when (result) {
    MeasureTargetResult.MET -> "✓ met"
    MeasureTargetResult.MISSED -> "○ short of target"
}

// -------------------------------------------------------------------- schedule label (ticket 09, third build)

/**
 * A [Checklist]'s schedule, in words, for the list-of-lists row - "Mon Wed Fri", "Daily", "Every 2
 * days", "No schedule". Reads the exact same [Checklist.scheduleKind]/[Checklist.scheduleEvery]/
 * [Checklist.scheduleDaysOfWeek] columns [ChecklistController]'s own private `appliesOnDay` does,
 * and degrades the same direction that function documents for a malformed schedule - toward "no
 * schedule" here rather than toward "applies every day" there, because this is a label, not a gate:
 * showing "No schedule" for a row that in fact still applies every day (`appliesOnDay`'s own
 * degrade-to-always-show posture) is a cosmetic understatement, not the "hides a live checklist"
 * failure that function's doc comment guards against.
 */
fun checklistScheduleLabel(checklist: Checklist): String {
    val kind = checklist.scheduleKind ?: return "No schedule"
    val every = checklist.scheduleEvery ?: return "No schedule"
    return when (kind) {
        "DAILY" -> if (every <= 1) "Daily" else "Every $every days"
        "WEEKLY" -> {
            val days = parseWeekdays(checklist.scheduleDaysOfWeek.orEmpty())
            if (days.isNullOrEmpty()) return "No schedule"
            val dayLabel = days.sortedBy { it.value }.joinToString(" ") { it.shortName() }
            if (every <= 1) dayLabel else "$dayLabel, every $every weeks"
        }
        else -> "No schedule"
    }
}

/** "Mon", "Tue", ... - the abbreviated form this label uses, distinct from [com.kevin.legion.notes.formatWeekdays]'s
 * own full-name comma-separated storage encoding (that one round-trips through [parseWeekdays]; this
 * one is display-only and never stored). Locale.US rather than the device default, matching this
 * label's own literal English wording ("Daily", "No schedule") elsewhere in this function. */
private fun DayOfWeek.shortName(): String = getDisplayName(TextStyle.SHORT, Locale.US)
