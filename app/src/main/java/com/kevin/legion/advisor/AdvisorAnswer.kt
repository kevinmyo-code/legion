package com.kevin.legion.advisor

import org.json.JSONArray
import org.json.JSONObject

/**
 * How a figure in an [AdvisorAnswer] was arrived at. Structural, not prose - ticket 10's "From
 * law, not asked" section is explicit that the estimate/unverified label is rendered from THIS
 * field by [DigestText] at read/render time, never trusted to survive in the model's own words.
 * [RECORD] means the figure came straight off the digest (a real, possibly [PLAYBOOK]-agnostic
 * fact); [ESTIMATE] means the advisor is guessing or extrapolating (CLAUDE.md §4 rule 5);
 * [PLAYBOOK] means it is domain guidance, not this person's own data (a suggested safe range, not
 * a measurement).
 */
enum class FigureBasis(val wire: String) {
    RECORD("record"),
    ESTIMATE("estimate"),
    PLAYBOOK("playbook"),
    ;

    companion object {
        fun fromWire(value: String?): FigureBasis? = values().firstOrNull { it.wire == value }
    }
}

/** One number or fact the advisor cites in its answer, carrying [basis] so a renderer decides how
 * to label it rather than trusting the model's prose to have labelled it correctly. */
data class AdvisorFigure(
    val label: String,
    val value: String,
    val basis: FigureBasis,
)

/**
 * The structured result of one [AdvisorAgent] exchange (ticket 01 answer call 3: "returns prose
 * plus an optional structured proposal for the write-back path"). [proposal] rides as a raw JSON
 * string, unopened - ticket 18 owns the concrete operation shapes a proposal can take; this
 * harness only needs to carry whatever the model returned through to
 * [com.kevin.legion.data.local.AdvisorAdvice.proposalJson] and back out again unmodified.
 */
data class AdvisorAnswer(
    /** Short, voice-length spoken answer - [HarnessPrompt] instructs "this is a voice path". */
    val spoken: String,
    val figures: List<AdvisorFigure> = emptyList(),
    /** A proposed write, as raw JSON text, or null for an exchange that proposed nothing. Never
     * asserted as already-applied - see [HarnessPrompt]'s propose-never-assert rule. */
    val proposal: String? = null,
) {
    companion object {
        /**
         * The response shape composed into every [HarnessPrompt]-governed call, enforced by
         * INSTRUCTION in the prompt text. **As of ticket 21 this is belt, not the whole harness**:
         * [com.kevin.legion.ai.SubAgent.askTyped] now also accepts a real
         * `responseSchema`/`responseMimeType` (`generationConfig`), and [responseSchema] below is
         * the machine-enforced braces half - [AdvisorAgent] passes both, on purpose. Kept here
         * rather than deleted: a `generationConfig` schema constrains shape but not English
         * meaning (nothing in it says a `figures[].basis` of `"record"` must actually be a real
         * digest figure and not a guess), and Kevin's ticket note is explicit not to remove this
         * copy without evidence the model still complies without it, which this build has no way
         * to gather (no live key in this environment - see ticket 21 item 4). [parse] is still the
         * actual gate either way: text that does not parse into this shape is a hard failure for
         * the harness to surface, never a best-effort partial read of whatever came back.
         */
        const val RESPONSE_SCHEMA = """
Respond with ONLY one JSON object, no prose outside it, no markdown code fence, shaped exactly:
{
  "spoken": "<short, voice-length spoken answer>",
  "figures": [
    {"label": "<what this number or fact is>", "value": "<the number or fact, as text>", "basis": "record|estimate|playbook"}
  ],
  "proposal": "<a JSON string describing a proposed write, or omit this field entirely if you are proposing nothing>"
}
"""

        /**
         * [RESPONSE_SCHEMA]'s prose contract, translated into the Gemini `responseSchema` object
         * (an OpenAPI-3.0 SUBSET - see [com.kevin.legion.ai.StructuredOutputRequest]'s doc comment
         * for the accepted field list, checked against it deliberately before writing this rather
         * than guessed). Two translation choices worth naming:
         *  - `figures` and `proposal` are NOT in `required` - [RESPONSE_SCHEMA]'s prose already
         *    allows an empty/omitted `figures` array and an omitted `proposal`, and [parse] treats
         *    a missing `figures` as empty and a missing/blank `proposal` as null, so making either
         *    required here would fight the parser's own leniency rather than mirror it.
         *  - `proposal` carries `"nullable": true` alongside `"type": "STRING"` - Gemini distinguishes
         *    "this key may be absent" (an optional, non-required property) from "this key's value
         *    may literally be JSON `null`"; the model has been seen doing either for "nothing
         *    proposed", and [parse] already accepts both (`!obj.has("proposal") || obj.isNull(...)`).
         *
         * Returns a fresh [JSONObject] on every call rather than a shared constant - a [JSONObject]
         * is mutable, and nothing here should risk one caller's request body sharing live state
         * with another's.
         */
        fun responseSchema(): JSONObject = JSONObject().apply {
            put("type", "OBJECT")
            put("properties", JSONObject().apply {
                put("spoken", JSONObject().apply {
                    put("type", "STRING")
                    put("description", "Short, voice-length spoken answer.")
                })
                put("figures", JSONObject().apply {
                    put("type", "ARRAY")
                    put("description", "Numbers or facts cited in the answer, if any.")
                    put("items", JSONObject().apply {
                        put("type", "OBJECT")
                        put("properties", JSONObject().apply {
                            put("label", JSONObject().apply {
                                put("type", "STRING")
                                put("description", "What this number or fact is.")
                            })
                            put("value", JSONObject().apply {
                                put("type", "STRING")
                                put("description", "The number or fact, as text.")
                            })
                            put("basis", JSONObject().apply {
                                put("type", "STRING")
                                put("enum", JSONArray().put("record").put("estimate").put("playbook"))
                            })
                        })
                        put("required", JSONArray().put("label").put("value").put("basis"))
                        put("propertyOrdering", JSONArray().put("label").put("value").put("basis"))
                    })
                })
                put("proposal", JSONObject().apply {
                    put("type", "STRING")
                    put("nullable", true)
                    put("description", "A JSON string describing a proposed write, or omitted/null if proposing nothing.")
                })
            })
            put("required", JSONArray().put("spoken"))
            put("propertyOrdering", JSONArray().put("spoken").put("figures").put("proposal"))
        }

        /**
         * Parses one exchange out of the model's raw response text. Tolerates a
         * ```json ... ``` or ``` ... ``` fence around the object (Flash wraps one often enough to
         * be worth stripping defensively) but otherwise requires the exact shape [RESPONSE_SCHEMA]
         * asks for - a response missing `spoken`, or carrying a `figures` entry with an
         * unrecognised `basis`, returns null rather than guessing a default, so a caller can
         * distinguish "the model answered but the shape broke" from "the model actually said
         * this".
         */
        fun parse(raw: String): AdvisorAnswer? {
            val stripped = stripFence(raw)
            return try {
                val obj = JSONObject(stripped)
                val spoken = obj.optString("spoken").takeIf { it.isNotBlank() } ?: return null
                val figuresJson = obj.optJSONArray("figures") ?: JSONArray()
                val figures = mutableListOf<AdvisorFigure>()
                for (i in 0 until figuresJson.length()) {
                    val f = figuresJson.optJSONObject(i) ?: return null
                    val basis = FigureBasis.fromWire(f.optString("basis")) ?: return null
                    figures.add(AdvisorFigure(label = f.optString("label"), value = f.optString("value"), basis = basis))
                }
                val proposal = if (obj.has("proposal") && !obj.isNull("proposal")) {
                    obj.optString("proposal").takeIf { it.isNotBlank() }
                } else null
                AdvisorAnswer(spoken = spoken, figures = figures, proposal = proposal)
            } catch (e: Exception) {
                null
            }
        }

        /** Strips a leading/trailing ```json or plain ``` fence, if present. Internal (not
         * private) so a unit test can hit it directly without round-tripping through [parse]. */
        internal fun stripFence(raw: String): String {
            var text = raw.trim()
            if (text.startsWith("```")) {
                text = text.removePrefix("```json").removePrefix("```").trim()
                if (text.endsWith("```")) text = text.removeSuffix("```").trim()
            }
            return text
        }
    }
}
