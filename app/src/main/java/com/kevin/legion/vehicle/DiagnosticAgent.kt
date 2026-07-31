package com.kevin.legion.vehicle

import android.content.Context
import com.kevin.legion.ai.AgentResult
import com.kevin.legion.ai.CompanionIdentity
import com.kevin.legion.ai.SubAgent

/**
 * The OBD-II diagnostics specialist - the first investigating [SubAgent] the
 * Live voice loop delegates to. Given the stored trouble codes plus the driver's
 * question, it PULLS the car's own recorded data through [CarToolbelt]
 * (freeze-frame code history, live readings, trends, readiness, specs, chassis
 * quirks) and web_lookup for model-specific failure patterns, then explains what
 * each code means, the likely causes, urgency, and the typical fix. The Live
 * model ([com.kevin.legion.service.GeminiLiveSession]) speaks the result.
 *
 * Reading the codes off the port is the caller's job (see
 * [com.kevin.legion.service.LiveToolbox]); this object owns the reasoning.
 */
object DiagnosticAgent {

    // Per call, not `by lazy`: the identity clause depends on whether the driver
    // has named their car (CompanionIdentity), so a cached agent would pin the
    // identity current at first use for the whole process.
    private fun agent(context: Context) =
        SubAgent(systemInstruction = system(context), useSearch = true)

    /**
     * Diagnoses [codes] for [vehicleLabel] (e.g. "2003 BMW 330i", or blank if the
     * car isn't registered), answering the driver's [question]. Investigates via
     * the diagnostics toolbelt; falls back to a one-shot answer on a soft failure.
     */
    suspend fun diagnose(
        context: Context,
        vehicleLabel: String,
        codes: List<String>,
        question: String,
    ): AgentResult {
        val ctx = buildString {
            if (vehicleLabel.isNotBlank()) append("Vehicle: ").append(vehicleLabel).append(".\n")
            if (codes.isNotEmpty()) {
                append("OBD-II trouble codes currently stored: ").append(codes.joinToString(", ")).append(".\n")
            } else {
                append("No stored trouble codes were read from the port.\n")
            }
        }
        val q = question.ifBlank { "What do these codes mean and what should I do about them?" }
        val agent = agent(context)
        return when (val r = agent.investigate(ctx, q, CarToolbelt.forDiagnostics(context))) {
            AgentResult.Failed, AgentResult.Overloaded -> fallback(agent, ctx, q)
            else -> r
        }
    }

    private suspend fun fallback(agent: SubAgent, ctx: String, q: String): AgentResult {
        val text = agent.ask(ctx, q)
        return if (text != null) AgentResult.Success(text) else AgentResult.Failed
    }

    /**
     * Short, screen-readable info per code for the visual DTC sheet (as opposed
     * to [diagnose]'s spoken deep-dive). One call covers all [codes]; returns a
     * map of code â†’ (title, plain-English detail), or null on failure. The
     * caller caches results (see the DTC sheet), so each code is asked at most
     * once per install.
     */
    suspend fun describeCodes(
        appContext: Context,
        vehicleLabel: String,
        codes: List<String>,
    ): Map<String, Pair<String, String>>? {
        if (codes.isEmpty()) return emptyMap()
        val context = buildString {
            if (vehicleLabel.isNotBlank()) append("Vehicle: ").append(vehicleLabel).append(".\n")
            append("OBD-II trouble codes: ").append(codes.joinToString(", ")).append(".\n")
        }
        val q = "For EACH code above, output exactly one line in this format and nothing else:\n" +
            "CODE|Official short name|Two to three plain-English sentences: what it means for this " +
            "vehicle, the most likely cause, and how urgent it is.\n" +
            "Example: P0301|Cylinder 1 Misfire Detected|Cylinder 1 is misfiring. On this engine the " +
            "usual culprits are a worn spark plug or a failing coil. Fine to drive short distances " +
            "but fix it soon, since raw fuel can damage the catalytic converter."
        val raw = agent(appContext).ask(context, q) ?: return null
        val out = LinkedHashMap<String, Pair<String, String>>()
        for (line in raw.lines()) {
            val parts = line.split("|", limit = 3)
            if (parts.size == 3) {
                val code = parts[0].trim().uppercase()
                if (codes.any { it.equals(code, ignoreCase = true) }) {
                    out[code] = parts[1].trim() to parts[2].trim()
                }
            }
        }
        return out.ifEmpty { null }
    }

    // Identity from CompanionIdentity, never restated here - see its doc.
    private fun system(context: Context) =
        CompanionIdentity.shortClause(context) + " " +
            "You are reasoning about this car's trouble codes - not an outside specialist consulted " +
            "about a vehicle. You are given the OBD-II trouble codes stored on the car plus the driver's " +
            "question, and you answer about THOSE codes. For each relevant code give: what it means " +
            "in plain language, the most likely causes (most likely first), how urgent it really is, " +
            "and the typical fix. Use web_lookup for " +
            "model-specific failure patterns - many codes are manufacturer-specific, so weight toward " +
            "this exact year, make, and model. Keep it concise and spoken-friendly: short sentences, " +
            "plain text only (no markdown, asterisks, headings, or bullet characters), since your " +
            "answer is read aloud to someone driving. Lead with the bottom line, then the detail." +
            URGENCY_CALIBRATION + TOOLS_NOTE
}

/**
 * Shared urgency calibration for the diagnostic specialists (2026-07-16, Kevin's
 * field report).
 *
 * **The problem it fixes:** on the Cherokee, four stored codes produced "stop
 * driving immediately" and a warning about possibly irreparable damage. The codes
 * were mild and Kevin knew it. Nothing in the prompt was wrong, exactly - it
 * simply offered "stop driving now" as one of three urgency levels with no
 * calibration, and an LLM with no calibration defaults to the liability-averse
 * answer every time. Alarm is the model's failure mode, not its judgement.
 *
 * **Why this matters beyond one bad answer:** §1's whole promise is that the
 * companion NOTICES. A companion that cries wolf at a steady check-engine light
 * gets ignored or muted, and then it cannot notice anything. Being right about
 * "this can wait" is what buys the credibility to be believed about "pull over
 * now" - which is why the genuine stop-driving cases are enumerated here rather
 * than softened away.
 *
 * The steady-vs-flashing distinction is the real-world rule this encodes: a
 * flashing CEL means an active misfire dumping raw fuel into the catalytic
 * converter, which is a real reason to stop. A steady one, which is what a stored
 * code normally means, is not.
 */
internal const val URGENCY_CALIBRATION =
    " Calibrate urgency honestly, and default to calm. Most stored trouble codes are NOT an " +
        "emergency: a steady check-engine light means look into it soon, not pull over. Say plainly " +
        "when something can wait, and do not hedge that into a warning. " +
        "Reserve advice to stop driving for the genuinely dangerous cases and no others: a FLASHING " +
        "check-engine light (active misfire, which damages the catalytic converter), overheating, " +
        "loss of oil pressure, loss of charging, or anything affecting brakes or steering. If it is " +
        "not one of those, do not tell the driver to stop. " +
        "Several codes at once usually share ONE root cause - often something cheap like a vacuum " +
        "leak, a bad sensor, or a loose gas cap - so reason about the common cause rather than " +
        "treating each code as a separate problem and stacking up the alarm. " +
        "Never speculate about catastrophic or irreparable damage, and never pile worst cases on top " +
        "of each other. You notice things and say what they mean; you are not a warning label. This " +
        "driver knows their car."

/**
 * Shared tail appended to every investigating specialist's system prompt: how to
 * use the [CarToolbelt] tools without keeping the driver waiting.
 */
internal const val TOOLS_NOTE =
    " You have tools that pull this car's real recorded data. Pull only what would change your " +
        "answer - each call adds seconds while the driver waits; two or three pulls at most, then " +
        "answer. If a tool errors or returns nothing, say so briefly and work with what you have. " +
        "Plain spoken text only, no markdown."
