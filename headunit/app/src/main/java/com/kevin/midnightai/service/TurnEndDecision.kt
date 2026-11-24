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
 *
 * `dismissed` (2026-11-24) is the driver's goodbye, armed by [DriverDismissal] from this turn's
 * transcript. It is FIRST, because it is the one input that is the driver's explicit instruction
 * rather than a state the code inferred; everything below it is about what the session was doing.
 * It takes the same pending-tool detour for the same reason: the call turn is not the answer turn,
 * so a dismissal said while a tool runs waits for the answer to be spoken rather than parking on
 * top of it. Parking rather than closing is what lets the sign-off finish - `parkWarm` shuts the
 * mic and brings the music back but never flushes the playback queue, exactly as a proactive
 * line's own PARK_WARM already does at its turnComplete.
 */
internal object TurnEndDecision {
    enum class Action { PARK_WARM, HOLD_FOR_TOOL, OPEN_MIC, RESTORE_AND_CLOSE }

    fun decide(
        suppressMic: Boolean,
        toolsPending: Boolean,
        vadMode: Boolean,
        dismissed: Boolean = false,
    ): Action = when {
        dismissed && toolsPending -> Action.HOLD_FOR_TOOL
        dismissed -> Action.PARK_WARM
        suppressMic && !toolsPending -> Action.PARK_WARM
        suppressMic -> Action.HOLD_FOR_TOOL
        vadMode -> Action.OPEN_MIC
        else -> Action.RESTORE_AND_CLOSE
    }
}
