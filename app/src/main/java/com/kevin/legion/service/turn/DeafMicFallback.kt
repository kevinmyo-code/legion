package com.kevin.legion.service.turn

/**
 * The deaf hands-free mic fallback's whole state: one capture's silence window, the once-per-
 * conversation verdict, and the claim that stops a second capture opening while the hand-over to the
 * phone's own mic is still running.
 *
 * Kevin, 2026-11-24, in the car: with the head unit's Bluetooth connected, Alfred did not hear him at
 * all, while the phone's log read `bluetooth mic routed=true` on every turn. That readback says the
 * platform SELECTED the link, never that the link carries his voice.
 *
 * Its own type because the session that uses it cannot be built in a unit test, and both halves are
 * decisions that cost him a conversation when they are wrong: a false verdict says "I can't hear you"
 * to a man who was heard, and a lost claim leaves two live captures feeding one turn detector.
 * The window is read and written from the mic thread on every buffer and read from the conversation's
 * own loop, so every field here is volatile.
 */
internal class DeafMicFallback {
    @Volatile private var counting = false
    @Volatile private var openedAtMs = 0L
    @Volatile private var peak = 0
    @Volatile private var fellBack = false
    @Volatile private var handingOver = false

    /** The loudest sample since the window last started; what `heard_nothing=` and the notice carry. */
    val peakSinceOpen: Int get() = peak

    /**
     * A switch off the hands-free mic is running ([handOver]). Nothing may open a capture while it is,
     * and nothing the dying capture still delivers is his turn.
     */
    val switching: Boolean get() = handingOver

    /** How long the window has been counting, 0 when it is not counting. */
    fun heldMs(nowMs: Long): Long = if (counting) nowMs - openedAtMs else 0L

    /**
     * A new capture gets a new window. The verdict is the conversation's and is not reset here.
     * [counting] carries "not started", rather than a zero clock: a sentinel in the timestamp itself
     * would read a real clock of 0 as unstarted, and a unit test is entitled to one.
     */
    fun reset() {
        counting = false
        peak = 0
    }

    /**
     * One buffer's [bufferPeak] at [nowMs]. True exactly once per conversation: the hands-free mic has
     * delivered nothing audible for [BluetoothMic.DEAF_MS] and the phone's own mic must take over.
     *
     * [judging] is false for every moment he cannot be heard anyway, and each of those starts the
     * window again rather than counting through it: Alfred playing (a hands-free unit's own echo
     * canceller holds its mic down to nearly nothing, and that is a working mic doing its job), Mute on
     * (he chose the silence, and the capture deliberately stays open through it), a call pause, and an
     * open whose voice is not built yet. Starting again rather than pausing is what stops an unmute
     * from drawing an instant verdict out of stale time.
     */
    fun read(
        bufferPeak: Int,
        nowMs: Long,
        judging: Boolean,
        onHandsFreeMic: Boolean,
        inLiveCall: Boolean,
    ): Boolean {
        if (!judging) {
            reset()
            return false
        }
        if (bufferPeak > peak) peak = bufferPeak
        if (!counting) {
            openedAtMs = nowMs
            counting = true
        }
        val deaf = BluetoothMic.fallBack(
            onBluetoothMic = onHandsFreeMic,
            inLiveCall = inLiveCall,
            alreadyFellBack = fellBack,
            elapsedMs = nowMs - openedAtMs,
            peakSinceOpen = peak,
        )
        if (deaf) fellBack = true
        return deaf
    }

    /**
     * [switch] moves the conversation off the hands-free mic. The claim is held for the whole of it and
     * released however it ends, because the switch suspends for seconds (the dying capture's join and the
     * new route's settle) on the same single thread as the open path's "nothing is capturing, open
     * one" check. On 2026-11-24 a hand-over that nulled the capture field and then
     * suspended let that check open a SECOND capture inside the gap, on exactly the failure this
     * fallback exists for: the first was never cancelled and kept feeding turns until the scope died.
     */
    suspend fun handOver(switch: suspend () -> Unit) {
        handingOver = true
        try {
            switch()
        } finally {
            handingOver = false
        }
    }
}
