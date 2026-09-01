package com.kevin.legion.service

import android.util.Log

/**
 * The single answer to "who has the microphone right now" (wake-word ticket 05,
 * `.scratch/wake-word/issues/05-mic-ownership.md`). Before this object existed, nothing could
 * answer that question at all: three subsystems - [WakeWordEngine], [GeminiLiveSession]'s own
 * capture, and the ring-listening window in [LiveSessionController] - each opened the microphone
 * on their own say-so, coordinated only by a boolean ([ConversationState.isBusy]) that nothing
 * enforced consistently and that describes "a conversation is active" rather than "who holds the
 * mic right now".
 *
 * **This shape already cost a real bug once, in a sibling system.**
 * `GeminiLiveSession.duckNow()`'s `ducked` flag stayed `true` after Android revoked audio focus,
 * because nothing told the session it had lost something - it only ever checked a flag it also
 * set itself (`.scratch/proactive-mode/issues/13-silent-after-focus-loss.md`). [MicArbiter] exists
 * so the microphone equivalent of that bug is structurally impossible: a claimant that loses the
 * mic is TOLD, via the [Listener] it registered when it was granted, in the same call that hands
 * the mic to whoever preempted it. It never has to poll a flag it also owns to find out.
 *
 * Priority is fixed and settled (ticket 05 resolution, Kevin 2026-08-21). [Claimant] is declared
 * in rank order on purpose, so [Claimant.ordinal] doubles as priority for every pair EXCEPT one
 * deliberate exception - see [outranks] - lower ordinal wins:
 *
 * 1. [Claimant.LIVE_TURN] - a conversation the user started. The user may be mid-sentence;
 *    nothing preempts it.
 * 2. [Claimant.RING_LISTENING] - the few-second window while a call rings and the user can say
 *    "answer it".
 * 3. [Claimant.VOICE_NOTE] - a recording Kevin deliberately started (voice-notes ticket 01,
 *    `.scratch/voice-notes/issues/01-the-recorder-and-the-mic.md`). See [outranks] for its one
 *    asymmetric rule against [Claimant.LIVE_TURN].
 * 4. [Claimant.WAKE_WORD] - yields to everything, and is expected to keep trying to reacquire once
 *    whatever preempted it releases (see [WakeWordEngine]'s watchdog - that retry loop lives on
 *    the claimant, not here, because it is the only claimant that ever needs one).
 *
 * A request from a claimant that outranks (or already IS) the sitting holder succeeds - either by
 * preempting it or, if [current] already equals [Claimant], as an idempotent no-op. A request from
 * a claimant that does not outrank the sitting holder is REFUSED, not queued: there is no "next in
 * line" here. This object tracks one holder, not a waiting list.
 */
object MicArbiter {
    private const val TAG = "MicArbiter"

    /** Declared in priority order - see the class doc. Do not reorder without re-reading ticket 05
     * (for the first three) and voice-notes ticket 01 (for [VOICE_NOTE]'s insertion point). */
    enum class Claimant { LIVE_TURN, RING_LISTENING, VOICE_NOTE, WAKE_WORD }

    /**
     * Does [requester] take the mic away from [holder]? [requester] already holding it (an
     * idempotent re-request) is handled by the caller in [request], not here.
     *
     * **Ordinal comparison for every pair except one.** Ticket 01 ruled three of
     * [Claimant.VOICE_NOTE]'s four relationships as ordinary priority (yields to nothing weaker
     * than itself, preempts [Claimant.WAKE_WORD], is preempted by [Claimant.RING_LISTENING] -
     * "losing the call to protect the recording is the wrong trade") - all three fall straight out
     * of [Claimant.ordinal] with [Claimant.VOICE_NOTE] sitting where it is declared, between
     * [Claimant.RING_LISTENING] and [Claimant.WAKE_WORD].
     *
     * **[Claimant.LIVE_TURN] and [Claimant.VOICE_NOTE] are the one pair ordinal comparison cannot
     * express, because the ticket asks for a relationship that isn't a total order.** Ticket 01,
     * verbatim: "Yields to LIVE_TURN? No. A recording in progress is not interrupted by the
     * assistant deciding to listen. Starting a Live turn while recording fails with a worded
     * refusal." - so a request FOR [Claimant.LIVE_TURN] against a [Claimant.VOICE_NOTE] holder must
     * be refused, even though [Claimant.LIVE_TURN] outranks everything else in this object by
     * ordinal. Reordering the enum to express that (moving [Claimant.VOICE_NOTE] ahead of
     * [Claimant.LIVE_TURN]) would flip it: a [Claimant.VOICE_NOTE] REQUEST would then preempt a
     * sitting [Claimant.LIVE_TURN], which contradicts this object's original, still-standing
     * invariant that nothing preempts a conversation the user is mid-sentence in. No ordinal
     * placement of [Claimant.VOICE_NOTE] satisfies both directions at once, which is exactly why
     * this is a named special case rather than a rank.
     *
     * The reverse direction - a [Claimant.VOICE_NOTE] request against a sitting
     * [Claimant.LIVE_TURN] holder - is not stated in so many words by the ticket, but follows from
     * the same already-settled invariant this object has carried since ticket 05: nothing preempts
     * [Claimant.LIVE_TURN]. Refusing it here is applying that existing rule, not inventing a new
     * one.
     */
    private fun outranks(requester: Claimant, holder: Claimant): Boolean {
        val pair = setOf(requester, holder)
        if (pair == setOf(Claimant.LIVE_TURN, Claimant.VOICE_NOTE)) return false
        return requester.ordinal < holder.ordinal
    }

    /**
     * How a preempted claimant is told to stop - a push, not something it has to poll for.
     * [onMicPreempted] is invoked synchronously, on whichever thread called [request] to preempt
     * you, so an implementation that needs to do real teardown work (stop an AudioRecord, close a
     * socket) must dispatch that work itself rather than block the preempting caller inside this
     * callback - the same reason [WakeWordEngine]'s listener launches onto its own scope instead
     * of tearing the recognizer down inline.
     */
    fun interface Listener {
        fun onMicPreempted()
    }

    private val lock = Any()
    private var holder: Claimant? = null
    // The listener the CURRENT holder registered when it was granted, so a later preemption has
    // something to call. Only ever holds the sitting holder's entry - cleared on release.
    private var holderListener: Listener? = null

    /**
     * Asks for the microphone on [claimant]'s behalf. [onPreempted], if given, replaces whatever
     * listener [claimant] registered on a previous grant (a fresh caller may hand in a fresh
     * closure) and is remembered for as long as [claimant] holds the mic - invoked exactly once,
     * later, if and when a higher-priority claimant takes it away. Never invoked as part of THIS
     * call, even when this call is itself the one doing the preempting.
     *
     * @return true if [claimant] now holds the mic - immediately, because it already did (a
     * re-request is a no-op success, not an error), or because it just preempted a lower-ranked
     * holder. False if a higher-or-equal-ranked claimant already holds it; the request is
     * REFUSED outright, never queued.
     */
    fun request(claimant: Claimant, onPreempted: Listener? = null): Boolean {
        var outgoingListener: Listener? = null
        var outgoingHolder: Claimant? = null
        val granted: Boolean
        synchronized(lock) {
            val current = holder
            outgoingHolder = current
            granted = current == null || current == claimant || outranks(claimant, current)
            if (granted) {
                if (current != claimant) {
                    // Either the mic was free, or we just outranked whoever had it. Either way
                    // capture the outgoing holder's listener (null if the mic was simply free) to
                    // fire AFTER the lock is released below - never call out to another
                    // subsystem while holding this lock, or a listener that itself calls back
                    // into request()/release() would deadlock.
                    outgoingListener = holderListener
                }
                holder = claimant
                holderListener = onPreempted
            }
        }
        if (granted) {
            Log.d(TAG, "request: $claimant granted" + (outgoingHolder?.let { " (preempted $it)" } ?: ""))
        } else {
            Log.d(TAG, "request: $claimant refused - held by $outgoingHolder")
        }
        // Fire the outgoing holder's listener outside the lock, and only when we actually took
        // the mic from a DIFFERENT claimant (not on our own idempotent re-request).
        if (granted) outgoingListener?.onMicPreempted()
        return granted
    }

    /**
     * Releases [claimant]'s claim. No-op if [claimant] does not currently hold the mic - either it
     * never held it, or it already lost it to a preemption, which already cleared [holder] out
     * from under it. Safe (and expected) to call defensively from a teardown path that does not
     * know whether it actually held the mic at the moment of teardown.
     */
    fun release(claimant: Claimant) {
        synchronized(lock) {
            if (holder == claimant) {
                holder = null
                holderListener = null
            }
        }
    }

    /** Who holds the microphone right now, or null if nobody has claimed it. */
    fun current(): Claimant? = synchronized(lock) { holder }
}
