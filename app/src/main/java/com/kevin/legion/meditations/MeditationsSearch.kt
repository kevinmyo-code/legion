package com.kevin.legion.meditations

import kotlin.math.ln

/**
 * Deterministic keyword search over the Meditations. No network, no embeddings, no randomness: the
 * same question returns the same passages in the same order on every phone, which is what lets a
 * unit test hold the quality bar and lets the persona be told "quote only what this returned".
 *
 * **Why this and not an embedding.** The text is 487 sections, ~250 KB. BM25 over stemmed words
 * scans it in a few milliseconds, ships nothing, needs no model, and every score can be explained
 * by naming the words that matched. The one weakness of keyword search is vocabulary - the user
 * says "stress" and Long wrote "perturbation", the user says "what others think" and Long wrote
 * "the opinion of our neighbours" - so [MeditationsVocabulary.CONCEPTS] is a small hand-written
 * bridge from modern words and phrases to Long's. Expansions count half a direct hit, so a passage
 * that uses the user's own word still outranks one reached only through the bridge.
 *
 * Ties break on (book, section) so the order is total.
 */
class MeditationsSearch(val passages: List<Passage>) {

    /** A search hit. [text] is verbatim; [isExcerpt] is true when it is part of a longer section. */
    data class Hit(val passage: Passage, val text: String, val isExcerpt: Boolean, val score: Double)

    private class Doc(val passage: Passage, val tf: Map<String, Int>, val length: Int)

    private val docs: List<Doc> = passages.map { p ->
        val stems = tokenize(p.text).map(::stem)
        Doc(p, stems.groupingBy { it }.eachCount(), stems.size)
    }
    private val avgLength: Double = docs.map { it.length }.average().takeIf { !it.isNaN() } ?: 1.0
    private val docFrequency: Map<String, Int> =
        HashMap<String, Int>().also { df -> docs.forEach { d -> d.tf.keys.forEach { df.merge(it, 1, Int::plus) } } }

    fun lookup(book: Int, section: Int): Hit? =
        passages.firstOrNull { it.book == book && it.section == section }?.let { excerptOf(it, emptyMap()) }

    /**
     * Up to [limit] best-matching sections for [query], best first. Empty means NOTHING matched -
     * never a padded list of weak guesses, because a weak passage presented as an answer is a
     * quotation the model will happily build a sentence on.
     */
    fun search(query: String, limit: Int = DEFAULT_LIMIT): List<Hit> {
        val terms = queryTerms(query)
        if (terms.isEmpty()) return emptyList()
        val scored = docs
            .map { d -> d to terms.entries.sumOf { (stem, weight) -> termScore(d, stem, weight) } }
            .filter { it.second > 0.0 }
            .sortedWith(
                compareByDescending<Pair<Doc, Double>> { it.second }
                    .thenBy { it.first.passage.book }.thenBy { it.first.passage.section },
            )
        return scored.take(limit).map { (d, s) -> excerptOf(d.passage, terms).copy(score = s) }
    }

    /** One term's BM25 contribution to one section: 0 when either the section or the corpus lacks it. */
    private fun termScore(d: Doc, stem: String, weight: Double): Double {
        val tf = d.tf[stem]
        val df = docFrequency[stem]
        if (tf == null || df == null) return 0.0
        val idf = ln(1.0 + (docs.size - df + IDF_SMOOTHING) / (df + IDF_SMOOTHING))
        val norm = tf + K1 * (1 - B + B * d.length / avgLength)
        return weight * idf * tf * (K1 + 1) / norm
    }

    /**
     * The whole section when it is short, else the sentences around the strongest match.
     *
     * An excerpt is a contiguous slice of [Passage.text] by character offset, never sentences
     * re-joined: the persona is told it may quote exactly what this returns, so what this returns
     * must be a substring of the book.
     */
    private fun excerptOf(p: Passage, terms: Map<String, Double>): Hit {
        if (p.text.length <= WHOLE_SECTION_CHARS) return Hit(p, p.text, false, 0.0)
        // Sentence boundaries as [start, end) offsets into p.text.
        val bounds = mutableListOf<IntRange>()
        var from = 0
        for (m in SENTENCE_BREAK.findAll(p.text)) {
            bounds += from until m.range.first
            from = m.range.last + 1
        }
        bounds += from until p.text.length
        val strength = bounds.map { r ->
            tokenize(p.text.substring(r.first, r.last + 1)).map(::stem).sumOf { terms[it] ?: 0.0 }
        }
        val best = strength.indices.maxByOrNull { strength[it] } ?: 0
        var start = if (best > 0 && strength[best] > 0.0) best - 1 else best
        var end = start
        while (end + 1 < bounds.size && bounds[end + 1].last + 1 - bounds[start].first <= EXCERPT_CHARS) end++
        // Spend any spare room on the sentence before, so a "But this is so..." is not orphaned.
        while (start > 0 && bounds[end].last + 1 - bounds[start - 1].first <= EXCERPT_CHARS) start--
        val text = p.text.substring(bounds[start].first, bounds[end].last + 1)
        return Hit(p, text, text.length < p.text.length, 0.0)
    }

    private fun queryTerms(query: String): Map<String, Double> {
        val words = tokenize(query)
        val terms = LinkedHashMap<String, Double>()
        for (token in words.filter { it !in MeditationsVocabulary.STOP }) terms[stem(token)] = 1.0
        if (terms.isEmpty()) return emptyMap()
        // Concepts fire on the stems of the question or on a phrase in it.
        val direct = terms.keys.toSet()
        val padded = " ${words.joinToString(" ")} "
        for (concept in MeditationsVocabulary.CONCEPTS) {
            val hit = concept.triggers.any { t ->
                if (t.contains(' ')) padded.contains(" $t ") else stem(t) in direct
            }
            if (hit) for (w in concept.words) terms.putIfAbsent(stem(w), EXPANSION_WEIGHT)
        }
        return terms
    }

    /** A suffix [stem] may strip: only from a word of at least [minLength], never after [notAfter]. */
    private class Suffix(
        val text: String,
        val minLength: Int,
        val replacement: String = "",
        val notAfter: Char? = null,
    ) {
        fun applies(w: String): Boolean =
            w.length >= minLength && w.endsWith(text) &&
                (notAfter == null || w[w.length - text.length - 1] != notAfter)

        fun strip(w: String): String = w.dropLast(text.length) + replacement
    }

    companion object {
        private const val DEFAULT_LIMIT = 3
        private const val K1 = 1.2
        private const val B = 0.75
        private const val IDF_SMOOTHING = 0.5
        private const val EXPANSION_WEIGHT = 0.5
        private const val WHOLE_SECTION_CHARS = 1_100
        private const val EXCERPT_CHARS = 950
        private val SENTENCE_BREAK = Regex("(?<=[.?!])\\s+(?=[A-Z\"\\[])")
        private val WORD = Regex("[a-z]+(?:'[a-z]+)?")

        /** "-ies" becomes "-y" and ends the matter; otherwise at most one of the next four applies. */
        private val PLURAL_IES = Suffix("ies", 6, replacement = "y")
        private val INFLECTIONS = listOf(
            Suffix("ing", 7), Suffix("ed", 6), Suffix("es", 6), Suffix("s", 5, notAfter = 's'),
        )
        private val TAILS = listOf(Suffix("ly", 6), Suffix("e", 5))

        private fun tokenize(text: String): List<String> =
            WORD.findAll(text.lowercase()).map { it.value.removeSuffix("'s") }.toList()

        /** Crude suffix stripping, applied identically to the text and the question. */
        internal fun stem(word: String): String {
            if (PLURAL_IES.applies(word)) return PLURAL_IES.strip(word)
            var w = INFLECTIONS.firstOrNull { it.applies(word) }?.strip(word) ?: word
            for (tail in TAILS) if (tail.applies(w)) w = tail.strip(w)
            return w
        }
    }
}
