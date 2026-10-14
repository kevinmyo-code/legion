package com.kevin.midnightai.media

/**
 * Tells an EXTERNAL media-volume change (the head unit's hardware or steering-wheel
 * keys) apart from the app's own writes, so [VolumeSync] can re-apply only the former.
 *
 * Why this exists (field report, 2026-10-13, a guess - not on-device verified): on
 * Kevin's head unit the volume keys move the STREAM_MUSIC index (our on-screen meter,
 * which polls [android.media.AudioManager.getStreamVolume], moves with them) but the
 * speakers stay at the old level until he taps the app's own volume key. The app's
 * path is `adjustStreamVolume(..., FLAG_SHOW_UI)`, which [VolumeController] already
 * notes is the only form that takes effect on many OEM ROMs. So the index changes,
 * but the amp is only told when that call is made. The fix mirrors the key press by
 * re-issuing the working call whenever an index change arrives that we did not make.
 *
 * Pure state, no Android types: every decision is unit-tested. Synchronized because
 * the voice tool path writes from a worker thread while the receiver runs on main.
 */
class ExternalVolumeTracker(
    private val quietMs: Long = QUIET_MS,
    private val holdGraceMs: Long = HOLD_GRACE_MS,
    private val breakerWindowMs: Long = BREAKER_WINDOW_MS,
    private val breakerMax: Int = BREAKER_MAX,
    private val breakerOpenMs: Long = BREAKER_OPEN_MS,
) {

    enum class Decision {
        /** Nothing to do: first sighting, unchanged, or our own write's echo. */
        IGNORE,

        /** An index change the app did not make: re-apply it. */
        REASSERT,

        /** Inside our own write's quiet window: check again once it closes. */
        DEFER,
    }

    private var known: Int? = null
    private var quietUntil = 0L

    // Voice-turn hold (2026-10-13 car report: music dropped out on every voice tap). While a
    // voice turn holds audio focus the system, or a head unit that ducks by moving the index
    // itself, changes STREAM_MUSIC. Those are not key presses: re-asserting them pinned the
    // ducked level as the driver's volume (with the volume panel flashing on every step).
    private var held = false
    private var heldUntil = 0L

    // Circuit breaker: something that keeps answering our re-assert with another change (an
    // MCU echoing a different index) must not turn into an endless FLAG_SHOW_UI loop.
    private val recentReasserts = ArrayDeque<Long>()
    private var breakerOpenUntil = 0L

    /** Times the breaker has tripped this process, for the breadcrumb. */
    @get:Synchronized
    var breakerTrips = 0
        private set

    /** The app itself just left the stream at [index]; echoes within [quietMs] are ours. */
    @Synchronized
    fun onOwnWrite(index: Int, nowMs: Long) {
        known = index
        quietUntil = nowMs + quietMs
    }

    /**
     * A voice turn took ([held] true) or released audio focus. Changes seen while held, and for
     * [holdGraceMs] after release (the un-duck), are adopted silently instead of re-asserted.
     */
    @Synchronized
    fun setHeld(held: Boolean, nowMs: Long) {
        this.held = held
        if (!held) heldUntil = nowMs + holdGraceMs
    }

    /** Classifies an observed STREAM_MUSIC [index] (from a broadcast, observer or recheck). */
    @Synchronized
    fun onObserved(index: Int, nowMs: Long): Decision {
        if (nowMs < quietUntil) return Decision.DEFER
        val prev = known
        known = index
        if (prev == null || prev == index) return Decision.IGNORE
        if (held || nowMs < heldUntil) return Decision.IGNORE
        if (nowMs < breakerOpenUntil) return Decision.IGNORE
        while (recentReasserts.isNotEmpty() && nowMs - recentReasserts.first() >= breakerWindowMs) {
            recentReasserts.removeFirst()
        }
        if (recentReasserts.size >= breakerMax) {
            recentReasserts.clear()
            breakerOpenUntil = nowMs + breakerOpenMs
            breakerTrips++
            return Decision.IGNORE
        }
        recentReasserts.addLast(nowMs)
        return Decision.REASSERT
    }

    /** Milliseconds until the quiet window closes (0 if already closed). */
    @Synchronized
    fun quietRemaining(nowMs: Long): Long = (quietUntil - nowMs).coerceAtLeast(0L)

    companion object {
        /** Long enough to swallow our own two-call re-assert's broadcasts, short enough to feel live. */
        const val QUIET_MS = 600L

        /** After a voice turn lets go of focus: long enough for the un-duck to land. */
        const val HOLD_GRACE_MS = 3_000L

        /**
         * More than [BREAKER_MAX] re-asserts inside [BREAKER_WINDOW_MS] is a loop, not a driver.
         * The quiet window caps a loop near one re-assert per [QUIET_MS], so ten in ten seconds
         * is a loop running flat out; a driver holding a volume key can reach it too, which
         * only costs the re-assert for the rest of that hold.
         */
        const val BREAKER_WINDOW_MS = 10_000L
        const val BREAKER_MAX = 10

        /** How long a tripped breaker stays open. */
        const val BREAKER_OPEN_MS = 30_000L
    }
}
