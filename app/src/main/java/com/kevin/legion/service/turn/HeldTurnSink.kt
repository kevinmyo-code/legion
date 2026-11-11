package com.kevin.legion.service.turn

import java.io.ByteArrayOutputStream

/**
 * Holds the FIRST user turn of a conversation on the phone before anything reaches Live (voice
 * cost work phase 2): the local command path's seam.
 *
 * Wraps the [TurnTaker.Sink] that sends activityStart / audio / activityEnd to Live. While armed,
 * those three are buffered instead of sent. At the turn's end, [decide] gets the whole utterance
 * (16 kHz mono PCM16, the same bytes Live would have received, pre-roll included) and answers:
 * - **true: handled on the phone.** Nothing is ever sent, so Live runs no generation and bills
 *   nothing for that turn.
 * - **false: a miss.** The held turn is replayed to Live exactly as it would have gone (start,
 *   every audio chunk in order, end), so Kevin never repeats himself.
 *
 * Only the first turn is held, then the sink passes everything straight through. Every other
 * callback (onUserTurnStart, onEndOfTurn, barge-in, logs) passes through untouched, so the
 * conversation's own bookkeeping is unchanged. A decision that throws counts as a miss.
 * [decide] runs on the caller's thread of `sendActivityEnd`; the caller passes a function that
 * returns quickly (the recogniser runs on the held PCM, a fraction of a second on a short phrase).
 */
internal class HeldTurnSink(
    private val inner: TurnTaker.Sink,
    private val decide: (pcm: ByteArray) -> Boolean,
    armed: Boolean,
) : TurnTaker.Sink by inner {

    @Volatile var holding: Boolean = armed
        private set

    private val held = ByteArrayOutputStream()
    private var started = false

    override fun sendActivityStart() {
        if (holding) started = true else inner.sendActivityStart()
    }

    override fun sendAudio(pcm: ByteArray, offset: Int, length: Int) {
        if (holding) held.write(pcm, offset, length) else inner.sendAudio(pcm, offset, length)
    }

    override fun sendActivityEnd() {
        if (!holding) {
            inner.sendActivityEnd()
            return
        }
        holding = false
        val pcm = held.toByteArray()
        held.reset()
        val handled = runCatching { decide(pcm) }.getOrDefault(false)
        if (handled) return
        if (started) inner.sendActivityStart()
        var at = 0
        while (at < pcm.size) {
            val n = minOf(REPLAY_CHUNK, pcm.size - at)
            inner.sendAudio(pcm, at, n)
            at += n
        }
        inner.sendActivityEnd()
    }

    /** Hold the next turn (a tapped or woken conversation's first). */
    fun arm() {
        held.reset()
        started = false
        holding = true
    }

    /** Stop holding without a decision (the conversation ended, or the route changed). */
    fun release() {
        holding = false
        held.reset()
    }

    companion object {
        /** 100 ms of 16 kHz mono PCM16, about what the mic loop sends per read. */
        const val REPLAY_CHUNK = 3_200
    }
}
