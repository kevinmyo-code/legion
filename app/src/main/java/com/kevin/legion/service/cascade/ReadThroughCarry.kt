package com.kevin.legion.service.cascade

/**
 * The cascade's one-turn carry after a read-through opener (ADR 0090 gap 5, 2026-11-17), Live's
 * `markTurnReadThrough` carry on the cascade. A spoken brief or a `mail_reply` line puts outside
 * text into the conversation's history as the opener's prompt, so the turn right after it (his
 * "yes", "do that") still has that text in context: it starts marked, and with the write guard
 * ([com.kevin.legion.service.ReadThroughWriteGuard]) no write runs in it. One turn only, as on Live.
 *
 * Used on the session's single loop thread; not thread-safe on its own.
 */
internal class ReadThroughCarry {
    private var pending = false

    /**
     * Called as each turn starts. True when this turn is the one after a read-through opener and
     * so starts marked. [openerMarked] is this turn's own opener mark (false for his own words).
     */
    fun startTurn(fromUser: Boolean, openerMarked: Boolean): Boolean {
        val carried = pending
        pending = !fromUser && openerMarked
        return carried
    }
}
