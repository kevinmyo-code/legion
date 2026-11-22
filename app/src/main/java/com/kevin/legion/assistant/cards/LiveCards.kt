package com.kevin.legion.assistant.cards

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/** What a live card was read from ([ConversationCard.source]). Phone only, never on the wire. */
sealed interface CardSource {
    /** `read_calendar`'s window, local [startMs]..[endMs]. */
    data class Calendar(val startMs: Long, val endMs: Long) : CardSource

    /** One checklist (`manage_checklist read`): Groceries, Todo, a routine. */
    data class Checklist(val checklistId: Long) : CardSource

    /** The reminders `read_list` reads. */
    data object Reminders : CardSource
}

/**
 * Cards that follow their data (2026-11-22). A card used to be a snapshot of the read that made it,
 * so when the assistant added to the list or the day on screen, the card stayed as it was until he
 * asked again. Now, while the stack is open, every change to the phone's own tables re-reads each
 * card that has a [ConversationCard.source] and swaps it in place ([ConversationCards.replace]).
 *
 * The phone writes Room first (calendar, reminders, checklists), so a write by voice, by hand or by
 * an engine pull all arrive as the same change. Pure: the change signal and the read come in.
 */
object LiveCards {

    /**
     * Runs for the life of the caller. While the stack is open, each emission of [changes] re-reads
     * the live cards through [read]; while it is closed, [changes] is not collected at all.
     */
    suspend fun follow(changes: Flow<*>, read: suspend (CardSource) -> ConversationCard?) {
        ConversationCards.state.map { it.open }.distinctUntilChanged().collectLatest { open ->
            if (open) changes.conflate().collect { refresh(read) }
        }
    }

    /**
     * Re-reads every card in the stack with a source and replaces the ones whose data changed. A read
     * that fails or returns null leaves the card as it was: a stale card beats a vanished one.
     */
    suspend fun refresh(read: suspend (CardSource) -> ConversationCard?) {
        for (card in ConversationCards.state.value.cards) {
            val fresh = card.source?.let { readOrNull(it, read) }
            if (fresh != null && fresh != card) ConversationCards.replace(card, fresh)
        }
    }

    /** [read] of [source], or null when it fails; a cancellation is never swallowed. */
    @Suppress("TooGenericExceptionCaught", "SwallowedException") // a failed read keeps the card
    suspend fun readOrNull(source: CardSource, read: suspend (CardSource) -> ConversationCard?) =
        try {
            read(source)
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            null
        }

    /** Whether a calendar card in the stack (shown or put away) covers [startMs]: a write there refreshes it. */
    fun calendarShows(state: ConversationCardsState, startMs: Long): Boolean =
        state.cards.any { c ->
            val s = c.source
            s is CardSource.Calendar && startMs in s.startMs..s.endMs
        }
}
