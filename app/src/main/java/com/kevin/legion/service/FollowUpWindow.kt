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

    /**
     * 2026-10-27, Kevin: "the conversation sometimes ends randomly while im still talking". The
     * only cancel is [Event.UserSpeech], the first input transcript, and the transcript trails
     * speech onset by up to seconds (more with startOfSpeechSensitivity LOW). Speech that starts
     * late in the window loses the race and the session closes mid-sentence. So the mic loop's own
     * level counts: if it heard something loud within [LOCAL_SPEECH_RECENT_MS] of the deadline, the
     * window runs again instead of closing - at most [MAX_EXTENSIONS] times, so road noise that
     * clears [LOCAL_SPEECH_PEAK] cannot hold a conversation open forever (the ruling stays 8 s of
     * quiet; noise buys at most 2 x 8 s more).
     */
    const val LOCAL_SPEECH_RECENT_MS = 1_500L
    const val MAX_EXTENSIONS = 2
    /**
     * Peak amplitude (of 32767) counted as the user talking. MEASURED ON ONE DEVICE (A25 spike,
     * 2026-10-27): Kevin's voice peaked at ~200-400 (logcat `mic level: peak=290/330/380`), the
     * quiet room at 16-31. The old 2,000 was a guess that real speech on that mic never reached,
     * so the local backup never fired. 150 sits clear of the room and under the speech; another
     * phone or a car cabin may need its own number.
     */
    const val LOCAL_SPEECH_PEAK = 150

    /**
     * True when a root server message is `voiceActivity` of type `ACTIVITY_START` (2026-10-27,
     * A25 spike). gemini-3.8-live sends `{"voiceActivity":{"type":"ACTIVITY_START",...}}` the
     * moment it hears speech, while inputTranscription arrives only once, at the END of the
     * utterance. ACTIVITY_START is therefore the earliest user-speech signal there is, and the
     * one the window must cancel on. ACTIVITY_END is not a cancel and reads false.
     */
    fun isActivityStart(root: org.json.JSONObject): Boolean =
        root.optJSONObject("voiceActivity")?.optString("type") == "ACTIVITY_START"

    /**
     * The longest an owed answer holds the window open (async-lookups ticket 07), counted from the
     * sent result or the bare holding line: past this, the next expiry closes as usual, so a model
     * that never answers cannot keep a conversation open. The close lands at the first expiry at or
     * after the cap.
     */
    const val ANSWER_HOLD_CAP_MS = 20_000L

    /**
     * [heldForTool]: the window expired while a tool was still running, so it stood down without
     * closing. Before ADR 0065 every tool blocked and its answer's turnComplete always re-armed. A
     * NON_BLOCKING lookup can outlive the holding line; [Event.ToolResponse] re-arms from here, so a
     * result the model never speaks still ends the conversation instead of leaving it open.
     *
     * [heldForAnswer]: an answer is owed. A NON_BLOCKING result was sent ([Event.ToolResponse]) or the
     * model's turn was only a holding line with nothing behind it ([Event.HoldingLineTurn]), and no
     * substantive turn has followed (async-lookups ticket 07; ticket 04 run 3 lost three answers AND
     * their conversations when a bare "Checking." or "Let me look." turn armed the window and it
     * closed 8.1 s later). While set, an expiry re-arms instead of closing, up to
     * [ANSWER_HOLD_CAP_MS]. Carried through every transition: only [Event.Answered] or the cap
     * clears it.
     */
    data class State(
        val awaitingMic: Boolean = false,
        val armed: Boolean = false,
        val extensions: Int = 0,
        val heldForTool: Boolean = false,
        val heldForAnswer: Boolean = false,
    )

    sealed interface Event {
        /** The assistant's turn finished, in a conversation whose mic is about to reopen. */
        data object TurnComplete : Event
        /** The mic is physically capturing again. */
        data object MicOpened : Event
        /**
         * The reply finished playing while the mic stayed open (barge-in, 2026-10-27): the same
         * moment [MicOpened] marks in half-duplex, which never comes when the mic never closes.
         */
        data object PlaybackDrained : Event
        /** The first input transcript text of the user's turn arrived. */
        data object UserSpeech : Event
        /** The model asked for a tool: a running tool is not silence. */
        data object ToolCall : Event
        /**
         * A NON_BLOCKING tool's result was handed back (ADR 0065). The answer it triggers needs a
         * full window to begin in, whether the window is still counting or stood down for the tool.
         */
        data object ToolResponse : Event
        /**
         * The model started speaking. Normally the user spoke first and the window is already
         * cancelled; a lookup's answer arrives with nobody speaking, so this is what stops the
         * window closing the conversation mid-reply (ADR 0065).
         */
        data object ModelSpeech : Event
        /**
         * The model's turn was only a holding line, with no tool called and nothing pending
         * ([com.kevin.legion.service.turn.HoldingLineWatch]): an answer is owed, hold the window.
         */
        data object HoldingLineTurn : Event
        /**
         * A model turn answered with substance, not a holding line
         * ([com.kevin.legion.service.turn.HoldingLineWatch]): the answer hold is released.
         */
        data object Answered : Event
        /**
         * [WINDOW_MS] passed since arming. [toolsInFlight] is read at the moment it fires;
         * [msSinceLocalSpeech] is how long ago the mic last read a buffer at or above
         * [LOCAL_SPEECH_PEAK] since it opened, null if it never did. [msSinceHold] is how long ago
         * the answer hold began, null if none did (a held window then closes).
         */
        data class Elapsed(
            val toolsInFlight: Int,
            val msSinceLocalSpeech: Long? = null,
            val msSinceHold: Long? = null,
        ) : Event
    }

    enum class Action { NONE, ARM, CANCEL, CLOSE }

    /**
     * [reason] names an answer-hold change for the `LIVE_FOLLOWUP` log: `held_for_answer` (an expiry
     * re-armed), `holding_line` (a bare holding line started a hold), `answered` (released),
     * `answer_hold_cap` (closed at the cap); null otherwise.
     */
    data class Decision(val state: State, val action: Action, val reason: String? = null)

    fun step(state: State, event: Event): Decision = when (event) {
        Event.TurnComplete ->
            // Re-arm path: a window still running from the previous answer is replaced.
            Decision(
                State(awaitingMic = true, heldForAnswer = state.heldForAnswer),
                if (state.armed) Action.CANCEL else Action.NONE,
            )
        Event.MicOpened, Event.PlaybackDrained ->
            if (state.awaitingMic) {
                Decision(State(armed = true, heldForAnswer = state.heldForAnswer), Action.ARM)
            } else {
                Decision(state, Action.NONE)
            }
        Event.UserSpeech, Event.ToolCall, Event.ModelSpeech ->
            Decision(
                State(heldForAnswer = state.heldForAnswer),
                if (state.armed || state.awaitingMic) Action.CANCEL else Action.NONE,
            )
        Event.ToolResponse, Event.HoldingLineTurn, Event.Answered -> answerHold(state, event)
        is Event.Elapsed -> elapsed(state, event)
    }

    /** The answer-owed events (async-lookups ticket 07), split out of [step] for its complexity. */
    private fun answerHold(state: State, event: Event): Decision = when {
        event == Event.HoldingLineTurn -> Decision(state.copy(heldForAnswer = true), Action.NONE, "holding_line")
        event == Event.Answered && state.heldForAnswer ->
            Decision(state.copy(heldForAnswer = false), Action.NONE, "answered")
        event == Event.Answered -> Decision(state, Action.NONE)
        state.armed || state.heldForTool -> Decision(State(armed = true, heldForAnswer = true), Action.ARM)
        else -> Decision(state.copy(heldForAnswer = true), Action.NONE)
    }

    private fun elapsed(state: State, event: Event.Elapsed): Decision = when {
        !state.armed -> Decision(state, Action.NONE)
        // A running tool is not silence. Stand down; the answer's turn, or the tool's own
        // response (ADR 0065), re-arms.
        event.toolsInFlight > 0 ->
            Decision(State(heldForTool = true, heldForAnswer = state.heldForAnswer), Action.NONE)
        heardLocally(event.msSinceLocalSpeech) && state.extensions < MAX_EXTENSIONS ->
            Decision(
                State(armed = true, extensions = state.extensions + 1, heldForAnswer = state.heldForAnswer),
                Action.ARM,
            )
        // An answer is still owed (a sent result, or a bare holding line): keep listening.
        state.heldForAnswer && event.msSinceHold != null && event.msSinceHold < ANSWER_HOLD_CAP_MS ->
            Decision(state, Action.ARM, "held_for_answer")
        state.heldForAnswer -> Decision(State(), Action.CLOSE, "answer_hold_cap")
        else -> Decision(State(), Action.CLOSE)
    }

    private fun heardLocally(msSinceLocalSpeech: Long?): Boolean =
        msSinceLocalSpeech != null && msSinceLocalSpeech <= LOCAL_SPEECH_RECENT_MS
}
