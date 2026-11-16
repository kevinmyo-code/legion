package com.kevin.legion.service.tools.day

import java.time.LocalDate
import java.time.ZoneId
import java.time.format.TextStyle
import java.util.Locale

/**
 * A date argument as the model may send it: `yyyy-MM-dd`, or the word the user said, `today`,
 * `tomorrow` or `yesterday`, resolved here on the phone's own LOCAL date (2026-11-16).
 *
 * Why: in the 2026-11-15 voice eval, flash-lite answered a follow-up "and tomorrow?" by asking for
 * today+2, in every evening run, even with today, tomorrow and yesterday spelled out as local dates
 * in the preamble; single-turn "tomorrow" was always right. The model doing date arithmetic is the
 * failure, so the arithmetic moves into Kotlin: the tool hint tells it to pass the word.
 */
internal object RelativeDay {
    /** [raw] as a date, or null when it is neither a known word nor `yyyy-MM-dd`. */
    fun parse(raw: String, today: LocalDate): LocalDate? = when (raw.trim().lowercase(Locale.US)) {
        "today" -> today
        "tomorrow" -> today.plusDays(1)
        "yesterday" -> today.minusDays(1)
        else -> runCatching { LocalDate.parse(raw.trim()) }.getOrNull()
    }

    fun today(zone: ZoneId = ZoneId.systemDefault()): LocalDate = LocalDate.now(zone)

    /** "Monday 2026-11-16": how a resolved day is said back in a result. */
    fun label(day: LocalDate): String = "${day.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.US)} $day"
}
