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
import org.json.JSONObject

/** [EventRemoval]'s words and days: the read-back, his answer read as a yes or a no, the window. Pure. */
internal object EventRemovalWords {
    /** With no day named, the entries looked through: today and this many days after. */
    const val LOOK_AHEAD_DAYS = 60L

    val SHORT_DAY: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE MMM d", Locale.US)

    /** Which pool his words named, if one. */
    enum class Kind { REMINDER, ENTRY }

    /** The days looked through: the day he named, else today through [LOOK_AHEAD_DAYS] after. */
    fun window(day: LocalDate?, today: LocalDate): Pair<LocalDate, LocalDate> =
        if (day != null) day to day else today to today.plusDays(LOOK_AHEAD_DAYS)

    /** The entry's day in words (today, tomorrow, or a short date) and its clock time, or "all day". */
    fun whenPhrase(row: Event, today: LocalDate, zone: ZoneId): String {
        val start = row.startsAt
        val day = rowDay(row, zone)
        return when {
            start == null || day == null -> "with no time"
            row.allDay -> "${dayWords(day, today)}, all day"
            else -> "${dayWords(day, today)} at ${clockWords(Instant.ofEpochMilli(start).atZone(zone).toLocalTime())}"
        }
    }

    /** The calendar day a row is on: an all-day row is UTC midnight of its date (activeByKindInLocalWindow). */
    fun rowDay(row: Event, zone: ZoneId): LocalDate? = row.startsAt?.let {
        Instant.ofEpochMilli(it).atZone(if (row.allDay) ZoneOffset.UTC else zone).toLocalDate()
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

    /**
     * A yes and nothing more (review 2026-11-22): every word is a yes word, a politeness or "remove
     * it", plus his companion's name. "Yes, the Friday one" and "yeah, the other one" name something
     * else, so they are not a yes to this row. Errs to false: a refused yes costs one more question.
     */
    fun isBareYes(text: String, companionName: String): Boolean {
        if (text.contains('?')) return false
        val name = tokens(companionName).toSet()
        val tokens = tokens(text).filter { it !in name }
        val joined = " ${tokens.joinToString(" ")} "
        val saysYes = tokens.any { it in YES_WORDS } || YES_PHRASES.any { joined.contains(" $it ") }
        return saysYes && tokens.all { it in YES_WORDS || it in BARE_FILLERS }
    }

    /** "the reminder" or "the calendar one" in his words; null for neither or both. */
    fun kindNamed(text: String?): Kind? {
        val words = tokens(text.orEmpty())
        val tokens = words.toSet()
        val reminder = tokens.any { it in REMINDER_WORDS } || " ${words.joinToString(" ")} ".contains(" to do ")
        val entry = tokens.any { it in ENTRY_WORDS }
        return when {
            reminder && !entry -> Kind.REMINDER
            entry && !reminder -> Kind.ENTRY
            else -> null
        }
    }

    /** "also", "another", "too", "second" in his words: a second entry is meant, not a correction. */
    fun saysAnother(text: String?): Boolean = tokens(text.orEmpty()).any { it in ANOTHER_WORDS }

    /** Nothing was removed. [asks]: the message is a question to him, and `needs_answer` says so, so
     * his next one word may answer it (the cascade's LoneWordWriteGuard.asks). */
    fun fail(message: String, asks: Boolean = false): JSONObject {
        val out = JSONObject().put("success", false).put("message", message)
        return if (asks) out.put("needs_answer", true) else out
    }

    private fun tokens(text: String): List<String> =
        text.lowercase().replace('’', '\'').replace(Regex("[^a-z' ]"), " ")
            .split(' ').map { it.trim('\'') }.filter { it.isNotEmpty() }

    private const val HALF_DAY = 12
    private val NO_WORDS = setOf("no", "nope", "nah", "don't", "dont", "cancel", "keep")
    private val NO_PHRASES = listOf("never mind", "leave it", "not that")
    private val YES_WORDS = setOf(
        "yes", "yeah", "yep", "yup", "yea", "ya", "sure", "ok", "okay", "absolutely", "definitely",
        "correct", "alright",
    )
    private val YES_PHRASES = listOf("go ahead", "do it", "remove it", "delete it", "that's right")
    private val BARE_FILLERS = setOf(
        "please", "go", "ahead", "do", "it", "remove", "delete", "that's", "thats", "right", "hey", "thanks",
        "thank", "you", "yes", "sir",
    )
    private val REMINDER_WORDS = setOf("reminder", "reminders", "todo")
    private val ANOTHER_WORDS = setOf("also", "another", "too", "second", "both", "extra")
    private val ENTRY_WORDS = setOf("calendar", "event", "appointment", "entry", "meeting")
}
