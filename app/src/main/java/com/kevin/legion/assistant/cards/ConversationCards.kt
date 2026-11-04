package com.kevin.legion.assistant.cards

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** What the strip draws: the cards this conversation made, and whether the newest was dismissed. */
data class ConversationCardsState(
    val open: Boolean = false,
    val cards: List<ConversationCard> = emptyList(),
    val dismissed: Boolean = false,
) {
    /** The card on screen: the newest one, unless Kevin dismissed it. */
    val latest: ConversationCard? get() = if (dismissed) null else cards.lastOrNull()
}

/**
 * The process-wide holder of the current voice conversation's cards (the same `object` +
 * `StateFlow` shape as [com.kevin.legion.service.GlanceCardController]; there is no Hilt in this
 * build). Display-only: never persisted, never synced.
 *
 * Lifecycle, driven by [com.kevin.legion.service.LiveSessionController]:
 * - [begin] when a voice conversation starts. A card published while no conversation is open (a
 *   typed-chat tool call, a background read) is dropped, so a card never lingers on the strip with
 *   no conversation to end it.
 * - [publish] from a tool's own result, inside the tool.
 * - [dismiss] when Kevin closes the card; the next card shows again.
 * - [end] when the conversation ends: everything is cleared.
 *
 * A car card for the same car as the newest card merges into it ([mergeCar]), so get_next_service,
 * check_recalls and get_codes in one answer build one car rather than three.
 */
object ConversationCards {
    private val _state = MutableStateFlow(ConversationCardsState())
    val state: StateFlow<ConversationCardsState> = _state.asStateFlow()

    /** Cards kept per conversation; older ones drop off the front. */
    private const val KEEP = 8

    fun begin() {
        _state.update { if (it.open) it else ConversationCardsState(open = true) }
    }

    fun publish(card: ConversationCard) {
        _state.update { current ->
            if (!current.open) return@update current
            val last = current.cards.lastOrNull()
            val cards = if (card is ConversationCard.Car && last is ConversationCard.Car && last.name == card.name) {
                current.cards.dropLast(1) + mergeCar(last, card)
            } else {
                current.cards + card
            }
            current.copy(cards = cards.takeLast(KEEP), dismissed = false)
        }
    }

    fun dismiss() {
        _state.update { it.copy(dismissed = true) }
    }

    fun end() {
        _state.value = ConversationCardsState()
    }

    /** [newer]'s readings win; a field [newer] did not look up keeps what [older] had. */
    internal fun mergeCar(older: ConversationCard.Car, newer: ConversationCard.Car): ConversationCard.Car =
        newer.copy(
            mileage = newer.mileage ?: older.mileage,
            mileageAsOf = newer.mileageAsOf ?: older.mileageAsOf,
            nextService = newer.nextService ?: older.nextService,
            recalls = newer.recalls ?: older.recalls,
            codes = newer.codes ?: older.codes,
            note = listOfNotNull(older.note, newer.note).distinct().joinToString(" ").ifBlank { null },
        )
}
