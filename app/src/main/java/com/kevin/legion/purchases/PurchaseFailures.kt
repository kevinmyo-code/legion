package com.kevin.legion.purchases

/**
 * What the bought log says when a call did NOT go through, shared by the `bought_log` voice tool
 * and the screens (ADR 0035). The log is online only (purchase-log ticket 04), so an unreachable
 * engine is said as unreachable - never as an empty log - and a write that did not return a 2xx
 * says in words that nothing was logged (CLAUDE.md section 7's outcome-verb rule).
 */
object PurchaseFailures {

    /** What a failed READ says. [what] completes "so I couldn't ...", e.g. "look up shampoo". */
    fun readFailed(outcome: PurchaseOutcome<*>, what: String): String = when (outcome) {
        is PurchaseOutcome.Unreachable ->
            "I can't reach the bought log right now, so I couldn't $what. That is not the same as " +
                "nothing being logged."
        is PurchaseOutcome.Refused -> "The bought log refused that, so I couldn't $what: ${outcome.sentence}"
        is PurchaseOutcome.Unconfirmed ->
            "The bought log answered but I couldn't read it, so I couldn't $what."
        is PurchaseOutcome.Ok -> error("readFailed called with an Ok outcome")
    }

    /** What a failed WRITE says: nothing was logged, or (unreadable reply) it is not known. */
    fun logFailed(outcome: PurchaseOutcome<*>, item: String): String = when (outcome) {
        is PurchaseOutcome.Unreachable ->
            "I can't reach the bought log right now, so I didn't log $item."
        is PurchaseOutcome.Refused -> "The bought log refused it, so I didn't log $item: ${outcome.sentence}"
        is PurchaseOutcome.Unconfirmed ->
            "The bought log answered but I couldn't read its reply, so I can't say whether $item " +
                "was logged. Check the bought log before assuming it was."
        is PurchaseOutcome.Ok -> error("logFailed called with an Ok outcome")
    }
}
