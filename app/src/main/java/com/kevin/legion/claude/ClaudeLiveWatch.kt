package com.kevin.legion.claude

import android.content.Context
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Where the ongoing "Claude: N running" notification goes. [ClaudeWatchNotifier] in production. */
interface ClaudeLivePoster {
    fun show(title: String, text: String, at: Instant)
    fun clear()

    /** True while the notification is actually in the shade (the OS's answer, not a remembered flag). */
    fun showing(): Boolean
}

/**
 * The latest [ClaudeWatchSummary] for the process, and the one ongoing notification that follows it.
 * Fed by every read of the engine's rows that already happens: [ClaudeResultWatcher]'s poll, the
 * Claude session screen's refresh and the Home line's poll ([seen]), so nothing here starts a loop.
 *
 * **Polling stays bounded.** [ClaudeResultWatcher] asks the engine only while a task this phone sent
 * is out, or while [active] (the notification is up). The notification comes down the moment a read
 * shows nothing running, and on any read that fails ([unreadable]): an ongoing "3 running" the engine
 * could not confirm is a claim nobody observed (CLAUDE.md section 7). So the loop stops by itself.
 *
 * **Compulsion test (CLAUDE.md section 7):** (a) every count is the engine's own row; (b) the tap
 * opens the screen where each task can be answered or followed; (c) it never mentions absence or
 * engagement; (d) the channel is its own, so Android's per-channel switch silences it in one step.
 */
class ClaudeLiveWatch(
    private val poster: ClaudeLivePoster,
    private val now: () -> Instant = Instant::now,
) {
    private val _summary = MutableStateFlow<ClaudeWatchSummary?>(null)

    /** Null until a read succeeds, and again after one fails: Home then shows nothing. */
    val summary: StateFlow<ClaudeWatchSummary?> = _summary.asStateFlow()

    @Volatile private var readAt: Instant? = null
    @Volatile private var posted: Pair<String, String>? = null
    @Volatile private var postedAt: Instant? = null

    /** True while the notification is up, which keeps [ClaudeResultWatcher] polling. */
    fun active(): Boolean = poster.showing()

    @Synchronized
    fun seen(overview: ClaudeOverview) {
        val at = now()
        val summary = ClaudeWatchSummary.from(overview, at)
        _summary.value = summary
        readAt = at
        val title = summary.notificationTitle
        val text = summary.notificationText
        if (title == null || text == null) {
            clear()
            return
        }
        val content = title to text
        val last = postedAt
        // Re-posted on a change, and every few minutes so the notification's own timeout
        // (ClaudeWatchNotifier.STALE_AFTER) never fires while reads are still arriving.
        val due = last == null || Duration.between(last, at) >= REPOST_EVERY
        if (content != posted || due || !poster.showing()) {
            poster.show(title, text, at)
            posted = content
            postedAt = at
        }
    }

    @Synchronized
    fun unreadable() {
        _summary.value = null
        readAt = null
        clear()
    }

    /** Reads the engine unless a read newer than [maxAge] already happened (the Home poll's door). */
    suspend fun refreshIfStale(controller: ClaudeSessionController, maxAge: Duration) {
        val last = readAt
        if (last != null && _summary.value != null && Duration.between(last, now()) < maxAge) return
        when (val out = controller.overview()) {
            is ClaudeOutcome.Ok -> seen(out.value)
            else -> unreadable()
        }
    }

    private fun clear() {
        if (posted != null || poster.showing()) poster.clear()
        posted = null
        postedAt = null
    }

    companion object {
        val REPOST_EVERY: Duration = Duration.ofMinutes(5)

        @Volatile private var instance: ClaudeLiveWatch? = null

        /** The process's one instance, posting through [ClaudeWatchNotifier]. */
        fun get(context: Context): ClaudeLiveWatch {
            instance?.let { return it }
            return synchronized(this) {
                instance ?: ClaudeLiveWatch(ClaudeWatchNotifier(context.applicationContext)).also { instance = it }
            }
        }
    }
}
