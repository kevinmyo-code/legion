package com.kevin.legion.service.turn

/**
 * Whether he started talking again soon after an early end ([EndOfTurnPolicy.Reason.EARLY]): the
 * real false-early-end rate in a drive log (`eot_early_resumed=1`), the number the early threshold
 * is tuned on. Counted in mic windows, so it needs no clock; [TurnTaker] feeds it every window.
 */
internal class EarlyEndWatch {
    /** Windows since the last early end, or -1 when the last end was not early or was answered. */
    private var windowsSince = -1

    /** A turn ended; only an early end starts the watch. */
    fun onEnd(early: Boolean) {
        windowsSince = if (early) 0 else -1
    }

    fun onWindow() {
        if (windowsSince >= 0) windowsSince = minOf(windowsSince + 1, CAP_WINDOWS)
    }

    /**
     * Speech confirmed, begun [onsetWindows] windows ago. Returns how long after the early end it
     * began, when that is within [LIMIT_MS]; null otherwise. Reports once per early end.
     */
    fun onStart(onsetWindows: Int): Int? {
        val since = windowsSince
        windowsSince = -1
        if (since < 0) return null
        val afterMs = maxOf(0, since - onsetWindows) * EndOfTurnPolicy.WINDOW_MS
        return afterMs.takeIf { it <= LIMIT_MS }
    }

    companion object {
        const val LIMIT_MS = 1_500
        private const val CAP_WINDOWS = 1_000

        fun line(afterMs: Int) = "eot_early_resumed=1 after_ms=$afterMs"
    }
}
