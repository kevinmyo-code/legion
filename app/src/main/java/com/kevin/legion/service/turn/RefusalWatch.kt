package com.kevin.legion.service.turn

/**
 * A canned refusal spoken over a successful tool result (LIVE_AUDIT, A25, 2026-10-30 11:30:51).
 * Asked about gifts for his wife, the model called `recall_memory`, got six memories back with
 * success=true, said "Checking the notes on her preferences. She seems to really" and then "I'm
 * just a language model and can't help with that." The result was in front of it; the user got
 * nothing. Cause not proven: no `safetySettings` field is sent (the Live setup carries none), the
 * audit line truncates the sixth memory, and nothing in the frames says why it broke off. The
 * reasoned reading is that it began asserting a preference the memories did not hold and fell into
 * the model's stock refusal.
 *
 * Only the model's STOCK refusal counts, never an honest "I can't". The honesty clause tells it to
 * say "I can't do that" when it has no tool, and a guard that fought that would undo ADR 0031; so
 * the phrases are the identity-shaped ones ("just a language model") and the stock "can't help with
 * that", and only after a tool came back successful.
 *
 * Fed the length of the companion transcript at the moment each result was sent, so it judges only
 * what was said AFTER the result: a turn that refused before its lookup returned is not this bug.
 * One note per result. Pure and clock-free, beside [HoldingLineWatch].
 */
internal class RefusalWatch {
    private var tool: String? = null
    private var saidOffset = 0

    /** A tool response went out. Only a successful one that was actually sent is watched. */
    @Synchronized
    fun onResultSent(name: String, success: Boolean, sent: Boolean, saidSoFar: Int) {
        if (!success || !sent) return
        tool = name
        saidOffset = saidSoFar
    }

    /**
     * A model turn completed with transcript [said]. Returns the tool to nudge about when the
     * speech after its result is a canned refusal, else null. A result followed by no speech this
     * turn is carried to the next turn, judged from its start.
     */
    @Synchronized
    fun onTurnComplete(said: String): String? {
        val t = tool ?: return null
        val after = if (saidOffset <= said.length) said.substring(saidOffset) else said
        return if (after.isBlank()) {
            saidOffset = 0
            null
        } else {
            clear()
            t.takeIf { isCannedRefusal(after) }
        }
    }

    @Synchronized
    fun clear() {
        tool = null
        saidOffset = 0
    }

    companion object {
        private val PHRASES = listOf(
            "just a language model",
            "only a language model",
            "i'm a language model",
            "i am a language model",
            "as a language model",
            "as an ai language model",
            "i'm just an ai",
            "can't help with that",
            "cannot help with that",
            "can't assist with that",
            "cannot assist with that",
            "not able to help with that",
            "unable to help with that",
        )

        /** The model's stock refusal, not an honest capability answer. Curly apostrophes count. */
        fun isCannedRefusal(said: String): Boolean {
            val s = said.lowercase().replace('’', '\'').replace(Regex("\\s+"), " ")
            return PHRASES.any { s.contains(it) }
        }

        /** The one note, framed like [HoldingLineWatch.lateInstruction]. Names no assistant. */
        fun instruction(tool: String): String =
            "(System note, not spoken by the user: your reply broke off into a refusal, but " +
                "`$tool` came back successfully and its result is in front of you. Answer the " +
                "user's question from that result now. If it does not hold what they asked, say " +
                "what it does hold and ask them for the rest. Do not apologise, and do not wait " +
                "for the user to ask again.)"
    }
}
