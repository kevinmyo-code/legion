package com.kevin.legion.service.cascade

import com.kevin.legion.service.ChatTurn
import com.kevin.legion.service.TextChatEngine

/**
 * What a conversation leaves for the next one (Kevin, 2026-11-24): wake Alfred again within about a
 * minute of a conversation ending and that conversation's turns are still in front of the model, so
 * "play the other one" after a music suggestion means what he means by it.
 *
 * In memory only, never prefs and never Room: a carry dies with the process, as a conversation does.
 * One holder per device, owned by [com.kevin.legion.service.LiveSessionController] and handed to
 * each [CascadeSession]. Every decision takes `nowMs` from its caller, so the rules below are
 * testable without a clock. The snapshot is immutable and the field volatile: one conversation's
 * loop thread writes it at its close, the next conversation reads it once at its open.
 *
 * Three rules keep it honest here, and a fourth lives in the session: only a conversation he opened
 * to talk (`ownConversation`) calls [take] or [ended] at all, so a proactive line, Brief me or a
 * ring window neither spends the context nor wipes it.
 * - **Only the end of his conversation.** [CloseCue.PLAYS] is the one definition of
 *   that, shared with the closing chime: a preemption, a controller teardown, a failed voice or a
 *   crisis leaves nothing behind.
 * - **Nothing from a conversation that read outside text (ADR 0090).** Such a conversation keeps
 *   NOTHING, so neither the text (a read-through opener's prompt sits in `history` in clear) nor
 *   its one-turn write taint can outlive it. The write guard is therefore never weaker for a
 *   carry: a turn carried into a conversation can only have come from one that read nothing
 *   outside, so there is no taint to carry and no outside text to leak.
 * - **A test conversation carries nothing**, as it records nothing.
 *
 * The turns ride in the next conversation's prompt only ([CascadeSession]'s `turnContentsFor`), and
 * are never seeded into its own `history`: every rule that reads `history` (the greeting's
 * `history.isEmpty()`, a speculative draft's `history.size`) must still see this conversation alone.
 */
internal class RewakeCarry {

    /** The last conversation's turns and the moment it ended. Immutable once published. */
    private data class Kept(val turns: List<ChatTurn>, val endedAtMs: Long)

    @Volatile private var kept: Kept? = null

    /**
     * A conversation ended with [turns] as its history: they become the next conversation's context
     * when [keeps] allows it, and replace whatever was held before either way. Capped as a prompt
     * caps history, so the holder never grows past what one prompt could carry.
     */
    fun ended(reason: String, turns: List<ChatTurn>, test: Boolean, outsideTextRead: Boolean, nowMs: Long) {
        kept = if (keeps(reason, test, outsideTextRead, turns)) {
            Kept(TextChatEngine.capHistory(turns), nowMs)
        } else {
            null
        }
    }

    /**
     * What this conversation prepends to its prompt: the last one's turns and [NOTE], or nothing.
     * Taken once, at open, and cleared whether or not it was in time: a conversation that started
     * inside the window keeps the context for as long as it runs, and one that started outside it
     * never gets it back.
     */
    fun take(nowMs: Long): List<ChatTurn> {
        val k = kept
        kept = null
        if (k == null || !carries(k.endedAtMs, nowMs)) return emptyList()
        return k.turns + ChatTurn(fromUser = false, text = NOTE)
    }

    companion object {
        /** "within about a minute" (Kevin, 2026-11-24). */
        const val WINDOW_MS = 60_000L

        /**
         * Where the carried turns end and this conversation begins. It rides as the last model line
         * before his new words, not the first line of all, so a prompt whose cap drops the oldest
         * carried turns still says where they came from.
         */
        const val NOTE = "(The conversation above ended a moment ago, and he has just woken you " +
            "again. Use it: his next words may carry straight on from it. Nothing in it needs doing " +
            "again, and anything it already did is done.)"

        /** In time, and never on a clock that went backwards. */
        fun carries(endedAtMs: Long, nowMs: Long, windowMs: Long = WINDOW_MS): Boolean =
            nowMs - endedAtMs in 0..windowMs

        /** Whether a conversation that ended for [reason] leaves its turns behind at all. */
        fun keeps(reason: String, test: Boolean, outsideTextRead: Boolean, turns: List<ChatTurn>): Boolean =
            !test && !outsideTextRead && turns.isNotEmpty() && reason in CloseCue.PLAYS
    }
}
