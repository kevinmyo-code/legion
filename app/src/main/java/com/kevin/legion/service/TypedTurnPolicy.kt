package com.kevin.legion.service

/**
 * What a TYPED message does. Pure and Android-free, so every case is a plain JVM unit test
 * ([TypedTurnPolicyTest]); [LiveSessionController.onTyped] only gathers the inputs and carries the
 * [Decision] out.
 *
 * **Typed chat goes to a text model, not the Live voice session (Kevin, 2026-10-27).** This
 * supersedes `.scratch/web-assistant/issues/06-android-typed-chat.md`'s 2026-10-04 resolution (a Live
 * text turn with the audio muted). A typed message never touches the Live socket, so the voice
 * session's state is not an input here at all: no cold connect, no microphone or wake-word side
 * effect, no follow-up window. The rules:
 *
 * - **A crisis phrase is handled before anything else** (CLAUDE.md sec 7, [com.kevin.legion.ai.CrisisDetector]):
 *   the crisis card is the whole response, even with no key or no connection, and the message is
 *   not sent to any model.
 * - **Typing during a live voice conversation answers in text and leaves the voice session alone.**
 *   Speech keeps playing, the mic keeps its state, the follow-up window keeps its own clock.
 * - **One typed message at a time.** A second one while the first is still being answered is NOT
 *   sent, and says so. Cancelling the first could interrupt a tool mid-write and leave its outcome
 *   unreported, which is the false-success shape sec 7 forbids.
 * - **Typing needs no microphone and says nothing about it.** A revoked RECORD_AUDIO grant must not
 *   stop typing.
 */
object TypedTurnPolicy {

    enum class Refusal(val words: String) {
        NO_KEY("The assistant isn't set up: add a Gemini key in Setup."),
        OFFLINE("Not sent: there is no internet connection."),
        BUSY("Not sent: still answering your last message. Try again when it finishes."),
    }

    sealed interface Decision {
        /** Nothing to send (blank). Not a refusal: there is nothing to report. */
        data object Ignore : Decision

        /** A crisis phrase: show the crisis resources, send nothing to any model. */
        data object Crisis : Decision

        /** Send to the text model. */
        data object Send : Decision

        data class Refuse(val reason: Refusal) : Decision
    }

    fun decide(
        hasText: Boolean,
        crisis: Boolean,
        hasKey: Boolean,
        online: Boolean,
        textTurnInFlight: Boolean,
    ): Decision = when {
        !hasText -> Decision.Ignore
        crisis -> Decision.Crisis
        !hasKey -> Decision.Refuse(Refusal.NO_KEY)
        !online -> Decision.Refuse(Refusal.OFFLINE)
        textTurnInFlight -> Decision.Refuse(Refusal.BUSY)
        else -> Decision.Send
    }
}
