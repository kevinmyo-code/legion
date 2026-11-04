package com.kevin.legion.service

import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Which month `get_monthly_spend` read, said in its result (2026-11-04 phone test: "What did I
 * spend last month?" read November, and the model then said it had no way to pull last month).
 * The declaration takes no parameter, and adding one would cost Live setup bytes
 * (`LiveSetupPayloadSizeTest`), so the RESULT says which month it covers and where any other month
 * is: `ask_engine`, whose engine `monthly_spend` reads any month. An optional `month` argument is
 * still honoured, in case a caller passes one.
 */
internal object SpendMonth {

    private val WORDS = DateTimeFormatter.ofPattern("MMMM yyyy", Locale.US)

    /** The month to read and, for an unreadable [raw], a sentence saying the current one was used. */
    fun resolve(raw: String, current: YearMonth): Pair<YearMonth, String?> {
        val text = raw.trim()
        if (text.isEmpty()) return current to null
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
        "This covers ${words(month)} only. For any other month (last month, " +
            "${words(month.minusMonths(1))}), call ask_engine with the user's question: the engine " +
            "reads any month's spending. Never say another month cannot be looked up."
}
