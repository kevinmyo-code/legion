package com.kevin.midnightai.service

/**
 * Swallows a talk tap that lands within [windowMs] of the last ACCEPTED one (voice-pipeline audit
 * B16, 2026-10-15). A double-fire (a touchscreen bounce, the avatar clickable plus a ripple, two
 * intents queued behind a busy main thread) read as start-then-stop: the first tap began a
 * session, the second saw it in conversation and stopped it. The window is measured from the
 * last accepted tap and is not extended by a swallowed one, so a deliberate second tap a moment
 * later still gets through. Note it also debounces a deliberate quick STOP tap; 700 ms is the
 * price.
 *
 * Time is passed in so this is a JVM test.
 */
internal class TapDebounce(private val windowMs: Long = DEFAULT_WINDOW_MS) {
    private var lastAcceptedMs = Long.MIN_VALUE

    fun accept(nowMs: Long): Boolean {
        if (lastAcceptedMs != Long.MIN_VALUE && nowMs - lastAcceptedMs < windowMs) return false
        lastAcceptedMs = nowMs
        return true
    }

    companion object {
        const val DEFAULT_WINDOW_MS = 700L
    }
}
