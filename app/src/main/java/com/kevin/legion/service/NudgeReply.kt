package com.kevin.legion.service

/**
 * Reads a reply to an unprompted line and decides whether it was a brush-off (ticket 05 call 5,
 * Kevin 2026-08-21: *"Counts, and a brush-off is inferred from your reply"*).
 *
 * ### Why this is deterministic rather than a model judgement
 *
 * Ticket 02 settled the hybrid: **deterministic rules decide, the model only words the line.** A
 * decline is a decision - it suppresses a rule for a day - so it belongs on the deterministic side.
 * Asking the live model "was that a no?" would also cost a round trip on every single proactive
 * reply to answer a question a word list answers for free.
 *
 * ### It is deliberately conservative, and the asymmetry is the point
 *
 * The two ways to be wrong do NOT cost the same:
 *
 *  - **A false decline** silences a rule for 24 hours that Kevin actually wanted. He never learns
 *    why; the nudge simply stops coming.
 *  - **A missed decline** means the rule returns on its normal schedule and he brushes it off
 *    again, which is mildly annoying and self-correcting.
 *
 * So this only fires on a **short, clearly negative reply**. "no", "not now", "later", "leave it".
 * Anything long, anything ambiguous, and anything with a positive word in it is NOT a decline -
 * because a wrong guess in that direction is the one that quietly loses a feature.
 *
 * **The honest limit, stated because ticket 05's resolution states it:** a grunt may or may not be
 * a no, and this cannot tell. It is inference, its failure is bounded on purpose, and nothing here
 * is asserted aloud - a wrong read changes when a nudge returns, never what the assistant claims.
 */
object NudgeReply {

    /** Only a reply this short is even considered - a real sentence is a conversation, not a
     * dismissal, and treating one as a brush-off is the expensive mistake. */
    private const val MAX_WORDS = 5

    /** Whole-word negatives. Matched as words, never as substrings: "no" must not fire on "notes"
     * or "November", which is exactly what a `contains` check would do. */
    private val NEGATIVE = setOf(
        "no", "nope", "nah", "later", "skip", "dismiss", "stop", "cancel", "ignore", "nevermind",
    )

    /** Multi-word forms, matched on the normalised whole reply. */
    private val NEGATIVE_PHRASES = listOf(
        "not now", "not right now", "leave it", "leave me alone", "never mind", "not today",
        "no thanks", "no thank you", "maybe later", "forget it", "drop it",
    )

    /** Any of these means it was NOT a dismissal, whatever else the reply contains - "no, actually
     * yes, do it" must never read as a refusal. */
    private val POSITIVE = setOf(
        "yes", "yeah", "yep", "sure", "ok", "okay", "please", "do", "go", "good",
    )
    // "thanks" is deliberately NOT here. It is a courtesy, not consent, and it appears in the
    // single most common polite refusal there is - "no thanks". Listing it as positive made that
    // phrase read as acceptance, so a brush-off never suppressed anything.

    /**
     * True when [reply] reads as a brush-off of the nudge just spoken.
     *
     * Returns false for a blank reply: **silence is not a refusal.** He may not have heard it, may
     * be driving, may be mid-thought - and suppressing a rule for a day on the strength of nobody
     * saying anything is the compulsion test's mirror image, quietly losing something he wanted.
     */
    fun isDecline(reply: String): Boolean {
        val normalised = reply.lowercase().trim().trim('.', '!', '?', ',')
        if (normalised.isBlank()) return false

        val words = normalised.split(Regex("[^a-z']+")).filter { it.isNotBlank() }
        if (words.isEmpty() || words.size > MAX_WORDS) return false

        // The positive veto runs FIRST and beats everything, because a false decline is the
        // expensive mistake (see the class doc): "no thanks, do it" must not suppress a rule.
        if (words.any { it in POSITIVE }) return false

        if (NEGATIVE_PHRASES.any { normalised == it || normalised.startsWith("$it ") }) return true
        return words.any { it in NEGATIVE }
    }
}
