package com.kevin.legion.service.cascade

/**
 * Gemini TTS expression tags (Kevin, 2026-11-17: "i like neno plain. and with tags where possible").
 * Measured in our request shape (`.legion/reports/tts-styles-1117.md`, private): a tag in the text is
 * performed, never read aloud; `<long pause>` adds ~1.5 s, `<breath>` is barely audible, so neither is
 * allowed. Only [ALLOWED].
 *
 * The rules, enforced here whatever the model writes:
 * - a tag not on [ALLOWED] is removed;
 * - a sentence with a figure (any digit, a money word, a trouble code), a refusal, an error or bad news,
 *   and every filler and scripted error line, carries none ([forSpeech] with `plain`);
 * - at most one per sentence and [MAX_PER_REPLY] per reply ([Budget]);
 * - only a voice that performs them gets them ([TextToSpeech.performsTags]); the laptop voice would read
 *   them aloud, so it gets none;
 * - tags live ONLY on the text handed to the voice. Everything read, stored or compared (captions, the
 *   transcript and history, audit lines, the money and diagnostics filters, memory) sees [strip]ped
 *   text.
 * Pure.
 */
internal object TtsTags {
    val ALLOWED: Set<String> = setOf("chuckle", "laugh", "sigh", "short pause")
    const val MAX_PER_REPLY = 2

    /** Any `<word>` or `<two words>` tag the model might write. */
    private val TAG = Regex("""\s*<\s*([a-zA-Z][a-zA-Z ]{0,20}?)\s*>""")
    private val FIGURE = Regex(
        """\d|\$|\b(dollars?|cents?|bucks?|percent|[pcbu][\s-]?[0-3][0-9a-f]{3})\b""",
        RegexOption.IGNORE_CASE,
    )
    private val BAD_NEWS = Regex(
        """\b(sorry|can'?t|cannot|couldn'?t|won'?t|didn'?t|not|no|nothing|failed|error|unable|refus\w*|""" +
            """overdue|late|missed|lost|wrong|problem|warning|fault|broke\w*|sick|hurt|died|unfortunately)\b""",
        RegexOption.IGNORE_CASE,
    )

    /** [text] with every tag removed and the spaces it leaves tidied: what is read, stored and compared. */
    fun strip(text: String): String {
        if ('<' !in text) return text
        return TAG.replace(text, "").replace(Regex(" {2,}"), " ").let { s ->
            // A tag at the very start leaves no leading space; one before punctuation leaves none either.
            s.replace(Regex(" +([.,!?;:])"), "$1")
        }
    }

    /** True when [text] has any tag. */
    fun hasTag(text: String): Boolean = TAG.containsMatchIn(text)

    /** A figure or bad news: never a tag. */
    private fun mustBePlain(bare: String): Boolean = FIGURE.containsMatchIn(bare) || BAD_NEWS.containsMatchIn(bare)

    /** How many tags this reply may still carry. */
    class Budget(var left: Int = MAX_PER_REPLY)

    /**
     * One sentence as the voice gets it: disallowed tags gone; all gone when [plain] (a filler, a
     * scripted error, a verbatim answer) or the sentence holds a figure or bad news; otherwise the
     * first allowed tag kept if [budget] allows.
     */
    @Suppress("ReturnCount") // no tag, none allowed here, or the kept one
    fun forSpeech(sentence: String, budget: Budget, plain: Boolean = false): String {
        if (!hasTag(sentence)) return sentence
        val bare = strip(sentence)
        if (plain || budget.left <= 0 || mustBePlain(bare)) return bare
        var kept = false
        val out = TAG.replace(sentence) { m ->
            val name = m.groupValues[1].trim().lowercase().replace(Regex("\\s+"), " ")
            if (!kept && name in ALLOWED) {
                kept = true
                " <$name>"
            } else {
                ""
            }
        }.trim().replace(Regex(" {2,}"), " ")
        if (kept) budget.left--
        return out
    }
}
