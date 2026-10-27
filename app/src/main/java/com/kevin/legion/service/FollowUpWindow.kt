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

    data class State(
        val awaitingMic: Boolean = false,
        val armed: Boolean = false,
        val extensions: Int = 0,
    )

    sealed interface Event {
        /** The assistant's turn finished, in a conversation whose mic is about to reopen. */
        data object TurnComplete : Event
        /** The mic is physically capturing again. */
        data object MicOpened : Event
        /** The first input transcript text of the user's turn arrived. */
        data object UserSpeech : Event
        /** The model asked for a tool: a running tool is not silence. */
        data object ToolCall : Event
        /**
         * [WINDOW_MS] passed since arming. [toolsInFlight] is read at the moment it fires;
         * [msSinceLocalSpeech] is how long ago the mic last read a buffer at or above
         * [LOCAL_SPEECH_PEAK] since it opened, null if it never did.
         */
        data class Elapsed(val toolsInFlight: Int, val msSinceLocalSpeech: Long? = null) : Event
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
                heardLocally(event.msSinceLocalSpeech) && state.extensions < MAX_EXTENSIONS ->
                    Decision(State(armed = true, extensions = state.extensions + 1), Action.ARM)
                else -> Decision(State(), Action.CLOSE)
            }
    }

    private fun heardLocally(msSinceLocalSpeech: Long?): Boolean =
        msSinceLocalSpeech != null && msSinceLocalSpeech <= LOCAL_SPEECH_RECENT_MS
}
