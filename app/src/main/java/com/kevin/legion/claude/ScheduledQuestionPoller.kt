package com.kevin.legion.claude

import android.content.Context
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** The scheduled questions already raised (or skipped), by task id, so each is raised once. */
interface ScheduledQuestionSeen {
    fun seen(): Set<Int>
    fun mark(id: Int)

    /** Forgets every id not in [ids]: an answered or pruned question never comes back. */
    fun keepOnly(ids: Set<Int>)
}

/** SharedPreferences, install-scoped, never synced: task ids only. */
class ScheduledQuestionSeenStore(context: Context) : ScheduledQuestionSeen {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    override fun seen(): Set<Int> =
        prefs.getStringSet(KEY, emptySet()).orEmpty().mapNotNull { it.toIntOrNull() }.toSet()

    @Synchronized
    override fun mark(id: Int) = put(seen() + id)

    @Synchronized
    override fun keepOnly(ids: Set<Int>) {
        val kept = seen().intersect(ids)
        if (kept != seen()) put(kept)
    }

    private fun put(ids: Set<Int>) {
        prefs.edit().putStringSet(KEY, ids.map { it.toString() }.toSet()).apply()
    }

    private companion object {
        const val PREFS = "claude_session"
        const val KEY = "scheduled_questions_seen"
    }
}

/**
 * A scheduled workflow run that stopped for Kevin's yes (ruling R3, worker-workflows ticket 06).
 * [ClaudeResultWatcher] only watches tasks this phone sent, and the engine starts a scheduled run
 * itself, so nothing else would ever tell him. This asks the engine for `origin=schedule` tasks in
 * `needs_confirmation` and raises each ONCE, in the Claude tasks category, through the same
 * announcement path as a result ([ClaudeResultPolling]: the open conversation, else the proactive
 * bus, which speaks or posts). His answer is the ordinary `confirms` follow-up (`answer`, or Yes on
 * the screen). A scheduled `done` is never raised: it waits for `check` or the screen.
 *
 * - Rides the result watcher's loop ([ClaudeResultWatcher]'s `alsoEachTick`) at its own slower
 *   cadence ([EVERY]), as the standing watches do; no loop of its own.
 * - A question that already has a follow-up is answered (a yes is a new task; the parent stays
 *   `needs_confirmation` on the engine until the 30-day prune), so it is skipped, not replayed.
 * - Older than [MAX_AGE] is stale: marked, never raised, so a fresh install does not read out a
 *   month of old questions.
 * - Silenced (the "Claude tasks" switch, `quiet`, or the master switch): marked, never raised.
 *
 * Compulsion test (CLAUDE.md section 7): (a) anchored to a workflow Kevin installed and a question
 * its run asked; (b) actionable (yes, or no); (c) never about his absence; (d) `quiet` stops it.
 */
class ScheduledQuestionPoller(
    private val controller: () -> ClaudeSessionController?,
    private val seen: ScheduledQuestionSeen,
    private val watch: ClaudeWatch,
    private val announce: suspend (taskId: Int, text: String, hint: String) -> ClaudeDelivery,
    private val now: () -> Instant = Instant::now,
) {
    @Volatile private var lastPoll: Instant? = null

    /** The foreground loop and the service loop can both tick: one poll at a time, as watches do. */
    private val lock = Mutex()

    /** One poll when [EVERY] has passed since the last; returns how many questions were raised. */
    suspend fun maybePoll(): Int = lock.withLock {
        val at = now()
        val last = lastPoll
        if (last != null && Duration.between(last, at) < EVERY) return@withLock 0
        lastPoll = at
        pollNow()
    }

    @Suppress("ReturnCount") // no engine, unreadable, or nothing new
    internal suspend fun pollNow(): Int {
        val c = controller() ?: return 0
        // Only rows the engine marks scheduled: an engine from before workflows ignores
        // `origin=schedule` and lists every question, his own sends included (integrate 2026-11-10).
        val questions = (c.scheduledQuestions() as? ClaudeOutcome.Ok)?.value?.filter { it.isScheduled } ?: return 0
        seen.keepOnly(questions.map { it.id }.toSet())
        val fresh = questions.filter { it.id !in seen.seen() }
        if (fresh.isEmpty()) return 0
        val answered = answeredAmong(c, fresh.map { it.id }.toSet()) ?: return 0
        var raised = 0
        for (q in fresh) {
            if (q.id in answered || stale(q) || watch.silenced()) {
                seen.mark(q.id)
                continue
            }
            val text = ClaudeWorkflowWording.scheduledQuestion(q)
            if (announce(q.id, text, ClaudeResultWatcher.hint(q, summariesOnly = false)) == ClaudeDelivery.DELIVERED) {
                seen.mark(q.id)
                raised++
            }
        }
        return raised
    }

    /** The ids in [ids] that already have a follow-up among the newest tasks; null when unreadable. */
    private suspend fun answeredAmong(c: ClaudeSessionController, ids: Set<Int>): Set<Int>? {
        val tasks = (c.overview(RECENT_FOR_ANSWERS) as? ClaudeOutcome.Ok)?.value?.tasks ?: return null
        return tasks.mapNotNull { it.parent }.filter { it in ids }.toSet()
    }

    private fun stale(task: ClaudeTask): Boolean {
        val finished = task.finishedAt ?: task.createdAt ?: return false
        return Duration.between(finished, now()) > MAX_AGE
    }

    companion object {
        /** How often the engine is asked (the questions wait on the screen in between). */
        val EVERY: Duration = Duration.ofMinutes(5)

        /** A question older than this is not raised: it is on the screen, and `check` reads it. */
        val MAX_AGE: Duration = Duration.ofDays(3)

        private const val RECENT_FOR_ANSWERS = 50
    }
}
