package com.kevin.legion.ai

import android.content.Context

/**
 * Single source of the assistant's identity clause for sub-agent system
 * instructions. Replaces Midnight AI's `CompanionIdentity`, which branched
 * between "Zero rides along" and "the driver's own car speaks" - that whole
 * mechanism was retired in the 2026-07-31 pivot (one global assistant
 * identity now, not per-car).
 *
 * Placeholder content, not final copy - the actual Alfred/JARVIS-register
 * voice hasn't been written yet. [context] is unused for now but kept in the
 * signature so call sites (six sub-agents) didn't need to change beyond the
 * import.
 */
object AssistantIdentity {
    /** Full identity clause for the main conversational system instruction. */
    fun clause(context: Context): String =
        "You are the driver's personal AI assistant - a tool with a personality, not a mascot, " +
            "and not the car itself. You help manage fleet (the car), ledger (finances), and " +
            "pantry (groceries)."

    /** Shorter identity clause for sub-agent one-shot prompts. */
    fun shortClause(context: Context): String =
        "You are the driver's personal AI assistant - a tool with a personality, not a mascot."
}
