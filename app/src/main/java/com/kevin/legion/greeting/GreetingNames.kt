package com.kevin.legion.greeting

/**
 * The greeting calls him by his own name or by none (2026-11-20 bug: a model-written hello on wake
 * addressed him by an invented first name, twice in five back-to-back wakes; the same evening's
 * ruling: the app may be given his real name, [com.kevin.legion.ai.OwnerName]).
 *
 * Why it happened, as far as the code shows: the persona said to use his first name "if you know
 * it" and no voice was given one; the hello is also handed the last hellos as wording not to reuse
 * ([GreetingPrompt.hello]), so after a few wakes the plain phrasings are used up. [rule] is the
 * prompt lever; [addresses] is the backstop the session runs on the whole line before any of it is
 * spoken.
 *
 * The check looks for a word used to address someone: a capitalised name or title in a vocative
 * spot (", Name" ending a sentence, "Name," or "Name -" opening one, ", Name," inside one, "hey
 * Name", "so Name what now", "what now Name?"), a title before a name ("Master Name"), and the
 * lowercase forms of address the persona forbids or a butler reaches for (", sir.", "boss", "old
 * boy", "my friend"). Ordinary openers ("Well,", "Right,") are not names. A false hit costs one
 * scripted hello instead of a model one, which is cheap; a missed name is the bug. Pure.
 */
object GreetingNames {
    /** The prompt line, for the hello and the personal greeting alike. [name] is his, or null. */
    fun rule(name: String?): String {
        val known = name?.trim()?.takeIf { it.isNotEmpty() }
            ?: return "- Call him by no name at all, for this greeting only: no first name, nickname, " +
                "title or character name, and never one you make up. This overrides any other instruction " +
                "about using his name."
        return "- Call him by no name other than $known, and only now and then, not in every greeting: " +
            "never an invented name, nickname, title or character name. This overrides any other " +
            "instruction about using his name."
    }

    /** True when [line] contains [name] as a word. Pure. */
    fun mentions(line: String, name: String?): Boolean {
        val n = name?.trim()?.takeIf { it.isNotEmpty() } ?: return false
        return Regex("\\b${Regex.escape(n)}\\b", RegexOption.IGNORE_CASE).containsMatchIn(line)
    }

    private const val WORD = "[A-Z][a-z]+(?:['-][A-Za-z]+)?"
    private const val TITLE = "(?:Mr|Mrs|Ms|Miss|Dr|Sir|Mister|Master|Lord|Lady|Madam|Captain|Chief|Boss)"
    private const val TITLED = "(?:$TITLE\\.?\\s+)?"
    private const val BREAK = "(?:\\s*(?:\u2014|\u2013|--|\u2026|\\.\\.\\.)|\\s+-\\s)"
    private const val HELLO_WORDS =
        "(?i:hey|hi|hello|hiya|howdy|morning|evening|afternoon|yo|ahoy|welcome back|there you are)"
    private const val LEAD_WORDS = "(?i:so|well|okay|ok|right|alright|oh|now|look|listen|yes|yeah)"
    private const val ASK_WORDS =
        "(?:what|what's|how|how's|where|who|why|when|ready|you|you're|are|is|did|do|got|any|shall|let's|go)"
    private const val TAIL_WORDS = "(?:now|then|there|up|ahead|again|anyway|it|you|me)"

    /** Lowercase forms of address: the persona's own ban ("sir", "madam", "Mr") and the butler set. */
    private const val ADDRESS =
        "(?i:sir|madam|ma'am|mister|master|my lord|my lady|boss|chief|guv|governor|old boy|old chap|" +
            "old sport|old friend|old man|my friend|my dear|my man|friend|buddy|pal|mate|champ|sport|" +
            "partner|dear|darling|young man|captain|sunshine|stranger)"

    private val NAMED = listOf(
        Regex(",\\s*$TITLED($WORD)\\s*[.!?\u2026]*[\"')]*\\s*$"),
        Regex("^[\"'(]*\\s*$TITLED($WORD)\\s*(?:,|$BREAK)"),
        Regex(",\\s*$TITLED($WORD)\\s*(?:,|$BREAK)"),
        Regex("\\b$HELLO_WORDS,?\\s+$TITLED($WORD)\\b"),
        Regex("\\b$LEAD_WORDS,?\\s+$TITLED($WORD)(?:,|$BREAK|\\s+$ASK_WORDS\\b)"),
        Regex("\\b$TAIL_WORDS\\s+$TITLED($WORD)\\s*[.!?\u2026]+[\"')]*\\s*$"),
    )

    // A title before a name is never allowed, his own name included ("Master Sam").
    private val ADDRESSED = listOf(
        Regex("\\b$TITLE\\.?\\s+$WORD\\b"),
        Regex(",\\s*$ADDRESS\\s*(?:[.!?\u2026,]|$BREAK|$)"),
        Regex("^[\"'(]*\\s*$ADDRESS\\s*(?:[,.!?]|$BREAK)"),
        Regex("\\b$HELLO_WORDS,?\\s+$ADDRESS\\b"),
    )
    private val SENTENCE_END = Regex("(?<=[.!?])\\s+(?=[A-Z\"'(])")

    /** Capitalised words that open or close a clause without being anyone's name. */
    private val NOT_NAMES = setOf(
        "well", "so", "right", "alright", "okay", "ok", "oh", "ah", "aha", "hey", "hi", "hello", "hiya",
        "howdy", "yo", "ahoy", "morning", "evening", "afternoon", "yes", "yeah", "yep", "no", "nope",
        "sure", "look", "listen", "now", "ready", "good", "hm", "hmm", "huh", "mm", "welcome", "still",
        "anyway", "then", "again", "there", "here", "indeed", "cheers", "sorry", "fine", "great", "nice",
        "wow", "ooh", "whoa", "ha", "heh", "er", "um", "uh", "please", "also", "plus", "though", "however",
        "go", "fire", "with", "listening", "and", "but", "or", "yours", "you", "me", "it", "that", "this",
        "what", "who", "see", "back", "in", "on", "up", "it's", "that's", "here's", "there's", "what's",
        "let's", "you're", "we're", "i'm", "i've", "i'll", "i'd",
        // A day or a month can follow "it" or "up" ("back on it Monday"): never a name here.
        "monday", "tuesday", "wednesday", "thursday", "friday", "saturday", "sunday", "today", "tonight",
        "january", "february", "march", "april", "june", "july", "august", "september", "october",
        "november", "december",
    )

    /** True when [line] addresses someone by anything but [allowed] (null: no name is allowed). */
    fun addresses(line: String, allowed: String? = null): Boolean {
        val ok = allowed?.trim()?.takeIf { it.isNotEmpty() }
        return line.trim().split(SENTENCE_END).any { s -> named(s, ok) || ADDRESSED.any { it.containsMatchIn(s) } }
    }

    private fun named(sentence: String, allowed: String?): Boolean =
        NAMED.any { re -> re.findAll(sentence).any { isName(it.groupValues[1], allowed) } }

    private fun isName(word: String, allowed: String?): Boolean {
        val w = word.lowercase()
        if (w in NOT_NAMES || w.endsWith("ly")) return false
        return allowed == null || !word.equals(allowed, ignoreCase = true)
    }
}
