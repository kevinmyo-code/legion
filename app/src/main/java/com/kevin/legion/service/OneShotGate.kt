package com.kevin.legion.service

/**
 * Decides when a one-shot conversation hangs up (2026-09-27). Summoned from the side key, LEGION
 * hears one thing, answers it, and stops, instead of reopening the mic. Kevin: *"a single input. so
 * it wont continue listening after i say something."*
 *
 * Pure, so the one sequence that matters is testable: **a conversation that opens with a greeting
 * ends a turn before the user has said anything**, and hanging up there would cut him off before
 * he spoke. So the hang-up is due only on the first turn end AFTER the mic has opened, which is the
 * reply to what he said. Fires once.
 */
class OneShotGate {
    private var armed = false
    private var micOpened = false

    /** A new conversation begins. [oneShot] true arms the gate; false (an ordinary tap) disarms it. */
    fun start(oneShot: Boolean) {
        armed = oneShot
        micOpened = false
    }

    /** A conversation already listening becomes one-shot: it ends after the next reply. The mic is
     * already open, so that reply counts. */
    fun armMidConversation() {
        armed = true
        micOpened = true
    }

    fun onMicOpened() {
        if (armed) micOpened = true
    }

    /** True exactly once: when the reply to the user's words has just finished. Speak-only turns
     * (no conversation) never count. */
    fun onTurnComplete(inConversation: Boolean): Boolean {
        if (!inConversation || !armed || !micOpened) return false
        armed = false
        return true
    }
}
