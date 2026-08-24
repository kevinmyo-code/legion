package com.kevin.legion.vehicle

import android.content.Context
import com.kevin.legion.ai.AgentResult
import com.kevin.legion.ai.AssistantIdentity
import com.kevin.legion.ai.SubAgent
import com.kevin.legion.data.local.CarDatabase
import com.kevin.legion.data.local.MaintenanceItem
import com.kevin.legion.data.local.ServiceRecord
import com.kevin.legion.data.local.intervalIsUnconfirmed
import com.kevin.legion.data.local.provenanceWords
import com.kevin.legion.util.shortDate
import kotlin.math.abs

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
     *
     * [vehicleId] is [items]' own `Vehicle.obdMac` (ticket 28,
     * `.scratch/hands-and-senses/issues/28-the-oil-change-it-forgot.md`) - needed to look up
     * `service_records` per item so [describeItem] can derive "last done" from a logged event
     * rather than trusting `maintenance_items`' anchor columns alone. See [describeItem]'s own doc
     * for why that lookup exists at all.
     */
    suspend fun answer(
        context: Context,
        vehicleLabel: String,
        mileageLabel: String,
        items: List<MaintenanceItem>,
        question: String,
        vehicleId: String,
    ): AgentResult {
        val ctx = buildString {
            if (vehicleLabel.isNotBlank()) append("Vehicle: ").append(vehicleLabel).append(".\n")
            if (mileageLabel.isNotBlank()) append("Current odometer: ").append(mileageLabel).append(".\n")

            if (items.isNotEmpty()) {
                append("\nScheduled maintenance (service: interval; last done):\n")
                for (it in items) {
                    // Only items with something to gain from the lookup pay for it - an item that
                    // is neverDone or already carries its own dated anchor has nothing a record
                    // would add (see describeItem's doc on why the anchor still wins when it is
                    // itself the newer fact), so this stays one query per item that actually needs
                    // one rather than N queries per schedule regardless of whether they help.
                    val record = if (!it.neverDone) FleetEngineStore.mostRecentForVehicleAndService(context, vehicleId, it.serviceName) else null
                    append("- ").append(describeItem(it, record)).append("\n")
                }
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
     *
     * **[record] and the two-store problem (ticket 28,
     * `.scratch/hands-and-senses/issues/28-the-oil-change-it-forgot.md`).** `service_records` and
     * `maintenance_items`' anchor columns are two independent stores of what should be one fact -
     * `service_records` holds the actual dated EVENTS the driver logged, the anchor is a CACHE of
     * "the latest one" that a write path updates alongside it. On Kevin's own phone the cache went
     * stale (a mileage-only write nulled the anchor's date - see
     * [VehicleController.mergeBackfillAnchors]) while the real event sat untouched in
     * `service_records`, and this function - the one that actually pre-seeds [answer]'s prompt -
     * consulted only the cache, so the assistant told him it had no record of an oil change it had
     * on file. **[record] exists so the cache is not the only source consulted: when the anchor
     * carries no date of its own, the most recent non-deleted record IS the fact, and the answer
     * should say so rather than fall to UNKNOWN.** This is a read-side fix, not the real one - the
     * two stores should be one, and hands-and-senses ticket 29 (charted after this ticket, not
     * built by it) owns collapsing them. Do not re-derive this diagnosis from scratch; read this
     * comment first.
     *
     * Three ways [record] gets used, in honesty order (never merge a disagreement into a single
     * fabricated fact - CLAUDE.md §4's reconciliation posture applied to speech rather than money):
     *  - Anchor already carries its own date **at least as new as [record]'s** - the anchor is
     *    itself the newer, self-consistent fact (a real backfill for a service done after the last
     *    logged record); it wins, unchanged from the pre-ticket-28 behaviour.
     *  - Anchor has no date but [record] does, and either the anchor has no mileage of its own or
     *    its mileage is within [PLAUSIBLE_MILEAGE_DRIFT] of [record]'s - they plausibly describe
     *    the SAME event, so one sentence, [record]'s date paired with the anchor's mileage when it
     *    has one (the more specific of two numbers describing the same fact).
     *  - Anchor and [record] disagree beyond plausible drift (the Jeep: anchor 227,483 mi/no date,
     *    record 227,374 mi/12 Aug, 109 miles apart) - **state both facts rather than pick one**; a
     *    merged "227,483 mi on 12 Aug" asserts an event that never happened at that mileage.
     */
    internal fun describeItem(item: MaintenanceItem, record: ServiceRecord? = null): String = buildString {
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
        append(lastDoneClause(item, record))
    }

    /** Miles apart an anchor's own mileage and a record's may be while still describing one event
     * (odometer rounding, a reading taken a day either side of the shop visit) rather than two
     * different services conflated by name. 109 miles - the Jeep's own real gap between its
     * 227,483 mi anchor and its 227,374 mi record - is exactly the case this constant must NOT
     * absorb; picked well below it so the honest-disagreement branch still fires on that real
     * data, not just in theory. */
    private const val PLAUSIBLE_MILEAGE_DRIFT = 15

    /**
     * The "last done" half of [describeItem], split out so its branches - and the honesty rule
     * governing them - can be read (and tested) on their own. See [describeItem]'s own doc for the
     * two-store reasoning behind why [record] is consulted at all.
     */
    private fun lastDoneClause(item: MaintenanceItem, record: ServiceRecord?): String {
        if (item.neverDone) return "never been done"

        // The anchor is itself a complete, self-consistent, at-least-as-new fact: either there is
        // no record to compare against, or the anchor's own date is not older than the record's -
        // mergeBackfillAnchors' "supplying both keeps both" rule means a dated anchor's mileage and
        // date came from the SAME driver statement, so there is nothing to reconcile it against.
        if (item.lastDoneDate != null && (record == null || item.lastDoneDate >= record.date)) {
            return listOfNotNull(
                item.lastDoneMileage?.let { "at ${"%,d".format(it)} mi" },
                "on ${shortDate(item.lastDoneDate)}",
            ).joinToString(" ")
        }

        // No record to derive from, and the anchor above already handled every case with a date -
        // what is left is a dateless anchor with no record either, or no anchor at all: mileage
        // alone if there is one, else the genuine unknown.
        if (record == null) {
            return item.lastDoneMileage?.let { "at ${"%,d".format(it)} mi" } ?: "UNKNOWN"
        }

        // record != null and the anchor's own date is either absent or older than record's date -
        // the record is the more recent (or the only) known event, so it is the derivation.
        val recordClause = "at ${"%,d".format(record.mileage)} mi on ${shortDate(record.date)}"
        val anchorMileage = item.lastDoneMileage
        return when {
            anchorMileage == null -> recordClause
            abs(anchorMileage - record.mileage) <= PLAUSIBLE_MILEAGE_DRIFT ->
                "at ${"%,d".format(anchorMileage)} mi on ${shortDate(record.date)}"
            else ->
                "logged at ${"%,d".format(record.mileage)} mi on ${shortDate(record.date)}; the maintenance " +
                    "clock was later set to ${"%,d".format(anchorMileage)} mi"
        }
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
            "done anchor AND no logged service record at all, never assume or infer one; say it's " +
            "unknown and offer to record it. " +
            "Keep it concise and spoken-friendly: " +
            "short sentences, plain text only (no markdown, asterisks, headings, or bullet " +
            "characters), since it is read aloud to someone driving. Lead with the bottom line, " +
            "then the detail." + TOOLS_NOTE
}
