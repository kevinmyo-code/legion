package com.kevin.legion.vehicle

import android.content.Context
import com.kevin.legion.ai.AgentResult
import com.kevin.legion.ai.AssistantIdentity
import com.kevin.legion.ai.SubAgent

/**
 * The symptom-triage specialist [SubAgent] - the "ask the car what's wrong in
 * plain English" worker. Where [DiagnosticAgent] explains a NAMED trouble code,
 * this one reasons from a description of how the car is *behaving* (a noise,
 * smell, vibration, leak, rough idle, hard start, loss of power, warning-light
 * behavior). It PULLS the car's recorded data through [CarToolbelt] (code
 * history, live readings, service history, trends, readiness, specs, quirks) and
 * web_lookup for model-specific weak points. The Live model speaks the result.
 *
 * Nothing CarPlay/Android Auto or a generic assistant can do - it needs the OBD
 * sensor AND car-specific grounding.
 */
object SymptomAgent {

    // Per call, not `by lazy`: see AssistantIdentity - the identity clause is not
    // a constant, so a cached agent would pin whichever was current at first use.
    private fun agent(context: Context) =
        SubAgent(systemInstruction = system(context), useSearch = true)

    /**
     * Triages [symptom] for [vehicleLabel], pre-seeded with a cheap live snapshot
     * ([liveReadings]) and stored [codes] to save a round. Investigates via the
     * symptom toolbelt; one-shot fallback on a soft failure.
     */
    suspend fun triage(
        context: Context,
        vehicleLabel: String,
        liveReadings: String,
        codes: List<String>,
        symptom: String,
    ): AgentResult {
        val ctx = buildString {
            if (vehicleLabel.isNotBlank()) append("Vehicle: ").append(vehicleLabel).append(".\n")
            append("Reported symptom: ").append(symptom.ifBlank { "(none given)" }).append("\n")
            if (liveReadings.isNotBlank()) append("Live readings: ").append(liveReadings).append("\n")
            append(
                if (codes.isNotEmpty()) "Stored trouble codes: ${codes.joinToString(", ")}.\n"
                else "No stored trouble codes were read from the port.\n"
            )
        }
        val q = "Triage this symptom: the most likely causes (most likely first), how urgent it " +
            "is, and what to check or do next."
        val agent = agent(context)
        return when (val r = agent.investigate(ctx, q, CarToolbelt.forSymptoms(context))) {
            AgentResult.Failed, AgentResult.Overloaded -> {
                val text = agent.ask(ctx, q)
                if (text != null) AgentResult.Success(text) else AgentResult.Failed
            }
            else -> r
        }
    }

    // Identity from AssistantIdentity, never restated here - see its doc.
    private fun system(context: Context) =
        AssistantIdentity.shortClause(context) + " " +
            "You are reasoning about this car's symptoms - not an outside specialist consulted " +
            "about a vehicle. The driver describes how the car is BEHAVING - a noise, smell, vibration, " +
            "leak, rough idle, hard start, loss of power, or how a warning light is acting - and you " +
            "are given any live readings and stored codes. Reason about the most " +
            "likely causes, ordered most-likely first, how urgent it really is, " +
            "and the first things to check. Use web_lookup grounded to " +
            "this exact year, make, and model, since many symptoms are model-specific (known weak " +
            "points, common failures). Do NOT ask clarifying questions - give the best ranked triage " +
            "from what you gather, and briefly note what would help narrow it down. Be honest about " +
            "uncertainty rather than guessing a single cause. Keep it concise and spoken-friendly: " +
            "short sentences, plain text only (no markdown, asterisks, headings, or bullet " +
            "characters), since it's read aloud to someone driving. Lead with the bottom line and " +
            "the urgency, then the detail." + URGENCY_CALIBRATION + TOOLS_NOTE
}
