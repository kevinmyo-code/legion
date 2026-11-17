package com.kevin.legion.service.cascade

/**
 * What the cloud transcriber is told, and what of its answer is believed (2026-11-17 phone logs:
 * English speech came back as "Cuidado.", "¿Qué dijiste?", "enutralizó prioridades." and "嗯", and the
 * last Spanish one reached the model, which called `car clear_codes` on it).
 *
 * Two halves, both pure:
 * - [hint]: a compact vocabulary line for the STT request (never the text model's prompt): the
 *   words he uses for the car and the tools, plus names built at runtime from data already on the
 *   phone (his cars' makes, models and nicknames, the companion's name). Nothing personal is
 *   written here; the names arrive as arguments.
 * - [verdict]: a transcript that is not plausibly English, or far too short for the audio it came
 *   from, is not sent to the model. The session treats it as words it did not catch (it says so,
 *   and the floor is his), never as an instruction.
 */
internal object SttGuard {
    /** The fixed half of the hint: his car and tool words that a general model mishears. */
    const val DOMAIN_WORDS = "OBD, OBD adapter, dongle, trouble codes, clear the codes, check engine light, " +
        "P-codes like P0420, coolant, odometer, oil change, recall, to-do list, reminder, calendar, timer, navigate"

    /** At most this many runtime names, so the hint stays a line, not a list. */
    const val MAX_NAMES = 12

    /** The hint line for [names] (cars, the companion); blank names dropped, duplicates once. */
    fun hint(names: List<String>): String {
        val clean = names.map { it.trim() }.filter { it.length in 2..MAX_NAME_CHARS }
            .distinctBy { it.lowercase() }.take(MAX_NAMES)
        val tail = if (clean.isEmpty()) "" else "; names: ${clean.joinToString(", ")}"
        return "Words he often says: $DOMAIN_WORDS$tail."
    }

    sealed interface Verdict {
        /** Believed: [text] goes to the model (a non-Latin aside like "嗯" already removed). */
        data class Keep(val text: String) : Verdict

        /** Not believed; [reason] is a log token, never the words. */
        data class Drop(val reason: String) : Verdict
    }

    /**
     * [text] as heard from [audioMs] of audio; [known] are the hint's names (one word each, lower
     * case), never counted as foreign ("José" may be a name). Dropped when:
     * - nothing Latin is left once other scripts are removed (`not_english_script`);
     * - Spanish marks or words make up a large part of it (`not_english`): `¿ ¡ ñ`, or a third or more
     *   of its words carry an accent or are common Spanish-only words;
     * - under two letters or digits from at least [SHORT_AUDIO_MS] of audio (`too_short`: "0" for
     *   a second of speech);
     * - from [LONG_AUDIO_MS] or more, under [MIN_CHARS_PER_S] characters a second (`too_short`; the
     *   lowest real rate on 2026-11-17 was 7.3).
     */
    @Suppress("ReturnCount", "CyclomaticComplexMethod") // one exit per reason, one clause per rule
    fun verdict(
        text: String,
        audioMs: Long,
        known: Set<String> = emptySet(),
        /** His languages ([SpokenLanguages]); English alone is the pin. */
        accepted: List<SpokenLanguages.Language> = listOf(SpokenLanguages.ENGLISH),
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
        // Spanish look-alikes count against English only while no other Latin-script language is his.
        val latinOther = accepted.any { it != SpokenLanguages.ENGLISH && it.script == Character.UnicodeScript.LATIN }
        if (!latinOther) {
            if (kept.any { it in SPANISH_MARKS }) return Verdict.Drop("not_english")
            val latin = ok.filter { w -> w.none { it.isLetter() && !isLatin(it) } }
            val bare = latin.map { w -> w.trim { !it.isLetterOrDigit() }.lowercase() }.filter { it.isNotEmpty() }
            val names = known.map { it.lowercase() }.toSet()
            val foreign = bare.count { w -> w !in names && (w.any { it in ACCENTED } || w in SPANISH_WORDS) }
            if (bare.isNotEmpty() && foreign * FOREIGN_DENOMINATOR >= bare.size) return Verdict.Drop("not_english")
        }
        val signal = kept.count { it.isLetterOrDigit() }
        if (signal < 2 && audioMs >= SHORT_AUDIO_MS) return Verdict.Drop("too_short")
        if (audioMs >= LONG_AUDIO_MS && kept.length * MS_PER_S < MIN_CHARS_PER_S * audioMs) {
            return Verdict.Drop("too_short")
        }
        return Verdict.Keep(kept)
    }

    private fun isLatin(c: Char): Boolean = Character.UnicodeScript.of(c.code) == Character.UnicodeScript.LATIN

    private const val MAX_NAME_CHARS = 40
    const val SHORT_AUDIO_MS = 900L
    const val LONG_AUDIO_MS = 3_000L
    const val MIN_CHARS_PER_S = 3L
    private const val MS_PER_S = 1_000L

    /** One word in three. */
    private const val FOREIGN_DENOMINATOR = 3

    private const val SPANISH_MARKS = "¿¡ñÑ"
    private const val ACCENTED = "áéíóúü"

    /** Common Spanish words that are not English words too ("no", "me", "a" are not here). */
    private val SPANISH_WORDS = setOf(
        "que", "qué", "el", "los", "las", "del", "por", "para", "con", "una", "pero", "porque", "cómo",
        "dónde", "esto", "eso", "esta", "está", "estoy", "dijiste", "cuidado", "gracias", "hola", "sí", "muy",
        "hay", "tengo", "quiero", "puedes", "vamos", "bueno", "ahora", "entonces", "nada", "todo", "también",
        "oye", "mira", "claro", "vale", "lo", "le", "les", "es", "mi", "tu", "su", "nos",
    )
}
