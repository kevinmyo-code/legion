package com.kevin.legion.service.cascade

/**
 * A filler-only sentence after real content is never spoken (2026-11-17 phone logs: twice the last
 * thing he heard was "Just a second." / "Give me a second with that." after a finished answer, and
 * he waited for an answer that never came while the follow-up window ran out).
 *
 * Applied to every model sentence in [GeminiTextLoop], after [FillerTurn.Hold] and the verbatim
 * filters, so the scripted voice, the transcript and the audit lines all lose it alike. A sentence is
 * dropped only when something real was already said this turn ([contentBefore], or an earlier
 * sentence through this filter); a reply that OPENS with a filler is left to [LeadingFiller] and
 * [FillerTurn], unchanged.
 *
 * Only a sentence that may still turn out to be filler is held ([mayBeFiller]); any other text passes
 * at once, so a voice that streams text (the laptop's) is not slowed. Pure: no clock, no Android.
 */
internal class TrailingFiller(private val contentBefore: () -> Boolean) : SentenceFilter {
    private val buf = StringBuilder()

    /** The unfinished sentence was already let through (it cannot be filler); pass until it ends. */
    private var open = false
    private var content = false

    override var dropped = 0
        private set

    @Suppress("LoopWithTooManyJumpStatements") // one pass per finished sentence
    override fun feed(delta: String): String {
        buf.append(delta)
        val out = StringBuilder()
        while (true) {
            val end = MoneyFilter.sentenceEnd(buf) ?: break
            val sentence = buf.substring(0, end)
            buf.delete(0, end)
            if (open) {
                out.append(sentence)
                open = false
                continue
            }
            if (isFiller(sentence) && hasContent()) {
                dropped++
            } else {
                out.append(sentence)
                if (!isFiller(sentence)) content = true
            }
        }
        if (buf.isNotEmpty() && (open || !mayBeFiller(buf.toString()))) {
            open = true
            content = true
            // A trailing ".", "?" or "!" waits: whether a sentence ends there is known with the next char.
            val keep = buf.length - buf.trimEnd { it in ENDS }.length
            out.append(buf, 0, buf.length - keep)
            buf.delete(0, buf.length - keep)
        }
        return out.toString()
    }

    @Suppress("ReturnCount") // nothing held, an open sentence, a dropped wait line, or the rest
    override fun flush(): String {
        val rest = buf.toString()
        buf.setLength(0)
        val wasOpen = open
        open = false
        if (rest.isBlank() || wasOpen) return rest
        if (isFiller(rest) && hasContent()) {
            dropped++
            return ""
        }
        return rest
    }

    private fun hasContent() = content || contentBefore()

    companion object {
        private const val ENDS = ".!?…။。！？"

        /** Past this many characters an unfinished sentence is no longer held. */
        private const val HOLD_MAX_CHARS = 60

        /** "Just a second.", "Give me a second with that.", "One sec.", "Hang on.", "Bear with me." */
        private val WAIT = Regex(
            "^(ok(ay)?,?\\s+)?(just|give me|wait|one|hang on,?|hold on,?)?\\s*(a|one)?\\s*" +
                "(sec|second|moment|minute|tick)s?(\\s+(with|on) (that|this|it))?(,?\\s*please)?$",
        )
        private val OTHER = setOf("bear with me", "hang on", "hold on", "stand by", "hang tight", "one sec")

        /** The openers a held sentence may still become ([FillerTurn.OPENERS] plus the waits). */
        private val OPENERS: List<String> = FillerTurn.OPENERS + OTHER +
            listOf("just a", "give me a", "wait a", "one second", "ok", "okay")

        /** A sentence that only asks him to wait ([FillerTurn.isFillerOnly], or a wait line). */
        fun isFiller(sentence: String): Boolean {
            val bare = TtsTags.strip(sentence)
            val core = LeadingFiller.core(bare)
            if (core.isEmpty()) return false
            return FillerTurn.isFillerOnly(bare) || core in OTHER || WAIT.matches(core)
        }

        /** The unfinished sentence [partial] could still be a filler line. */
        @Suppress("ReturnCount")
        fun mayBeFiller(partial: String): Boolean {
            val s = partial.trimStart().lowercase()
            if (s.isEmpty()) return true
            if (s.length > HOLD_MAX_CHARS) return false
            return OPENERS.any { o -> o.startsWith(s) || s.startsWith(o) }
        }
    }
}
