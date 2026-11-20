package com.kevin.legion.service.cascade

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * One transcription, asked a second time while the first is still out (Kevin, 2026-11-19, speed plan
 * item 4a). On the 11-19 drive cloud speech-to-text hangs were behind 5 of the 8 slowest replies and
 * every "Didn't catch that"; the old rule waited out a whole attempt (3.5 s or more) before asking
 * again. Now:
 * - the first request starts at once;
 * - if it has not answered after [hedgeAfterMs], a second identical request starts beside it, and the
 *   first answer that is good wins (the other is cancelled, its usage reported as discarded);
 * - a first request that fails in a way worth retrying, or hangs to its own deadline, before then
 *   gets its second request at once (the rule before this one);
 * - never more than two requests ([MAX_REQUESTS]).
 *
 * Cost: a hedged turn pays for its audio twice (about 32 tokens a second of audio plus the short
 * instruction, on the speech-to-text model); only turns slower than [hedgeAfterMs] are hedged.
 */
internal object SttHedge {
    const val MAX_REQUESTS = 2

    /** The outcome: the good answer and which request gave it (0 first, 1 second), or the last failure. */
    sealed interface Result<out T> {
        data class Won<T>(val value: T, val request: Int) : Result<T>

        /** Nothing good: [last] is the last answer that came (null: every request hung). */
        data class Lost<T>(val last: T?) : Result<T>
    }

    /** Why the second request started, for the log. */
    enum class Why { SLOW, FAILED, HUNG }

    @Suppress("LongParameterList") // the race's knobs
    suspend fun <T : Any> race(
        hedgeAfterMs: Long,
        attemptTimeoutMs: Long,
        attempt: suspend () -> T,
        good: (T) -> Boolean,
        worthRetry: (T) -> Boolean,
        onSecond: (Why, T?) -> Unit = { _, _ -> },
    ): Result<T> = coroutineScope {
        val answers = Channel<Pair<Int, T?>>(MAX_REQUESTS)
        val jobs = mutableListOf<kotlinx.coroutines.Job>()
        fun start(i: Int) {
            jobs += launch {
                answers.send(i to withTimeoutOrNull(attemptTimeoutMs) { attempt() })
            }
        }
        start(0)
        var running = 1
        var last: T? = null
        // Only the first request is out until the hedge time.
        val early = withTimeoutOrNull(hedgeAfterMs) { answers.receive() }
        if (early == null) {
            onSecond(Why.SLOW, null)
            start(1)
            running++
        } else {
            running--
            val v = early.second
            if (v != null && good(v)) return@coroutineScope Result.Won(v, 0)
            last = v
            if (v == null || worthRetry(v)) {
                onSecond(if (v == null) Why.HUNG else Why.FAILED, v)
                start(1)
                running++
            }
        }
        while (running > 0) {
            val (i, v) = answers.receive()
            running--
            if (v != null && good(v)) {
                jobs.forEach { it.cancel() }
                return@coroutineScope Result.Won(v, i)
            }
            if (v != null) last = v
        }
        Result.Lost(last)
    }

    /**
     * When the second request starts: 1.5 s for up to 4 s of audio (every good request on 11-17 took
     * 0.96 to 2.35 s, the median about 1.1 s), 250 ms more per further second, at most 3 s.
     */
    fun hedgeAfterFor(audioMs: Long): Long {
        val extraSeconds = ((audioMs - BASE_AUDIO_MS).coerceAtLeast(0) + MS_PER_S - 1) / MS_PER_S
        return (HEDGE_AFTER_MS + extraSeconds * PER_SECOND_MS).coerceAtMost(HEDGE_CAP_MS)
    }

    const val HEDGE_AFTER_MS = 1_500L
    const val HEDGE_CAP_MS = 3_000L
    private const val BASE_AUDIO_MS = 4_000L
    private const val PER_SECOND_MS = 250L
    private const val MS_PER_S = 1_000L
}
