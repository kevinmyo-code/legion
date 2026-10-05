package com.kevin.legion.service

/**
 * What a TYPED message does, given what the Live session is doing right now. Pure and Android-free
 * so every overlap between a typed turn and a spoken one is a plain JVM unit test
 * ([TypedTurnPolicyTest]); [LiveSessionController.onTyped] only maps its own state onto [Shape]
 * and carries out the [Decision].
 *
 * Ruled in `.scratch/web-assistant/issues/06-android-typed-chat.md`: a typed turn goes into the SAME
 * Live session as a text turn, its reply is shown as text and not spoken, typing over a spoken reply
 * stops it, and typing with no session opens one with the microphone CLOSED. The rules below are
 * the part that ticket left open ("what happens when a typed chat and push-to-talk overlap"):
 *
 * - **Typing wins over the assistant's speech.** Same as speaking over it: playback is cut and the
 *   typed message is the next turn (`interrupts` on [Decision.SendInConversation] /
 *   [Decision.SendOnWarm]).
 * - **A typed message never takes the microphone from a conversation, and never opens one.** In a
 *   running voice conversation the mic keeps its own state (it reopens after the reply as it always
 *   does); on a warm or fresh socket it stays closed.
 * - **Push-to-talk wins over a typed turn still in flight** - see [ChatTranscript.voiceTookOver]
 *   and `GeminiLiveSession.beginConversation`, which un-mutes playback. The person who starts
 *   talking wants to hear the answer.
 * - **Not while a tool is running.** A tool call is the model waiting on a response from us;
 *   sending a second user turn into that gap is not something the Live protocol promises to handle.
 *   The message is NOT sent and the panel says so in words (never a silent drop).
 * - **Typing needs no microphone and says nothing about it.** There is no mic input here by design:
 *   a revoked RECORD_AUDIO grant must not stop typing.
 */
object TypedTurnPolicy {

    /** What the session is doing, reduced to the cases that change the answer. */
    enum class Shape {
        /** No socket at all. */
        NO_SESSION,

        /** A prewarm socket still connecting - nothing is promised to it yet, so a typed turn can wait for it. */
        CONNECTING_IDLE,

        /** A socket still connecting for a voice conversation or a proactive line. */
        CONNECTING_BUSY,

        /** Connected, mic closed, no conversation. */
        WARM,

        /** A hands-free voice conversation is running (mic open, thinking, or speaking). */
        IN_CONVERSATION,

        /** The model asked for a tool and is waiting on our response. */
        TOOL_RUNNING,

        /** Connected, not warm, not a conversation: a speak-only proactive line mid-delivery. */
        PROACTIVE_LINE,
    }

    enum class Refusal(val words: String) {
        NO_KEY("The assistant isn't set up: add a Gemini key in Setup."),
        OFFLINE("Not sent: there is no internet connection."),
        CONNECTING("Not sent: still connecting. Try again in a moment."),
        TOOL_RUNNING("Not sent: still working on your last request. Try again when it finishes."),
        SOCKET_GONE("Not sent: the connection had already closed. Try again."),
        COULD_NOT_CONNECT("Not sent: couldn't connect to the assistant. Check the connection and try again."),
    }

    sealed interface Decision {
        /** Nothing to send (blank). Not a refusal: there is nothing to report. */
        data object Ignore : Decision

        /** No session: open one with the mic closed, send on connect. */
        data object OpenSession : Decision

        /** A prewarm socket is connecting: queue the message for when it is up. */
        data object QueueUntilConnected : Decision

        /** Tear down the proactive line's socket, then [OpenSession]. */
        data object ReplaceAndOpen : Decision

        /** Warm socket: send now, mic stays closed. [interrupts] when a line is being spoken. */
        data class SendOnWarm(val interrupts: Boolean) : Decision

        /** Voice conversation running: send now, mic untouched. [interrupts] when the assistant is speaking. */
        data class SendInConversation(val interrupts: Boolean) : Decision

        data class Refuse(val reason: Refusal) : Decision
    }

    fun decide(
        hasText: Boolean,
        hasKey: Boolean,
        online: Boolean,
        shape: Shape,
        assistantSpeaking: Boolean,
    ): Decision = when {
        !hasText -> Decision.Ignore
        !hasKey -> Decision.Refuse(Refusal.NO_KEY)
        !online -> Decision.Refuse(Refusal.OFFLINE)
        else -> when (shape) {
            Shape.NO_SESSION -> Decision.OpenSession
            Shape.CONNECTING_IDLE -> Decision.QueueUntilConnected
            Shape.CONNECTING_BUSY -> Decision.Refuse(Refusal.CONNECTING)
            Shape.TOOL_RUNNING -> Decision.Refuse(Refusal.TOOL_RUNNING)
            Shape.PROACTIVE_LINE -> Decision.ReplaceAndOpen
            Shape.WARM -> Decision.SendOnWarm(interrupts = assistantSpeaking)
            Shape.IN_CONVERSATION -> Decision.SendInConversation(interrupts = assistantSpeaking)
        }
    }
}
