package com.kevin.legion.service.cascade

/**
 * After the phone has spoken a diagnostics answer itself ([com.kevin.legion.ai.VerbatimSpeech], tool
 * `get_codes`: the engine code read or the module scan), the model may still add a sentence, but one
 * that restates the result is dropped before speech (stage 7 diagnostics, 2026-11-17: flash-lite
 * turned "No stored engine codes ... pending codes and the other modules were not read" into
 * "Clean bill"). The sibling of [MoneyFilter], same sentence-by-sentence stream. Pure.
 *
 * Dropped ([restatesResult]): a trouble code (P0420, "P zero four two zero", "p 0 4 2 0"), a module
 * count ("seven modules", "3 module(s)"), or a verdict word: clear, cleared, clean, fine, healthy,
 * ok / okay, all good, no problems / issues, nothing wrong; or a bare scripted filler line ("Checking.").
 * Anything else may pass ("Want me to look one of them up?").
 */
internal class DiagFilter : SentenceFilter {
    private val buf = StringBuilder()

    override var dropped = 0
        private set

    override fun feed(delta: String): String {
        buf.append(delta)
        val out = StringBuilder()
        while (true) {
            val end = MoneyFilter.sentenceEnd(buf) ?: break
            val sentence = buf.substring(0, end)
            buf.delete(0, end)
            if (restatesResult(sentence)) dropped++ else out.append(sentence)
        }
        return out.toString()
    }

    override fun flush(): String {
        val rest = buf.toString()
        buf.setLength(0)
        if (rest.isBlank() || !restatesResult(rest)) return rest
        dropped++
        return ""
    }

    companion object {
        private const val SPOKEN_DIGITS = "zero|oh|one|two|three|four|five|six|seven|eight|nine"
        private val CODE = Regex(
            "\\b[pcbu][\\s-]?[0-3][0-9a-f]{3}\\b|\\b[pcbu]([\\s-]+[0-9a-f]){4}\\b|\\b[pcbu][\\s-]+($SPOKEN_DIGITS)\\b",
            RegexOption.IGNORE_CASE,
        )
        private val COUNT = Regex(
            "\\b(\\d+|no|one|two|three|four|five|six|seven|eight|nine|ten|eleven|twelve|several|all)\\s+" +
                "(modules?|module\\(s\\)|computers?|controllers?|ecus?)\\b",
            RegexOption.IGNORE_CASE,
        )
        private val VERDICT = Regex(
            "\\b(clear|cleared|clean|fine|healthy|ok|okay|all good|no problems?|no issues?|nothing wrong)\\b",
            RegexOption.IGNORE_CASE,
        )

        /** Also a bare scripted filler line said after the answer ("... (Bank 1). Checking."). */
        fun restatesResult(sentence: String): Boolean =
            CODE.containsMatchIn(sentence) || COUNT.containsMatchIn(sentence) || VERDICT.containsMatchIn(sentence) ||
                LeadingFiller.core(sentence) in LeadingFiller.CORES
    }
}

/** A filter on the model's own sentences after a verbatim answer ([MoneyFilter], [DiagFilter]). */
internal interface SentenceFilter {
    val dropped: Int
    fun feed(delta: String): String
    fun flush(): String
}
