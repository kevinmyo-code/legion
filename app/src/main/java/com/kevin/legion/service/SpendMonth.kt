package com.kevin.legion.service

import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Which month `get_monthly_spend` reads, and says in its result (2026-11-04 phone test: "What did I
 * spend last month?" read November, and the model then said it had no way to pull last month). Its
 * `month` argument (declared 2026-11-19; the tool sits behind the `ask_money` router, so it costs no
 * Live setup bytes) takes "this", "last" or YYYY-MM: the words he said are resolved here against the
 * phone's own month, never worked out by the model.
 */
internal object SpendMonth {

    private val WORDS = DateTimeFormatter.ofPattern("MMMM yyyy", Locale.US)
    private val THIS = setOf("this", "this month", "current")
    private val LAST = setOf("last", "last month", "previous", "previous month")

    /** The month to read and, for an unreadable [raw], a sentence saying the current one was used. */
    @Suppress("ReturnCount") // this, last, then a YYYY-MM or the fallback
    fun resolve(raw: String, current: YearMonth): Pair<YearMonth, String?> {
        val text = raw.trim()
        val said = text.lowercase(Locale.US)
        if (text.isEmpty() || said in THIS) return current to null
        if (said in LAST) return current.minusMonths(1) to null
        val parsed = runCatching { YearMonth.parse(text) }.getOrNull()
        return if (parsed == null) {
            current to "\"$text\" is not a month as YYYY-MM, so this is ${words(current)} instead."
        } else {
            parsed to null
        }
    }

    fun words(month: YearMonth): String = month.format(WORDS)

    /** The `other_months` result field. */
    fun otherMonths(month: YearMonth): String =
        "This covers ${words(month)} only. For another month call get_monthly_spend again with " +
            "month=last or month=YYYY-MM (${month.minusMonths(1)} is ${words(month.minusMonths(1))})."
}
