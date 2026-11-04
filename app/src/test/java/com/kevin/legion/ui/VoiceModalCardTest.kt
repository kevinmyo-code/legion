package com.kevin.legion.ui

import com.kevin.legion.assistant.cards.CarPart
import com.kevin.legion.assistant.cards.ConversationCard
import com.kevin.legion.service.VoiceModalTarget
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Which conversation card rides at the top of a voice-called sheet (phone test, 2026-11-04). */
class VoiceModalCardTest {

    private fun car(vararg parts: CarPart) = ConversationCard.Car(
        "Jeep", "Jeep", null, null, null, emptyList(), emptyList(), parts.associateWith { "" },
    )

    private val timeline = ConversationCard.Timeline("Calendar", "2026-11-04", "UTC-06:00", emptyList())

    @Test
    fun `a car card shows over the agenda sheet, a timeline does not`() {
        assertTrue(cardOverModal(VoiceModalTarget.AGENDA, car(CarPart.SERVICE)))
        assertFalse(cardOverModal(VoiceModalTarget.AGENDA, timeline))
    }

    @Test
    fun `over the codes sheet a codes-only car is left out, a car with service or recalls shows`() {
        assertFalse(cardOverModal(VoiceModalTarget.CODES, car(CarPart.CODES)))
        assertTrue(cardOverModal(VoiceModalTarget.CODES, car(CarPart.CODES, CarPart.RECALLS)))
        assertTrue(cardOverModal(VoiceModalTarget.CODES, timeline))
    }
}
