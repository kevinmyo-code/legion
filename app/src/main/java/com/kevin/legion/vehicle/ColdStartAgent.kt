package com.kevin.legion.vehicle

import android.content.Context
import com.kevin.legion.ai.AgentResult
import com.kevin.legion.ai.AssistantIdentity
import com.kevin.legion.ai.SubAgent

/**
 * The cold-start analyst [SubAgent]: interprets the 60-second OBD burst
 * [TelemetryRecorder] captures after a cold engine start (RPM, coolant, fuel
 * trims, intake air temp) and compares it against prior cold starts. Warm-up
 * behavior is where O2 sensors, vacuum leaks, and aging catalysts show up
 * earliest - long before a code sets. The bursts are pre-gathered via
 * [CarToolbelt.coldStartReport] and reasoned over in a single one-shot (no
 * investigate loop - there's nothing to adaptively pull). No web search: pure
 * reasoning over the numbers. The Live model speaks the result.
 */
object ColdStartAgent {

    // Built per call, not `by lazy`: the identity clause depends on whether the
    // driver has named their car (AssistantIdentity), so a cached agent would pin
    // whichever identity was current at first use for the rest of the process.
    // A SubAgent is a thin holder around a REST call, so this costs nothing.
    private fun agent(context: Context) =
        SubAgent(systemInstruction = system(context), useSearch = false)

    /**
     * Analyzes the recorded cold starts for [vehicleLabel]. This is a one-shot
     * (not an investigate loop): its whole job is reasoning over the cold-start
     * bursts, so we pre-gather [CarToolbelt.coldStartReport] and answer in a
     * single POST rather than paying up to four rounds to adaptively re-pull the
     * same data. No web search either - pure numeric reasoning.
     */
    suspend fun analyze(context: Context, vehicleLabel: String): AgentResult {
        val ctx = buildString {
            if (vehicleLabel.isNotBlank()) append("Vehicle: ").append(vehicleLabel).append(".\n")
            append("Cold-start data:\n").append(CarToolbelt.coldStartReport(context))
        }
        val q = "How did the latest cold start look, and is anything drifting?"
        return agent(context).askTyped(ctx, q)
    }

    // Identity comes from AssistantIdentity, never restated here: Zero reasons
    // ABOUT the car's cold starts, a named car reasons about its OWN. Both need
    // the same numeric brief, so only the stance differs.
    private fun system(context: Context) =
        AssistantIdentity.shortClause(context) + " " +
            "You are reasoning about this car's cold starts - not an outside analyst consulted " +
            "about an enthusiast's car. You are given its recorded cold starts: OBD samples from the " +
            "first minute after a cold engine start - RPM, coolant temperature, short/long fuel " +
            "trims, intake air temperature. Judge the warm-up health: idle stability, enrichment " +
            "behavior (trims), warm-up rate. Compare the latest against prior cold starts and say " +
            "plainly whether anything is drifting - worn O2 sensors, vacuum leaks, and aging catalysts " +
            "show up here first. Like a knowledgeable friend, 2-4 sentences, no lists, no headers. " +
            "Frame findings as things you noticed, never as predictions. Plain spoken text only, " +
            "no markdown."
}
