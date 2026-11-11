package com.kevin.legion.service.cascade

/**
 * Cuts a streamed reply into sentences for text-to-speech, one at a time (design doc, "How
 * sentence streaming gets there"): the first sentence is spoken while the model is still writing
 * the next, so speech adds the time of one short sentence, never the whole reply.
 *
 * A boundary is `.`, `!` or `?` (closing quotes and brackets ride along) followed by whitespace, or
 * a line break. Not a boundary: a decimal or money figure (`3.5`, `$1,284.17`: no space after the
 * point), an abbreviation (`Dr.`, `p.m.`, `e.g.`), or a single-letter initial (`J. Smith`).
 *
 * The FIRST sentence of a reply may also be cut at a comma, semicolon, colon or dash once it holds
 * [FIRST_CLAUSE_WORDS] words, so a long opening sentence does not hold back the first audio.
 *
 * Not thread-safe; one per reply.
 */
class SentenceSplitter(private val firstClauseWords: Int = FIRST_CLAUSE_WORDS) {
    private val buf = StringBuilder()
    private var emitted = 0

    /** Appends [delta]; returns every sentence it completed, trimmed, in order. */
    fun feed(delta: String): List<String> {
        if (delta.isEmpty()) return emptyList()
        buf.append(delta)
        val out = mutableListOf<String>()
        while (true) {
            val cut = nextCut() ?: break
            val sentence = buf.substring(0, cut).trim()
            buf.delete(0, cut)
            if (sentence.isNotEmpty()) {
                out.add(sentence)
                emitted++
            }
        }
        return out
    }

    /** Whatever is left once the reply is complete, or null if nothing is. */
    fun flush(): String? {
        val rest = buf.toString().trim()
        buf.setLength(0)
        if (rest.isEmpty()) return null
        emitted++
        return rest
    }

    /** The end index (exclusive) of the next complete sentence in [buf], or null. */
    private fun nextCut(): Int? {
        var i = 0
        while (i < buf.length) {
            val c = buf[i]
            if (c == '\n') return i + 1
            if (c == '.' || c == '!' || c == '?') {
                var j = i + 1
                while (j < buf.length && buf[j] in CLOSERS) j++
                // Need to see what follows: a boundary is only known once whitespace arrives.
                if (j >= buf.length) return clauseCut()
                if (buf[j].isWhitespace() && !(c == '.' && isAbbreviation(i))) return j
            }
            i++
        }
        return clauseCut()
    }

    /** First sentence only: a clause break after enough words. */
    private fun clauseCut(): Int? {
        if (emitted > 0) return null
        var words = 0
        var inWord = false
        for (k in 0 until buf.length) {
            val c = buf[k]
            if (c.isWhitespace()) {
                inWord = false
            } else if (!inWord) {
                inWord = true
                words++
            }
            if (words >= firstClauseWords && c in CLAUSE_MARKS && k + 1 < buf.length && buf[k + 1].isWhitespace()) {
                // "1,284" has no space after the comma, so it never lands here.
                return k + 1
            }
        }
        return null
    }

    /** True when the `.` at [dot] ends an abbreviation or an initial rather than a sentence. */
    private fun isAbbreviation(dot: Int): Boolean {
        var s = dot - 1
        while (s >= 0 && (buf[s].isLetter() || buf[s] == '.')) s--
        val word = buf.substring(s + 1, dot).lowercase()
        if (word.isEmpty()) return false
        if (word.length == 1 && word[0].isLetter() && buf[dot - 1].isUpperCase()) return true
        return word in ABBREVIATIONS
    }

    companion object {
        const val FIRST_CLAUSE_WORDS = 12
        private const val CLOSERS = "\"')]”’"
        private const val CLAUSE_MARKS = ",;:–—"
        private val ABBREVIATIONS = setOf(
            "mr", "mrs", "ms", "dr", "st", "jr", "sr", "vs", "etc", "e.g", "i.e", "a.m", "p.m",
            "approx", "u.s", "u.k", "mt", "inc", "ave",
        )
    }
}

/**
 * What the laptop voice is handed: plain speakable text (contract section 4: "the phone normalises
 * numbers before sending"). The espeak front end behind Kokoro reads `$1,284.17` as "dollar one
 * thousand ... point one seven" (design doc, measured), so money and grouped numbers are rewritten
 * here, and markdown the model sometimes emits despite the voice clause is stripped.
 */
object SpokenText {
    private val MONEY = Regex("\\$(\\d{1,3}(?:,\\d{3})+|\\d+)(?:\\.(\\d{1,2}))?")
    private val GROUPED = Regex("(?<=\\d),(?=\\d{3}\\b)")
    private val BULLET = Regex("(?m)^\\s*(?:[-*•]|#{1,6})\\s+")
    private val EMPHASIS = Regex("[*_`]{1,3}")
    private val PERCENT = Regex("(\\d)\\s?%")
    private val SPACES = Regex("[ \\t]{2,}")

    fun forSpeech(text: String): String {
        var t = text
        t = BULLET.replace(t, "")
        t = EMPHASIS.replace(t, "")
        t = MONEY.replace(t) { m ->
            val dollars = m.groupValues[1].replace(",", "")
            val centsRaw = m.groupValues[2]
            val cents = if (centsRaw.isEmpty()) 0 else centsRaw.padEnd(2, '0').toInt()
            val unit = if (dollars == "1") "dollar" else "dollars"
            when {
                cents == 0 -> "$dollars $unit"
                dollars == "0" -> "$cents ${if (cents == 1) "cent" else "cents"}"
                else -> "$dollars $unit and $cents ${if (cents == 1) "cent" else "cents"}"
            }
        }
        t = GROUPED.replace(t, "")
        t = PERCENT.replace(t, "$1 percent")
        t = t.replace("&", " and ")
        t = SPACES.replace(t, " ")
        return t.trim()
    }
}
