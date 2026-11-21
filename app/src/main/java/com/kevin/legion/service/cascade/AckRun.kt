package com.kevin.legion.service.cascade

import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The end-of-turn acknowledgement, as the session runs it (2026-11-21, the guard on "instant"): wait
 * [Backchannel.DELAY_MS] from the end-of-turn decision, check it is still due, pick a clip from memory,
 * and under the play gate hand it to the player. The session supplies the steps ([Steps]); this order
 * and its only wait are here, so `AckTimingTest` runs this same code on a virtual clock and asserts the
 * first sample reaches the player within [FillerTiming.ACK_MAX_START_MS].
 */
internal class AckRun(private val now: () -> Long, private val gate: Mutex, private val steps: Steps) {
    /** What the session does at each step; none may wait except as its doc says. */
    interface Steps {
        /** The still-due check to repeat under the gate, or null when skipped (it may wait for the open's cue). */
        suspend fun stillDue(): (() -> Boolean)?

        /** The clip from memory ([AckClips.pick]), or null when none fits. */
        suspend fun pick(): AckClips.Picked?

        /** No clip fits: logged as a skip. */
        fun noClip()

        /** It plays now: the audit, the rotation, the counters. */
        fun started(picked: AckClips.Picked)

        /** Hands the clip to the player and waits for it to drain. */
        suspend fun play(pcm: ByteArray)

        /** After the clip: the route's settle and the turn's marks. */
        suspend fun after()
    }

    suspend fun run(eotAtMs: Long) {
        delay((eotAtMs + Backchannel.DELAY_MS - now()).coerceAtLeast(0))
        val due = steps.stillDue() ?: return
        val picked = steps.pick() ?: return steps.noClip()
        val played = gate.withLock {
            if (!due()) return@withLock false
            steps.started(picked)
            steps.play(picked.pcm)
            true
        }
        if (played) steps.after()
    }
}
