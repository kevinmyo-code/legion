package com.kevin.legion.service

/**
 * How the car's code answers SOUND (2026-11-17, "the conversations felt slow"): the AI-explanation
 * label is part of every result and every screen, but read aloud on every answer it was a long
 * parenthesis heard again and again. Heard, it is a short form, at most once in [WINDOW_MS] (about
 * one conversation; the phone's tool code does not know where a conversation starts). The words on
 * screen and in the tool result are unchanged, so the estimate is still labelled (CLAUDE.md sec 4 rule 5).
 */
internal object SpokenCarLines {
    const val AI_LABEL_SPOKEN = "(an AI's reading, not the code book)"
    const val WINDOW_MS = 15L * 60 * 1000

    @Volatile private var saidAtMs = 0L

    /** True when the short AI label should be heard now (and marks it said). */
    @Synchronized
    fun sayAiLabel(now: Long = System.currentTimeMillis()): Boolean {
        if (saidAtMs > 0L && now - saidAtMs in 0 until WINDOW_MS) return false
        saidAtMs = now
        return true
    }

    /** Test seam. */
    internal fun resetForTest() {
        saidAtMs = 0L
    }
}
