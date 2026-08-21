package com.kevin.legion.vehicle

import android.content.Context
import com.kevin.legion.ai.AgentResult
import com.kevin.legion.ai.AssistantIdentity
import com.kevin.legion.ai.SubAgent
import com.kevin.legion.data.local.MaintenanceItem
import com.kevin.legion.data.local.intervalIsUnconfirmed
import com.kevin.legion.data.local.provenanceWords
import com.kevin.legion.util.shortDate

/**
 * The maintenance specialist [SubAgent]. Pre-seeded with the vehicle, its current
 * mileage, and its scheduled maintenance (always needed), it then PULLS the rest
 * through [CarToolbelt] - logged service history (get_service_history), trends,
 * code history, oil analyses, and web_lookup for real procedures/capacities/
 * torque specs. The Live model speaks the result in character.
 */
object MaintenanceAgent {

    // Per call, not `by lazy`: the identity clause depends on whether the driver
    // has named their car, so a cached agent would pin the identity current at
    // first use for the whole process. A SubAgent is a thin REST holder.
    private fun agent(context: Context) =
        SubAgent(systemInstruction = system(context), useSearch = true)

    /**
     * Answers [question] about [vehicleLabel]'s maintenance, pre-seeded with its current
     * [mileageLabel] and scheduled [items]. Investigates via the maintenance toolbelt; one-shot
     * fallback on a soft failure.
     *
     * [mileageLabel] is [VehicleController.mileageLabel]'s already-formatted string
     * (`"227,900 mi"` / `"about 227,900 mi - estimated, last confirmed 3 days ago"`, or blank when
     * there is no reading at all yet) - ticket 10: this used to take a raw `Int` and caption it
     * `"(estimated)"` unconditionally here, which meant a driver's own just-typed reading was told
     * back to them as an estimate seconds after they gave it. The label now carries whichever is
     * actually true, computed once by the caller against the same rule every other surface uses.
     */
    suspend fun answer(
        context: Context,
        vehicleLabel: String,
        mileageLabel: String,
        items: List<MaintenanceItem>,
        question: String,
    ): AgentResult {
        val ctx = buildString {
            if (vehicleLabel.isNotBlank()) append("Vehicle: ").append(vehicleLabel).append(".\n")
            if (mileageLabel.isNotBlank()) append("Current odometer: ").append(mileageLabel).append(".\n")

            if (items.isNotEmpty()) {
                append("\nScheduled maintenance (service: interval; last done):\n")
                for (it in items) append("- ").append(describeItem(it)).append("\n")
            } else {
                append("\nNo maintenance schedule is on file for this vehicle yet.\n")
            }
        }
        val q = question.ifBlank { "What maintenance is due or coming up, and what should I take care of?" }
        val agent = agent(context)
        return when (val r = agent.investigate(ctx, q, CarToolbelt.forMaintenance(context))) {
            AgentResult.Failed, AgentResult.Overloaded -> {
                val text = agent.ask(ctx, q)
                if (text != null) AgentResult.Success(text) else AgentResult.Failed
            }
            else -> r
        }
    }

    /**
     * Renders one item's status. THREE distinct states, not two - "never been
     * done" ([MaintenanceItem.neverDone], a confirmed fact) and "last done
     * UNKNOWN" ([VehicleController.isUnknown], no anchor and not confirmed
     * either) used to collapse onto the same "never logged" string, which told
     * the agent - and the driver - a guess-worthy absence when one of them is
     * actually known.
     *
     * **This is the ACTUAL live formatter that pre-seeds [answer]'s prompt** - ticket 06 required a
     * seeded-interval disclosure on every surface that speaks or renders an interval and audited
     * `CarToolbelt.maintenanceSchedule` instead, which greps identically (`intervalMiles`/
     * `intervalMonths`, formatted almost the same way) but has zero callers anywhere in the tree
     * (see its tombstone comment in `VehicleController.kt`) - the audit counted a function nobody
     * calls and missed the one that actually feeds the model. Fixed mission-control ticket 16
     * (`.scratch/fleet-maintenance/issues/16-ticket-06-audited-a-dead-surface-and-missed-a-live-one.md`).
     * The interval clause now carries [MaintenanceItem.provenanceWords] in full words, spelled out
     * rather than abbreviated, whenever [MaintenanceItem.intervalIsUnconfirmed] - ticket 06's own
     * stated harm: "feeding an unlabelled guess into a model that states it back confidently is how
     * an estimate launders itself into a fact." A `CONFIRMED` item gets no suffix (nothing to
     * disclose), and an item with no interval at all reads "no interval on file" with no suffix
     * either - [intervalIsUnconfirmed]'s own second clause exists exactly because there is no number
     * there to doubt.
     *
     * `internal`, not `private`, ONLY so [MaintenanceAgentTest] can exercise this directly without
     * going through the network-calling [answer] - there is no other seam to test the three output
     * shapes (SEEDED / LOOKUP / CONFIRMED-or-no-interval) through.
     */
    internal fun describeItem(item: MaintenanceItem): String = buildString {
        append(item.serviceName).append(": ")
        val interval = listOfNotNull(
            item.intervalMiles?.let { "every ${"%,d".format(it)} mi" },
            item.intervalMonths?.let { "every $it mo" },
        ).joinToString(" / ").ifBlank { "no interval on file" }
        append(interval)
        if (item.intervalIsUnconfirmed) {
            item.provenanceWords?.let { append(" (").append(it).append(", unconfirmed by the user)") }
        }
        append("; last done ")
        append(
            when {
                item.neverDone -> "never been done"
                item.lastDoneMileage != null || item.lastDoneDate != null -> listOfNotNull(
                    item.lastDoneMileage?.let { "at ${"%,d".format(it)} mi" },
                    item.lastDoneDate?.let { "on ${shortDate(it)}" },
                ).joinToString(" ")
                else -> "UNKNOWN"
            }
        )
    }

    // Identity from AssistantIdentity, never restated here - see its doc.
    private fun system(context: Context) =
        AssistantIdentity.shortClause(context) + " " +
            "You are reasoning about this car's maintenance - not an outside specialist consulted " +
            "about a vehicle. You are given its current mileage - already labelled as an estimate " +
            "when it is one, bare when it's the user's own confirmed reading, never relabel it " +
            "yourself - and its scheduled maintenance (each item's interval and when it was last " +
            "done), plus the user's question. Pull the logged service history with get_service_history when it matters. " +
            "Work out what is due now, what is overdue and by how much, and when the next service is " +
            "due (by miles and/or time). If the user asks how to do a service or what it involves, " +
            "use web_lookup grounded to this exact year, make, and model for the real procedure, fluid " +
            "type/capacity, torque specs, and part numbers where useful. Be honest when the schedule " +
            "or last-done data is missing rather than guessing - an item marked UNKNOWN has no last-" +
            "done anchor at all, never assume or infer one; say it's unknown and offer to record it. " +
            "Keep it concise and spoken-friendly: " +
            "short sentences, plain text only (no markdown, asterisks, headings, or bullet " +
            "characters), since it is read aloud to someone driving. Lead with the bottom line, " +
            "then the detail." + TOOLS_NOTE
}
