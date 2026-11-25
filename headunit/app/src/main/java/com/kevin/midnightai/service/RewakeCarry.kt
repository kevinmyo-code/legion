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
     * A conversation started or resumed on this socket ([GeminiLiveSession] `beginConversation`),
     * so what is held is a conversation that is RUNNING again, not one that ended. Dropping the
     * stamp is what makes [abandoned] mean what it says: without it, a conversation resumed on a
     * socket that had already parked kept adding turns under the old park's stamp, and a teardown
     * of that conversation (a drop, an error, `silentDestroy`, a crisis) left its own completed
     * turns behind for the next cold connect to replay.
     *
     * The turns themselves stay: they are what the driver last heard, and this same conversation is
     * carrying on. Its next real end ([ended]) stamps the lot again.
     */
    @Synchronized
    fun running() {
        endedAtMs = null
    }

    /**
     * A completed exchange. Turns accumulate across a park: a proactive line spoken on the warm
     * socket, and the next conversation on it, are both more of what he last heard, so they join
     * the list rather than replacing it, and the next end re-stamps the lot. Only [take] running
     * out of time, [abandoned] on a conversation that is not ended, or [wipe] empties it.
     */
    @Synchronized
    fun turn(driver: String, companion: String) {
        if (driver.isBlank() && companion.isBlank()) return
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
     * not open. A conversation that is not ended leaves nothing, its own completed turns included
     * ([running] is what keeps that true across a park). One that had already ended keeps what it
     * left, which is the point: a parked socket the server closes must not cost the driver his
     * context.
     */
    @Synchronized
    fun abandoned() {
        if (endedAtMs == null) wipe()
    }

    /**
     * Forget the conversation outright, ended or not: the crisis teardown (`LiveSessionController`'s
     * one crisis handler, phone parity). CLAUDE.md sec 9.1 says stop performing the character, and
     * replaying that conversation into the next greeting prompt a minute later is performing it
     * again. Not a close reason, because a crisis lands on whatever socket is up.
     */
    @Synchronized
    fun wipe() {
        turns.clear()
        endedAtMs = null
    }

    /**
     * The block a cold conversation puts in front of its greeting, or null when there is nothing to
     * carry or it is too late. What is handed over stays held: a cold connect that fails, or a
     * conversation that hears nothing, does not cost him the context. Out of time, it is emptied on
     * the way past, because nothing will want it again.
     */
    @Synchronized
    fun take(nowMs: Long): String? {
        val endedAt = endedAtMs
        val expired = endedAt != null && !carries(endedAt, nowMs)
        // Held too long: emptied on the way past, because nothing will want it again. Turns with no
        // end stamp belong to a conversation still running, and are left alone.
        if (expired) wipe()
        return if (endedAt != null && !expired && turns.isNotEmpty()) block(turns.toList()) else null
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
