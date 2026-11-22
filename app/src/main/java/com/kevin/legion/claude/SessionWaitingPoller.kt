package com.kevin.legion.claude

import android.content.Context
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** The waits already raised, as `sessionId@waitingSince` keys, so each wait is raised once. */
interface SessionWaitSeen {
    fun seen(): Set<String>
    fun mark(key: String)

    /** Forgets every key not in [keys]: a wait that ended never comes back. */
    fun keepOnly(keys: Set<String>)
}

/** SharedPreferences, install-scoped, never synced: session ids and times only. */
class SessionWaitSeenStore(context: Context) : SessionWaitSeen {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    override fun seen(): Set<String> = prefs.getStringSet(KEY, emptySet()).orEmpty().toSet()

    @Synchronized
    override fun mark(key: String) = put(seen() + key)

    @Synchronized
    override fun keepOnly(keys: Set<String>) {
        val kept = seen().intersect(keys)
        if (kept != seen()) put(kept)
    }

    private fun put(keys: Set<String>) {
        prefs.edit().putStringSet(KEY, keys).apply()
    }

    private companion object {
        const val PREFS = "claude_session"
        const val KEY = "session_waits_seen"
    }
}

/**
 * Any of Kevin's Claude Code sessions that starts waiting on him (Kevin 2026-11-22, ruling 5: "any
 * session", not only watched ones). The laptop's session hooks already push `waiting` to the
 * engine, which stamps when each wait began (`waiting_since`); this turns a new wait into one
 * phone alert, spoken when the proactive rules allow and posted when they do not, through the same
 * path as a task result ([ClaudeResultPolling]).
 *
 * - Rides the result watcher's loop ([ClaudeResultWatcher]'s `alsoEachTick`) at [EVERY]: one
 *   `status/` read a minute, and only while that loop runs (LEGION in front, or the assistant
 *   service up). With both off nothing polls and a wait is not raised.
 * - Once per wait: keyed by session and the start of its wait, so the Notification hook's repeats
 *   are one alert, and a session that works and waits again is a new one.
 * - Rate-limited: one alert per session per [PER_SESSION], at most [MAX_PER_WINDOW] in [WINDOW];
 *   a wait past either is marked and dropped (it is still on the screen and in `check`).
 * - A wait older than [MAX_AGE] when first seen is stale: marked, never raised.
 * - Silenced (the "Claude tasks" switch, `quiet`, or the master switch): marked, never raised.
 *
 * Compulsion test (CLAUDE.md section 7): (a) anchored to a session the laptop reported waiting
 * on him; (b) actionable (answer it); (c) never about his absence; (d) `quiet` stops it.
 */
class SessionWaitingPoller(
    private val controller: () -> ClaudeSessionController?,
    private val seen: SessionWaitSeen,
    private val watch: ClaudeWatch,
    private val announce: suspend (key: String, text: String) -> ClaudeDelivery,
    private val now: () -> Instant = Instant::now,
) {
    @Volatile private var lastPoll: Instant? = null
    private val lastBySession = mutableMapOf<String, Instant>()
    private val recent = ArrayDeque<Instant>()
    private val lock = Mutex()

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
        val sessions = (c.status() as? ClaudeOutcome.Ok)?.value?.sessions ?: return 0
        val waits = sessions.filter { it.waitingOnYou && it.sessionId.isNotBlank() && it.waitingSince != null }
        val keys = waits.associateBy(::key)
        seen.keepOnly(keys.keys)
        val fresh = keys.filterKeys { it !in seen.seen() }
        if (fresh.isEmpty()) return 0
        var raised = 0
        for ((k, s) in fresh) {
            if (stale(s) || watch.silenced() || limited(s)) {
                seen.mark(k)
                continue
            }
            if (announce(k, words(s)) == ClaudeDelivery.DELIVERED) {
                seen.mark(k)
                lastBySession[s.sessionId] = now()
                recent.addLast(now())
                raised++
            }
        }
        return raised
    }

    private fun limited(s: ClaudeCodeSession): Boolean {
        val at = now()
        while (recent.isNotEmpty() && Duration.between(recent.first(), at) > WINDOW) recent.removeFirst()
        val last = lastBySession[s.sessionId]
        return recent.size >= MAX_PER_WINDOW || (last != null && Duration.between(last, at) < PER_SESSION)
    }

    private fun stale(s: ClaudeCodeSession): Boolean =
        s.waitingSince?.let { Duration.between(it, now()) > MAX_AGE } ?: true

    companion object {
        val EVERY: Duration = Duration.ofMinutes(1)
        val PER_SESSION: Duration = Duration.ofMinutes(10)
        val WINDOW: Duration = Duration.ofMinutes(30)
        const val MAX_PER_WINDOW = 4
        val MAX_AGE: Duration = Duration.ofHours(2)

        fun key(s: ClaudeCodeSession): String = "${s.sessionId}@${s.waitingSince?.epochSecond}"

        /** The one line, said or posted. Facts only: which session, where, that it waits on him. */
        fun words(s: ClaudeCodeSession): String =
            "Your Claude session ${s.spokenName} on the ${s.machine} is waiting on you."
    }
}
