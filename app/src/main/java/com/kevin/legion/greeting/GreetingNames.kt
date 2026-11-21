package com.kevin.legion.greeting

/**
 * The greeting calls him by no name, or only by [allowed] (2026-11-20 bug: a model-written hello on
 * wake addressed him by an invented first name, twice in five back-to-back wakes).
 *
 * Why it happened, as far as the code shows: the persona says to use his first name "if you know it"
 * (`ai/Personas.kt`), and nothing hands the app's voices a name (`set_driver` has no handler that
 * keeps one). The hello is also given the last hellos as wording not to reuse ([GreetingPrompt.hello]),
 * so after a few wakes in a row the plain phrasings are used up. [RULE] is the prompt lever;
 * [addresses] is the backstop the session runs on the whole line before any of it is spoken.
 *
 * The check is for a capitalised word used as a vocative: ", Name" ending a sentence (a title before
 * it too, "Mr. Name"), "Name," opening one, ", Name," inside one, and "hey Name" style. Ordinary
 * openers ("Well,", "Right,") are not names. A false hit costs one scripted hello instead of a model
 * one, which is cheap; a missed name is the bug. Pure.
 */
object GreetingNames {
    /** The prompt line, for the hello and the personal greeting alike. It names no one. */
    const val RULE = "- Call him by no name at all, for this greeting only: no first name, nickname, " +
        "title or character name, and never one you make up. This overrides any other instruction " +
        "about using his name."

    private const val WORD = "[A-Z][a-z]+(?:['-][A-Za-z]+)?"
    private const val TITLED = "(?:(?:Mr|Mrs|Ms|Miss|Dr|Sir|Mister|Captain|Chief|Boss)\\.?\\s+)?"
    private val AT_END = Regex(",\\s*$TITLED($WORD)\\s*[.!?…]*[\"')]*\\s*$")
    private val AT_START = Regex("^[\"'(]*\\s*$TITLED($WORD)\\s*,")
    private val INSIDE = Regex(",\\s*$TITLED($WORD)\\s*,")
    private val AFTER_HELLO = Regex(
        "\\b(?i:hey|hi|hello|hiya|howdy|morning|evening|afternoon|yo|ahoy|welcome back|there you are)" +
            ",?\\s+$TITLED($WORD)\\b",
    )
    private val SENTENCE_END = Regex("(?<=[.!?…])\\s+")

    /** Capitalised words that open or close a clause without being anyone's name. */
    private val NOT_NAMES = setOf(
        "well", "so", "right", "alright", "okay", "ok", "oh", "ah", "aha", "hey", "hi", "hello", "hiya",
        "howdy", "yo", "ahoy", "morning", "evening", "afternoon", "yes", "yeah", "yep", "no", "nope",
        "sure", "look", "listen", "now", "ready", "good", "hm", "hmm", "huh", "mm", "welcome", "still",
        "anyway", "then", "again", "there", "here", "indeed", "cheers", "sorry", "fine", "great", "nice",
        "wow", "ooh", "whoa", "ha", "heh", "er", "um", "uh", "please", "also", "plus", "though", "however",
        "go", "fire", "with", "listening", "and", "but", "or", "yours", "you", "me", "it", "that", "this",
        "what", "who", "see", "back", "in", "on", "up", "it's", "that's", "here's", "there's", "what's",
        "let's", "you're", "we're",
    )

    /** True when [line] addresses someone by a name other than [allowed] (null: no name is allowed). */
    fun addresses(line: String, allowed: String? = null): Boolean {
        val ok = allowed?.trim()?.takeIf { it.isNotEmpty() }
        return line.trim().split(SENTENCE_END).any { sentence ->
            listOf(AT_END, AT_START, INSIDE, AFTER_HELLO).any { re ->
                re.findAll(sentence).any { isName(it.groupValues[1], ok) }
            }
        }
    }

    private fun isName(word: String, allowed: String?): Boolean {
        val w = word.lowercase()
        if (w in NOT_NAMES || w.endsWith("ly")) return false
        return allowed == null || !word.equals(allowed, ignoreCase = true)
    }
}
