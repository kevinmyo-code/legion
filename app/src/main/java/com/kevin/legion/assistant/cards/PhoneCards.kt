package com.kevin.legion.assistant.cards

import com.kevin.legion.backend.EventKind
import com.kevin.legion.data.local.Event
import com.kevin.legion.data.local.Vehicle
import com.kevin.legion.ledger.BudgetVsActual
import com.kevin.legion.ledger.formatCents
import com.kevin.legion.ledger.uncategorizedExcludedSentence
import com.kevin.legion.vehicle.VehicleController
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
            if (!budget.isComplete) add("The statements on file do not cover the whole month, so the total is unverified.")
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
     * One local day of [events]: the first day in the window that has anything, else the window's
     * own first day. An all-day row's `startsAt` is UTC midnight of its date (see
     * `activeByKindInLocalWindow`), so its date is read in UTC; a timed row's in [zone]. Rows on
     * later days become `more`, said in words.
     */
    fun calendar(events: List<Event>, zone: ZoneId, windowStartMs: Long): ConversationCard.Timeline {
        val dated = events.mapNotNull { e -> e.startsAt?.let { e to localDate(e, it, zone) } }
        val day = dated.minOfOrNull { it.second } ?: Instant.ofEpochMilli(windowStartMs).atZone(zone).toLocalDate()
        val onDay = dated.filter { it.second == day }.map { it.first }
        val shown = onDay.take(ConversationCard.MAX_POINTS)
        val laterDays = dated.size - onDay.size
        val more = (onDay.size - shown.size) + laterDays
        val note = buildList {
            if (dated.isEmpty()) add("Nothing on the calendar and nothing due in that window.")
            if (laterDays > 0) add("$laterDays more on later days in this window.")
        }.joinToString(" ").ifBlank { null }
        return ConversationCard.Timeline(
            title = "Calendar",
            date = day.toString(),
            zone = zoneLabel(zone.rules.getOffset(day.atStartOfDay(zone).toInstant())),
            items = shown.map { timelineItem(it, zone) },
            more = more,
            period = day.format(DAY_WORDS),
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

    private fun hhmm(epochMs: Long, zone: ZoneId): String = Instant.ofEpochMilli(epochMs).atZone(zone).format(HH_MM)

    // --- car: get_next_service, check_recalls, get_codes ---------------------------------------

    /** The car with its next service, as get_next_service computed it. Recalls and codes: not checked. */
    fun carService(
        label: String,
        vehicle: Vehicle,
        next: VehicleController.NextService?,
        zone: ZoneId,
        nowMs: Long = System.currentTimeMillis(),
    ): ConversationCard.Car {
        val mileage = VehicleController.currentMileage(vehicle).takeIf { it > 0 }
        val today = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
        val miles = next?.byMiles?.takeIf { !next.odometerUnset && mileage != null }
        val time = next?.byTime
        val due = when {
            next == null || next.allDue -> null
            miles != null -> NextServiceDue(
                label = miles.serviceName,
                dueMiles = mileage!! + miles.remaining.toInt(),
                dueDate = time?.takeIf { it.serviceName == miles.serviceName }?.let { today.plusDays(it.remaining).toString() },
            )
            time != null -> NextServiceDue(label = time.serviceName, dueDate = today.plusDays(time.remaining).toString())
            else -> null
        }
        val note = buildList {
            when {
                next == null -> add("No maintenance schedule can be timed for this car yet.")
                next.allDue -> add("Everything on the schedule is already due.")
            }
            if (due != null) add("The next service is an estimate from the schedule's intervals.")
            if (miles != null && time != null && time.serviceName != miles.serviceName) {
                add("By time, ${time.serviceName} is soonest, about ${today.plusDays(time.remaining)}.")
            }
            if (next != null && next.unknownCount > 0) add("${next.unknownCount} more have no last-done on file.")
            mileageNote(vehicle, nowMs)?.let(::add)
            add("Recalls and trouble codes were not checked in this answer.")
        }.joinToString(" ")
        return car(label, vehicle, mileage, zone, due, recalls = null, codes = null, note = note)
    }

    /** The car with the recall list NHTSA returned. An empty list is a real "none on file". */
    fun carRecalls(label: String, vehicle: Vehicle, summaries: List<String>, zone: ZoneId): ConversationCard.Car =
        car(
            label, vehicle, VehicleController.currentMileage(vehicle).takeIf { it > 0 }, zone,
            due = null, recalls = summaries.map { Recall(it) }, codes = null,
            note = listOfNotNull(
                if (summaries.isEmpty()) "NHTSA lists no recalls for this car." else null,
                mileageNote(vehicle),
            ).joinToString(" ").ifBlank { null },
        )

    /** The car with the codes read off the port now. [meanings] null for a code with no known meaning. */
    fun carCodes(label: String, vehicle: Vehicle?, codes: List<Pair<String, String?>>, zone: ZoneId): ConversationCard.Car =
        ConversationCard.Car(
            title = label,
            name = label,
            mileage = vehicle?.let { VehicleController.currentMileage(it).takeIf { m -> m > 0 } },
            mileageAsOf = vehicle?.let { asOf(it, zone) },
            nextService = null,
            recalls = null,
            codes = codes.map { (code, meaning) -> TroubleCode(code, meaning) },
            note = if (codes.isEmpty()) "No trouble codes stored." else null,
        )

    @Suppress("LongParameterList") // the car card's own fields, one per argument
    private fun car(
        label: String,
        vehicle: Vehicle,
        mileage: Int?,
        zone: ZoneId,
        due: NextServiceDue?,
        recalls: List<Recall>?,
        codes: List<TroubleCode>?,
        note: String?,
    ) = ConversationCard.Car(
        title = label,
        name = label,
        mileage = mileage,
        mileageAsOf = asOf(vehicle, zone),
        nextService = due,
        recalls = recalls,
        codes = codes,
        note = note,
    )

    /** The date of the driver's own last odometer reading, null if he never gave one. */
    private fun asOf(vehicle: Vehicle, zone: ZoneId): String? =
        vehicle.odometerBaselineAt.takeIf { it > 0L }?.let { Instant.ofEpochMilli(it).atZone(zone).toLocalDate().toString() }

    private fun mileageNote(vehicle: Vehicle, nowMs: Long = System.currentTimeMillis()): String? =
        VehicleController.mileageCaveat(vehicle, nowMs)?.let { "Mileage is $it." }

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
