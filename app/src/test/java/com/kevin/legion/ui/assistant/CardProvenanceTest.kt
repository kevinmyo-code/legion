package com.kevin.legion.ui.assistant

import com.kevin.legion.assistant.cards.CardProvenance
import com.kevin.legion.assistant.cards.ChecklistLine
import com.kevin.legion.assistant.cards.ConversationCard
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * Kevin, 2026-11-10 (ADR 0081): a card names its source and age only when its data carries them.
 * No provenance, no line; each part only when present.
 */
class CardProvenanceTest {

    private val zone = ZoneOffset.ofHours(-6)
    private val now = LocalDate.of(2026, 11, 10).atTime(12, 0).toInstant(zone).toEpochMilli()

    private fun card(p: CardProvenance?) =
        ConversationCard.Checklist("Groceries", "Groceries", listOf(ChecklistLine("Milk", false)), provenance = p)

    @Test
    fun `no provenance draws no line`() = assertNull(CardProvenanceWords.provenance(card(null), now, zone))

    @Test
    fun `an empty provenance draws no line`() = assertNull(CardProvenanceWords.provenance(card(CardProvenance()), now, zone))

    @Test
    fun `a live read says where and how long ago`() {
        assertEquals("OBD port, just now", CardProvenanceWords.provenance(card(CardProvenance("OBD port", now - 5_000)), now, zone))
        assertEquals("NHTSA, 2 hours ago", CardProvenanceWords.provenance(card(CardProvenance("NHTSA", now - 7_200_000)), now, zone))
    }

    @Test
    fun `a coverage date says through when, with no source invented`() {
        val through = LocalDate.of(2026, 11, 8).atTime(23, 0).toInstant(zone).toEpochMilli()
        assertEquals("Through 8 Nov", CardProvenanceWords.provenance(card(CardProvenance(throughMs = through)), now, zone))
    }
}
