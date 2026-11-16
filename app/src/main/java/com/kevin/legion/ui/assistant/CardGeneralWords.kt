package com.kevin.legion.ui.assistant

import com.kevin.legion.assistant.cards.PhoneCards
import com.kevin.legion.assistant.cards.Reading
import com.kevin.legion.assistant.cards.TrendSeries
import java.math.BigDecimal
import java.math.RoundingMode
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * The words of the general kinds (readings, status_list, trend), the same sentences the web says
 * (`frontend/src/components/assistant/cards/types.ts`) and `shared/cards/fixtures.json` pins.
 * The one deliberate difference: the phone's clock is 24-hour (ADR 0081), the web's is not.
 */
internal object CardGeneralWords {

    private val AT = Regex("""^(\d{4}-\d{2}-\d{2})(?:T(\d{2}:\d{2}))?$""")
    private val DAY = DateTimeFormatter.ofPattern("EEE MMM d, yyyy", Locale.US)
    private val SHORT = DateTimeFormatter.ofPattern("MMM d", Locale.US)

    /** "72.5", "203,000": at most three decimals, no trailing zeros, halves away from zero (as JS). */
    fun number(value: BigDecimal): String =
        DecimalFormat("#,##0.###", DecimalFormatSymbols(Locale.US))
            .apply { roundingMode = RoundingMode.HALF_UP }
            .format(value)

    /** "$1,234.56" (usd is integer cents), "45%", "72.5 °C", "3". */
    fun withUnit(value: BigDecimal, unit: String): String = when (unit) {
        "usd" -> PhoneCards.money(value.toLong(), "usd")
        "" -> number(value)
        "%" -> number(value) + "%"
        else -> number(value) + " " + unit
    }

    /**
     * "not read", or the value, its earlier value, its flag and whether it needs attention:
     * "32 g (estimate)", "96 °C (needs attention)".
     */
    fun reading(r: Reading): String {
        val value = r.value ?: return r.text ?: "not read"
        val was = r.was?.let { " (was ${withUnit(it, r.unit)})" } ?: ""
        val flag = r.flag?.let { " (${it.words})" } ?: ""
        val attention = if (r.attention) " (needs attention)" else ""
        return withUnit(value, r.unit) + was + flag + attention
    }

    /** "As of Sun Nov 15, 2026, 14:20", or the day alone; null when the card states no time. */
    fun asOf(at: String?): String? {
        val m = at?.let { AT.matchEntire(it) }
        val day = m?.let { runCatching { LocalDate.parse(it.groupValues[1]).format(DAY) }.getOrNull() }
        val clock = m?.groupValues?.get(2).orEmpty()
        return day?.let { if (clock.isBlank()) "As of $it" else "As of $it, $clock" }
    }

    /** "Weight: latest 182.4 lb on Nov 14, low 181 lb, high 186.2 lb." */
    fun series(s: TrendSeries, unit: String): String {
        val name = s.flag?.let { "${s.name} (${it.words})" } ?: s.name
        val last = s.points.lastOrNull() ?: return "$name: nothing recorded in this window."
        val low = s.points.minOf { it.value }
        val high = s.points.maxOf { it.value }
        return "$name: latest ${withUnit(last.value, unit)} on ${shortDay(last.at)}, " +
            "low ${withUnit(low, unit)}, high ${withUnit(high, unit)}."
    }

    /** "Nov 14" from an `at`. */
    fun shortDay(at: String): String =
        AT.matchEntire(at)?.let { runCatching { LocalDate.parse(it.groupValues[1]).format(SHORT) }.getOrNull() } ?: at

    /** Minutes since 1970 for an `at`, read as a clock time with no zone: for placing points only. */
    fun minutes(at: String): Long {
        val m = AT.matchEntire(at) ?: return 0L
        val time = m.groupValues[2].ifBlank { "00:00" }
        return runCatching {
            LocalDateTime.parse("${m.groupValues[1]}T$time").toEpochSecond(ZoneOffset.UTC) / SECONDS_PER_MINUTE
        }.getOrDefault(0L)
    }

    private const val SECONDS_PER_MINUTE = 60L
}
