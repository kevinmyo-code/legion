package com.kevin.legion.finance

import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.abs

/**
 * How the money screens say a figure, matching Kevin's approved prototype
 * (`legion-money-prototypes/money-screens.html`): whole dollars, "$1,402" for USD and "SGD 980"
 * for SGD. **A deliberate departure from [com.kevin.legion.ledger.formatMoney]'s "USD 1,402.00"**,
 * scoped to these screens: they show one household currency at a time, with the currency said
 * whenever it is not USD.
 */
object FinanceText {
    private const val CENTS = 100L
    private const val HALF = 50L
    private const val THOUSAND = 1_000L
    private const val COMPACT_FROM_DOLLARS = 10_000L
    private const val MICROS = 1_000_000L

    /** Whole units, half away from zero. */
    fun wholeUnits(cents: Long): Long = (abs(cents) + HALF) / CENTS * if (cents < 0) -1 else 1

    private fun grouped(units: Long): String =
        abs(units).toString().reversed().chunked(3).joinToString(",").reversed()

    /** "$1,402", "-$1,657", "SGD 980". */
    fun money(cents: Long, currency: String = "USD"): String {
        val units = wholeUnits(cents)
        val sign = if (units < 0) "-" else ""
        return if (currency == "USD") "$sign$${grouped(units)}" else "$sign$currency ${grouped(units)}"
    }

    /** "-$1,745.74", "SGD 980.00": to the cent, for a transaction or an account balance. */
    fun exact(cents: Long, currency: String = "USD"): String {
        val sign = if (cents < 0) "-" else ""
        val body = "${grouped(abs(cents) / CENTS)}.${(abs(cents) % CENTS).toString().padStart(2, '0')}"
        return if (currency == "USD") "$sign$$body" else "$sign$currency $body"
    }

    /** "$148.2k" from $10,000 up, else [money]. */
    fun compact(cents: Long, currency: String = "USD"): String {
        val units = wholeUnits(cents)
        if (abs(units) < COMPACT_FROM_DOLLARS) return money(cents, currency)
        val tenths = (abs(units) * 10 + THOUSAND / 2) / THOUSAND
        val sign = if (units < 0) "-" else ""
        val body = "${tenths / 10}.${tenths % 10}k"
        return if (currency == "USD") "$sign$$body" else "$sign$currency $body"
    }

    /** "119" for the bar's "spent / cap" pair: whole dollars, no symbol. */
    fun bare(cents: Long): String {
        val units = wholeUnits(cents)
        return (if (units < 0) "-" else "") + grouped(units)
    }

    /** USD cents to SGD cents at [rateMicros] (1 USD = rateMicros / 1e6 SGD), half away from zero. */
    fun usdToSgd(usdCents: Long, rateMicros: Long): Long {
        val product = abs(usdCents) * rateMicros
        val rounded = (product + MICROS / 2) / MICROS
        return if (usdCents < 0) -rounded else rounded
    }

    /** "1 USD = 1.2946 SGD": the rate said in full, four places. */
    fun rate(rateMicros: Long): String {
        val tenThousandths = (rateMicros + HALF) / CENTS
        val frac = (tenThousandths % 10_000).toString().padStart(4, '0')
        return "1 USD = ${tenThousandths / 10_000}.$frac SGD"
    }

    /** "Nov 5". */
    fun day(date: LocalDate): String =
        "${date.month.getDisplayName(TextStyle.SHORT, Locale.ENGLISH)} ${date.dayOfMonth}"

    fun parseDay(iso: String?): LocalDate? = iso?.let { runCatching { LocalDate.parse(it) }.getOrNull() }

    /** "today", "yesterday", "Nov 3", or "Nov 3, 2025": an as-of day in words. */
    fun asOf(date: LocalDate, today: LocalDate): String = when (date) {
        today -> "today"
        today.minusDays(1) -> "yesterday"
        else -> if (date.year == today.year) day(date) else "${day(date)}, ${date.year}"
    }
}
