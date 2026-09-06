package com.kevin.legion.backend.engine

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [engineRefusalSentence] - DRF's error envelope, unwrapped.
 *
 * **Why this exists at all.** `ChecklistsBackfill`'s summary line was reaching the Setup screen as
 * `checklist_ticks: {"non_field_errors":["\"3 sets goblet squats\" is measured in kg - give a
 * number to tick it, nothing was recorded."]}` - the engine's own English, correct in every word,
 * wearing a JSON costume. The refusal body itself stays verbatim everywhere a decision is made on
 * it; this is only for the places a human reads it.
 */
class EngineRefusalSentenceTest {

    @Test
    fun `a non_field_errors envelope comes out as the sentence inside it`() {
        // Verbatim from the A25, 2026-09-06.
        val body = """{"non_field_errors":["\"3 sets goblet squats\" is measured in kg - """ +
            """give a number to tick it, nothing was recorded."]}"""

        assertEquals(
            "\"3 sets goblet squats\" is measured in kg - give a number to tick it, nothing was recorded.",
            engineRefusalSentence(body),
        )
    }

    @Test
    fun `DRF's detail shape unwraps too`() {
        assertEquals("Invalid token.", engineRefusalSentence("""{"detail": "Invalid token."}"""))
    }

    @Test
    fun `per-field errors are joined, never dropped`() {
        // A serializer that refuses two fields must not have one of them silently disappear on the
        // way to the screen.
        assertEquals(
            "This field is required. Enter a number.",
            engineRefusalSentence("""{"day": ["This field is required."], "value": ["Enter a number."]}"""),
        )
    }

    @Test
    fun `a body this cannot unwrap is returned untouched`() {
        // Failing to unwrap must never lose the only explanation there is.
        assertEquals("502 Bad Gateway", engineRefusalSentence("502 Bad Gateway"))
        assertEquals("[]", engineRefusalSentence("[]"))
        assertEquals("{}", engineRefusalSentence("{}"))
    }
}
