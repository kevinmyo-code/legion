package com.kevin.legion.ui.phone

import com.kevin.legion.service.PlaceCallAction

/**
 * The dial screen's confirm-gate state machine, as a pure function of
 * [PlaceCallAction.dispatchVoiceCall]'s own result (command-center ticket 05, ADR 0035's second
 * case for this ticket - `place_call` had no hands path but a hardcoded-number debug screen).
 *
 * **Not a second implementation of the confirm gate.** [PlaceCallAction.dispatchVoiceCall] is the
 * one function that resolves a contact/number, refuses an emergency number, and gates a real dial
 * behind `confirmed=true` - this file calls it TWICE, exactly the way the voice tool's own two
 * turns do (first `confirmed=false` to resolve and read back, then `confirmed=true` after the
 * user's own second tap), and only classifies the English sentence that comes back into a state a
 * Compose screen can render. The classification is presentation logic; the gate itself never moves.
 *
 * ### Why classification is string-matching rather than a new enum on [PlaceCallAction]
 *
 * [PlaceCallAction] is READ-ONLY for this ticket (another agent's territory-adjacent surface), and
 * `VoiceCallResult` is deliberately a bare `(success, message)` pair - the voice tool only ever
 * needs the sentence to speak. The three markers below
 * ([EMERGENCY_MARKER], [CONFIRM_MARKER]) are read directly out of `PlaceCallAction.kt`'s own
 * literal strings; [PlaceCallActionTest] already asserts each one appears in its matching branch,
 * so a future edit to those sentences fails THAT test before it can silently break this
 * classification.
 */
object PhoneDialLogic {

    /** One step of the two-tap flow, ticket 05's "confirm gate survives translation to touch". */
    sealed class Step {
        /** Tap 1 resolved cleanly. [readBack] is the exact sentence to show and to re-confirm -
         * the contact's name, or the digits grouped for speech. Tap 2 must call
         * [PlaceCallAction.dispatchVoiceCall] again with `confirmed = true` and the SAME query. */
        data class Confirm(val readBack: String) : Step()

        /** Refused before ever reaching the confirm gate - shown as its own screen state per the
         * ticket's rule, never folded into an ordinary failure message. */
        data class EmergencyRefused(val message: String) : Step()

        /** One of the three non-emergency failure sentences: no such contact, several matches (the
         * message itself lists them - this state ASKS, it never auto-picks), or no permission. */
        data class Rejected(val message: String) : Step()

        /** Tap 2 actually placed the call - [PlaceCallAction.dial] observed OFFHOOK. Only this
         * state may show an outcome verb, per CLAUDE.md §7 - it is built from `success == true`,
         * which [PlaceCallAction] only sets after watching the platform's own call state. */
        data class Called(val message: String) : Step()

        /** Tap 2 was confirmed but the call did not connect (ACTION_CALL swallowed, or the
         * platform never reached OFFHOOK) - distinct from [Rejected] because it happened AFTER
         * confirmation, not before it, and the screen's retry affordance differs (retry the SAME
         * dial, not re-resolve the target). */
        data class Failed(val message: String) : Step()
    }

    /** Read directly from `PlaceCallAction.dispatchVoiceCall`'s own emergency-refusal sentence. */
    private const val EMERGENCY_MARKER = "emergency"

    /** Read directly from `PlaceCallAction.dispatchVoiceCall`'s own confirm-gate sentence:
     * `"Before I dial, say this back to the user and get a yes: \"$readBack\". ..."`. */
    private const val CONFIRM_MARKER = "Before I dial"

    /**
     * Classifies one [PlaceCallAction.VoiceCallResult] into a [Step], given whether THIS call was
     * itself the confirmed (second) tap.
     */
    fun classify(result: PlaceCallAction.VoiceCallResult, wasConfirmed: Boolean): Step = when {
        result.success -> Step.Called(result.message)
        result.message.contains(EMERGENCY_MARKER, ignoreCase = true) ->
            Step.EmergencyRefused(result.message)
        !wasConfirmed && result.message.contains(CONFIRM_MARKER) -> Step.Confirm(readBackOf(result.message))
        wasConfirmed -> Step.Failed(result.message)
        else -> Step.Rejected(result.message)
    }

    /**
     * Pulls the quoted read-back sentence out of the confirm-gate message so the screen can show
     * just "Mom" or "555, 123, 456, 7" rather than the whole instruction-to-the-model sentence that
     * surrounds it. Falls back to the full message if the quoting ever changes shape - a slightly
     * verbose read-back is a cosmetic loss, never a crash.
     */
    internal fun readBackOf(message: String): String {
        val start = message.indexOf('"')
        val end = message.indexOf('"', start + 1)
        return if (start >= 0 && end > start) message.substring(start + 1, end) else message
    }
}
