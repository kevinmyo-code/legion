package com.kevin.legion.meditations

/**
 * One numbered section of the Meditations, in George Long's 1862 translation.
 *
 * [book] is 1..12 and [section] is the number Long printed (the first section of each book is
 * unnumbered in the original and is 1 here). [text] is the section with its paragraphs joined by a
 * blank line, verbatim from `assets/meditations/meditations.txt` - see `NOTICE.txt` beside it for
 * exactly what was stripped from the Gutenberg file to make that one.
 */
data class Passage(val book: Int, val section: Int, val text: String) {
    /** "Book IV, 3" - the one citation form the persona is told to speak, so it is built in one place. */
    val cite: String get() = "Book ${MeditationsText.toRoman(book)}, $section"
}

/**
 * Parses the bundled Meditations file and turns "Book IV" into 4 and back.
 *
 * Pure: no Context, no asset access, so a unit test can feed it the real file from the source tree.
 *
 * **It throws rather than returning a short list.** A loader that quietly yields eleven books, or a
 * book with no sections, would make [MeditationsSearch] answer "nothing matches" for half the text
 * and the persona would say the Meditations contain no such passage when they do. That is CLAUDE.md
 * section 4 rule 6 (a check that passes when nothing parsed is not a gate) in a new place, so a
 * malformed file fails loudly here and `MeditationsTextTest` pins the real section counts.
 */
object MeditationsText {

    private val ROMANS = listOf("I", "II", "III", "IV", "V", "VI", "VII", "VIII", "IX", "X", "XI", "XII")

    fun toRoman(book: Int): String = ROMANS.getOrElse(book - 1) { book.toString() }

    /** "IV" / "iv" / "4" to 4, or null when it is none of those or outside 1..12. */
    fun bookNumber(token: String): Int? {
        val t = token.trim()
        t.toIntOrNull()?.let { return if (it in 1..ROMANS.size) it else null }
        val idx = ROMANS.indexOf(t.uppercase())
        return if (idx >= 0) idx + 1 else null
    }

    /**
     * Format: `## IV` starts a book, `### 3` starts a section, and every non-blank line after that
     * is one paragraph of the section. Anything before the first `## ` is ignored.
     */
    fun parse(raw: String): List<Passage> {
        val out = mutableListOf<Passage>()
        var book = 0
        var section = 0
        val paragraphs = mutableListOf<String>()

        fun flush() {
            if (book != 0 && section != 0) {
                check(paragraphs.isNotEmpty()) { "Meditations ${toRoman(book)}, $section has no text" }
                out += Passage(book, section, paragraphs.joinToString("\n\n"))
            }
            paragraphs.clear()
        }

        for (line in raw.lineSequence()) {
            when {
                line.startsWith("## ") -> {
                    flush()
                    book = checkNotNull(bookNumber(line.removePrefix("## "))) { "bad book heading: $line" }
                    section = 0
                }
                line.startsWith("### ") -> {
                    flush()
                    section = checkNotNull(line.removePrefix("### ").trim().toIntOrNull()) {
                        "bad section heading: $line"
                    }
                }
                line.isNotBlank() && section != 0 -> paragraphs += line.trim()
            }
        }
        flush()
        check(out.isNotEmpty()) { "the Meditations file parsed to zero passages" }
        check(out.map { it.book }.toSet().size == ROMANS.size) { "the Meditations file is missing a book" }
        return out
    }
}
