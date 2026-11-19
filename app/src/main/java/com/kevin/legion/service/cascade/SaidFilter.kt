package com.kevin.legion.service.cascade

/**
 * After code said a write's confirmation in a turn ([ScriptedLines.doneLine]), a sentence of the
 * model's that names the thing just changed is not spoken (Kevin, 2026-11-19, ruling A: the code
 * line IS the confirmation; the eval measured the model repeating it in 28 to 50% of write turns).
 *
 * "Names" is membership, never a reading of intent: the sentence contains one of [names], the very
 * strings the code line used ([ScriptedLines.namesIn]), case- and space-folded, as a whole word in
 * a script written with spaces (a substring in one written without). A sentence without one is
 * spoken as before: an answer to something else, a question. Sentence by sentence as text streams,
 * cut by the speaker's own splitter; a dropped sentence is counted, never logged as text. Pure.
 *
 * Known limit: a useful sentence about the same item ("Oat milk was already on the list twice") is
 * not spoken in that turn.
 */
internal class SaidFilter(names: List<String>) : SentenceFilter {
    // The first-clause cut is for speech latency; here a whole sentence is judged at once.
    private val splitter = SentenceSplitter(firstClauseWords = Int.MAX_VALUE)
    private val patterns = names.map { pattern(it) }

    override var dropped = 0
        private set

    override fun feed(delta: String): String = keep(splitter.feed(delta))

    override fun flush(): String = keep(listOfNotNull(splitter.flush()))

    private fun keep(sentences: List<String>): String = buildString {
        for (s in sentences) {
            if (patterns.any { it.containsMatchIn(fold(s)) }) dropped++ else append(s).append(' ')
        }
    }

    companion object {
        /** Lowercase, every run of whitespace one space. */
        fun fold(s: String): String = s.lowercase().replace(Regex("\\s+"), " ").trim()

        /** [name] folded, as a whole word where its script has spaces, else as a substring. */
        fun pattern(name: String): Regex {
            val n = Regex.escape(fold(name))
            val spaced = name.codePoints().noneMatch { Character.UnicodeScript.of(it) in LoneWordWriteGuard.UNSPACED }
            return if (spaced) Regex("(?<![\\p{L}\\p{N}])$n(?![\\p{L}\\p{N}])") else Regex(n)
        }
    }
}
