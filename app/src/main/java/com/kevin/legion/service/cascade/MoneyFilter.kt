package com.kevin.legion.service.cascade

/**
 * After the phone has spoken a balance answer itself ([com.kevin.legion.ai.VerbatimSpeech]), the
 * model may still answer the rest of his question, but any sentence of its own that carries a money
 * amount is dropped before speech (stage 7, 2026-11-17: no amount he hears is the model's). Sentence
 * by sentence as text streams; a dropped sentence is counted, never logged as text. Pure.
 *
 * A money amount ([hasMoney]): a currency sign before a digit, a decimal like 12.50, digits followed
 * by dollars / bucks / cents, any of the words dollar(s) / buck(s) / cent(s), or a run of two or more
 * number words ("eleven ninety-five", "two thousand", "four four one zero"). One number word alone
 * ("two things due") is not money.
 */
internal class MoneyFilter {
    private val buf = StringBuilder()

    var dropped = 0
        private set

    /** Whole sentences that may be spoken; the unfinished tail is held. */
    fun feed(delta: String): String {
        buf.append(delta)
        val out = StringBuilder()
        while (true) {
            val end = sentenceEnd(buf) ?: break
            val sentence = buf.substring(0, end)
            buf.delete(0, end)
            if (hasMoney(sentence)) dropped++ else out.append(sentence)
        }
        return out.toString()
    }

    /** The model is done: the last sentence, unless it carries money. */
    fun flush(): String {
        val rest = buf.toString()
        buf.setLength(0)
        if (rest.isBlank() || !hasMoney(rest)) return rest
        dropped++
        return ""
    }

    companion object {
        private const val ENDS = ".!?…"
        private val SYMBOL = Regex(
            """[$€£]\s?\d|\d+\.\d{2}\b|\d[\d,]*\s*(dollars?|bucks?|cents?)\b""",
            RegexOption.IGNORE_CASE,
        )
        private val MONEY_WORD = Regex("""\b(dollars?|bucks?|cents?)\b""", RegexOption.IGNORE_CASE)
        private val SPLIT = Regex("""[^a-z\-]+""")
        private val NUMBER_WORDS = setOf(
            "zero", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten", "eleven",
            "twelve", "thirteen", "fourteen", "fifteen", "sixteen", "seventeen", "eighteen", "nineteen", "twenty",
            "thirty", "forty", "fifty", "sixty", "seventy", "eighty", "ninety", "hundred", "thousand", "million",
        )

        /** End index (after one following space) of the first finished sentence, or null. */
        fun sentenceEnd(s: CharSequence): Int? {
            for (i in 0 until s.length - 1) {
                if (s[i] in ENDS && s[i + 1].isWhitespace()) return i + 2
            }
            return null
        }

        fun hasMoney(sentence: String): Boolean =
            SYMBOL.containsMatchIn(sentence) || MONEY_WORD.containsMatchIn(sentence) || numberRun(sentence)

        /** Two or more number words in a row ("eleven ninety-five", "four four one zero"). */
        private fun numberRun(sentence: String): Boolean {
            val words = sentence.lowercase().split(SPLIT)
            return words.zipWithNext().any { (a, b) -> isNumberWord(a) && isNumberWord(b) }
        }

        private fun isNumberWord(word: String) = word.isNotEmpty() && word.split('-').all { it in NUMBER_WORDS }
    }
}
