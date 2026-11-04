package com.kevin.legion.assistant.cards

import com.kevin.legion.backend.EventKind
import com.kevin.legion.data.local.Event
import com.kevin.legion.ledger.BudgetVsActual
import com.kevin.legion.ledger.formatCents
import com.kevin.legion.ledger.uncategorizedExcludedSentence
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Builders for the phone's own voice tools: each makes a card from the SAME data the tool's JSON
 * came from (the budget, the event rows, the schedule, the recall list, the codes, the checklist
 * rows), never from the model's words. Pure: no Context, no Room, so every one runs in a JVM test.
 * The tool calls the builder and hands the card to [ConversationCards.publish].
 */
object PhoneCards {

    private val HH_MM = DateTimeFormatter.ofPattern("HH:mm")
    private val DAY_WORDS = DateTimeFormatter.ofPattern("EEEE, MMMM d", Locale.US)
    private val SHORT_DAY = DateTimeFormatter.ofPattern("EEE MMM d", Locale.US)
    private val MONTH_WORDS = DateTimeFormatter.ofPattern("MMMM yyyy", Locale.US)

    /** "UTC-06:00", "UTC+05:30", "UTC+00:00". Never an IANA id (CLAUDE.md section 1). */
    fun zoneLabel(offset: ZoneOffset): String = "UTC" + if (offset.totalSeconds == 0) "+00:00" else offset.id

    /** Money from cents for a card: "$1,234.56" for usd, "SGD 12.00" otherwise. Never a Double. */
    fun money(cents: Long, unit: String): String = when (unit) {
        "usd" -> if (cents < 0) "-$" + formatCents(-cents) else "$" + formatCents(cents)
        "" -> cents.toString()
        else -> unit.uppercase() + " " + formatCents(cents)
    }

    // --- bars: get_monthly_spend ---------------------------------------------------------------

    /**
     * Spend by category for [month]: one bar per category line, its actual in cents. A line with
     * unreconciled rows is flagged unverified, a line resting on an unconfirmed category guess is
     * flagged estimate. The uncategorised bucket (not counted in spend) and missing statement
     * coverage are said in [ConversationCard.note], as every spend surface must (LedgerBudget.kt).
     */
    fun monthlySpend(budget: BudgetVsActual, month: YearMonth): ConversationCard.Bars {
        val unit = budget.entity.currency.name.lowercase()
        val lines = budget.lines.filter { it.gap.actual != 0L }.sortedByDescending { it.gap.actual }
        val points = lines.take(ConversationCard.MAX_POINTS).map { line ->
            val flag = when {
                line.hasProvisionalRows -> CardFlag.UNVERIFIED
                line.hasPendingCategoryGuesses -> CardFlag.ESTIMATE
                else -> null
            }
            BarPoint(line.category, line.gap.actual, flag)
        }
        val note = buildList {
            if (lines.isEmpty()) add("No spending in a category this month.")
            if (!budget.isComplete) {
                add("The statements on file do not cover the whole month, so the total is unverified.")
            }
            if (budget.uncategorized.spentCents != 0L) {
                add(uncategorizedExcludedSentence(budget.uncategorized, budget.entity.currency))
            }
        }.joinToString(" ").ifBlank { null }
        return ConversationCard.Bars(
            title = "Spending, ${money(budget.spentCents, unit)} in all",
            unit = unit,
            points = points,
            more = (lines.size - points.size).coerceAtLeast(0),
            scope = budget.entity.displayName,
            period = month.format(MONTH_WORDS),
            note = note,
        )
    }

    // --- timeline: read_calendar ---------------------------------------------------------------

    /**
     * [events] over the window. One day's items carry no date; a window whose rows fall on several
     * days gives each item its `date` (the shared format's multi-day field) and the view groups
     * them under day headings. An all-day row's `startsAt` is UTC midnight of its date (see
     * `activeByKindInLocalWindow`), so its date is read in UTC; a timed row's in [zone]. At most
     * [ConversationCard.MAX_TIMELINE] items; the rest are `more`, said in words.
     */
    fun calendar(events: List<Event>, zone: ZoneId, windowStartMs: Long): ConversationCard.Timeline {
        val dated = events.mapNotNull { e -> e.startsAt?.let { e to localDate(e, it, zone) } }
            .sortedWith(compareBy({ it.second }, { !it.first.allDay }, { it.first.startsAt }))
        val days = dated.map { it.second }.distinct()
        val first = days.firstOrNull() ?: Instant.ofEpochMilli(windowStartMs).atZone(zone).toLocalDate()
        val several = days.size > 1
        val shown = dated.take(ConversationCard.MAX_TIMELINE)
        val more = dated.size - shown.size
        val note = buildList {
            if (dated.isEmpty()) add("Nothing on the calendar and nothing due in that window.")
            if (more > 0) add("$more more not shown.")
        }.joinToString(" ").ifBlank { null }
        return ConversationCard.Timeline(
            title = "Calendar",
            date = first.toString(),
            zone = zoneLabel(zone.rules.getOffset(first.atStartOfDay(zone).toInstant())),
            items = shown.map { (e, day) -> timelineItem(e, zone).copy(date = day.toString().takeIf { several }) },
            more = more,
            period = if (several) {
                "${first.format(SHORT_DAY)} to ${days.last().format(SHORT_DAY)}"
            } else {
                first.format(DAY_WORDS)
            },
            note = note,
        )
    }

    private fun localDate(e: Event, startsAt: Long, zone: ZoneId): LocalDate =
        Instant.ofEpochMilli(startsAt).atZone(if (e.allDay) ZoneOffset.UTC else zone).toLocalDate()

    private fun timelineItem(e: Event, zone: ZoneId): TimelineItem {
        val start = e.startsAt?.takeIf { !e.allDay }?.let { hhmm(it, zone) }
        val end = e.endsAt?.takeIf { !e.allDay && it != e.startsAt }?.let { hhmm(it, zone) }
        val kind = when (e.kind) {
            EventKind.TASK, EventKind.REMINDER -> TimelineKind.TODO
            EventKind.SUGGESTION -> TimelineKind.SUGGESTION
            else -> TimelineKind.EVENT
        }
        val title = if (kind == TimelineKind.TODO && e.done) "${e.title} (done)" else e.title
        return TimelineItem(start, end, e.allDay, title, kind)
    }

    private fun hhmm(epochMs: Long, zone: ZoneId): String =
        Instant.ofEpochMilli(epochMs).atZone(zone).format(HH_MM)

    // --- checklist: manage_checklist read, read_list -------------------------------------------

    fun checklist(listName: String, lines: List<ChecklistLine>): ConversationCard.Checklist {
        val shown = lines.take(ConversationCard.MAX_POINTS)
        val open = lines.count { !it.done }
        return ConversationCard.Checklist(
            title = listName,
            listName = listName,
            items = shown,
            more = lines.size - shown.size,
            note = when {
                lines.isEmpty() -> "Nothing on this list."
                open == 0 -> "Everything is ticked."
                else -> "$open of ${lines.size} not done."
            },
        )
    }

    /** The to-do list read_list reads (reminders and dated items, one list). */
    const val TODO_LIST_NAME = "To-dos"
}
