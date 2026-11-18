package com.kevin.legion.service.cascade

/**
 * What the cloud transcriber is told, and what of its answer is believed (2026-11-17 phone logs:
 * English speech came back as short Spanish phrases and a Chinese interjection, and the
 * last Spanish one reached the model, which called `car clear_codes` on it).
 *
 * Two halves, both pure:
 * - [hint]: a compact vocabulary line for the STT request (never the text model's prompt): the
 *   words he uses for the car and the tools. No names.
 * - [verdict]: a transcript that is not plausibly English, or far too short for the audio it came
 *   from, is not sent to the model. The session treats it as words it did not catch (it says so,
 *   and the floor is his), never as an instruction.
 */
internal object SttGuard {
    /**
     * The fixed half of the hint: his car and tool words that a general model mishears. Words only, never
     * an example number or sentence (2026-11-17, Midcon: a transcriber handed examples wrote them back as
     * "speech" from room noise). Trouble codes are described by shape, not by an example code.
     */
    const val DOMAIN_WORDS = "OBD, OBD adapter, dongle, trouble codes (a letter then four digits), clear the " +
        "codes, check engine light, coolant, odometer, oil change, recall, to-do list, reminder, calendar, timer, navigate"


    /**
     * The hint line: his car and tool words only. No names (2026-11-17 real-model run with invented
     * names: on noise, the hint's names came back as "speech"); the names stay on the phone as
     * [verdict]'s `known`, never counted foreign.
     */
    fun hint(): String = "Words he often says: $DOMAIN_WORDS."

    /** Every word the fixed hint names, lower case: a transcript of nothing else was not heard, it was read. */
    private val DOMAIN_VOCAB: Set<String> =
        words(DOMAIN_WORDS) - setOf("a", "the", "then", "four", "digits", "letter", "to", "do", "list", "light")

    private fun words(text: String): Set<String> =
        text.lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotEmpty() }.toSet()

    /** Drop reasons that mean "nothing was said": silence, never a spoken "didn't catch that". */
    val SILENT_REASONS: Set<String> = setOf("too_short", "hint_only")

    sealed interface Verdict {
        /** Believed: [text] goes to the model (a non-Latin aside like "嗯" already removed). */
        data class Keep(val text: String) : Verdict

        /** Not believed; [reason] is a log token, never the words. */
        data class Drop(val reason: String) : Verdict
    }

    /**
     * [text] as heard from [audioMs] of audio; [known] are names on the phone (one word each, lower
     * case), never counted as foreign; [afterQuestion]: the last thing said asked him something, so a
     * one-word answer is expected. Dropped when:
     * - nothing is left once scripts none of his languages use are removed (`not_english_script`);
     * - it reads as Spanish (`not_english`): `¿` or `¡`, or at least two thirds of its words are
     *   Spanish-only words (accented letters alone never count: "café", "José", "jalapeño");
     * - one word from [LONG_AUDIO_MS] or more of audio, not a number, and not an answer to a question
     *   (`too_short`, silence);
     * - two or more words, all from the hint's fixed words, and not an answer to a question
     *   (`hint_only`, silence: the transcriber read its hint back).
     * Digits and number words are never dropped as short ("5." answers "how many minutes?").
     */
    @Suppress("ReturnCount", "CyclomaticComplexMethod") // one exit per reason, one clause per rule
    fun verdict(
        text: String,
        audioMs: Long,
        known: Set<String> = emptySet(),
        /** His languages ([SpokenLanguages]); English alone is the pin. */
        accepted: List<SpokenLanguages.Language> = listOf(SpokenLanguages.ENGLISH),
        afterQuestion: Boolean = false,
    ): Verdict {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return Verdict.Keep("")
        // Scripts none of his languages use (CJK, Cyrillic, Arabic...) are removed word by word; words in
        // his languages' scripts stay.
        val scripts = accepted.map { it.script }.toSet() + Character.UnicodeScript.LATIN
        val words = trimmed.split(Regex("\\s+")).filter { it.isNotEmpty() }
        val ok = words.filter { w -> w.none { it.isLetter() && Character.UnicodeScript.of(it.code) !in scripts } }
        if (ok.none { w -> w.any { it.isLetterOrDigit() } }) return Verdict.Drop("not_english_script")
        val kept = ok.joinToString(" ")
        val bare = ok.map { w -> w.trim { !it.isLetterOrDigit() }.lowercase() }.filter { it.isNotEmpty() }
        // Spanish look-alikes count against English only while no other Latin-script language is his.
        val latinOther = accepted.any { it != SpokenLanguages.ENGLISH && it.script == Character.UnicodeScript.LATIN }
        if (!latinOther) {
            if (kept.any { it in SPANISH_MARKS }) return Verdict.Drop("not_english")
            val names = known.map { it.lowercase() }.toSet()
            val foreign = bare.count { w -> w !in names && w in SPANISH_WORDS }
            if (bare.isNotEmpty() && foreign * FOREIGN_DENOMINATOR >= bare.size * FOREIGN_NUMERATOR) {
                return Verdict.Drop("not_english")
            }
        }
        val number = bare.isNotEmpty() && bare.all { w -> w.all(Char::isDigit) || w in NUMBER_WORDS }
        val oneWordLong = bare.size < 2 && audioMs >= LONG_AUDIO_MS
        if (!afterQuestion && !number && oneWordLong) return Verdict.Drop("too_short")
        if (!afterQuestion && bare.size >= 2 && bare.all { it in DOMAIN_VOCAB }) return Verdict.Drop("hint_only")
        return Verdict.Keep(kept)
    }

    private fun isLatin(c: Char): Boolean = Character.UnicodeScript.of(c.code) == Character.UnicodeScript.LATIN

    const val LONG_AUDIO_MS = 3_000L

    /** Two words in three. */
    private const val FOREIGN_NUMERATOR = 2
    private const val FOREIGN_DENOMINATOR = 3

    private const val SPANISH_MARKS = "¿¡"

    private val NUMBER_WORDS = setOf(
        "zero", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten", "eleven", "twelve",
        "fifteen", "twenty", "thirty", "forty", "fifty", "sixty", "hundred", "half", "a", "an",
    )

    /** Common Spanish words that are not English words too ("no", "me", "a" are not here). */
    private val SPANISH_WORDS = setOf(
        "que", "qué", "el", "los", "las", "del", "por", "para", "con", "una", "pero", "porque", "cómo",
        "dónde", "esto", "eso", "esta", "está", "estoy", "dijo", "gracias", "hola", "sí", "muy",
        "hay", "tengo", "quiero", "puedes", "vamos", "bueno", "ahora", "entonces", "nada", "todo", "también",
        "oye", "mira", "claro", "vale", "lo", "le", "les", "es", "mi", "tu", "su", "nos",
    )
}
