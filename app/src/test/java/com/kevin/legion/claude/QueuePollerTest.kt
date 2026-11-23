package com.kevin.legion.claude

import com.kevin.legion.board.BoardOutcome
import com.kevin.legion.board.BoardQueue
import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [QueuePoller] (control room C4): a new board question or action record is raised once per key,
 * in fixed facts-only words; sessions and Claude task questions are left to their own pollers.
 */
class QueuePollerTest {
    private val watch = FakeWatch()
    private val said = mutableListOf<Pair<String, String>>()
    private var clock: Instant = Instant.parse("2031-04-02T10:00:00Z")
    private var items = listOf<Pair<String, String>>()
    private val seen = object : SessionWaitSeen {
        val keys = mutableSetOf<String>()
        override fun seen() = keys.toSet()
        override fun mark(key: String) { keys += key }
        override fun keepOnly(keys: Set<String>) { this.keys.retainAll(keys) }
    }
    private val poller = QueuePoller(
        queue = { BoardOutcome.Ok(BoardQueue(0, 0, 0, "", emptyList(), items)) },
        seen = seen,
        watch = watch,
        announce = { key, text -> said += key to text; ClaudeDelivery.DELIVERED },
        now = { clock },
    )

    private fun poll() = runBlocking { poller.pollNow() }

    @Test
    fun `the poller keys raise each question and record once, in facts-only words`() {
        items = listOf(
            "sessions" to "session:s-1@100",
            "approvals" to "task:7",
            "approvals" to "action:rec-1",
            "decisions" to "decision:teapot:aaaaaaaaaaaa",
        )
        assertEquals(2, poll())
        assertEquals(
            listOf(
                "action:rec-1" to QueuePoller.ACTION,
                "decision:teapot:aaaaaaaaaaaa" to QueuePoller.DECISION,
            ),
            said,
        )
        assertEquals(0, poll())
    }

    @Test
    fun `a changed question is a new key and a key that left is forgotten`() {
        items = listOf("decisions" to "decision:teapot:aaaaaaaaaaaa")
        poll()
        items = listOf("decisions" to "decision:teapot:bbbbbbbbbbbb")
        assertEquals(1, poll())
        assertEquals(setOf("decision:teapot:bbbbbbbbbbbb"), seen.keys)
    }

    @Test
    fun `silenced marks and says nothing`() {
        watch.quiet = true
        items = listOf("approvals" to "action:rec-1")
        assertEquals(0, poll())
        assertTrue(said.isEmpty())
        assertEquals(setOf("action:rec-1"), seen.keys)
    }

    @Test
    fun `at most four in half an hour`() {
        items = (1..6).map { "approvals" to "action:rec-$it" }
        assertEquals(QueuePoller.MAX_PER_WINDOW, poll())
        assertEquals(6, seen.keys.size)
    }
}
