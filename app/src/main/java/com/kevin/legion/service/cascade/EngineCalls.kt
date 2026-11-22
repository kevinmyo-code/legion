package com.kevin.legion.service.cascade

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/** One relayed call's result, as posted to `tool_result` (relay contract section 4). */
internal data class CallOutcome(val outcome: String, val result: JSONObject?, val words: String, val ms: Long) {
    companion object {
        const val OK = "ok"
        const val FAILED = "failed"
        const val REFUSED = "refused"
        const val TIMED_OUT = "timed_out"
        const val NOT_RUN = "not_run"
    }
}

/**
 * The conversation's call ledger (relay contract section 6): every call id the engine asked for,
 * run at most once. A repeated request gets the stored result back, never a second run.
 *
 * A call is given its own `deadline_ms` (section 3). A call still running then is reported
 * `timed_out` and left to finish; a WRITE that then lands posts `ok` for the same id (section 7,
 * the one second result the engine accepts) through [lateOk].
 */
internal class EngineCalls(
    private val scope: CoroutineScope,
    private val runner: suspend (EngineCall) -> CallOutcome,
    private val lateOk: suspend (EngineCall, CallOutcome) -> Unit,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val stored = ConcurrentHashMap<String, CallOutcome>()
    private val running = ConcurrentHashMap<String, Deferred<CallOutcome>>()

    /** The stored result of [callId], if it ran (or was answered) before. */
    fun stored(callId: String): CallOutcome? = stored[callId]

    /** [callId] started and has not finished (its turn was cancelled meanwhile), else null. */
    fun inFlight(callId: String): Deferred<CallOutcome>? = running[callId]?.takeIf { it.isActive }

    /** Records an answer the phone gave without running anything (`not_run`, a refusal). */
    fun record(callId: String, outcome: CallOutcome) {
        stored.putIfAbsent(callId, outcome)
    }

    /**
     * [call]'s result: the stored one for a call id seen before, else it runs now, bounded by its
     * own deadline. Never runs the same call id twice, even when two requests race.
     */
    suspend fun result(call: EngineCall): CallOutcome = stored[call.callId] ?: runOnce(call)

    private suspend fun runOnce(call: EngineCall): CallOutcome {
        val started = now()
        // The job stores its own result: a turn cancelled while it runs still has it to post.
        val job = running.computeIfAbsent(call.callId) {
            scope.async { runner(call).also { stored.putIfAbsent(call.callId, it) } }
        }
        val done = withTimeoutOrNull(call.deadlineMs) { job.await() }
        return if (done != null) {
            running.remove(call.callId)
            stored.putIfAbsent(call.callId, done) ?: done
        } else {
            gaveUp(call, job, started)
        }
    }

    /** Past its deadline: `timed_out` now; a write that lands later posts `ok` (relay contract 7). */
    private fun gaveUp(call: EngineCall, job: Deferred<CallOutcome>, started: Long): CallOutcome {
        val gaveUp = CallOutcome(
            CallOutcome.TIMED_OUT,
            null,
            "${call.name} did not finish on the phone in time; it may still go through.",
            now() - started,
        )
        stored[call.callId] = gaveUp
        scope.launch {
            val late = job.await()
            running.remove(call.callId)
            if (call.writes && late.outcome == CallOutcome.OK) {
                stored[call.callId] = late
                lateOk(call, late)
            }
        }
        return gaveUp
    }
}
