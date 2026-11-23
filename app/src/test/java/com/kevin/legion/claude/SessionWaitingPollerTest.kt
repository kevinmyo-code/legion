package com.kevin.legion.claude

import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [SessionWaitingPoller] (alfred-sessions, Kevin 2026-11-22): any session that starts waiting on
 * him is raised once per wait, rate-limited per session and overall, never when silenced or stale.
 */
class SessionWaitingPollerTest {
    private val backend = FakeClaudeBackend()
    private val watch = FakeWatch()
    private val said = mutableListOf<String>()
    private var delivery = ClaudeDelivery.DELIVERED
    private var clock: Instant = Instant.parse("2026-11-22T18:00:00Z")
    private val seen = object : SessionWaitSeen {
        val keys = mutableSetOf<String>()
        override fun seen() = keys.toSet()
        override fun mark(key: String) { keys += key }
        override fun keepOnly(keys: Set<String>) { this.keys.retainAll(keys) }
    }
    private val poller = SessionWaitingPoller(
        controller = { ClaudeSessionController(backend) },
        seen = seen,
        watch = watch,
        announce = { _, text -> said += text; delivery },
        now = { clock },
    )

    private fun waiting(id: String, name: String, since: Instant = clock.minusSeconds(30), state: String = "waiting") =
        ClaudeCodeSession("Alienware", "garden", state, null, clock, sessionId = id, name = name, waitingSince = since)

    private fun sessions(vararg s: ClaudeCodeSession) {
        backend.statusOut = ClaudeOutcome.Ok(ClaudeStatus(emptyList(), s.toList()))
    }

    private fun poll() = runBlocking { poller.pollNow() }

    @Test
    fun `a new wait is said once, by name, and a repeat report is the same wait`() {
        sessions(waiting("s-1", "planner"))
        assertEquals(1, poll())
        assertEquals(0, poll())
        assertEquals(listOf("Your Claude session planner on the Alienware is waiting on you."), said)
    }

    @Test
    fun `a session that works and waits again is a new wait, after the per-session gap`() {
        val first = clock.minusSeconds(30)
        sessions(waiting("s-1", "planner", since = first))
        poll()
        sessions(waiting("s-1", "planner", since = clock))
        assertEquals("inside the gap: dropped", 0, poll())
        clock = clock.plus(Duration.ofMinutes(11))
        sessions(waiting("s-1", "planner", since = clock.minusSeconds(5)))
        assertEquals(1, poll())
        assertEquals(2, said.size)
    }

    @Test
    fun `at most four alerts in thirty minutes across sessions`() {
        sessions(*(1..6).map { waiting("s-$it", "s$it") }.toTypedArray())
        assertEquals(SessionWaitingPoller.MAX_PER_WINDOW, poll())
        assertEquals(0, poll())
    }

    @Test
    fun `silenced, stale, not waiting or unnamed by id are never said`() {
        watch.quiet = true
        sessions(waiting("s-1", "planner"))
        assertEquals(0, poll())
        watch.quiet = false
        sessions(
            waiting("s-2", "old", since = clock.minus(Duration.ofHours(3))),
            waiting("s-3", "busy", state = "working"),
            waiting("", "no id"),
        )
        assertEquals(0, poll())
        assertEquals(emptyList<String>(), said)
    }

    @Test
    fun `not now leaves the wait for the next poll`() {
        delivery = ClaudeDelivery.RETRY
        sessions(waiting("s-1", "planner"))
        assertEquals(0, poll())
        delivery = ClaudeDelivery.DELIVERED
        assertEquals(1, poll())
    }

    @Test
    fun `no name falls back to the folder`() {
        sessions(waiting("s-1", ""))
        poll()
        assertEquals("Your Claude session garden on the Alienware is waiting on you.", said.single())
    }
}
