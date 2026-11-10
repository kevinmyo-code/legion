package com.kevin.legion.ui.typeset

import com.kevin.legion.assistant.cards.PhoneCards
import com.kevin.legion.finance.BudgetsWire
import com.kevin.legion.finance.FinanceRead
import com.kevin.legion.ui.home.AgendaKind
import com.kevin.legion.ui.home.AgendaRowUi
import com.kevin.legion.ui.home.TodoRowUi
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * One figure on the Typeset front page (ADR 0081): the number in the left column ([number], with a
 * small-caps [unit] under it such as "PM"), the words on the right ([label] bold, then [lines]).
 * A figure that has no number says so in words in [number]; it is never a zero standing in for a
 * failed read.
 */
data class TypesetFigure(
    val number: String,
    val unit: String? = null,
    val label: String,
    val lines: List<String> = emptyList(),
)

/** A dock tile: a two-letter monogram over the app's name. No logos. */
data class TypesetDockTile(val monogram: String, val name: String)

/** What the Typeset home draws with nothing asked: the glance. Pure data, built by [TypesetHomeDerive]. */
data class TypesetGlance(
    val dateline: String,
    val time: String,
    val ampm: String,
    val next: TypesetFigure,
    val due: TypesetFigure,
    val left: TypesetFigure,
)

/** Every tap the Typeset home can make. Stateless: the caller wires each to its screen or service. */
data class TypesetHomeActions(
    /** The masthead's Home button: ends the conversation and clears its cards (ADR 0081). */
    val onHome: () -> Unit = {},
    /** The masthead's Controls link: the panel of six (Phase C). */
    val onControls: () -> Unit = {},
    /** The sun or moon: the one-tap edition switch. */
    val onToggleEdition: () -> Unit = {},
    val onOpenClock: () -> Unit = {},
    val onOpenToday: () -> Unit = {},
    val onOpenMoney: () -> Unit = {},
    val onOpenCar: () -> Unit = {},
    /** The orb: talk, or interrupt Alfred while he speaks. */
    val onTalk: () -> Unit = {},
    val onType: () -> Unit = {},
    val onDismissCard: () -> Unit = {},
    val onOpenApp: (TypesetDockTile) -> Unit = {},
    val onAllApps: () -> Unit = {},
)

/** The three figures, pure, from the view models HOME already has. Pinned by `TypesetHomeDeriveTest`. */
object TypesetHomeDerive {

    private const val MINUTES_PER_HOUR = 60L

    private val CLOCK = DateTimeFormatter.ofPattern("h:mm a", Locale.US)
    private val CLOCK_24 = DateTimeFormatter.ofPattern("HH:mm", Locale.US)

    /**
     * "9:41" and "AM", or "21:41" and "" when the phone is set to 24-hour time: the headline
     * follows the phone's own setting (the app ships both today; no ruling picks one).
     */
    fun time(now: LocalTime, twentyFourHour: Boolean): Pair<String, String> {
        if (twentyFourHour) return now.format(CLOCK_24) to ""
        val text = now.format(CLOCK)
        return text.substringBefore(' ') to text.substringAfter(' ')
    }

    /**
     * The next timed event still ahead today, from the day's agenda rows (their `whenLabel` is
     * "HH:mm", or "h:mm AM"). Past events, all-day events, to-dos and suggestions are not "next".
     */
    fun next(rows: List<AgendaRowUi>, now: LocalTime, twentyFourHour: Boolean): TypesetFigure {
        val upcoming = rows.asSequence()
            .filter { it.kind == AgendaKind.EVENT }
            .mapNotNull { row -> parse(row.whenLabel)?.let { row to it } }
            .filter { (_, at) -> !at.isBefore(now) }
            .minByOrNull { (_, at) -> at }
            ?: return TypesetFigure(number = "None", label = "Nothing else today", lines = emptyList())
        val (row, at) = upcoming
        val (clock, ampm) = time(at, twentyFourHour)
        val unit = ampm.ifBlank { null }
        return TypesetFigure(number = clock, unit = unit, label = row.title, lines = listOf(inWords(now, at)))
    }

    /** Open to-dos due today: the count, and the first two by name. */
    fun due(openCount: Int, todos: List<TodoRowUi>): TypesetFigure {
        val names = todos.filter { !it.done }.map { it.title }.take(2)
        val more = (openCount - names.size).coerceAtLeast(0)
        val lines = names + listOfNotNull(if (more > 0) "and $more more" else null)
        return TypesetFigure(number = openCount.toString(), label = "due today", lines = lines)
    }

    /**
     * Budget left this month: cap minus spent from the engine's budgets, with how many days are
     * left. Saved data says how old it is; a refusal or no data says why, in words, never "$0".
     */
    fun left(read: FinanceRead<BudgetsWire>?, monthName: String): TypesetFigure = when (read) {
        null -> noFigure(monthName, "Reading the budget.")
        is FinanceRead.Refused -> noFigure(monthName, read.sentence)
        is FinanceRead.Unavailable -> noFigure(monthName, read.sentence)
        is FinanceRead.Fresh -> leftOf(read.value, monthName, note = null)
        is FinanceRead.Saved -> leftOf(read.value, monthName, note = read.note)
    }

    private fun noFigure(monthName: String, why: String) =
        TypesetFigure(number = "No figure", label = "left for $monthName", lines = listOf(why))

    private fun leftOf(b: BudgetsWire, monthName: String, note: String?): TypesetFigure {
        val unit = b.currency.lowercase()
        if (b.total.capCents <= 0L) {
            return TypesetFigure(
                number = "No cap",
                label = "set for $monthName",
                lines = listOfNotNull("${PhoneCards.money(b.total.spentCents, unit)} spent so far", note),
            )
        }
        val left = b.total.capCents - b.total.spentCents
        val daysLeft = (b.daysInMonth - b.daysElapsed).coerceAtLeast(0)
        val days = if (daysLeft == 1) "1 day left" else "$daysLeft days left"
        val figure = if (left < 0) "-" + PhoneCards.money(-left, unit) else PhoneCards.money(left, unit)
        val label = if (left < 0) "over for $monthName" else "left for $monthName"
        return TypesetFigure(
            number = figure,
            label = label,
            lines = listOfNotNull("of ${PhoneCards.money(b.total.capCents, unit)}, $days", note),
        )
    }

    /** "in 40 min", "in 2 h 5 min", "now". */
    fun inWords(now: LocalTime, at: LocalTime): String {
        val minutes = java.time.Duration.between(now, at).toMinutes()
        return when {
            minutes <= 0 -> "now"
            minutes < MINUTES_PER_HOUR -> "in $minutes min"
            minutes % MINUTES_PER_HOUR == 0L -> "in ${minutes / MINUTES_PER_HOUR} h"
            else -> "in ${minutes / MINUTES_PER_HOUR} h ${minutes % MINUTES_PER_HOUR} min"
        }
    }

    private fun parse(label: String): LocalTime? {
        val text = label.trim().uppercase(Locale.US)
        return listOf(CLOCK_24, CLOCK).firstNotNullOfOrNull { format ->
            runCatching { LocalTime.parse(text, format) }.getOrNull()
        }
    }

    /** The prototype's six: monograms, never logos. */
    val DOCK = listOf(
        TypesetDockTile("Wa", "WhatsApp"),
        TypesetDockTile("Gm", "Gmail"),
        TypesetDockTile("Ma", "Maps"),
        TypesetDockTile("Mu", "Music"),
        TypesetDockTile("Ca", "Camera"),
        TypesetDockTile("Ph", "Photos"),
    )
}
