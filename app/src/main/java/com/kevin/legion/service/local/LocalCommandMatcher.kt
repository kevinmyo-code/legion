// Number words, clock hours and unit lengths are the vocabulary this parser reads: naming each
// literal (TWELVE = 12) would read worse than the table it is.
@file:Suppress("MagicNumber")

package com.kevin.legion.service.local

/**
 * Simple commands matched on the phone with no model (voice cost work phase 2; Kevin 2026-11-10:
 * "we offload as much as we can to the phones own speech recognizer").
 *
 * A closed set: timers, alarms, pause/resume/next/previous, volume, dismissal ("that's all",
 * "go to sleep", "quiet"), opening a named screen, clearing ticked lines on a named list. Anything
 * else, anything ambiguous, and anything heard with low confidence is a [LocalMatch.Miss], and the
 * caller hands the same utterance to Alfred.
 *
 * How it avoids the three known failures of phrase matchers (the 2026-11-10 voice industry notes):
 * - **Rigid templates:** it reads words, not sentences. Fillers ("please", "can you", the
 *   companion's name) are ignored, number words are numbers, and each command accepts any order of
 *   its own words ("10 minute timer", "set a timer for ten minutes").
 * - **Slow or explosive patterns:** no regular expressions over the utterance. One pass to
 *   normalise, then each parser walks the token list once: linear in the words heard.
 * - **Confident wrong answers:** every word must be accounted for by one command's vocabulary, or
 *   it is a miss ("pause and tell me the weather" is not a pause). Ambiguity is a miss ("wake me
 *   at six thirty": morning or evening?). One unknown word ([unk]) or one word under the
 *   recogniser's confidence cut-off is a miss.
 */
class LocalCommandMatcher(
    companionName: String = "",
    private val minConfidence: Float = DEFAULT_MIN_CONFIDENCE,
) {
    private val nameTokens: Set<String> = tokens(companionName).toSet()

    @Suppress("ReturnCount", "CyclomaticComplexMethod") // one early miss per reason, said in words
    fun match(utterance: Utterance): LocalMatch {
        val raw = utterance.text
        if (raw.contains(UNKNOWN)) return LocalMatch.Miss("unknown word")
        utterance.coverage()?.let { if (it < MIN_COVERAGE) return LocalMatch.Miss("most of the speech unrecognised") }
        utterance.confidences?.let { c ->
            if (c.isNotEmpty() && c.min() < minConfidence) return LocalMatch.Miss("low confidence ${c.min()}")
        }
        val all = tokens(raw)
        if (all.isEmpty()) return LocalMatch.Miss("nothing heard")
        if (all.size > MAX_WORDS) return LocalMatch.Miss("too long for a simple command")
        val words = all.filter { it !in FILLER && it !in nameTokens }
        if (words.isEmpty()) return LocalMatch.Miss("only fillers")
        val command = sleep(words) ?: timer(words) ?: alarm(words) ?: clearTicked(words) ?: volume(words)
            ?: media(words) ?: openScreen(words)
        return command?.let { LocalMatch.Hit(it, utterance.confidences?.minOrNull() ?: 1f) }
            ?: LocalMatch.Miss("not a simple command")
    }

    // --- commands --------------------------------------------------------------------------------

    private fun sleep(w: List<String>): LocalCommand? =
        if (w.joinToString(" ") in SLEEP_PHRASES) LocalCommand.Sleep else null

    @Suppress("ReturnCount") // no timer word, no duration: two different misses
    private fun timer(w: List<String>): LocalCommand? {
        if ("timer" !in w) return null
        val d = Duration.parse(w) ?: return null
        val rest = w.filterIndexed { i, t -> i !in d.used && t !in TIMER_WORDS }
        return if (rest.isEmpty() && d.seconds in 1..MAX_TIMER_SECONDS) LocalCommand.Timer(d.seconds) else null
    }

    // One exit per reason the time is not a clear time of day; the half-of-day words are the rule.
    @Suppress("ReturnCount", "CyclomaticComplexMethod", "ComplexCondition")
    private fun alarm(w: List<String>): LocalCommand? {
        if ("alarm" !in w && "wake" !in w) return null
        val nums = mutableListOf<Int>()
        val used = mutableSetOf<Int>()
        var i = 0
        while (i < w.size) {
            val n = Numbers.read(w, i)
            if (n != null) {
                nums += n.value
                for (j in i until i + n.length) used += j
                i += n.length
            } else {
                i++
            }
        }
        val text = " " + w.joinToString(" ") + " "
        val am = " am " in text || " a m " in text || " in the morning " in text
        val pm = " pm " in text || " p m " in text || " in the evening " in text || " tonight " in text ||
            " at night " in text
        val rest = w.filterIndexed { idx, t -> idx !in used && t !in ALARM_WORDS }
        if (rest.isNotEmpty() || nums.isEmpty() || nums.size > 2 || (am && pm)) return null
        val hour = nums[0]
        val minute = nums.getOrElse(1) { 0 }
        if (minute !in 0..59) return null
        val h24 = when {
            hour in 13..23 && !am -> hour
            hour == 0 && !pm -> 0
            hour in 1..12 && am -> hour % 12
            hour in 1..12 && pm -> hour % 12 + 12
            else -> return null // 1-12 with no half of the day, or out of range: ask Alfred
        }
        return LocalCommand.Alarm(h24, minute)
    }

    @Suppress("CyclomaticComplexMethod", "ComplexCondition") // each action's words, side by side
    private fun media(w: List<String>): LocalCommand? {
        if (w.any { it !in MEDIA_WORDS }) return null
        val set = w.toSet()
        val noun = set.any { it in MEDIA_NOUNS }
        val actions = buildSet {
            if ("pause" in set || ("stop" in set && noun)) add(MediaAction.PAUSE)
            if (set.any { it in RESUME_WORDS }) add(MediaAction.RESUME)
            if ("next" in set || "skip" in set) add(MediaAction.NEXT)
            if ("previous" in set || ("back" in set && ("go" in set || noun)) || ("last" in set && noun)) {
                add(MediaAction.PREVIOUS)
            }
        }
        return actions.singleOrNull()?.let { LocalCommand.Media(it) }
    }

    @Suppress("CyclomaticComplexMethod", "ComplexCondition", "ReturnCount") // as media, plus a level
    private fun volume(w: List<String>): LocalCommand? {
        val number = w.indices.firstNotNullOfOrNull { i -> Numbers.read(w, i)?.let { i to it } }
        val used = number?.let { (i, n) -> (i until i + n.length).toSet() }.orEmpty()
        val rest = w.filterIndexed { i, _ -> i !in used }
        if (rest.any { it !in VOLUME_WORDS }) return null
        val set = rest.toSet()
        val actions = buildSet {
            if (set.any { it in UP_WORDS } && ("turn" in set || "volume" in set || set.any { it in UP_ALONE })) {
                add(VolumeAction.UP)
            }
            if (set.any { it in DOWN_WORDS } && ("turn" in set || "volume" in set || set.any { it in DOWN_ALONE })) {
                add(VolumeAction.DOWN)
            }
            if ("mute" in set) add(VolumeAction.MUTE)
            if ("unmute" in set) add(VolumeAction.UNMUTE)
            if (number != null && "volume" in set) add(VolumeAction.SET)
        }
        val action = actions.singleOrNull() ?: return null
        if (action != VolumeAction.SET && number != null) return null
        val level = number?.second?.value
        if (action == VolumeAction.SET && level !in 0..100) return null
        return LocalCommand.Volume(action, if (action == VolumeAction.SET) level else null)
    }

    private fun openScreen(w: List<String>): LocalCommand? {
        val verbAt = w.indexOfFirst { it in OPEN_VERBS }
        if (verbAt != 0) return null
        val name = w.drop(1).filter { it !in SCREEN_FILLER }.joinToString(" ")
        return SCREENS[name]?.let { LocalCommand.OpenScreen(it) }
    }

    private fun clearTicked(w: List<String>): LocalCommand? {
        if (w.none { it in CLEAR_VERBS } || w.none { it in TICK_WORDS }) return null
        val name = w.filter { it !in CLEAR_VERBS && it !in TICK_WORDS && it !in CLEAR_FILLER }.joinToString(" ")
        return LISTS[name]?.let { LocalCommand.ClearTicked(it) }
    }

    companion object {
        const val DEFAULT_MIN_CONFIDENCE = 0.8f
        const val UNKNOWN = "[unk]"

        /** Under this share of the speech covered by recognised words, a hit is not trusted. */
        const val MIN_COVERAGE = 0.35f

        /**
         * Words the recogniser may hear that no command uses, so a grammar-bound recogniser does
         * not force "cancel the timer" onto "the timer" or "what's the weather" onto "the": each one
         * is an unaccounted word, so a miss.
         */
        val DECOYS: Set<String> = setOf(
            "cancel", "dont", "don't", "not", "no", "what", "whats", "what's", "how", "when", "why",
            "where", "who", "tell", "find", "call", "text", "send", "remind", "add", "weather",
            "if", "but", "after", "before", "tomorrow", "today", "every", "some",
            "message", "email", "navigate", "directions", "buy", "order",
        )
        private const val MAX_WORDS = 24
        private const val MAX_TIMER_SECONDS = 24 * 3_600

        /** Lowercase words, apostrophes dropped ("that's" is "thats"), other punctuation a space. */
        fun tokens(text: String): List<String> {
            val out = ArrayList<String>()
            val sb = StringBuilder()
            for (ch in text.lowercase()) {
                when {
                    ch.isLetterOrDigit() -> sb.append(ch)
                    ch == '\'' || ch == '’' -> Unit
                    sb.isNotEmpty() -> { out += sb.toString(); sb.clear() }
                }
            }
            if (sb.isNotEmpty()) out += sb.toString()
            return out
        }

        private val FILLER = setOf(
            "please", "can", "could", "would", "you", "hey", "ok", "okay", "just", "now", "thanks",
            "thank", "kindly", "hi",
        )
        private val SLEEP_PHRASES = setOf(
            "thats all", "that is all", "that will be all", "thatll be all", "go to sleep", "sleep",
            "stand down", "never mind", "nevermind", "be quiet", "quiet", "dismissed", "go away",
            "thats it", "nothing", "all done",
        )
        private val TIMER_WORDS = setOf("set", "start", "a", "an", "timer", "for", "of", "me", "new")
        private val ALARM_WORDS = setOf(
            "set", "an", "a", "alarm", "for", "at", "wake", "me", "up", "in", "the", "morning", "evening",
            "tonight", "night", "am", "pm", "m", "p", "oclock", "clock", "o",
        )
        private val MEDIA_NOUNS = setOf("music", "song", "track", "playback", "tune")
        private val RESUME_WORDS = setOf("resume", "unpause", "continue", "play")
        private val MEDIA_WORDS = MEDIA_NOUNS + RESUME_WORDS + setOf(
            "pause", "stop", "next", "skip", "previous", "back", "go", "last", "the", "this", "that",
            "it", "again", "a", "one", "to",
        )
        private val UP_WORDS = setOf("up", "louder", "raise")
        private val UP_ALONE = setOf("louder", "raise")
        private val DOWN_WORDS = setOf("down", "quieter", "softer", "lower")
        private val DOWN_ALONE = setOf("quieter", "softer", "lower")
        private val VOLUME_WORDS = UP_WORDS + DOWN_WORDS + setOf(
            "turn", "it", "the", "volume", "mute", "unmute", "set", "to", "percent", "a", "bit", "little",
            "music", "sound", "media", "at",
        )
        private val OPEN_VERBS = setOf("open", "show", "go", "take", "bring")
        private val SCREEN_FILLER = setOf("me", "my", "the", "to", "up", "screen", "page", "app", "tab")
        private val SCREENS = mapOf(
            "settings" to "settings", "setup" to "settings",
            "calendar" to "calendar", "agenda" to "calendar", "schedule" to "calendar",
            "lists" to "checklists", "list" to "checklists", "checklists" to "checklists",
            "ledger" to "money", "money" to "money", "budget" to "money", "finances" to "money",
            "car" to "fleet", "cars" to "fleet", "fleet" to "fleet", "garage" to "fleet",
            "body" to "body", "workouts" to "body", "fitness" to "body",
            "news" to "news", "home" to "home", "apps" to "apps", "all apps" to "apps",
            "saved places" to "places", "places" to "places",
            "bought log" to "bought", "purchases" to "bought",
        )
        private val CLEAR_VERBS = setOf("clear", "remove", "delete")
        private val TICK_WORDS = setOf("ticked", "tick", "checked", "done", "crossed")
        private val CLEAR_FILLER = setOf(
            "the", "all", "off", "items", "ones", "things", "lines", "on", "from", "my", "list", "in", "out",
        )
        private val LISTS = mapOf(
            "groceries" to "groceries", "grocery" to "groceries", "shopping" to "groceries",
            "todo" to "todo", "to do" to "todo", "todos" to "todo", "to dos" to "todo",
        )

        /**
         * Every word some command understands: the recogniser's grammar (plus `[unk]` and the
         * companion's name), so anything else it hears comes out unknown and is a miss.
         */
        val VOCABULARY: Set<String> by lazy {
            FILLER + SLEEP_PHRASES.flatMap { it.split(" ") } + TIMER_WORDS + ALARM_WORDS + MEDIA_WORDS +
                VOLUME_WORDS + OPEN_VERBS + SCREEN_FILLER + SCREENS.keys.flatMap { it.split(" ") } +
                CLEAR_VERBS + TICK_WORDS + CLEAR_FILLER + LISTS.keys.flatMap { it.split(" ") } +
                Numbers.WORDS + Duration.WORDS + setOf("half", "and", "percent", "that", "will", "be", "all")
        }
    }
}

/**
 * What the recogniser heard, with a confidence per word when it gives one, and how much of the held
 * audio its words span ([wordSeconds] of [speechSeconds]) when it says.
 */
data class Utterance(
    val text: String,
    val confidences: List<Float>? = null,
    val wordSeconds: Float? = null,
    val speechSeconds: Float? = null,
) {
    /** The share of the speech the recognised words cover, or null when unknown. */
    fun coverage(): Float? {
        val words = wordSeconds ?: return null
        val speech = speechSeconds?.takeIf { it > 0f } ?: return null
        return (words / speech).coerceAtMost(1f)
    }
}

sealed interface LocalMatch {
    data class Hit(val command: LocalCommand, val confidence: Float) : LocalMatch
    data class Miss(val reason: String) : LocalMatch
}

enum class MediaAction { PAUSE, RESUME, NEXT, PREVIOUS }

enum class VolumeAction { UP, DOWN, MUTE, UNMUTE, SET }

sealed interface LocalCommand {
    data class Timer(val seconds: Int) : LocalCommand
    data class Alarm(val hour: Int, val minute: Int) : LocalCommand
    data class Media(val action: MediaAction) : LocalCommand
    data class Volume(val action: VolumeAction, val level: Int?) : LocalCommand
    data object Sleep : LocalCommand
    data class OpenScreen(val screen: String) : LocalCommand
    data class ClearTicked(val list: String) : LocalCommand
}

/** Number words and digits: "ten", "forty five", "45", "a"/"an" only where a duration allows it. */
internal object Numbers {
    data class Read(val value: Int, val length: Int)

    private val ONES = mapOf(
        "zero" to 0, "oh" to 0, "one" to 1, "two" to 2, "three" to 3, "four" to 4, "five" to 5,
        "six" to 6, "seven" to 7, "eight" to 8, "nine" to 9, "ten" to 10, "eleven" to 11,
        "twelve" to 12, "thirteen" to 13, "fourteen" to 14, "fifteen" to 15, "sixteen" to 16,
        "seventeen" to 17, "eighteen" to 18, "nineteen" to 19,
    )
    private val TENS = mapOf(
        "twenty" to 20, "thirty" to 30, "forty" to 40, "fifty" to 50, "sixty" to 60, "seventy" to 70,
        "eighty" to 80, "ninety" to 90,
    )

    /** The number words, for the recogniser's grammar. */
    val WORDS: Set<String> get() = ONES.keys + TENS.keys

    /** The number starting at [i], or null. Digits are capped at 4 characters (no huge values). */
    @Suppress("ReturnCount") // digits, a ones word, a tens word with or without its ones
    fun read(w: List<String>, i: Int): Read? {
        val t = w.getOrNull(i) ?: return null
        if (t.length <= 4 && t.all(Char::isDigit)) return Read(t.toInt(), 1)
        ONES[t]?.let { return Read(it, 1) }
        val tens = TENS[t] ?: return null
        val next = w.getOrNull(i + 1)?.let { ONES[it] }
        return if (next != null && next in 1..9) Read(tens + next, 2) else Read(tens, 1)
    }
}

/** "ten minutes", "a minute and a half", "half an hour", "an hour and thirty minutes", "45 second". */
internal object Duration {
    data class Parsed(val seconds: Int, val used: Set<Int>)

    private val UNITS = mapOf(
        "second" to 1, "seconds" to 1, "sec" to 1, "secs" to 1,
        "minute" to 60, "minutes" to 60, "min" to 60, "mins" to 60,
        "hour" to 3_600, "hours" to 3_600, "hr" to 3_600, "hrs" to 3_600,
    )

    /** The unit words, for the recogniser's grammar. */
    val WORDS: Set<String> get() = UNITS.keys

    @Suppress("CyclomaticComplexMethod", "ReturnCount", "ComplexCondition") // one walk, one branch per word kind
    fun parse(w: List<String>): Parsed? {
        var total = 0
        val used = mutableSetOf<Int>()
        var pending: Int? = null
        var pendingAt = emptyList<Int>()
        var lastUnit = 0
        var halfNext = false
        var i = 0
        while (i < w.size) {
            val t = w[i]
            val n = Numbers.read(w, i)
            when {
                n != null -> {
                    if (pending != null) return null
                    pending = n.value
                    pendingAt = (i until i + n.length).toList()
                    i += n.length
                    continue
                }
                (t == "a" || t == "an") && UNITS.containsKey(w.getOrNull(i + 1)) && pending == null && !halfNext -> {
                    pending = 1
                    pendingAt = listOf(i)
                }
                t == "half" -> {
                    used += i
                    if (w.getOrNull(i - 1) == "a" && w.getOrNull(i - 2) == "and" && lastUnit > 0) {
                        total += lastUnit / 2
                        used += i - 1
                        used += i - 2
                    } else if (w.getOrNull(i - 1) == "and" && lastUnit > 0) {
                        total += lastUnit / 2
                        used += i - 1
                    } else {
                        halfNext = true
                        if (w.getOrNull(i + 1) == "a" || w.getOrNull(i + 1) == "an") {
                            used += i + 1
                            i++
                        }
                    }
                }
                UNITS.containsKey(t) -> {
                    val unit = UNITS.getValue(t)
                    when {
                        halfNext -> { total += unit / 2; halfNext = false }
                        pending != null -> { total += pending * unit; used += pendingAt }
                        else -> return null
                    }
                    used += i
                    pending = null
                    lastUnit = unit
                }
                t == "and" && UNITS.containsKey(w.getOrNull(i - 1)) -> used += i
            }
            i++
        }
        if (pending != null || halfNext || total == 0 && used.isEmpty()) return null
        return Parsed(total, used)
    }
}
