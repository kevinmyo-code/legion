package com.kevin.legion.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Typed chat's system instruction comes from the same builder as voice ([composeIdentityAndRules])
 * minus the voice-only layers. The honesty and safety text must be the IDENTICAL strings, not a
 * text-mode copy that could drift. Presence only, like [AriaBrainHonestyClauseTest]: obedience is
 * not something a unit test can see.
 */
class AriaBrainTextModeTest {

    private val identity = ALFRED.clause
    private val delivery = ALFRED.delivery
    private val voiceStyle = "Speak slowly and warmly."

    private fun compose(textMode: Boolean, defaultPersona: String? = null) =
        composeIdentityAndRules(defaultPersona, identity, delivery, voiceStyle, textMode)

    @Test
    fun `voice keeps its exact shape - identity, accent, voice style, shared rules, safety`() {
        assertEquals(
            "$identity $delivery $voiceStyle $SHARED_INSTRUCTIONS $SAFETY_INSTRUCTIONS",
            compose(textMode = false),
        )
        assertEquals("PROSE $identity $delivery $voiceStyle $SHARED_INSTRUCTIONS $SAFETY_INSTRUCTIONS",
            compose(textMode = false, defaultPersona = "PROSE"))
    }

    @Test
    fun `blank accent and voice style add nothing to voice, as before`() {
        assertEquals(
            "$identity $SHARED_INSTRUCTIONS $SAFETY_INSTRUCTIONS",
            composeIdentityAndRules(null, identity, "", "", textMode = false),
        )
    }

    @Test
    fun `typed chat drops the accent and the voice style and adds the text note`() {
        val text = compose(textMode = true)
        assertEquals("$identity $SHARED_INSTRUCTIONS $SAFETY_INSTRUCTIONS $TEXT_MODE_NOTE", text)
        assertFalse(text.contains(delivery))
        assertFalse(text.contains("British English accent"))
        assertFalse(text.contains("the sixth of August"))
        assertFalse(text.contains(voiceStyle))
    }

    @Test
    fun `the honesty clauses and the safety text are the identical strings in both modes`() {
        for (mode in listOf(false, true)) {
            val s = compose(textMode = mode)
            assertTrue("CANNOT_CLAUSE (textMode=$mode)", s.contains(CANNOT_CLAUSE))
            assertTrue("DATA_ACCESS_CLAUSE (textMode=$mode)", s.contains(DATA_ACCESS_CLAUSE))
            assertTrue("SAFETY_INSTRUCTIONS (textMode=$mode)", s.contains(SAFETY_INSTRUCTIONS))
            assertTrue("SHARED_INSTRUCTIONS (textMode=$mode)", s.contains(SHARED_INSTRUCTIONS))
        }
        assertTrue(SAFETY_INSTRUCTIONS.contains("988"))
    }

    @Test
    fun `safety still comes after the persona, and the text note after safety`() {
        val text = compose(textMode = true)
        assertTrue(text.indexOf(identity) < text.indexOf(SAFETY_INSTRUCTIONS))
        assertTrue(text.indexOf(SAFETY_INSTRUCTIONS) < text.indexOf(TEXT_MODE_NOTE))
    }

    @Test
    fun `the text note changes form only and stands in for the voice filler and the search tool`() {
        assertTrue(TEXT_MODE_NOTE.contains("TYPED, not spoken"))
        assertTrue(TEXT_MODE_NOTE.contains("digits"))
        assertTrue(TEXT_MODE_NOTE.contains("no markdown headings"))
        assertTrue(TEXT_MODE_NOTE.contains("Do not announce that you are looking something up"))
        assertTrue(TEXT_MODE_NOTE.contains("no web search in typed chat"))
        assertTrue(TEXT_MODE_NOTE.contains("Every rule above applies unchanged"))
        assertFalse("the note must not loosen the outcome-verb rule", TEXT_MODE_NOTE.contains("done"))
    }
}
