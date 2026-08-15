package com.kevin.legion.advisor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [AdvisorAnswer.parse] is the real gate on structured output - [SubAgent.askTyped] has no native
 * `responseSchema` plumbing (see [AdvisorAnswer.RESPONSE_SCHEMA]'s doc comment), so this parser is
 * what actually enforces the shape rather than a schema on Gemini's side.
 */
class AdvisorAnswerTest {

    @Test
    fun `parses a well-formed response with figures and a proposal`() {
        val raw = """
            {
              "spoken": "You're at 312 of your 400 grocery budget.",
              "figures": [
                {"label": "budget remaining", "value": "87.55", "basis": "record"},
                {"label": "estimated calories", "value": "620", "basis": "estimate"}
              ],
              "proposal": "{\"op\":\"set_budget_target\",\"category\":\"groceries\",\"cents\":40000}"
            }
        """.trimIndent()

        val answer = AdvisorAnswer.parse(raw)

        assertNotNull(answer)
        assertEquals("You're at 312 of your 400 grocery budget.", answer!!.spoken)
        assertEquals(2, answer.figures.size)
        assertEquals(FigureBasis.RECORD, answer.figures[0].basis)
        assertEquals(FigureBasis.ESTIMATE, answer.figures[1].basis)
        assertTrue(answer.proposal!!.contains("set_budget_target"))
    }

    @Test
    fun `parses a response with no figures and no proposal`() {
        val raw = """{"spoken": "You're on track.", "figures": [], "proposal": null}"""
        val answer = AdvisorAnswer.parse(raw)
        assertNotNull(answer)
        assertEquals("You're on track.", answer!!.spoken)
        assertTrue(answer.figures.isEmpty())
        assertNull(answer.proposal)
    }

    @Test
    fun `proposal field can be omitted entirely`() {
        val raw = """{"spoken": "Nothing to propose."}"""
        val answer = AdvisorAnswer.parse(raw)
        assertNotNull(answer)
        assertNull(answer!!.proposal)
    }

    @Test
    fun `strips a json-tagged markdown fence before parsing`() {
        val raw = "```json\n{\"spoken\": \"fenced\"}\n```"
        val answer = AdvisorAnswer.parse(raw)
        assertNotNull(answer)
        assertEquals("fenced", answer!!.spoken)
    }

    @Test
    fun `strips a bare markdown fence before parsing`() {
        val raw = "```\n{\"spoken\": \"fenced\"}\n```"
        val answer = AdvisorAnswer.parse(raw)
        assertNotNull(answer)
        assertEquals("fenced", answer!!.spoken)
    }

    @Test
    fun `missing spoken field fails to parse rather than defaulting`() {
        val raw = """{"figures": []}"""
        assertNull(AdvisorAnswer.parse(raw))
    }

    @Test
    fun `blank spoken field fails to parse`() {
        val raw = """{"spoken": "   "}"""
        assertNull(AdvisorAnswer.parse(raw))
    }

    @Test
    fun `a figure with an unrecognised basis fails the whole parse - never defaults silently`() {
        val raw = """
            {"spoken": "x", "figures": [{"label": "a", "value": "1", "basis": "vibes"}]}
        """.trimIndent()
        assertNull(AdvisorAnswer.parse(raw))
    }

    @Test
    fun `malformed json fails to parse`() {
        assertNull(AdvisorAnswer.parse("not json at all"))
    }

    @Test
    fun `figure basis round-trips through its wire value`() {
        assertEquals(FigureBasis.RECORD, FigureBasis.fromWire("record"))
        assertEquals(FigureBasis.ESTIMATE, FigureBasis.fromWire("estimate"))
        assertEquals(FigureBasis.PLAYBOOK, FigureBasis.fromWire("playbook"))
        assertNull(FigureBasis.fromWire("nonsense"))
        assertNull(FigureBasis.fromWire(null))
    }

    @Test
    fun `stripFence leaves already-plain json untouched`() {
        val raw = """{"spoken": "plain"}"""
        assertEquals(raw, AdvisorAnswer.stripFence(raw))
    }
}
