package com.kevin.legion.claude

import com.kevin.legion.board.BoardOutcome
import com.kevin.legion.board.BoardQueue
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The control room's queue on the same loop as [SessionWaitingPoller] (control room C4), raising
 * what that poller and the result watcher do not: a new board question he may answer, and a new
 * action record waiting on his tap. A waiting session stays [SessionWaitingPoller]'s and a Claude
 * task's question stays the result watcher's, so nothing is raised twice.
 *
 * - Keys are the engine's (`GET /api/board/queue` `items`): a question by its hash, a record by its
 *   id. Each is raised once ([SessionWaitSeen], its own store); a key that left the queue is forgotten.
 * - At most [MAX_PER_WINDOW] in [WINDOW]; past that a key is marked and dropped (it is still in the
 *   control room and on the home line).
 * - Silenced with the "Claude tasks" switch, `quiet`, or the master switch: marked, never raised.
 * - The words are FACTS ONLY, made here from the kind: no card, record or agent text is ever in a push.
 *
 * Compulsion test (CLAUDE.md section 7): (a) anchored to a question or record the laptop put on his
 * board; (b) actionable (answer or allow it); (c) never about his absence; (d) `quiet` stops it.
 */
class QueuePoller(
    private val queue: suspend () -> BoardOutcome<BoardQueue>,
    private val seen: SessionWaitSeen,
    private val watch: ClaudeWatch,
    private val announce: suspend (key: String, text: String) -> ClaudeDelivery,
    private val now: () -> Instant = Instant::now,
) {
    @Volatile private var lastPoll: Instant? = null
    private val recent = ArrayDeque<Instant>()
    private val lock = Mutex()

    suspend fun maybePoll(): Int = lock.withLock {
        val at = now()
        val last = lastPoll
        if (last != null && Duration.between(last, at) < EVERY) return@withLock 0
        lastPoll = at
        pollNow()
    }

    internal suspend fun pollNow(): Int {
        val out = queue() as? BoardOutcome.Ok ?: return 0
        val raisable = out.value.items.mapNotNull { (_, key) -> words(key)?.let { key to it } }.toMap()
        seen.keepOnly(raisable.keys)
        var raised = 0
        for ((key, text) in raisable.toSortedMap().filterKeys { it !in seen.seen() }) {
            if (watch.silenced() || limited()) {
                seen.mark(key)
                continue
            }
            if (announce(key, text) == ClaudeDelivery.DELIVERED) {
                seen.mark(key)
                recent.addLast(now())
                raised++
            }
        }
        return raised
    }

    private fun limited(): Boolean {
        val at = now()
        while (recent.isNotEmpty() && Duration.between(recent.first(), at) > WINDOW) recent.removeFirst()
        return recent.size >= MAX_PER_WINDOW
    }

    companion object {
        val EVERY: Duration = Duration.ofMinutes(1)
        val WINDOW: Duration = Duration.ofMinutes(30)
        const val MAX_PER_WINDOW = 4
        const val DECISION = "Your factory board has a new question for you. It is in the control room."
        const val ACTION = "The laptop asks you to allow something. It is in the control room's approvals."

        /** Facts only, by the key's kind; null for a kind another poller raises (or none). */
        fun words(key: String): String? = when {
            key.startsWith("decision:") -> DECISION
            key.startsWith("action:") -> ACTION
            else -> null
        }
    }
}
