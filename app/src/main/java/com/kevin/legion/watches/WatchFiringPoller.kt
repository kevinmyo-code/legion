package com.kevin.legion.watches

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** What became of one announcement. [RETRY] leaves the firings unacked for the next poll. */
enum class WatchDelivery(val outcome: String?) {
    SPOKEN(OUTCOME_SPOKEN),
    NOTIFIED(OUTCOME_NOTIFIED),
    SILENCED(OUTCOME_SILENCED),
    RETRY(null),
}

/**
 * Firings this phone already delivered whose ack has not yet reached the engine, so a failed ack
 * never makes Alfred say the same thing twice. Holds firing ids (engine row ids) and nothing from
 * the facts.
 */
interface SaidFirings {
    fun outcomeOf(id: String): String?
    fun remember(ids: List<String>, outcome: String)
    fun forget(ids: List<String>)
}

/**
 * Says what the engine's watches found, once each (ADR 0083). **No loop of its own**: [maybePoll]
 * is called on every tick of the existing engine poll ([com.kevin.legion.claude.ClaudeResultWatcher]
 * `run`, the two loops in `ClaudeResultPolling`), and asks the engine at most every [intervalMs].
 * So it polls exactly when that loop runs: while a LEGION screen is in front, or while
 * `AriaForegroundService` runs (the assistant switched on). With the assistant off and the app
 * closed nothing polls, and firings wait on the engine until LEGION is next opened (or expire).
 *
 * One poll: read the pending firings, skip any already said here (re-acking them), drop expired
 * ones, hand the oldest [WatchWording.SPOKEN_FACTS] to [announce] as ONE line with a count of the
 * rest, then ack every one of them with how it was delivered. [WatchDelivery.RETRY] ("not now": a
 * conversation or call in progress) acks nothing, so the next poll tries again.
 *
 * [phoneChecks] are the phone-evaluated kinds' own checks (mail_reply), run on the same tick at
 * their own sub-cadence; they never touch the engine's firings.
 */
class WatchFiringPoller(
    private val controller: () -> WatchesController?,
    private val announce: suspend (firings: List<WatchFiring>, more: Int) -> WatchDelivery,
    private val said: SaidFirings,
    private val clockMs: () -> Long = System::currentTimeMillis,
    private val intervalMs: Long = POLL_INTERVAL_MS,
    private val phoneChecks: List<suspend () -> Unit> = emptyList(),
) {
    private val lock = Mutex()
    private var lastPollMs: Long? = null

    /** Polls when [intervalMs] has passed since the last poll. Returns how many facts were said. */
    suspend fun maybePoll(): Int = lock.withLock {
        val now = clockMs()
        val last = lastPollMs
        if (last != null && now - last < intervalMs) return@withLock 0
        lastPollMs = now
        for (check in phoneChecks) runCheck(check)
        poll(now)
    }

    @Suppress("ReturnCount") // guard clauses: no engine, unreadable, nothing new, not now
    internal suspend fun poll(now: Long = clockMs()): Int {
        val c = controller() ?: return 0
        val pending = (c.pending() as? WatchOutcome.Ok)?.value ?: return 0
        val (already, fresh) = pending.partition { said.outcomeOf(it.id) != null }
        for ((outcome, rows) in already.groupBy { said.outcomeOf(it.id).orEmpty() }) {
            ack(c, rows.map { it.id }, outcome)
        }
        val live = fresh.filter { it.expiresAtMs > now }
        if (live.isEmpty()) return 0
        val head = live.take(WatchWording.SPOKEN_FACTS)
        val delivery = announce(head, live.size - head.size)
        val outcome = delivery.outcome ?: return 0
        val ids = live.map { it.id }
        said.remember(ids, outcome)
        ack(c, ids, outcome)
        return if (delivery == WatchDelivery.SILENCED) 0 else head.size
    }

    /** A phone check that throws never stops the engine's firings from being said. */
    @Suppress("TooGenericExceptionCaught") // any failure is "not checked this time"; cancellation rethrown
    private suspend fun runCheck(check: suspend () -> Unit) {
        try {
            check()
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            lastCheckFailure = e
        }
    }

    /** The last phone check's failure, for a debugger; never spoken, never stored. */
    @Volatile var lastCheckFailure: Exception? = null
        private set

    private suspend fun ack(c: WatchesController, ids: List<String>, outcome: String) {
        if (c.ack(ids, outcome) is WatchOutcome.Ok) said.forget(ids)
    }

    companion object {
        /** How often the engine is asked. Firings are made every 15 minutes (evaluate_watches). */
        const val POLL_INTERVAL_MS = 3L * 60 * 1000
    }
}
