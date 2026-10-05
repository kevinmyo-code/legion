package com.kevin.legion.meditations

import com.kevin.legion.ai.CrisisDetector

/**
 * The one decision both paths to the text go through: the voice tool
 * ([com.kevin.legion.service.MeditationsToolbox]) and the Companions screen's reader
 * (`ui/companions/MeditationsDialog.kt`). CLAUDE.md section 7 (ADR 0035): the hands path calls the
 * same controller as the voice path, never a second implementation of the capability, because two
 * implementations drift into disagreeing about what a question returns.
 */
object MeditationsLookup {

    sealed interface Outcome {
        /** Nothing was asked. */
        data object Blank : Outcome

        /**
         * The query looks like distress ([CrisisDetector]). The text has passages about leaving life,
         * which are history and never an answer to a person who is hurting, so no lookup is made.
         */
        data object Distress : Outcome

        /** Nothing matched. Never a padded list of weak guesses. */
        data object NoMatch : Outcome

        data class Found(val hits: List<MeditationsSearch.Hit>) : Outcome
    }

    /**
     * "Book IV, 3", "book 4 section 3", "Book II.1" - the one shape worth a direct fetch. Group 1 is
     * the book (Roman or Arabic), group 2 the section. A raw string, so the backslashes are the regex's.
     */
    private val REFERENCE = Regex(
        """\bbook\s+([ivx]{1,4}|\d{1,2})\b[\s,.:;-]*""" +
            """(?:section|chapter|number|no\.?|verse|passage|paragraph)?[\s,.:;#-]*(\d{1,2})\b""",
        RegexOption.IGNORE_CASE,
    )

    fun run(index: MeditationsSearch, query: String, limit: Int = DEFAULT_LIMIT): Outcome = when {
        query.isBlank() -> Outcome.Blank
        CrisisDetector.detect(query) -> Outcome.Distress
        else -> {
            val hits = referenceHit(index, query)?.let { listOf(it) } ?: index.search(query, limit)
            if (hits.isEmpty()) Outcome.NoMatch else Outcome.Found(hits)
        }
    }

    private fun referenceHit(index: MeditationsSearch, query: String): MeditationsSearch.Hit? {
        val m = REFERENCE.find(query)
        val book = m?.let { MeditationsText.bookNumber(it.groupValues[1]) }
        return if (m != null && book != null) index.lookup(book, m.groupValues[2].toInt()) else null
    }

    private const val DEFAULT_LIMIT = 3
}
