package com.kevin.legion.vehicle

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `clear_codes`' voice yes-check, replayed against Kevin's 2026-11-05 ~20:18 CST conversation:
 *
 *  1. "Can you try to clear the codes?" -> clear_codes(confirmed=false) -> the warning and "Do you
 *     want me to clear?"
 *  2. "Can you try it again? It's weird." -> the model called clear_codes(confirmed=true).
 *  3. "Yeah, yeah, do it." arrived only after.
 *
 * Pure: [ClearCodesVoiceGate] has no Context and an injected clock.
 */
class ClearCodesVoiceGateTest {

    private var now = 1_000_000L
    private val gate = ClearCodesVoiceGate { now }

    @Test
    fun `try it again is refused, and the real yes after it goes through`() {
        gate.asked("Can you try to clear the codes?")
        now += 8_000

        val refusal = gate.checkConfirmed("Can you try it again? It's weird.")
        assertNotNull("'try it again' is not consent to wipe readiness monitors", refusal)
        assertTrue(refusal!!, refusal.startsWith("Nothing was cleared"))

        now += 6_000
        assertNull("the plain yes that followed is consent", gate.checkConfirmed("Yeah, yeah, do it."))
    }

    @Test
    fun `one yes buys one clear`() {
        gate.asked("clear the codes")
        assertNull(gate.checkConfirmed("yes"))
        assertEquals(ClearCodesVoiceGate.NO_QUESTION, gate.checkConfirmed("yes"))
    }

    @Test
    fun `a confirmed call with no question asked is refused`() {
        assertEquals(ClearCodesVoiceGate.NO_QUESTION, gate.checkConfirmed("Yes, clear the codes"))
    }

    @Test
    fun `the asking turn cannot answer its own question`() {
        gate.asked("Yes clear the codes")
        assertEquals(ClearCodesVoiceGate.NOT_ANSWERED_YET, gate.checkConfirmed("Yes clear the codes"))
    }

    @Test
    fun `an unheard answer is not a yes`() {
        gate.asked("clear the codes")
        assertEquals(ClearCodesVoiceGate.UNHEARD, gate.checkConfirmed(""))
        assertEquals(ClearCodesVoiceGate.UNHEARD, gate.checkConfirmed(null))
        assertNull("the question is still open after an unheard answer", gate.checkConfirmed("go ahead"))
    }

    @Test
    fun `a stale question cannot be answered`() {
        gate.asked("clear the codes")
        now += ClearCodesVoiceGate.TTL_MS + 1
        assertEquals(ClearCodesVoiceGate.NO_QUESTION, gate.checkConfirmed("yes"))
    }

    @Test
    fun `reset closes the question`() {
        gate.asked("clear the codes")
        gate.reset()
        assertEquals(ClearCodesVoiceGate.NO_QUESTION, gate.checkConfirmed("yes"))
    }

    @Test
    fun `plain yeses`() {
        listOf("Yes", "yeah", "Yep.", "Sure, go ahead.", "Okay, do it", "yes please", "Clear them", "go for it")
            .forEach { assertTrue(it, ClearCodesVoiceGate.isExplicitYes(it)) }
    }

    @Test
    fun `not yeses`() {
        listOf(
            "Can you try it again? It's weird.", "try again", "Is that safe?", "No", "Yeah, no",
            "Okay wait", "Do you think I should?", "hold on", "not yet", "Could you do it", "what", "",
            "maybe", "don't", "Actually, never mind",
        ).forEach { assertFalse(it, ClearCodesVoiceGate.isExplicitYes(it)) }
    }
}
