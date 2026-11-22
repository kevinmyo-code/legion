package com.kevin.legion.service.tools.day

import com.kevin.legion.data.local.Event
import com.kevin.legion.vehicle.ClearCodesVoiceGate
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

/** [EventRemoval]'s words: the read-back of an entry's day and time, and his answer read as no. Pure. */
internal object EventRemovalWords {
    /** The entry's day in words (today, tomorrow, or a short date) and its clock time, or "all day". */
    fun whenPhrase(row: Event, today: LocalDate, zone: ZoneId): String {
        val start = row.startsAt
        // An all-day row is UTC midnight of its date (activeByKindInLocalWindow's doc comment).
        val allDay = start?.let { Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate() }
        val at = start?.let { Instant.ofEpochMilli(it).atZone(zone) }
        return when {
            start == null || at == null || allDay == null -> "with no time"
            row.allDay -> "${dayWords(allDay, today)}, all day"
            else -> "${dayWords(at.toLocalDate(), today)} at ${clockWords(at.toLocalTime())}"
        }
    }

    fun dayWords(date: LocalDate, today: LocalDate): String = when (date) {
        today -> "today"
        today.plusDays(1) -> "tomorrow"
        today.minusDays(1) -> "yesterday"
        else -> date.format(SHORT_DAY)
    }

    private fun clockWords(t: LocalTime): String {
        val hour = if (t.hour % HALF_DAY == 0) HALF_DAY else t.hour % HALF_DAY
        val half = if (t.hour < HALF_DAY) "am" else "pm"
        return if (t.minute == 0) "$hour $half" else "$hour:${"%02d".format(t.minute)} $half"
    }

    /** A plain no: a no word and no yes word. Errs to false (then it is only asked again). */
    fun isPlainNo(text: String): Boolean {
        val tokens = tokens(text)
        val joined = " ${tokens.joinToString(" ")} "
        val no = tokens.any { it in NO_WORDS } || NO_PHRASES.any { joined.contains(" $it ") }
        return no && !ClearCodesVoiceGate.isExplicitYes(text)
    }

    private fun tokens(text: String): List<String> =
        text.lowercase().replace('’', '\'').replace(Regex("[^a-z' ]"), " ")
            .split(' ').map { it.trim('\'') }.filter { it.isNotEmpty() }

    fun normalise(text: String?): String = tokens(text.orEmpty()).joinToString(" ")

    private const val HALF_DAY = 12
    val SHORT_DAY = DateTimeFormatter.ofPattern("EEE MMM d", Locale.US)
    private val NO_WORDS = setOf("no", "nope", "nah", "don't", "dont", "cancel", "keep")
    private val NO_PHRASES = listOf("never mind", "leave it", "not that")
}
