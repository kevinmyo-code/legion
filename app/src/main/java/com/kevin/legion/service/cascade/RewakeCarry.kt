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
 * **A re-wake that goes nowhere does not spend the context.** [take] does not clear what it hands
 * over: a conversation that opened, heard nothing and closed on noise leaves the same context for
 * his next try, and a conversation that did say something leaves its own turns AFTER the ones it
 * carried, so a second follow-up still reaches the conversation that started it. Only the window
 * running out, a wipe, or an outside read empties the holder.
 *
 * What is kept, and what is never kept:
 * - **Only the end of his conversation.** [CloseCue.PLAYS] is the one definition of that, shared
 *   with the closing chime. A close that is not one (a preemption, a teardown, a failed voice, a
 *   crisis) leaves the holder exactly as it was, rather than emptying it: that close is not the end
 *   of a conversation, so it has no context of its own to leave and no business taking the last
 *   one's away. A fourth rule lives in the session: only a conversation he opened to talk
 *   (`ownConversation`) calls [take] or [ended] at all, so a proactive line, Brief me or a ring
 *   window neither spends the context nor wipes it.
 * - **"New chat" wipes it** ([WIPES]): he asked to start fresh, and that is the one close that
 *   means the last conversation is not to be carried.
 * - **Nothing from a conversation that read outside text (ADR 0090).** Such a conversation empties
 *   the holder, so neither the text (a read-through opener's prompt sits in `history` in clear) nor
 *   its one-turn write taint can outlive it. The write guard is therefore never weaker for a carry:
 *   a turn carried into a conversation can only have come from one that read nothing outside, so
 *   there is no taint to carry and no outside text to leak.
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
     * A conversation ended with [turns] as everything in front of the model (what it carried in,
     * then its own history). They become the next conversation's context when this close keeps
     * anything; a close that keeps nothing leaves what is held alone, except the wipes above.
     * Capped as a prompt caps history, so the holder never grows past what one prompt could carry.
     */
    fun ended(reason: String, turns: List<ChatTurn>, test: Boolean, outsideTextRead: Boolean, nowMs: Long) {
        if (wipes(reason, test, outsideTextRead)) {
            kept = null
            return
        }
        if (keeps(reason, turns)) kept = Kept(TextChatEngine.capHistory(turns), nowMs)
    }

    /**
     * The last conversation's turns for this one's prompt, or nothing. Taken once, at open. What is
     * handed over stays held, so a conversation that heard nothing does not spend it; one that is
     * out of time empties the holder on its way past, because nothing will want it again.
     */
    fun take(nowMs: Long): List<ChatTurn> {
        val k = kept ?: return emptyList()
        if (carries(k.endedAtMs, nowMs)) return k.turns
        kept = null
        return emptyList()
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

        /**
         * His "new chat" tap (`LiveSessionController.newConversation`), which closes a conversation
         * with this reason: he asked to start fresh, so nothing is carried into what he starts next.
         * It is in [CloseCue.PLAYS] because it ends a conversation he was in and so earns the chime;
         * whether a close ends a conversation and whether its context carries are two questions.
         */
        val WIPES = setOf("new conversation")

        /** In time, and never on a clock that went backwards. */
        fun carries(endedAtMs: Long, nowMs: Long, windowMs: Long = WINDOW_MS): Boolean =
            nowMs - endedAtMs in 0..windowMs

        /** Whether what this conversation had in front of the model is thrown away outright. */
        fun wipes(reason: String, test: Boolean, outsideTextRead: Boolean): Boolean =
            test || outsideTextRead || reason in WIPES

        /** Whether a conversation that ended for [reason] with [turns] leaves them behind. */
        fun keeps(reason: String, turns: List<ChatTurn>): Boolean =
            turns.isNotEmpty() && reason in CloseCue.PLAYS
    }
}
