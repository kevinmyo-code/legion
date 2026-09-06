package com.kevin.legion.ai

/**
 * How an unattended background pass's model call ended, at the only granularity its retry policy
 * actually needs: did it answer, is it worth trying again, or is trying again pointless?
 *
 * **Why this exists (2026-09-06, second half of the day).** The first fix of the day gave
 * [ReflectionEngine] and [MemoryConsolidator] a cap and a backoff, on the assumption that a failure
 * is usually transient - a dropped signal, a 503, a reply that came back mangled. Then Kevin said
 * *"i ran out of credits and im probably not gonna top up for a while"*, and that assumption stops
 * holding: a key with no quota fails identically on every attempt, for weeks, and burning five
 * attempts with fifteen-minute-and-doubling gaps between them is still five pointless calls plus a
 * pile of log noise per unit of work.
 *
 * So a failure is split in two, and the split is drawn from the SERVER'S OWN answer rather than
 * guessed:
 *
 * - [SoftFailure] - offline, overloaded, or a reply that would not parse. The thing that failed
 *   might well work in an hour. Bounded retry with backoff, as before.
 * - [HardFailure] - the key was rejected or has nothing left to spend. Retrying changes nothing,
 *   because nothing about the situation is going to change without Kevin doing something. The pass
 *   is set aside on the FIRST one of these, not the fifth.
 *
 * **A 429 is treated as hard, and that is a deliberate over-reach worth stating.** Gemini returns
 * 429 `RESOURCE_EXHAUSTED` both for a per-minute rate limit (genuinely transient, clears in
 * seconds) and for an exhausted quota (not transient at all), and the two are not reliably
 * distinguishable from the status code. Choosing "hard" means a per-minute rate limit parks a
 * background pass until new input arrives, which costs a delayed memory nobody was waiting on.
 * Choosing "soft" would mean an exhausted key keeps being retried, which is the thing this exists
 * to stop. The cheap failure is the right one to take. **This applies only to the unattended
 * background passes** - a tool the user is waiting on still says "rate limit, try again in a
 * minute", because there a person is present to decide.
 */
internal sealed class ModelPassOutcome {
    /** The call came back. [text] is the model's raw output, not yet parsed - the answered-with-
     *  nothing versus unparseable distinction is drawn by the caller's own parser, below this. */
    data class Answered(val text: String) : ModelPassOutcome()

    /** Worth another go later. */
    object SoftFailure : ModelPassOutcome()

    /** Not worth another go. [reason] is said in words on the Setup screen, so it is written for a
     *  person reading it rather than for a log. */
    data class HardFailure(val reason: String) : ModelPassOutcome()

    companion object {
        /**
         * Classifies a [SubAgent] result. [AgentResult.Overloaded] and [AgentResult.Offline] are
         * soft because the server said so in as many words; [AgentResult.Failed] is soft because it
         * is the bucket for "something else", and treating an unknown as permanent would silently
         * park work for a reason nobody has established.
         */
        fun from(result: AgentResult): ModelPassOutcome = when (result) {
            is AgentResult.Success -> Answered(result.text)
            AgentResult.RateLimited -> HardFailure(
                "The Gemini key has no quota left, or is being rate-limited. Background memory " +
                    "work has stopped rather than keep retrying; nothing else in the app is affected.",
            )
            AgentResult.KeyInvalid -> HardFailure(
                "The Gemini key was rejected. Background memory work has stopped until the key in " +
                    "Setup is changed.",
            )
            AgentResult.Overloaded, AgentResult.Offline, AgentResult.Failed -> SoftFailure
        }
    }
}
