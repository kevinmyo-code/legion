package com.kevin.legion.service.cascade

/**
 * The model's own reply sometimes opens with one of the scripted filler lines ("Checking.",
 * 2026-11-16: seen in the agenda gates, once joined straight to the answer as "Checking.Math 110 ...").
 * Spoken, that doubles up with the scripted filler just played, or sounds like a stutter. So a
 * reply whose FIRST sentence is exactly a [ScriptedLines.FILLERS] line (case, surrounding
 * whitespace and the final punctuation aside) loses that sentence, whether or not a filler played:
 * it is filler either way.
 *
 * Deterministic, not a prompt rule. Only the leading sentence, only an exact match ("Checking the
 * calendar now." stays), and never when nothing would be left (a reply that is only "On it." is
 * spoken as it is). [ReplySpeaker.text] runs every streamed delta through one of these before
 * anything is spoken, so the clips heard, the audit's `assistant text` lines and the transcript
 * ([ReplySpeaker.spoken]) all see the same text. Pure: no clock, no Android.
 */
internal class LeadingFiller(private val cores: List<String> = CORES) {
    private val buf = StringBuilder()
    private var decided = false

    /** True once a leading filler sentence was dropped from this reply. */
    var dropped = false
        private set

    /** The text to pass on now: "" while the opening could still be a filler line. */
    fun feed(delta: String): String {
        if (decided) return delta
        buf.append(delta)
        return when (val v = verdict(buf.toString(), cores)) {
            Verdict.Wait -> ""
            Verdict.Keep -> release(buf.toString())
            is Verdict.Drop -> {
                dropped = true
                release(v.rest)
            }
        }
    }

    /** The model is done: anything still held (a reply that is only a filler line) goes as it is. */
    fun flush(): String = if (decided) "" else release(buf.toString())

    /**
     * Something not the model's (a code line, [ReplySpeaker.line]) is said now: what is held is
     * decided at once, so none of it can be spoken after that line. A held filler line is dropped;
     * anything else goes.
     */
    fun settle(): String {
        if (decided) return ""
        val held = buf.toString()
        return when (val v = verdict(held, cores)) {
            Verdict.Keep -> release(held)
            is Verdict.Drop -> {
                dropped = true
                release(v.rest)
            }
            Verdict.Wait -> {
                dropped = held.isNotBlank()
                release("")
            }
        }
    }

    private fun release(text: String): String {
        decided = true
        buf.setLength(0)
        return text
    }

    sealed interface Verdict {
        /** Could still be a filler line, or is one with nothing after it yet. */
        data object Wait : Verdict

        /** Not a filler line: everything goes. */
        data object Keep : Verdict

        /** A filler line, then [rest]: only [rest] goes. */
        data class Drop(val rest: String) : Verdict
    }

    companion object {
        private const val ENDS = ".!?…"

        /** The filler lines without case or final punctuation ("checking", "on it"). */
        val CORES: List<String> = ScriptedLines.FILLERS.map { core(it) }

        fun core(line: String): String = line.trim().trimEnd { it in ENDS }.lowercase()

        /** [text] as far as it has streamed. */
        @Suppress("ReturnCount") // one exit per verdict
        fun verdict(text: String, cores: List<String> = CORES): Verdict {
            val lead = text.trimStart()
            for (f in cores) {
                // Still being written: "Check", or nothing but whitespace so far.
                if (lead.length <= f.length && f.regionMatches(0, lead, 0, lead.length, ignoreCase = true)) {
                    return Verdict.Wait
                }
                if (lead.length > f.length && lead.regionMatches(0, f, 0, f.length, ignoreCase = true) &&
                    lead[f.length] in ENDS
                ) {
                    // A sentence boundary with or without a space after it ("Checking.Math 110").
                    var i = f.length
                    while (i < lead.length && lead[i] in ENDS) i++
                    val rest = lead.substring(i)
                    return if (rest.isBlank()) Verdict.Wait else Verdict.Drop(rest.trimStart())
                }
            }
            return Verdict.Keep
        }

        /** [text] whole, without a leading filler sentence (the transcript's fallback). */
        fun strip(text: String): String = (verdict(text) as? Verdict.Drop)?.rest ?: text
    }
}
