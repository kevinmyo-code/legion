package com.kevin.legion.service.cascade

/**
 * What the cloud transcriber is told, and what of its answer is believed (2026-11-17 phone logs:
 * English speech came back as short Spanish phrases and a Chinese interjection, and the
 * last Spanish one reached the model, which called `car clear_codes` on it).
 *
 * Two halves, both pure:
 * - [hint]: a compact vocabulary line for the STT request (never the text model's prompt): the
 *   words he uses for the car and the tools. No names.
 * - [verdict]: a transcript that is not plausibly English, or from audio with no voice in it, is
 *   not sent to the model. Foreign words get the spoken miss; noise gets silence. Never an
 *   instruction.
 */
internal object SttGuard {
    /**
     * The fixed half of the hint: his car and tool words that a general model mishears. Words only, never
     * an example number or sentence (2026-11-17: in the dashboard voice app, a transcriber handed
     * examples wrote them back as "speech" from room noise). Trouble codes are described by shape.
     */
    const val DOMAIN_WORDS = "OBD, OBD adapter, dongle, trouble codes (a letter then four digits), clear the " +
        "codes, check engine light, coolant, odometer, oil change, recall, to-do list, reminder, calendar, timer, navigate"


    /**
     * The hint line: his car and tool words only. No names (2026-11-17 real-model run with invented
     * names: on noise, the hint's names came back as "speech"); the names stay on the phone as
     * [verdict]'s `known`, never counted foreign.
     */
    fun hint(): String = "Words he often says: $DOMAIN_WORDS."

    /** Drop reasons that mean "nothing was said": silence, never a spoken "didn't catch that". */
    val SILENT_REASONS: Set<String> = setOf(NOISE)

    /** The drop reason for a transcript of audio with no voice in it ([voicedMs]). */
    const val NOISE = "noise"

    sealed interface Verdict {
        /** Believed: [text] goes to the model (a non-Latin aside like "嗯" already removed). */
        data class Keep(val text: String) : Verdict

        /** Not believed; [reason] is a log token, never the words. */
        data class Drop(val reason: String) : Verdict
    }

    /**
     * [text] as heard; [voicedMs] is how much of its audio carried a voice ([voicedMs], null when not
     * measured); [known] are names on the phone (one word each, lower case), never counted foreign.
     * Dropped when:
     * - nothing is left once scripts none of his languages use are removed (`not_english_script`);
     * - it reads as Spanish (`not_english`): `¿` or `¡`, or at least two thirds of its words are
     *   Spanish-only words (accented letters alone never count: "café", "José", "jalapeño");
     * - the audio itself says no one spoke: under [MIN_VOICED_MS] above its own floor (`noise`,
     *   silence). The words never decide this (2026-11-18 review: "clear codes", "oil change" were
     *   dropped as the hint read back).
     */
    @Suppress("ReturnCount") // one exit per reason
    fun verdict(
        text: String,
        voicedMs: Long?,
        known: Set<String> = emptySet(),
        /** His languages ([SpokenLanguages]); English alone is the pin. */
        accepted: List<SpokenLanguages.Language> = listOf(SpokenLanguages.ENGLISH),
    ): Verdict {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return Verdict.Keep("")
        if (voicedMs != null && voicedMs < MIN_VOICED_MS) return Verdict.Drop(NOISE)
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
        return Verdict.Keep(kept)
    }

    /**
     * Milliseconds of [pcm16k] (16 kHz PCM16 mono) in [WINDOW_MS] windows whose RMS stands at least
     * [VOICE_OVER_FLOOR_DB] above the audio's own quiet floor (its 10th-percentile window). Relative,
     * not absolute: this phone's speech arrives anywhere from -51 to -74 dBFS. A steady hum, road
     * noise or fan stays within a few dB of its floor and scores near 0; a spoken word, even one,
     * scores its length. Every phone turn holds its pre-roll and end-of-turn silence, so a floor exists.
     */
    @Suppress("MagicNumber") // PCM16 byte layout
    fun voicedMs(pcm16k: ByteArray): Long {
        val samplesPerWindow = SAMPLE_RATE / 1000 * WINDOW_MS.toInt()
        val n = pcm16k.size / 2 / samplesPerWindow
        if (n == 0) return 0
        val rms = DoubleArray(n) { w ->
            var sum = 0.0
            for (i in 0 until samplesPerWindow) {
                val at = 2 * (w * samplesPerWindow + i)
                val v = ((pcm16k[at].toInt() and 0xff) or (pcm16k[at + 1].toInt() shl 8)).toShort().toDouble()
                sum += v * v
            }
            kotlin.math.sqrt(sum / samplesPerWindow)
        }
        val floor = maxOf(rms.sorted()[(n * FLOOR_PERCENTILE) / 100], MIN_FLOOR)
        val gate = floor * Math.pow(10.0, VOICE_OVER_FLOOR_DB / 20.0)
        return rms.count { it >= gate } * WINDOW_MS
    }

    /** Under this much voiced audio nothing was said: one short word ("Stop.", "Yes.") is 250 ms or more. */
    const val MIN_VOICED_MS = 150L
    const val VOICE_OVER_FLOOR_DB = 12.0
    private const val WINDOW_MS = 30L
    private const val SAMPLE_RATE = 16_000
    private const val FLOOR_PERCENTILE = 10
    private const val MIN_FLOOR = 1.0

    /** Two words in three. */
    private const val FOREIGN_NUMERATOR = 2
    private const val FOREIGN_DENOMINATOR = 3

    private const val SPANISH_MARKS = "¿¡"

    /** Common Spanish words that are not English words too ("no", "me", "a" are not here). */
    private val SPANISH_WORDS = setOf(
        "que", "qué", "el", "los", "las", "del", "por", "para", "con", "una", "pero", "porque", "cómo",
        "dónde", "esto", "eso", "esta", "está", "estoy", "dijo", "gracias", "hola", "sí", "muy",
        "hay", "tengo", "quiero", "puedes", "vamos", "bueno", "ahora", "entonces", "nada", "todo", "también",
        "oye", "mira", "claro", "vale", "lo", "le", "les", "es", "mi", "tu", "su", "nos",
    )
}
