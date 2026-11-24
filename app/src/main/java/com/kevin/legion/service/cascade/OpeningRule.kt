package com.kevin.legion.service.cascade

import com.kevin.legion.service.turn.AudioRoute

/**
 * How a conversation opens (Kevin, 2026-11-19: "agreed" to the speed plan, item 1).
 *
 * - [Opening.CHIME]: he tapped (push to talk). He already knows what he wants to say, so nothing is
 *   said: a short local chime and the mic is listening; the voice service, the prompt and the cache
 *   are set up behind it, and his audio is kept from the tap ([MicBacklog]). Before this, a tap waited
 *   for a model-written greeting (median first audio 2.3 s after the open on the 11-19 drive) and,
 *   half-duplex, could not be heard until it had finished playing.
 * - [Opening.GREETING]: the wake word. The spoken greeting stays: he may be across the room and the
 *   voice is how he knows he was heard.
 * - [Opening.OPENER]: a line the model is asked for first (a proactive check-in, Brief me): that
 *   line is the opening.
 * - [Opening.PICKUP]: he pressed Call (Kevin, 2026-11-24). Alfred answers like a person, with a
 *   short scripted line ([CallPickup]) and only then listens: a call that opens in silence does not
 *   feel like a call, and a model-written hello is two seconds late.
 *
 * Pure: no Android.
 */
internal object OpeningRule {
    enum class Opening { CHIME, GREETING, OPENER, PICKUP }

    /**
     * A line it was asked for first still wins (that line IS the opening, and it is the one opening
     * the earpiece refuses); then a call, then the wake word, then the tap's chime.
     */
    fun of(fromWakeWord: Boolean, hasOpener: Boolean, call: Boolean = false): Opening = when {
        hasOpener -> Opening.OPENER
        call -> Opening.PICKUP
        fromWakeWord -> Opening.GREETING
        else -> Opening.CHIME
    }

    /**
     * Barge-in for this conversation, from the route alone (no voice service needed), so a tap can
     * enter the call audio mode or the car's hands-free mic before the mic opens. The same rule the
     * turn plan applies ([CascadeRules.turnPlan] calls this).
     */
    fun bargeIn(bargeSetting: Boolean, route: AudioRoute, inCall: Boolean): Boolean =
        bargeSetting && route != AudioRoute.BLUETOOTH && !inCall
}
