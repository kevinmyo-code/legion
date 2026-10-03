package com.kevin.legion.service

/**
 * The follow-up window: after the assistant finishes speaking, the mic stays open for
 * [WINDOW_MS], and silence for the whole of it closes the conversation like a deliberate stop.
 * `.scratch/wake-word/issues/17-follow-up-window.md`, ruled by ticket 16 (Kevin, 2026-10-03:
 * "8 sec sounds right.").
 *
 * This is the whole decision as a pure fold over events, so the arm / cancel / expire rules are
 * unit-testable without a socket. [GeminiLiveSession] owns the state and the timer (it reuses the
 * existing `idleJob`, not a second timer system) and acts on the returned [Action].
 *
 * Two-step arming on purpose: [Event.TurnComplete] only says the model finished SENDING. The mic
 * physically reopens up to ~1.5 s later (awaitPlaybackDrained), and a window counted from
 * turnComplete would spend part of the 8 s while the assistant's own tail is still playing. So
 * turnComplete marks the window as awaiting, and [Event.MicOpened] starts the clock.
 */
object FollowUpWindow {
    /** Ticket 17 / ruling in ticket 16. The one number to change if the window is ever retuned. */
    const val WINDOW_MS = 8_000L

    data class State(val awaitingMic: Boolean = false, val armed: Boolean = false)

    sealed interface Event {
        /** The assistant's turn finished, in a conversation whose mic is about to reopen. */
        data object TurnComplete : Event
        /** The mic is physically capturing again. */
        data object MicOpened : Event
        /** The first input transcript text of the user's turn arrived. */
        data object UserSpeech : Event
        /** The model asked for a tool: a running tool is not silence. */
        data object ToolCall : Event
        /** [WINDOW_MS] passed since arming. [toolsInFlight] is read at the moment it fires. */
        data class Elapsed(val toolsInFlight: Int) : Event
    }

    enum class Action { NONE, ARM, CANCEL, CLOSE }

    data class Decision(val state: State, val action: Action)

    fun step(state: State, event: Event): Decision = when (event) {
        Event.TurnComplete ->
            // Re-arm path: a window still running from the previous answer is replaced.
            Decision(State(awaitingMic = true), if (state.armed) Action.CANCEL else Action.NONE)
        Event.MicOpened ->
            if (state.awaitingMic) Decision(State(armed = true), Action.ARM)
            else Decision(state, Action.NONE)
        Event.UserSpeech, Event.ToolCall ->
            Decision(
                State(),
                if (state.armed || state.awaitingMic) Action.CANCEL else Action.NONE,
            )
        is Event.Elapsed ->
            when {
                !state.armed -> Decision(state, Action.NONE)
                // A running tool is not silence. Disarm; the turn that follows it re-arms.
                event.toolsInFlight > 0 -> Decision(State(), Action.NONE)
                else -> Decision(State(), Action.CLOSE)
            }
    }
}
