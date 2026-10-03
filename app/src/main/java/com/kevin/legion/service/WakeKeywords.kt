package com.kevin.legion.service

/**
 * Builds the keywords file for stage 1 from the phrases [WakePhrases.grammar] already derives from
 * the companion name (ticket 09: never hardcoded). One line per phrase, so "excelsior" (the
 * shipping phrase, Kevin 2026-09-10) and "hey <name>" are both spotted exactly as Vosk's grammar
 * has them - stage 1 must not be narrower than the phrase set stage 2 and Settings promise.
 */
object WakeKeywords {

    /** Why a keywords file could not be built, in words for the debug panel. */
    sealed interface Result {
        data class Ok(val fileText: String) : Result
        data class Refused(val reason: String) : Result
    }

    fun build(companionName: String, tokenizer: BpeKeywordTokenizer): Result {
        // A blank name is not a refusal: WakePhrases.WAKE does not depend on it. It only means the
        // "hey <name>" line is absent, same as the Vosk grammar.
        val phrases = WakePhrases.grammar(companionName)
        val lines = phrases.map { tokenizer.tokenise(it) }
        val failed = phrases.zip(lines).firstOrNull { it.second == null }?.first
        return when {
            failed != null -> Result.Refused(
                "cannot encode \"$failed\" for the keyword spotter (a character the model does not know)",
            )
            lines.isEmpty() -> Result.Refused("no wake phrase")
            else -> Result.Ok(lines.joinToString("\n") + "\n")
        }
    }
}
