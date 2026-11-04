package com.kevin.legion.claude

import com.kevin.legion.claude.FakeClaudeBackend.Companion.NOW
import com.kevin.legion.claude.FakeClaudeBackend.Companion.machine
import com.kevin.legion.claude.FakeClaudeBackend.Companion.task
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [ClaudeLiveWatch]: the ongoing notification goes up while something runs, is re-posted only on a
 * change (or to keep its timeout from firing), comes down when nothing runs or a read fails, and the
 * Home poll reuses a fresh read. [ClaudeResultWatcher] keeps polling only while it is up.
 */
class ClaudeLiveWatchTest {

    private class FakePoster : ClaudeLivePoster {
        val shown = mutableListOf<Pair<String, String>>()
        var up = false
        var clears = 0

        override fun show(title: String, text: String, at: Instant) {
            shown += title to text
            up = true
        }

        override fun clear() {
            clears++
            up = false
        }

        override fun showing(): Boolean = up
    }

    private val poster = FakePoster()
    private var clock = NOW
    private val live = ClaudeLiveWatch(poster, now = { clock })

    private fun overview(vararg tasks: ClaudeTask) =
        ClaudeOverview(ClaudeStatus(listOf(machine("Alienware", state = "busy")), emptyList()), tasks.toList())

    @Test
    fun `running posts once, the same counts do not re-post, a new step does`() {
        live.seen(overview(task(41, ClaudeTask.RUNNING, progress = "reading Downloads")))
        live.seen(overview(task(41, ClaudeTask.RUNNING, progress = "reading Downloads")))
        assertEquals(listOf("Claude: 1 running" to "reading Downloads"), poster.shown)
        live.seen(overview(task(41, ClaudeTask.RUNNING, progress = "opening wiring.pdf")))
        assertEquals("Claude: 1 running" to "opening wiring.pdf", poster.shown.last())
        assertEquals(2, poster.shown.size)
        assertTrue(live.active())
        assertEquals("Claude: 1 running", live.summary.value?.homeLine)
    }

    @Test
    fun `an unchanged notification is re-posted after a few minutes so its timeout never fires`() {
        live.seen(overview(task(41, ClaudeTask.RUNNING, progress = "x")))
        clock = NOW.plus(ClaudeLiveWatch.REPOST_EVERY)
        live.seen(overview(task(41, ClaudeTask.RUNNING, progress = "x")))
        assertEquals(2, poster.shown.size)
    }

    @Test
    fun `nothing running takes it down, and a failed read does too`() {
        live.seen(overview(task(41, ClaudeTask.RUNNING)))
        live.seen(overview(task(41, ClaudeTask.DONE)))
        assertFalse(live.active())
        assertNull(live.summary.value?.homeLine)

        live.seen(overview(task(42, ClaudeTask.RUNNING)))
        live.unreadable()
        assertFalse(live.active())
        assertNull(live.summary.value)
    }

    @Test
    fun `a question alone is a Home line with no notification`() {
        live.seen(overview(task(41, ClaudeTask.NEEDS_CONFIRMATION)))
        assertTrue(poster.shown.isEmpty())
        assertEquals("Claude: 1 needs you", live.summary.value?.homeLine)
    }

    @Test
    fun `the Home poll reuses a fresh read and reads again once it is stale`() = runBlocking {
        val backend = FakeClaudeBackend()
        var reads = 0
        val counting = object : ClaudeSessionBackend by backend {
            override suspend fun status(): ClaudeOutcome<ClaudeStatus> {
                reads++
                return backend.status()
            }
        }
        backend.tasksOut = ClaudeOutcome.Ok(listOf(task(41, ClaudeTask.RUNNING)))
        val controller = ClaudeSessionController(counting)
        live.refreshIfStale(controller, Duration.ofSeconds(25))
        clock = NOW.plusSeconds(10)
        live.refreshIfStale(controller, Duration.ofSeconds(25))
        assertEquals(1, reads)
        clock = NOW.plusSeconds(30)
        backend.statusOut = ClaudeOutcome.Unreachable("offline")
        live.refreshIfStale(controller, Duration.ofSeconds(25))
        assertEquals(2, reads)
        assertNull(live.summary.value)
        assertFalse(live.active())
    }

    @Test
    fun `the result watcher reads while the notification is up, and feeds every read to it`() = runBlocking {
        val backend = FakeClaudeBackend()
        backend.tasksOut = ClaudeOutcome.Ok(listOf(task(41, ClaudeTask.RUNNING, progress = "reading Downloads")))
        val watch = FakeWatch()
        val watcher = ClaudeResultWatcher(
            controller = { ClaudeSessionController(backend) },
            watch = watch,
            announce = { _, _, _ -> ClaudeDelivery.DELIVERED },
            saveFile = { _, _ -> true },
            liveActive = live::active,
            onOverview = { o -> if (o != null) live.seen(o) else live.unreadable() },
        )
        // Nothing watched and nothing up: no read at all.
        backend.statusOut = ClaudeOutcome.Unreachable("would fail if asked")
        watcher.pollOnce()
        assertTrue(poster.shown.isEmpty())
        assertNull(live.summary.value)

        // A task this phone sent: the read brings the notification up.
        backend.statusOut = ClaudeOutcome.Ok(ClaudeStatus(listOf(machine("Alienware")), emptyList()))
        watch.tasks += 41
        watcher.pollOnce()
        assertTrue(live.active())

        // Forgotten by the phone (say it was announced), still running: the notification keeps it read.
        watch.tasks.clear()
        backend.tasksOut = ClaudeOutcome.Ok(listOf(task(41, ClaudeTask.DONE)))
        watcher.pollOnce()
        assertFalse(live.active())
        assertEquals(1, poster.clears)
    }
}
