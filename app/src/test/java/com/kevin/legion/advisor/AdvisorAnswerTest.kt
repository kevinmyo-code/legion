package com.kevin.legion.advisor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [AdvisorAnswer.parse] is the last-line gate on structured output regardless of what Gemini's
 * `responseSchema` enforces server-side (ticket 21: [SubAgent.askTyped] now DOES get a real
 * `generationConfig.responseSchema` from [AdvisorAnswer.responseSchema], but a schema narrows
 * malformed output, it doesn't guarantee it away - see [AdvisorAnswer.RESPONSE_SCHEMA]'s doc
 * comment for why the prose copy and the parser both stay).
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

    // --- responseSchema (ticket 21) -------------------------------------------------------------

    @Test
    fun `responseSchema declares OBJECT type with spoken required and figures, proposal optional`() {
        val schema = AdvisorAnswer.responseSchema()
        assertEquals("OBJECT", schema.getString("type"))

        val required = schema.getJSONArray("required")
        assertEquals(1, required.length())
        assertEquals("spoken", required.getString(0))

        val props = schema.getJSONObject("properties")
        assertTrue(props.has("spoken"))
        assertTrue(props.has("figures"))
        assertTrue(props.has("proposal"))
    }

    @Test
    fun `responseSchema types every leaf with an uppercase Gemini Type name, never lowercase JSON Schema`() {
        val schema = AdvisorAnswer.responseSchema()
        val props = schema.getJSONObject("properties")

        assertEquals("STRING", props.getJSONObject("spoken").getString("type"))
        assertEquals("ARRAY", props.getJSONObject("figures").getString("type"))
        assertEquals("STRING", props.getJSONObject("proposal").getString("type"))

        val figureItem = props.getJSONObject("figures").getJSONObject("items")
        assertEquals("OBJECT", figureItem.getString("type"))
        val figureProps = figureItem.getJSONObject("properties")
        assertEquals("STRING", figureProps.getJSONObject("label").getString("type"))
        assertEquals("STRING", figureProps.getJSONObject("value").getString("type"))
        assertEquals("STRING", figureProps.getJSONObject("basis").getString("type"))
    }

    @Test
    fun `responseSchema constrains figures basis to the three FigureBasis wire values`() {
        val schema = AdvisorAnswer.responseSchema()
        val basisEnum = schema.getJSONObject("properties")
            .getJSONObject("figures").getJSONObject("items")
            .getJSONObject("properties").getJSONObject("basis")
            .getJSONArray("enum")

        val values = (0 until basisEnum.length()).map { basisEnum.getString(it) }
        assertEquals(listOf("record", "estimate", "playbook"), values)
        // Every FigureBasis wire value is represented, and nothing extra.
        assertEquals(FigureBasis.values().map { it.wire }.toSet(), values.toSet())
    }

    @Test
    fun `responseSchema marks proposal nullable rather than required`() {
        val schema = AdvisorAnswer.responseSchema()
        val proposal = schema.getJSONObject("properties").getJSONObject("proposal")
        assertTrue(proposal.getBoolean("nullable"))

        val required = schema.getJSONArray("required")
        val requiredNames = (0 until required.length()).map { required.getString(it) }
        assertFalse("proposal must stay optional - RESPONSE_SCHEMA's prose allows omitting it", requiredNames.contains("proposal"))
        assertFalse("figures must stay optional - an answer with nothing to cite is valid", requiredNames.contains("figures"))
    }

    @Test
    fun `responseSchema builds a fresh JSONObject per call, not a shared mutable instance`() {
        val first = AdvisorAnswer.responseSchema()
        val second = AdvisorAnswer.responseSchema()
        assertTrue(first !== second)
        assertEquals(first.toString(), second.toString())
    }
}
