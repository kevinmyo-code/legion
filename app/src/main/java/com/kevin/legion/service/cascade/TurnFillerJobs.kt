package com.kevin.legion.service.cascade

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.coroutines.CoroutineContext

/**
 * This turn's two filler timers, the backchannel ([Backchannel]) and the wait lines ([WaitFillers]).
 * They live exactly as long as the wait for an answer: started at his end of speech, and ended by
 * [endTurn] when he speaks again, a tap stops the turn, or the turn ends with nothing to say (noise,
 * a dropped transcript, a failed voice). Review B1, 2026-11-20: the wait timer outlived a turn that
 * ended silently and said "Still on it." up to three times with no answer coming.
 */
internal class TurnFillerJobs(private val scope: CoroutineScope, private val context: CoroutineContext) {
    private var backchannel: Job? = null
    private var wait: Job? = null

    /** The backchannel timer for this turn, replacing any earlier one. */
    fun startBackchannel(block: suspend CoroutineScope.() -> Unit) {
        backchannel?.cancel()
        backchannel = scope.launch(context, block = block)
    }

    /** The wait-line timer for this turn, replacing any earlier one. */
    fun startWait(block: suspend CoroutineScope.() -> Unit) {
        wait?.cancel()
        wait = scope.launch(context, block = block)
    }

    /** The turn's wait is over, answered or not: neither timer plays anything more. */
    fun endTurn() {
        backchannel?.cancel()
        wait?.cancel()
    }

    /** Waits for this turn's backchannel to finish (the task line's spacing). */
    suspend fun awaitBackchannel() {
        backchannel?.join()
    }
}

/**
 * A clip's playback mark (review M1, 2026-11-20): [during] marks playback for its block and clears it
 * at the end, also when the block is cancelled mid-clip (his speech cancels a backchannel or a wait
 * line), unless [replyHolds] says a real reply has the playback now. Before this, a cancelled clip
 * left the mark set, and the mic judged his audio by the stricter during-playback gate until the next
 * reply ended.
 */
internal class ClipPlayback(private val setActive: (Boolean) -> Unit, private val replyHolds: () -> Boolean) {
    suspend fun <T> during(block: suspend () -> T): T {
        setActive(true)
        try {
            return block()
        } finally {
            if (!replyHolds()) setActive(false)
        }
    }
}
