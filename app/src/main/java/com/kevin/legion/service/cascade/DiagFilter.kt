package com.kevin.legion.service.cascade

/**
 * After the phone has spoken a diagnostics answer itself ([com.kevin.legion.ai.VerbatimSpeech], tool
 * `get_codes`: the engine code read or the module scan), the model may still add a sentence, but one
 * that restates the result is dropped before speech (stage 7 diagnostics, 2026-11-17: flash-lite
 * turned "No stored engine codes ... pending codes and the other modules were not read" into
 * "Clean bill"). The sibling of [MoneyFilter], same sentence-by-sentence stream. Pure.
 *
 * Dropped ([restatesResult]): a bare module count ("Seven modules answered."); a bare trouble code
 * ("P0420 is still there.", "P zero four two zero again."), or any code the spoken line did not say;
 * a bare verdict ("Clean bill.", "All good.");
 * a bare scripted filler line ("Checking."); or a sentence repeating the spoken line word for word.
 *
 * **Never dropped (2026-11-21, review of the engine twin, V1):** a sentence with a safety cue
 * ([SAFETY_CUES], "It's not okay to drive: pull over."), whatever else it says; a verdict word inside
 * other content ("I can clear them if you say yes.", "It's clear tonight."); and a code explained
 * ("P0301 means cylinder 1 is misfiring."). Before, any sentence with a verdict word or a code was
 * dropped, so a warning could be silenced. [SAFETY_CUES] matches the engine's list
 * (`server/agents/legion/limb_verbatim.py`).
 */
internal class DiagFilter(private val said: String? = null) : SentenceFilter {
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
            if (restatesResult(sentence, said)) dropped++ else out.append(sentence)
        }
        return out.toString()
    }

    override fun flush(): String {
        val rest = buf.toString()
        buf.setLength(0)
        if (rest.isBlank() || !restatesResult(rest, said)) return rest
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

        /** A warning about driving the car: never dropped. The engine's list, the one list. */
        val SAFETY_CUES = listOf(
            "pull over", "pull off", "stop driving", "don't drive", "do not drive", "flashing", "overheating",
            "overheated", "overheat", "smoke", "burning", "brakes", "oil pressure", "temperature warning",
        )
        private val SAFETY = Regex(
            "\\b(" + SAFETY_CUES.joinToString("|") { Regex.escape(it) } + ")\\b",
            RegexOption.IGNORE_CASE,
        )

        /** The words a bare verdict or a bare code mention is made of: any other word says something new. */
        private val BARE = (
            "a all an and are again be bill car car's clean clear cleared code codes completely engine engine's " +
                "everything everything's fine gone good got has have health healthy here is issue issues it it's its " +
                "look looking looks no nothing of ok okay one pending pretty problem problems right seem seems " +
                "set so still stored that that's the there there's this totally very was with wrong you you're " +
                "your zero oh two three four five six seven eight nine"
            ).split(" ").toSet()
        /** A module-count sentence made only of these (and [BARE]) says nothing new ("Seven modules answered."). */
        private val COUNT_BARE = setOf(
            "answered", "responded", "replied", "read", "scanned", "checked", "were", "came", "back", "up", "in",
            "out", "on", "reported", "module", "modules", "s", "computers", "controllers", "ecus",
        )
        private val WORD = Regex("[a-z]+(?:'[a-z]+)?")
        private val DIGIT_WORDS = listOf("zero", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine")
        private val JOINED_CODE = Regex("\\b[pcbu][0-3][0-9a-f]{3}\\b")
        private val CODE_GAP = Regex("(?<=\\b[pcbu]|[0-9])[\\s-]+(?=[0-9])")
        private const val ENDS = ".!?\u2026"

        /** Fewest words for a sentence to count as repeating the spoken line. */
        private const val REPEAT_MIN_WORDS = 3

        /**
         * True when [sentence] only restates the diagnostics answer already said ([said], when known);
         * never a sentence with a safety cue.
         */
        fun restatesResult(sentence: String, said: String? = null): Boolean {
            val text = sentence.replace('\u2019', '\'')
            if (SAFETY.containsMatchIn(text)) return false
            val core = text.trim().trimEnd(*ENDS.toCharArray()).lowercase()
            return (COUNT.containsMatchIn(text) && bare(COUNT.replace(core, " "), COUNT_BARE)) ||
                (CODE.containsMatchIn(text) && codeRestates(core, said)) ||
                (VERDICT.containsMatchIn(text) && bare(core)) ||
                repeats(core, said) ||
                LeadingFiller.core(sentence) in LeadingFiller.CORES
        }

        private fun bare(core: String, more: Set<String> = emptySet()): Boolean =
            WORD.findAll(core).all { it.value in BARE || it.value in more }

        /**
         * A sentence naming a code restates the read when it says nothing else, or when any code in it
         * was not in the spoken line: the model may explain a code it was given, never say one itself
         * (review D1, 2026-11-21: the text model garbles figures, the reason the line is spoken by code).
         */
        private fun codeRestates(core: String, said: String?): Boolean {
            if (bare(CODE.replace(core, " "))) return true
            val codes = codesIn(core)
            return codes.isEmpty() || said == null || !codesIn(said).containsAll(codes)
        }

        /** The codes in [text], written as P0301 whether said "P0301", "p 0 3 0 1" or "P zero three oh one". */
        fun codesIn(text: String): Set<String> {
            var t = text.lowercase()
            DIGIT_WORDS.forEachIndexed { d, w -> t = t.replace(Regex("\\b$w\\b"), "$d") }
            t = t.replace(Regex("\\boh\\b"), "0").replace(CODE_GAP, "")
            return JOINED_CODE.findAll(t).map { it.value.uppercase() }.toSet()
        }

        /** The sentence is the spoken line, or a run of it, word for word. */
        private fun repeats(core: String, said: String?): Boolean {
            val words = WORD.findAll(core).map { it.value }.toList()
            if (said == null || words.size < REPEAT_MIN_WORDS) return false
            val line = WORD.findAll(said.replace('\u2019', '\'').lowercase()).joinToString(" ") { it.value }
            return " $line ".contains(" ${words.joinToString(" ")} ")
        }
    }
}

/** A filter on the model's own sentences after a verbatim answer ([MoneyFilter], [DiagFilter]). */
internal interface SentenceFilter {
    val dropped: Int
    fun feed(delta: String): String
    fun flush(): String
}
