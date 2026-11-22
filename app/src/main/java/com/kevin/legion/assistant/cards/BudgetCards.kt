package com.kevin.legion.assistant.cards

import com.kevin.legion.finance.BudgetLineWire
import com.kevin.legion.finance.BudgetsWire
import com.kevin.legion.finance.FinanceRead
import com.kevin.legion.finance.valueOrNull
import java.math.BigDecimal
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * The budget card a money answer puts up (2026-11-22: an affordability question was answered by
 * voice alone, because only `get_monthly_spend`'s total view made a card). The engine's own
 * `budget_pace_card` (`server/engine_mcp/cards.py`) row for row, from the same
 * `GET /api/finance/budgets` body the phone's budget views read ([BudgetsWire]), so the web and the
 * phone draw one card. Every figure is one the body states, in Long cents; the projection is said to
 * be an estimate, and a saved copy says so in words. Pure.
 */
object BudgetCards {
    private val MONTH_WORDS = DateTimeFormatter.ofPattern("MMMM yyyy", Locale.US)

    /** Each capped category's pace, the ones that need attention first; null with no read, not USD, or no caps. */
    @Suppress("ReturnCount") // no read, not USD, no caps, a card
    fun pace(read: FinanceRead<BudgetsWire>?, nowMs: Long): ConversationCard.StatusList? {
        val b = read?.valueOrNull ?: return null
        if (!b.currency.equals("USD", ignoreCase = true)) return null
        val capped = b.categories.filter { (it.capCents ?: 0L) > 0L }
        if (capped.isEmpty()) return null
        val rows = capped.map { row(it, b) }
            .sortedWith(compareBy<StatusRow>({ it.status != RowStatus.ATTENTION }, { it.name }))
        val saved = read as? FinanceRead.Saved
        val note = listOfNotNull(
            "Altogether ${money(b.total.spentCents)} of ${money(b.total.capCents)}.".takeIf { b.total.capCents > 0L },
            "Too early in the month to project a month end.".takeIf { b.daysElapsed < b.projectionFromDay },
            saved?.note,
        ).joinToString(" ").ifBlank { null }
        return ConversationCard.StatusList(
            title = "Budget pace",
            items = rows.take(ConversationCard.MAX_ROWS),
            more = (rows.size - ConversationCard.MAX_ROWS).coerceAtLeast(0),
            period = "${monthWords(b.month)}, day ${b.daysElapsed} of ${b.daysInMonth}",
            note = note,
            provenance = CardProvenance(readAtMs = saved?.savedAtMs ?: nowMs),
            open = CardDestination.MONEY,
        )
    }

    private fun row(line: BudgetLineWire, b: BudgetsWire): StatusRow {
        val spent = line.spentCents
        val cap = line.capCents ?: 0L
        val of = "${money(spent)} of ${money(cap)}"
        val projected = line.projectionCents
        val (status, words) = when {
            line.status == "over" -> RowStatus.ATTENTION to "Over: $of."
            line.status == "near" -> RowStatus.ATTENTION to "Past 80%: $of."
            projected != null && projected > cap -> RowStatus.ATTENTION to "On pace to go over: $of."
            else -> RowStatus.OK to "On track: $of."
        }
        val extra = listOfNotNull(
            projected?.let { "About ${money(it)} by month end (estimate)." },
            "${money(line.pendingCents)} of it pending.".takeIf { line.pendingCount > 0 },
            "Includes unverified rows.".takeIf { line.unverified },
        )
        // A net refund (spent below zero) has no bar to draw: the words carry it alone.
        val meter = if (spent >= 0) {
            StatusMeter(BigDecimal.valueOf(spent), BigDecimal.valueOf(cap), "usd", paceMark(cap, b))
        } else {
            null
        }
        return StatusRow(line.category, status, (listOf(words) + extra).joinToString(" "), meter = meter)
    }

    /** Where an even pace through the month would be today, in whole cents; null without the days. */
    private fun paceMark(cap: Long, b: BudgetsWire): CardMark? {
        if (b.daysInMonth <= 0) return null
        val elapsed = minOf(b.daysElapsed, b.daysInMonth).toLong()
        val cents = (cap * elapsed + b.daysInMonth / 2) / b.daysInMonth
        return CardMark(BigDecimal.valueOf(cents), "Even pace, day ${b.daysElapsed}")
    }

    /** "$1,234.56", the engine's `money_words`. */
    private fun money(cents: Long): String = PhoneCards.money(cents, "usd")

    private fun monthWords(month: String): String =
        runCatching { YearMonth.parse(month).format(MONTH_WORDS) }.getOrDefault(month)
}
