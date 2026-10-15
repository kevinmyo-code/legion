package com.kevin.midnightai.service

/**
 * What a completed model turn does next, as a pure function of three flags so the branch order
 * is a JVM test rather than a reading exercise (voice-pipeline audit B7, 2026-10-15).
 *
 * The order is the point. A proactive line spoken on a warm socket sets `suppressMic`; if the
 * model answers it with a tool call, the first turnComplete is the CALL turn and a tool is still
 * pending. Parking there dropped the session to idle mid-flow, and the answer that followed
 * opened the mic with the phase reset. So a suppressed turn with a tool pending HOLDS, and only
 * the answer's own turnComplete (nothing pending) parks.
 */
internal object TurnEndDecision {
    enum class Action { PARK_WARM, HOLD_FOR_TOOL, OPEN_MIC, RESTORE_AND_CLOSE }

    fun decide(suppressMic: Boolean, toolsPending: Boolean, vadMode: Boolean): Action = when {
        suppressMic && !toolsPending -> Action.PARK_WARM
        suppressMic -> Action.HOLD_FOR_TOOL
        vadMode -> Action.OPEN_MIC
        else -> Action.RESTORE_AND_CLOSE
    }
}
