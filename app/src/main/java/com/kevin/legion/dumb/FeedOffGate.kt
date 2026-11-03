package com.kevin.legion.dumb

import android.content.SharedPreferences

/**
 * Where pending "turn it off" requests live (ADR 0066, 2026-11-02): one wall-clock instant per target.
 * Its own prefix, never `feed_pass.*`, so the service's pass re-arm never reads a gate request as a pass.
 */
interface FeedOffGateStore {
    fun get(target: String): Long?

    /** Null clears the request. */
    fun set(target: String, requestedAtMs: Long?)
}

/** [FeedOffGateStore] over the Matrix mode preferences file, synchronous `commit()` like the pass. */
class PrefsFeedOffGateStore(private val prefs: SharedPreferences) : FeedOffGateStore {
    override fun get(target: String): Long? = prefs.getLong(KEY + target, NONE).takeIf { it != NONE }

    override fun set(target: String, requestedAtMs: Long?) {
        prefs.edit().apply {
            if (requestedAtMs == null) remove(KEY + target) else putLong(KEY + target, requestedAtMs)
        }.commit()
    }

    private companion object {
        const val KEY = "feed_off.requested."
        const val NONE = -1L
    }
}

/**
 * Turning the feed block down costs the same as a pass (Kevin, 2026-11-02): the switch going OFF and
 * a blocked app being unblocked both take the 60 second wall-clock wait and a typed sentence. Turning
 * it ON and blocking an app stay instant. The timing rules are [FeedPassRule]'s, not a second set.
 */
object FeedOffRule {
    /** The target id for the "Block feed apps always" switch; never a package name (no dots). */
    const val SWITCH = "#switch"

    const val SWITCH_SENTENCE = "I am choosing to turn off the feed blocker"

    fun unblockSentence(appLabel: String): String = "I am choosing to unblock $appLabel"

    /** Case-insensitive and trimmed, like the pass and the Matrix leave. Inner spacing must match. */
    fun matches(typed: String, sentence: String): Boolean = typed.trim().equals(sentence, ignoreCase = true)

    /**
     * Whether a settings tap on an app needs the gate: only when it would make a blocked app open.
     * [blockedWithoutPass] is the app's verdict ignoring a running pass ([BlockPolicy.verdictWithoutPass]),
     * since a flip changes what the app is after the pass, and an app on a pass is still blocked underneath.
     */
    fun flipNeedsGate(blockedWithoutPass: Boolean): Boolean = blockedWithoutPass
}

/** What a gate request or confirm did, worded by the screen. */
sealed interface GateResult {
    data class Waiting(val remainingMs: Long) : GateResult
    data object NotRequested : GateResult
    data class TooEarly(val remainingMs: Long) : GateResult
    data object WrongSentence : GateResult

    /** The wait is over and the sentence matched: the caller applies the change; the request is cleared. */
    data object Confirmed : GateResult
}

/**
 * The 60 second wait and sentence in front of turning the feed block down (ADR 0066, 2026-11-02).
 * Same wall-clock persisted instant as the pass and the Matrix leave, same clamp, same 10 minute lapse.
 * Pure over its store and clock; the effect (switch off, flip) stays with the caller.
 */
class FeedOffGate(
    private val store: FeedOffGateStore,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val lock = Any()

    /** Starts the wait. Idempotent: asking again keeps the original instant. */
    fun request(target: String): GateResult.Waiting = synchronized(lock) {
        val now = clock()
        val live = liveRequest(target, now)
        if (live != null) return GateResult.Waiting(FeedPassRule.waitRemainingMs(live, now))
        store.set(target, now)
        GateResult.Waiting(FeedPassRule.WAIT_MS)
    }

    /** Wait left on a pending request, or null when none is pending (or it lapsed). */
    fun waitRemainingMs(target: String): Long? = synchronized(lock) {
        val now = clock()
        liveRequest(target, now)?.let { FeedPassRule.waitRemainingMs(it, now) }
    }

    /** Confirms when the wait is over AND [typed] matches [sentence]; otherwise changes nothing. */
    fun confirm(target: String, sentence: String, typed: String): GateResult = synchronized(lock) {
        val now = clock()
        val live = liveRequest(target, now) ?: return GateResult.NotRequested
        val remaining = FeedPassRule.waitRemainingMs(live, now)
        when {
            remaining > 0L -> GateResult.TooEarly(remaining)
            !FeedOffRule.matches(typed, sentence) -> GateResult.WrongSentence
            else -> {
                store.set(target, null)
                GateResult.Confirmed
            }
        }
    }

    fun cancel(target: String) = synchronized(lock) { store.set(target, null) }

    /** The stored instant if it is still live; a lapsed one (or one from a clock set backwards) is cleared. */
    private fun liveRequest(target: String, now: Long): Long? {
        val at = store.get(target)
        if (at != null && FeedPassRule.requestLapsed(at, now)) {
            store.set(target, null)
            return null
        }
        return at
    }
}

/**
 * Whether the "Feed blocker is off" notice should be up (ADR 0066, 2026-11-02): the switch is on but
 * the LEGION blocker accessibility service is not, so nothing is actually sent home.
 */
object FeedBlockWatchRule {
    fun warn(switchOn: Boolean, accessibilityOn: Boolean): Boolean = switchOn && !accessibilityOn
}
