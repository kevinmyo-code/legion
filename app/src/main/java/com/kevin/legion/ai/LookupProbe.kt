package com.kevin.legion.ai

/**
 * Per-stage timing for ONE [SubAgent.route] or [SubAgent.investigate] call (async-lookups ticket
 * 03). The caller makes one per call, names the Live tool it serves ([tool]), and passes it in;
 * [SubAgent] fills the rest as each stage finishes, because SubAgent is what owns the stages. Read
 * it after the call returns, whatever the result: a call cut at its budget still has [totalMs],
 * and the stage that was running when the budget ran out still has the time it spent.
 *
 * A side object rather than fields on [AgentResult], so that sealed shape (and every `when` over
 * it) stays as it is. Never share one probe between calls: the numbers would add up.
 *
 * [tool] also goes on the `subagent_fallback` line, so a fallback can be tied to its lookup.
 */
class LookupProbe(val tool: String) {
    /** The model the last POST went to: the fallback model once one fired, else the requested one. */
    @Volatile var model: String = ""

    /** Whole call, timeout included, in ms. */
    @Volatile var totalMs: Long = 0L

    /** Wall time spent in model POSTs, a fallback retry included, in ms. */
    @Volatile var postMs: Long = 0L

    /** [SubAgent.route] only: wall time of the parallel tool fan-out, in ms. 0 when no tool ran. */
    @Volatile var toolsMs: Long = 0L

    /** [SubAgent.route] only: the tools the router asked for, in call order. */
    @Volatile var toolNames: List<String> = emptyList()

    /** Model rounds (POSTs the loop made). A fallback retry is part of its round, not a new one. */
    @Volatile var rounds: Int = 0
}
