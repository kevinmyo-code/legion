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
    /** A typed ask opened this stack: a voice conversation ending does not clear it. */
    val typed: Boolean = false,
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
 * - [begin] when a voice conversation starts, [beginTyped] when a typed question is sent: typed
 *   questions get the same cards as voice (Kevin, 2026-11-10, ADR 0081). A card published while
 *   neither is open (a background read) is dropped, so a card never lingers with nothing to end it.
 * - [publish] from a tool's own result, inside the tool.
 * - [dismiss] when Kevin closes the card; the next card shows again.
 * - [endVoice] when a voice conversation ends: cleared, unless a typed ask opened the stack.
 * - [end] for the Home button, a new conversation or a crisis: everything is cleared.
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

    /** A typed question was sent: open the stack (or keep it) and mark it as a typed ask's. */
    fun beginTyped() {
        _state.update { if (it.open) it.copy(typed = true) else ConversationCardsState(open = true, typed = true) }
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

    /** A voice conversation ended. A typed ask's cards stay until the Home button or a new chat. */
    fun endVoice() {
        _state.update { if (it.typed) it else ConversationCardsState() }
    }

    /**
     * Merged by part ([CarPart]): a part [newer] read replaces [older]'s, a part it did not read
     * keeps [older]'s, so a card never shows a part as empty because the later call skipped it.
     */
    internal fun mergeCar(older: ConversationCard.Car, newer: ConversationCard.Car): ConversationCard.Car {
        fun <T> pick(part: CarPart, value: (ConversationCard.Car) -> T): T =
            if (newer.read(part) || !older.read(part)) value(newer) else value(older)
        return newer.copy(
            mileage = newer.mileage ?: older.mileage,
            mileageAsOf = newer.mileageAsOf ?: older.mileageAsOf,
            nextService = pick(CarPart.SERVICE) { it.nextService },
            recalls = pick(CarPart.RECALLS) { it.recalls },
            codes = pick(CarPart.CODES) { it.codes },
            parts = older.parts + newer.parts,
            extraNote = newer.extraNote ?: older.extraNote,
            provenance = newer.provenance ?: older.provenance,
        )
    }
}
