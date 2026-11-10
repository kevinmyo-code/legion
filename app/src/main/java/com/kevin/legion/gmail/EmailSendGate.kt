package com.kevin.legion.gmail

/**
 * The send gate for the `email` tool (ADR 0086, Kevin 2026-11-10: *"i want alfred to draft it,
 * offer for me to check in gmail app on phone, while also sending it if i say send without
 * checking."*). Enforced here, in code, not only in the tool description.
 *
 * A send goes ahead only when all of these hold:
 *  1. the draft id was created by this tool in this conversation ([register], kept [TTL_MS]), so a
 *     made-up id, or a draft Kevin wrote himself in Gmail, can never be sent by voice;
 *  2. the words of the turn making the send call are not the words of the turn that drafted it
 *     (the read-back has to come between the draft and the send);
 *  3. those words are an explicit send ([isExplicitSend]): the word "send", no hedge, no negation,
 *     not a question. A bare "yes" is not a send: the offer was "check it in Gmail, or say send".
 *
 * Words that cannot be heard (an empty transcript) are not a send. A send that went through
 * [consume]s the draft; a failed one keeps it, so another "send" can retry, but never with the
 * same words that already tried once. Pure and clock-injected, so `EmailSendGateTest` needs no
 * Context. In-process only: an app restart forgets every draft, which only ever blocks a send.
 */
class EmailSendGate(private val clock: () -> Long = System::currentTimeMillis) {

    /** One draft this tool made: what to read back, and which turn made it. */
    data class Draft(
        val draftId: String,
        val messageId: String,
        val to: String,
        val subject: String,
        val createdAtMs: Long,
        val draftTurnWords: String,
        val lastTriedWords: String? = null,
    )

    private val drafts = LinkedHashMap<String, Draft>()

    @Synchronized
    fun register(draftId: String, messageId: String, to: String, subject: String, userText: String?) {
        prune()
        drafts[draftId] = Draft(draftId, messageId, to, subject, clock(), normalise(userText))
    }

    /** The draft [draftId] if this conversation made it, else the newest one when [draftId] is blank. */
    @Synchronized
    fun find(draftId: String?): Draft? {
        prune()
        return if (draftId.isNullOrBlank()) drafts.values.lastOrNull() else drafts[draftId.trim()]
    }

    /** Outcome of [check]: [Go] with the draft, or [Refused] with the words to return. */
    sealed interface Decision {
        data class Go(val draft: Draft) : Decision
        data class Refused(val message: String) : Decision
    }

    /** Decides a send call. A [Decision.Go] marks the words used, so they cannot send twice. */
    @Synchronized
    fun check(draftId: String?, userText: String?): Decision {
        prune()
        val id = draftId?.trim().orEmpty()
        val draft = drafts[id]
        val words = normalise(userText)
        val refusal = when {
            draft == null -> if (id.isEmpty()) NO_ID else UNKNOWN_DRAFT
            words.isEmpty() -> UNHEARD
            words == draft?.draftTurnWords -> NOT_OFFERED_YET
            words == draft?.lastTriedWords -> SAY_SEND_AGAIN
            !isExplicitSend(userText.orEmpty()) -> notASend(userText.orEmpty().trim())
            else -> null
        }
        if (refusal != null || draft == null) return Decision.Refused(refusal ?: UNKNOWN_DRAFT)
        drafts[id] = draft.copy(lastTriedWords = words)
        return Decision.Go(draft)
    }

    /** The draft went out; it can never be sent again. */
    @Synchronized
    fun consume(draftId: String) {
        drafts.remove(draftId)
    }

    private fun prune() {
        val now = clock()
        drafts.values.removeAll { now - it.createdAtMs > TTL_MS }
    }

    companion object {
        /** How long a draft from this conversation stays sendable by voice. After it, Gmail's own
         * Send button is the way; the draft itself stays in Drafts. */
        const val TTL_MS = 15 * 60_000L

        const val NO_ID =
            "Not sent: no draft_id. Pass the draft_id that action=draft returned. Nothing was sent."

        const val UNKNOWN_DRAFT =
            "Not sent: that is not a draft made in this conversation, so I will not send it. Nothing was " +
                "sent. Draft it again, or the user can send it from Gmail."

        const val UNHEARD =
            "Not sent: I could not hear the user's answer. The draft is still in Drafts. Ask whether to " +
                "send it, and wait for the word send."

        const val NOT_OFFERED_YET =
            "Not sent: the user has not heard the draft yet. Read back who it is to, the subject and the " +
                "gist, offer check it in Gmail or say send, and wait."

        const val SAY_SEND_AGAIN =
            "Not sent: those words already tried once. The draft is still in Drafts. Ask the user to say " +
                "send again if they still want it sent."

        fun notASend(words: String) =
            "Not sent: \"$words\" is not the word send. The draft is still in Drafts, unchanged. Only " +
                "the user saying send sends it."

        private val SEND_WORDS = setOf("send")
        private val NOT_SEND_WORDS = setOf(
            "no", "nope", "nah", "not", "don't", "dont", "never", "stop", "wait", "hold", "cancel",
            "later", "actually", "maybe", "check", "look", "before", "change", "edit", "instead",
            "unless", "after", "first", "why", "what", "how", "if",
        )
        private val QUESTION_OPENERS = setOf("can", "could", "would", "will", "should", "is", "are", "did", "does")

        /**
         * True only for an explicit send: the word "send", nothing that hedges, negates or asks for
         * something else, no question mark, not phrased as a question. Errs to false: a refused
         * send costs Kevin one more "send", a wrong one sends mail he did not mean to.
         */
        fun isExplicitSend(text: String): Boolean {
            val tokens = tokens(text)
            val askedBack = tokens.firstOrNull() in QUESTION_OPENERS ||
                (tokens.firstOrNull() == "do" && tokens.getOrNull(1) in setOf("you", "we", "i"))
            val hedged = text.contains('?') || askedBack || tokens.any { it in NOT_SEND_WORDS }
            return tokens.any { it in SEND_WORDS } && !hedged
        }

        private fun tokens(text: String): List<String> =
            text.lowercase().replace('’', '\'').replace(Regex("[^a-z' ]"), " ")
                .split(' ').map { it.trim('\'') }.filter { it.isNotEmpty() }

        private fun normalise(text: String?): String = tokens(text.orEmpty()).joinToString(" ")
    }
}
