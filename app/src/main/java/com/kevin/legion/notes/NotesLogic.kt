package com.kevin.legion.notes

import com.kevin.legion.data.local.ListItem
import java.time.DayOfWeek

/**
 * Pure logic behind the notes/lists/calendar voice tools (`service/LiveToolbox.kt`'s
 * `manage_list`/`manage_item`/`read_list`) and `notes/NotesController.kt`. No `Context`, no Room -
 * same posture as `ledger/LedgerPendingLog.kt`, so this is a plain JVM unit test.
 */

// ------------------------------------------------------------------ addressing an item by voice

/** Which item(s), if any, a spoken query matches, for the tick/untick/remove/schedule/skip verbs. */
sealed class ItemMatch {
    data class Resolved(val item: ListItem) : ItemMatch()

    /** Nothing on the list matched at all - `.scratch/notes-lists-calendar/issues/05-*`: refuse and offer. */
    object NoMatch : ItemMatch()

    /** More than one candidate matched at the same confidence tier - refuse and name them, never guess. */
    data class Ambiguous(val candidates: List<ListItem>) : ItemMatch()
}

/**
 * Matches [query] against [items] by fuzzy text only, **never by position** (ticket 05: "'scratch
 * the third one' is not supported"). Three tiers, most confident first, each capable of resolving
 * OR being ambiguous on its own - a tier is only skipped when it matches nothing at all:
 * 1. Exact (case-insensitive) text match.
 * 2. Substring match, either direction (the item's text contains the query, or the query contains
 *    the item's text) - e.g. "batteries" matches "AA batteries for the headlamp".
 * 3. Best word-overlap match (content words only, >2 characters, so "the"/"a" don't drive it).
 *
 * This is the same three-tier shape [com.kevin.legion.vehicle.CarTaskController]'s private `match`
 * already used, but that function always guessed on ambiguity; this one refuses instead, per
 * ticket 05's precedent from `log_pending_transaction`.
 */
fun matchItem(query: String, items: List<ListItem>): ItemMatch {
    val q = query.trim().lowercase()
    if (q.isBlank() || items.isEmpty()) return ItemMatch.NoMatch

    val exact = items.filter { it.text.trim().lowercase() == q }
    if (exact.isNotEmpty()) return resolveOrAmbiguous(exact)

    val substring = items.filter { it.text.lowercase().contains(q) || q.contains(it.text.trim().lowercase()) }
    if (substring.isNotEmpty()) return resolveOrAmbiguous(substring)

    val qWords = contentWords(q)
    if (qWords.isEmpty()) return ItemMatch.NoMatch
    val scored = items
        .map { it to (contentWords(it.text.lowercase()) intersect qWords).size }
        .filter { it.second > 0 }
    if (scored.isEmpty()) return ItemMatch.NoMatch
    val bestScore = scored.maxOf { it.second }
    return resolveOrAmbiguous(scored.filter { it.second == bestScore }.map { it.first })
}

private fun resolveOrAmbiguous(matches: List<ListItem>): ItemMatch =
    if (matches.size == 1) ItemMatch.Resolved(matches.first()) else ItemMatch.Ambiguous(matches)

private fun contentWords(s: String): Set<String> =
    s.split(Regex("\\W+")).filter { it.length > 2 }.toSet()

// ------------------------------------------------------------------------------- repeat columns

/**
 * Reads [item]'s `repeat*` columns back into a [RepeatRule], or null if it does not repeat
 * (`repeatKind == null`). The inverse of [repeatColumnsFor]. Returns null (rather than throwing)
 * for a row whose stored `repeatKind` doesn't parse or whose required columns are missing/blank -
 * a defensively-corrupt row should read as "not recurring", never crash a calendar query.
 */
fun ruleFromItem(item: ListItem): RepeatRule? {
    val kind = item.repeatKind?.let { runCatching { RepeatKind.valueOf(it) }.getOrNull() } ?: return null
    return when (kind) {
        RepeatKind.DAILY -> item.repeatEvery?.let { RepeatRule.Daily(it) }
        RepeatKind.WEEKLY -> {
            val every = item.repeatEvery ?: return null
            val days = parseWeekdays(item.repeatDaysOfWeek.orEmpty()) ?: return null
            if (days.isEmpty()) null else RepeatRule.Weekly(every, days)
        }
        RepeatKind.MONTHLY_ON_DATE -> {
            val every = item.repeatEvery ?: return null
            val day = item.repeatDay ?: return null
            RepeatRule.MonthlyOnDate(every, day)
        }
        RepeatKind.YEARLY -> {
            val month = item.repeatMonth ?: return null
            val day = item.repeatDay ?: return null
            RepeatRule.Yearly(month, day)
        }
    }
}

/** Reads [item]'s `repeatEnd*` columns back into a [RepeatEnd] - `Never` if none is stored. */
fun endFromItem(item: ListItem): RepeatEnd {
    val kind = item.repeatEndKind?.let { runCatching { RepeatEndKind.valueOf(it) }.getOrNull() }
        ?: return RepeatEnd.Never
    return when (kind) {
        RepeatEndKind.NEVER -> RepeatEnd.Never
        RepeatEndKind.ON_DATE -> item.repeatEndDate?.let { RepeatEnd.OnDate(it) } ?: RepeatEnd.Never
        RepeatEndKind.AFTER_COUNT -> item.repeatEndCount?.let { RepeatEnd.AfterCount(it) } ?: RepeatEnd.Never
    }
}

/** The nine `repeat*` column values [rule]/[end] would be stored as - the inverse of [ruleFromItem]/[endFromItem]. */
data class RepeatColumns(
    val repeatKind: String?,
    val repeatEvery: Int?,
    val repeatDaysOfWeek: String?,
    val repeatDay: Int?,
    val repeatMonth: Int?,
    val repeatEndKind: String?,
    val repeatEndDate: Long?,
    val repeatEndCount: Int?,
)

/** Column values for [rule]/[end], or all-null if [rule] is null (clears any existing repeat). */
fun repeatColumnsFor(rule: RepeatRule?, end: RepeatEnd): RepeatColumns {
    if (rule == null) return RepeatColumns(null, null, null, null, null, null, null, null)
    val (kind, every, days, day, month) = when (rule) {
        is RepeatRule.Daily -> RepeatFields(RepeatKind.DAILY, rule.every, null, null, null)
        is RepeatRule.Weekly -> RepeatFields(RepeatKind.WEEKLY, rule.every, formatWeekdays(rule.days), null, null)
        is RepeatRule.MonthlyOnDate -> RepeatFields(RepeatKind.MONTHLY_ON_DATE, rule.every, null, rule.day, null)
        is RepeatRule.Yearly -> RepeatFields(RepeatKind.YEARLY, null, null, rule.day, rule.month)
    }
    val endKind = when (end) {
        RepeatEnd.Never -> RepeatEndKind.NEVER
        is RepeatEnd.OnDate -> RepeatEndKind.ON_DATE
        is RepeatEnd.AfterCount -> RepeatEndKind.AFTER_COUNT
    }
    return RepeatColumns(
        repeatKind = kind.name,
        repeatEvery = every,
        repeatDaysOfWeek = days,
        repeatDay = day,
        repeatMonth = month,
        repeatEndKind = endKind.name,
        repeatEndDate = (end as? RepeatEnd.OnDate)?.dateEpochMillis,
        repeatEndCount = (end as? RepeatEnd.AfterCount)?.n,
    )
}

private data class RepeatFields(
    val kind: RepeatKind,
    val every: Int?,
    val days: String?,
    val day: Int?,
    val month: Int?,
)

/** "MON,WED,FRI" (or full names, case-insensitive) -> a [DayOfWeek] set. Null on any unrecognized token. */
fun parseWeekdays(spec: String): Set<DayOfWeek>? {
    if (spec.isBlank()) return emptySet()
    val tokens = spec.split(",").map { it.trim() }.filter { it.isNotEmpty() }
    val days = mutableSetOf<DayOfWeek>()
    for (token in tokens) {
        val day = WEEKDAY_ABBREVIATIONS[token.uppercase()]
            ?: runCatching { DayOfWeek.valueOf(token.uppercase()) }.getOrNull()
            ?: return null
        days.add(day)
    }
    return days
}

/** The inverse of [parseWeekdays] - always full names, comma-separated, ISO (Monday-first) order. */
fun formatWeekdays(days: Set<DayOfWeek>): String =
    days.sortedBy { it.value }.joinToString(",") { it.name }

private val WEEKDAY_ABBREVIATIONS = mapOf(
    "MON" to DayOfWeek.MONDAY, "TUE" to DayOfWeek.TUESDAY, "WED" to DayOfWeek.WEDNESDAY,
    "THU" to DayOfWeek.THURSDAY, "FRI" to DayOfWeek.FRIDAY, "SAT" to DayOfWeek.SATURDAY,
    "SUN" to DayOfWeek.SUNDAY,
)
