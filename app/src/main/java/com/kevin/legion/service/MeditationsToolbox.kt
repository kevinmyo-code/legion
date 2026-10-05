package com.kevin.legion.service

import android.content.Context
import com.kevin.legion.meditations.Meditations
import com.kevin.legion.meditations.MeditationsLookup
import com.kevin.legion.meditations.MeditationsSearch
import org.json.JSONArray
import org.json.JSONObject

/**
 * `consult_meditations`: the pull-based grounding for the Marcus companion (Kevin, 2026-10-04:
 * "ingest the entirety of meditations and try to make the persona answer as close as possible to
 * how real marcus would have").
 *
 * **Why a tool and not the book in the prompt.** The Live setup message is re-sent on every
 * reconnect (see `LiveSetupPayloadSizeTest`) and the text is ~63,000 estimated tokens, so pasting it
 * is not on the table; CLAUDE.md section 7 says pull-based tools always. The persona clause
 * ([com.kevin.legion.ai.MARCUS]) carries the manner, written once and short; this tool carries the
 * words, fetched when a question needs them.
 *
 * **Declared only while Marcus is the active companion** ([declarations] is appended by
 * [LiveToolbox.declarations] when its persona key is Marcus). The declaration costs the whole setup
 * payload 676 chars, ~169 estimated tokens, on every turn (measured by `MarcusPayloadTest`), and
 * `LiveSetupPayloadSizeTest`'s ceiling has ~221 of headroom (22,279 against 22,500): declaring it
 * always would spend most of that on three companions that can never use it and would trip the
 * ceiling on the next tool anyone adds. A switch of companion rebuilds the
 * socket (ADR 0047), so the declaration set follows the active companion. [dispatch] itself does
 * not check who is active, so a stale call from a session opened before a switch still answers.
 *
 * **Honesty rule this tool exists to make possible.** The persona may quote only text this tool
 * returned in the same turn, with the Book and section it gave. A quotation recalled from the
 * model's own training is exactly the failure to prevent: the Meditations are quoted everywhere
 * in modern wording, Gregory Hays's copyrighted translation among it, and a model asked to "quote
 * Marcus" will produce a confident blend that is neither Long's text nor verifiable. Every
 * passage returned here is verbatim from the bundled file (an excerpt of a long section says so).
 */
object MeditationsToolbox {

    const val TOOL_NAME = "consult_meditations"

    /** The persona key whose companion gets the tool. Matches `Persona.key` of [com.kevin.legion.ai.MARCUS]. */
    const val PERSONA_KEY = "marcus"

    /** What the model is told about the tool; kept to the rules that matter, it is paid for every turn. */
    private const val DESCRIPTION =
        "Look up what the Emperor actually wrote in the Meditations (George Long's translation) on a " +
            "topic or question, or fetch one passage by reference like 'Book IV, 3'. Returns up to three " +
            "passages with Book and section. Call it BEFORE quoting yourself or citing the book: quote only " +
            "words it returned this turn, with the cite it gave. Anything else is paraphrase and must be " +
            "said as paraphrase. If nothing matched, say so and do not quote from memory."

    fun declarations(): JSONArray = JSONArray().put(
        // The literal name below is what tools/voice_guide.py scans for (`name = "..."`), so it stays
        // a literal here rather than TOOL_NAME; MeditationsToolboxTest pins the two together.
        fn(
            name = "consult_meditations",
            description = DESCRIPTION,
            property = "query" to "The topic or question in plain words, or a reference like 'Book IV, 3'.",
        ),
    )

    private fun fn(name: String, description: String, property: Pair<String, String>): JSONObject =
        JSONObject()
            .put("name", name)
            .put("description", description)
            .put(
                "parameters",
                JSONObject()
                    .put("type", "object")
                    .put(
                        "properties",
                        JSONObject().put(
                            property.first,
                            JSONObject().put("type", "string").put("description", property.second),
                        ),
                    )
                    .put("required", JSONArray().put(property.first)),
            )

    /** Runs the tool, or returns null when [name] is not this tool so the caller can fall through. */
    fun dispatch(context: Context, name: String, args: JSONObject): JSONObject? {
        if (name != TOOL_NAME) return null
        return consult(Meditations.search(context), args.optString("query"))
    }

    /** The decision half, with the index passed in so a test needs neither a Context nor an asset. */
    internal fun consult(index: MeditationsSearch, query: String): JSONObject =
        when (val outcome = MeditationsLookup.run(index, query)) {
            MeditationsLookup.Outcome.Blank ->
                result(false, "No question was given, so nothing was looked up.")
            // The text has passages about leaving life (the "door is open" strain of Stoic talk).
            // They are history, not counsel, and never an answer to a person in distress.
            // CrisisDetector is the code-level backstop on what the user SAID; this is the same
            // check on what the model is about to look up, so a distressed turn gets the safety
            // path and not a Stoic quotation.
            MeditationsLookup.Outcome.Distress -> result(
                false,
                "Nothing was looked up. This is not a question for the book: stop speaking as the " +
                    "Emperor, say plainly that you are not equipped for this, and give the real resource " +
                    "from your instructions.",
            )
            MeditationsLookup.Outcome.NoMatch -> result(
                true,
                "Nothing in the Meditations matched that. Say so in your own words; do not quote or " +
                    "attribute anything from memory.",
            ).put("found", false)
            is MeditationsLookup.Outcome.Found -> found(outcome.hits)
        }

    private fun found(hits: List<MeditationsSearch.Hit>): JSONObject {
        val passages = JSONArray()
        for (h in hits) {
            passages.put(
                JSONObject()
                    .put("cite", h.passage.cite)
                    .put("text", h.text)
                    .put("excerpt", h.isExcerpt),
            )
        }
        return JSONObject()
            .put("success", true)
            .put("found", true)
            .put("translation", "George Long, 1862")
            .put("passages", passages)
            .put(
                "note",
                "Quote only these words, with the cite beside each. An excerpt is part of a longer " +
                    "section. Say 'in the Meditations' only for what is here; your own wording is " +
                    "paraphrase and must be said as such. A passage about leaving life is never counsel " +
                    "for someone who is suffering.",
            )
    }

    private fun result(success: Boolean, message: String): JSONObject =
        JSONObject().put("success", success).put("message", message)
}
