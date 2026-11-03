package com.kevin.legion.dumb

import android.content.SharedPreferences

/**
 * "Block feed apps always" (ADR 0066): the switch that turns the feed block on outside Matrix mode.
 * Its own key in the Matrix mode preferences file, not part of [DumbModeState], and OFF by default
 * (clone-and-run: a stranger's build blocks nothing until they choose it). Read live on every window
 * change, so flipping it reaches the bounce at once.
 */
interface FeedBlockSwitch {
    fun get(): Boolean
    fun set(on: Boolean)
}

/** [FeedBlockSwitch] over the Matrix mode preferences file. */
class PrefsFeedBlockSwitch(private val prefs: SharedPreferences) : FeedBlockSwitch {
    override fun get(): Boolean = prefs.getBoolean(KEY, false)

    override fun set(on: Boolean) {
        prefs.edit().putBoolean(KEY, on).commit()
    }

    private companion object {
        const val KEY = "feed_block_always"
    }
}

/**
 * One app's pass, both times WALL-CLOCK epoch millis (Kevin's rule: nothing in memory that a process
 * kill or a screen lock can reset). [grantedUntilMs] is null while the 60 second wait or the sentence
 * is still owed.
 */
data class FeedPass(val requestedAtMs: Long, val grantedUntilMs: Long? = null)

/** Where passes live, one per package. Read live: the bounce asks on every window change. */
interface FeedPassStore {
    fun get(packageName: String): FeedPass?

    /** Null clears the pass. */
    fun set(packageName: String, pass: FeedPass?)

    /** Every package with a stored pass, for re-arming the expiry check after a restart. */
    fun all(): Map<String, FeedPass>
}

/** [FeedPassStore] over the Matrix mode preferences file, with synchronous `commit()` like the leave timer. */
class PrefsFeedPassStore(private val prefs: SharedPreferences) : FeedPassStore {
    override fun get(packageName: String): FeedPass? {
        val requested = prefs.getLong(REQUESTED + packageName, NONE).takeIf { it != NONE } ?: return null
        val until = prefs.getLong(UNTIL + packageName, NONE).takeIf { it != NONE }
        return FeedPass(requested, until)
    }

    override fun set(packageName: String, pass: FeedPass?) {
        prefs.edit().apply {
            if (pass == null) {
                remove(REQUESTED + packageName)
                remove(UNTIL + packageName)
            } else {
                putLong(REQUESTED + packageName, pass.requestedAtMs)
                val until = pass.grantedUntilMs
                if (until == null) remove(UNTIL + packageName) else putLong(UNTIL + packageName, until)
            }
        }.commit()
    }

    override fun all(): Map<String, FeedPass> =
        prefs.all.keys.filter { it.startsWith(REQUESTED) }
            .map { it.removePrefix(REQUESTED) }
            .mapNotNull { pkg -> get(pkg)?.let { pkg to it } }
            .toMap()

    private companion object {
        const val REQUESTED = "feed_pass.requested."
        const val UNTIL = "feed_pass.until."
        const val NONE = -1L
    }
}

/** The pass rules (ADR 0066), pure so the screen and the tests read the same ones. */
object FeedPassRule {
    /** The same wait as leaving Matrix mode (ADR 0062). */
    const val WAIT_MS = DumbLeaveRule.WAIT_MS
    const val PASS_MS = 15L * 60_000L

    /**
     * An unfinished request lapses this long after the tap, so a request started and walked away
     * from does not leave a pass one sentence away hours later.
     */
    const val REQUEST_LAPSE_MS = 10L * 60_000L

    private const val MS_PER_MINUTE = 60_000L

    fun sentence(appLabel: String): String = "I am choosing to open $appLabel for 15 minutes"

    /** Case-insensitive and trimmed, like the Matrix leave sentence. Inner spacing must match. */
    fun sentenceMatches(typed: String, appLabel: String): Boolean =
        typed.trim().equals(sentence(appLabel), ignoreCase = true)

    /** Wait left, clamped to [0, WAIT_MS]: a clock set BACKWARDS cannot stretch it ([DumbLeaveRule.remainingMs]). */
    fun waitRemainingMs(requestedAtMs: Long, nowMs: Long): Long =
        (WAIT_MS - (nowMs - requestedAtMs)).coerceIn(0L, WAIT_MS)

    /** Pass time left, clamped to [0, PASS_MS] so a clock set backwards cannot stretch a pass past 15 minutes. */
    fun passRemainingMs(grantedUntilMs: Long, nowMs: Long): Long =
        (grantedUntilMs - nowMs).coerceIn(0L, PASS_MS)

    /** A request with no grant that is older than [REQUEST_LAPSE_MS] (or from a future set backwards) is gone. */
    fun requestLapsed(requestedAtMs: Long, nowMs: Long): Boolean =
        nowMs - requestedAtMs !in 0L..REQUEST_LAPSE_MS

    /** "15 min pass, 12 min left". Minutes round UP, so the last minute reads 1 and never 0. */
    fun words(leftMs: Long): String = "15 min pass, ${(leftMs + MS_PER_MINUTE - 1) / MS_PER_MINUTE} min left"

    /**
     * Whether the expiry check should bounce [passedPackage]. Only when it is still the last
     * foreground window the service saw and its pass has run out; the check never reads the screen.
     */
    fun bounceAtExpiry(lastForeground: String?, passedPackage: String, grantedUntilMs: Long, nowMs: Long): Boolean =
        lastForeground == passedPackage && passRemainingMs(grantedUntilMs, nowMs) == 0L
}

/** What a pass request or grant did, worded for the screen. */
sealed interface PassResult {
    /** The wait has started (or was already running; the original instant is kept). */
    data class Waiting(val remainingMs: Long) : PassResult
    data class Granted(val untilMs: Long) : PassResult
    /** A pass is already running for this app. */
    data class AlreadyOpen(val untilMs: Long) : PassResult
    /** Refused: Matrix leave is the only way out of Matrix mode (ADR 0066). */
    data object MatrixOn : PassResult
    data object NotRequested : PassResult
    data class TooEarly(val remainingMs: Long) : PassResult
    data object WrongSentence : PassResult
}

/**
 * The 15 minute pass (ADR 0066): a 60 second wall-clock wait and a typed sentence unlock ONE feed app
 * for 15 minutes, then it is blocked again. Refused while Matrix mode is on. The clock and store are
 * passed in so every case is a unit test; `DumbModeGraph.feedPass` is the one instance.
 */
class FeedPassController(
    private val store: FeedPassStore,
    private val matrixOn: () -> Boolean,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val lock = Any()

    /**
     * Starts the wait for [packageName]. Idempotent: asking again while waiting keeps the original
     * instant, so looking at the screen again never restarts it. One pass at a time per app.
     */
    fun request(packageName: String): PassResult = synchronized(lock) {
        if (matrixOn()) return PassResult.MatrixOn
        val now = clock()
        val live = liveEntry(packageName, now)
        val until = live?.grantedUntilMs
        when {
            until != null -> PassResult.AlreadyOpen(until)
            live != null -> PassResult.Waiting(FeedPassRule.waitRemainingMs(live.requestedAtMs, now))
            else -> {
                store.set(packageName, FeedPass(now))
                PassResult.Waiting(FeedPassRule.WAIT_MS)
            }
        }
    }

    /** Wait left on a pending request, or null when none is pending. */
    fun waitRemainingMs(packageName: String): Long? = synchronized(lock) {
        val now = clock()
        val live = liveEntry(packageName, now)
        if (live == null || live.grantedUntilMs != null) null else FeedPassRule.waitRemainingMs(live.requestedAtMs, now)
    }

    /** Grants the pass when the wait is over AND [typed] matches [appLabel]'s sentence; otherwise changes nothing. */
    fun grant(packageName: String, appLabel: String, typed: String): PassResult = synchronized(lock) {
        if (matrixOn()) return PassResult.MatrixOn
        val now = clock()
        val live = liveEntry(packageName, now) ?: return PassResult.NotRequested
        val until = live.grantedUntilMs
        val remaining = FeedPassRule.waitRemainingMs(live.requestedAtMs, now)
        when {
            until != null -> PassResult.AlreadyOpen(until)
            remaining > 0L -> PassResult.TooEarly(remaining)
            !FeedPassRule.sentenceMatches(typed, appLabel) -> PassResult.WrongSentence
            else -> {
                val granted = now + FeedPassRule.PASS_MS
                store.set(packageName, live.copy(grantedUntilMs = granted))
                PassResult.Granted(granted)
            }
        }
    }

    /** "Go back": drops a pending request. A running pass is left alone. */
    fun cancelRequest(packageName: String) = synchronized(lock) {
        val entry = store.get(packageName)
        if (entry != null && entry.grantedUntilMs == null) store.set(packageName, null)
    }

    /**
     * Pass time left for [packageName], or null when it has none running. Ignores Matrix mode; the
     * ladder ([BlockModel.decide]) is what refuses a pass while Matrix mode is on.
     */
    fun passRemainingMs(packageName: String): Long? = synchronized(lock) {
        val now = clock()
        val until = liveEntry(packageName, now)?.grantedUntilMs ?: return null
        FeedPassRule.passRemainingMs(until, now)
    }

    /** [packageName]'s running pass end (wall clock), or null when it has none. One prefs read, no scan. */
    fun grantedUntilMs(packageName: String): Long? = synchronized(lock) {
        liveEntry(packageName, clock())?.grantedUntilMs
    }

    /** The running passes with their grantedUntil instants, for the service's expiry checks. */
    fun running(): Map<String, Long> = synchronized(lock) {
        val now = clock()
        store.all().keys.mapNotNull { pkg -> liveEntry(pkg, now)?.grantedUntilMs?.let { pkg to it } }.toMap()
    }

    /** The stored entry if it still means something; a spent pass or a lapsed request is cleared and reads null. */
    private fun liveEntry(packageName: String, now: Long): FeedPass? {
        val entry = store.get(packageName) ?: return null
        val until = entry.grantedUntilMs
        // A clock set back to before the request ends a pass rather than stretching it.
        val dead = if (until != null) {
            FeedPassRule.passRemainingMs(until, now) == 0L || now < entry.requestedAtMs
        } else {
            FeedPassRule.requestLapsed(entry.requestedAtMs, now)
        }
        if (dead) store.set(packageName, null)
        return if (dead) null else entry
    }
}

/**
 * The last foreground app the bounce service saw (ADR 0066), from window-state events it already
 * receives; it never reads window content. Overlays with no launcher entry (keyboard, SystemUI's
 * shade) do not count, so pulling the shade over a passed app does not hide it from the expiry check.
 */
class ForegroundTracker {
    @Volatile
    var last: String? = null
        private set

    /** [countsAsApp]: the package has a launcher entry or is LEGION, Settings or the Home app. */
    fun note(packageName: String?, countsAsApp: Boolean) {
        if (!packageName.isNullOrBlank() && countsAsApp) last = packageName
    }
}
