package com.kevin.legion.board

/**
 * The phone's half of "read it back first" (Kevin, 2026-11-22): a backlog idea is saved only on
 * his explicit yes in a LATER turn than the one that read it back. Enforced here in code, the
 * shape [com.kevin.legion.gmail.EmailSendGate] gives a mail send:
 *  1. the idea was drafted by this phone in this conversation ([register], kept [TTL_MS]);
 *  2. the words of the turn saying yes are not the words of the turn that drafted it;
 *  3. those words are an explicit yes ([isExplicitYes]): no hedge, no negation, not a question.
 *
 * Silence saves nothing (the draft expires here and on the engine). A no is the model's
 * `yes: false`, which drops it. The engine keeps its own floor too (`board/ideas.py`). Pure and
 * clock-injected; in process only, so an app restart forgets the draft, which only blocks a save.
 */
class IdeaSaveGate(private val clock: () -> Long = System::currentTimeMillis) {

    data class Pending(val id: String, val createdAtMs: Long, val draftTurnWords: String)

    private var pending: Pending? = null

    @Synchronized
    fun register(id: String, userText: String?) {
        pending = Pending(id, clock(), normalise(userText))
    }

    /** The idea waiting for an answer, if one is and it is still fresh. */
    @Synchronized
    fun current(): Pending? = pending?.takeIf { clock() - it.createdAtMs <= TTL_MS }

    sealed interface Decision {
        data class Go(val id: String) : Decision
        data class Refused(val message: String) : Decision
    }

    @Synchronized
    fun check(userText: String?): Decision {
        val idea = current()
        val words = normalise(userText)
        val refusal = when {
            idea == null -> NOTHING_WAITING
            words.isEmpty() -> UNHEARD
            words == idea.draftTurnWords -> NOT_ANSWERED_YET
            !isExplicitYes(userText.orEmpty()) -> NOT_A_YES
            else -> null
        }
        if (refusal != null || idea == null) return Decision.Refused(refusal ?: NOTHING_WAITING)
        return Decision.Go(idea.id)
    }

    /** The idea was answered (saved or dropped): nothing waits any more. */
    @Synchronized
    fun clear() {
        pending = null
    }

    companion object {
        const val TTL_MS = 15 * 60_000L

        const val NOTHING_WAITING =
            "Nothing was saved: no backlog idea is waiting for a yes. Say the idea again to add it."
        const val UNHEARD = "Nothing was saved: I could not hear the answer. Ask again whether to add it."
        const val NOT_ANSWERED_YET =
            "Nothing was saved: he has not heard the idea read back yet. It was read back; wait for his answer."
        const val NOT_A_YES =
            "Nothing was saved: that was not a clear yes. Ask whether to add it, and save it only on a yes."

        private val YES_WORDS = setOf("yes", "yeah", "yep", "yup", "sure", "add", "save", "correct")
        private val NOT_YES_WORDS = setOf(
            "no", "nope", "nah", "not", "don't", "dont", "never", "stop", "wait", "hold", "cancel", "later",
            "actually", "maybe", "change", "edit", "instead", "unless", "but", "why", "what", "how", "if",
            "drop", "forget", "skip", "away", "scrap", "delete", "remove",
        )
        private val QUESTION_OPENERS = setOf("can", "could", "would", "will", "should", "is", "are", "did", "does")

        /** True only for a clear yes. Errs to false: a refused yes costs one more "yes". */
        fun isExplicitYes(text: String): Boolean {
            val tokens = tokens(text)
            val asked = text.contains('?') || tokens.firstOrNull() in QUESTION_OPENERS
            return tokens.any { it in YES_WORDS } && !asked && tokens.none { it in NOT_YES_WORDS }
        }

        private fun tokens(text: String): List<String> =
            text.lowercase().replace('’', '\'').replace(Regex("[^a-z' ]"), " ")
                .split(' ').map { it.trim('\'') }.filter { it.isNotEmpty() }

        private fun normalise(text: String?): String = tokens(text.orEmpty()).joinToString(" ")
    }
}
