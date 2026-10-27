package com.kevin.legion.service

/**
 * What a companion switch does to the Live session, as pure decisions
 * ([LiveSessionController.companionChanged] carries them out). Split out because the controller
 * cannot be built on a plain JVM, and this is the part a 2026-10-05 on-device run showed wrong.
 *
 * **The defect.** With Alfred active, switching companion on the Companions screen left the next
 * TYPED question answered as Alfred, because `companionChanged` rebuilt an idle socket but kept
 * `sessionResumeHandle`. The handle points at the outgoing companion's server-side history, and
 * ADR 0047 drops it for exactly that reason; it was only dropped on the mid-conversation branch.
 * The rebuilt prewarm, and the typed session opened next, both resumed the old thread.
 *
 * The rule (ADR 0047): a switch ends whatever session is open, typed or spoken, and the next turn
 * - typed or spoken - opens one with the new companion's prompt and voice.
 */
object CompanionHandover {

    /** What [LiveSessionController.companionChanged] must do with its session state. */
    data class Plan(
        /** Destroy the open socket, whatever opened it (typed, warm, proactive or spoken). */
        val dropSession: Boolean,
        /** Always null: the old thread's resume handle must never reach the new companion. */
        val resumeHandle: String?,
        /** A mid-conversation switch greets on the handover prompt; otherwise nobody is mid-sentence. */
        val spokenHandover: Boolean,
    )

    fun plan(inVoiceConversation: Boolean) =
        Plan(dropSession = true, resumeHandle = null, spokenHandover = inVoiceConversation)

    /** What a typed message sees right after a switch: no session, so it opens one. */
    val shapeAfterSwitch: TypedTurnPolicy.Shape = TypedTurnPolicy.Shape.NO_SESSION
}
