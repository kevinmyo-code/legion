package com.kevin.legion.books

/** How a spoken or typed name resolved against the listed books. */
sealed interface BookMatch {
    data class One(val item: BookItem) : BookMatch

    /** More than one book fits and none fits clearly better; the caller names them and asks. */
    data class Several(val items: List<BookItem>) : BookMatch

    data object None : BookMatch
}

/**
 * Pure: finds a book by a name as people say it ("moby dick", "the outlander manual"). An exact
 * normalised title wins; otherwise every word of the query must appear in the title, author or file
 * name. Ties that remain are [BookMatch.Several]: guessing which of two books to open is how the
 * wrong one gets opened.
 */
object BookMatcher {
    fun find(items: List<BookItem>, query: String): BookMatch {
        val q = normalise(query)
        if (q.isEmpty()) return BookMatch.None
        val exact = items.filter { normalise(it.title) == q || normalise(it.book.fileTitle) == q }
        return pick(exact.ifEmpty { partial(items, q) })
    }

    /** One match is a match, none is none, and several are named rather than guessed between. */
    private fun pick(found: List<BookItem>): BookMatch = when (found.size) {
        0 -> BookMatch.None
        1 -> BookMatch.One(found.first())
        else -> BookMatch.Several(found.take(MAX_NAMED))
    }

    private fun partial(items: List<BookItem>, q: String): List<BookItem> {
        val words = q.split(' ').filter { it.isNotEmpty() && it !in STOP_WORDS }.ifEmpty { q.split(' ') }
        return items.filter { item ->
            val haystack = normalise("${item.title} ${item.author.orEmpty()} ${item.book.fileTitle}")
            words.all { it in haystack }
        }
    }

    private fun normalise(text: String): String =
        text.lowercase().replace(Regex("[^a-z0-9]+"), " ").trim()

    private val STOP_WORDS = setOf("the", "a", "an", "book", "of")
    private const val MAX_NAMED = 4
}
