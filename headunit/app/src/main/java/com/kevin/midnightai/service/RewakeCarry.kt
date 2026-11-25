package com.kevin.midnightai.service

/**
 * What one conversation leaves for the next (Kevin, 2026-11-24, phone parity): start talking to
 * Moose again within about a minute of a conversation ending and that conversation is still in front
 * of him, so "play the other one" after a music suggestion means what the driver means by it. The
 * phone's rule is [com.kevin.legion.service.cascade.RewakeCarry] in the LEGION app; Moose matches
 * the phone in every voice behaviour, which is the standing rule this exists for.
 *
 * **Most ends on the head unit already carry more than this.** A conversation that goes quiet or
 * ends on a goodbye PARKS the socket warm for three minutes ([GeminiLiveSession] `parkWarm`), and a
 * tap then resumes that same socket with Gemini's own session context intact, which is richer than
 * any replay. This fills the holes where that context is gone: the driver tapping to stop a
 * conversation, and a parked socket the server or the hold timer closed inside the window.
 *
 * In memory only, never prefs and never Room: it dies with the process, as a conversation does. The
 * controller owns it so it outlives each socket, and every decision takes `nowMs` from its caller.
 *
 * **What it holds is what was said aloud**, the same driver and companion transcript pairs that
 * already go to `episodic_turns` in Room for the memory consolidation pass, so it exposes nothing
 * new: it holds them for a minute instead of a drive. Tool output and sub-agent text never enter
 * it, only Moose's spoken sentences.
 */
class RewakeCarry {

    /** One exchange as it was heard and spoken. */
    data class Turn(val driver: String, val companion: String)

    private val turns = ArrayDeque<Turn>()

    /** When the conversation these turns belong to ended, or null while it is still running. */
    private var endedAtMs: Long? = null

    /**
     * A completed exchange of the running conversation. A turn arriving after an end belongs to a
     * new conversation on the same socket, so it starts the list over.
     */
    @Synchronized
    fun turn(driver: String, companion: String) {
        if (driver.isBlank() && companion.isBlank()) return
        if (endedAtMs != null) {
            turns.clear()
            endedAtMs = null
        }
        turns.addLast(Turn(driver.trim(), companion.trim()))
        while (turns.size > MAX_TURNS) turns.removeFirst()
    }

    /**
     * The conversation ended the way his conversations end: it went quiet, he said goodbye, or he
     * tapped to stop it. Its turns are the next one's for [WINDOW_MS] from here.
     */
    @Synchronized
    fun ended(nowMs: Long) {
        if (turns.isNotEmpty()) endedAtMs = nowMs
    }

    /**
     * The socket died without the conversation ending: a drop, an error, a teardown, a mic it could
     * not open. A conversation that never ended leaves nothing. One that had already ended keeps
     * what it left, which is the point: a parked socket the server closes must not cost the driver
     * his context.
     */
    @Synchronized
    fun abandoned() {
        if (endedAtMs == null) turns.clear()
    }

    /**
     * The block a cold conversation puts in front of its greeting, or null when there is nothing to
     * carry or it is too late. Taken once: whatever was held is cleared either way, so a
     * conversation that opened outside the window never gets it back.
     */
    @Synchronized
    fun take(nowMs: Long): String? {
        val endedAt = endedAtMs
        val kept = turns.toList()
        turns.clear()
        endedAtMs = null
        if (kept.isEmpty() || endedAt == null || !carries(endedAt, nowMs)) return null
        return block(kept)
    }

    companion object {
        /** "within about a minute" (Kevin, 2026-11-24). */
        const val WINDOW_MS = 60_000L

        /** Enough for the thread of a short exchange, never a whole drive. */
        const val MAX_TURNS = 8

        /**
         * The close reasons that are the end of a conversation he was in, as `parkWarm` is. Only
         * his tap on a running conversation: `idle` closes a speak-only socket (a conversation
         * parks instead), `warm expired`, `go away` and `stale socket` close a socket whose
         * conversation already ended (and so keep what it left), and a drop, an error, a mic that
         * would not open or a teardown is not an end he chose.
         */
        fun endsHisConversation(reason: String): Boolean = reason == STOPPED

        /** [GeminiLiveSession] `stop()`: the driver tapped to end a running conversation. */
        const val STOPPED = "stopped"

        /** In time, and never on a clock that went backwards. */
        fun carries(endedAtMs: Long, nowMs: Long, windowMs: Long = WINDOW_MS): Boolean =
            nowMs - endedAtMs in 0..windowMs

        /**
         * The carried conversation as the greeting prompt reads it: the exchange, oldest first,
         * under one line saying where it came from. Pure prompt text, no tool output, no car data.
         */
        fun block(turns: List<Turn>): String = buildString {
            append(
                "(The conversation below ended a moment ago, and he has just started talking to you " +
                    "again. Use it: his next words may carry straight on from it. Nothing in it needs " +
                    "doing again, and anything it already did is done.\n",
            )
            for (t in turns) {
                if (t.driver.isNotBlank()) append("Driver: ").append(t.driver).append('\n')
                if (t.companion.isNotBlank()) append("You: ").append(t.companion).append('\n')
            }
            append(')')
        }
    }
}
