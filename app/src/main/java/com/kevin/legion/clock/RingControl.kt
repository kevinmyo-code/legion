package com.kevin.legion.clock

import android.content.Context

/** Why a ring stopped, as its log line names it. Stub: behaviour lands in the next commit. */
enum class RingStopReason(val audit: String) {
    BUTTON("button"),
    NOTIFICATION("notification"),
    VOICE("voice"),
    SCREEN("screen"),
    TIMEOUT("timeout"),
    SWIPE("swipe"),
    SNOOZE("snooze"),
    CLOCK("clock"),
}

/** Stub of the one owner of stopping a ring. */
object RingControl {
    fun stopAll(context: Context, reason: RingStopReason): Int = 0

    fun stop(context: Context, r: RingRequest, reason: RingStopReason) = Unit

    fun snooze(context: Context, id: Int) = Unit

    fun voiceStops(words: String, companionName: String = ""): Boolean = false

    internal fun isStopPhrase(words: String, companionName: String = ""): Boolean = false
}

/** Stub of the ring's log lines. */
object RingAudit {
    const val TAG = "AlfredRing"

    internal fun startLine(kind: RingKind): String = ""

    internal fun stopLine(kind: RingKind, reason: RingStopReason): String = ""
}
