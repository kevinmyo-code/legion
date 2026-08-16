package com.kevin.legion.vehicle

import android.content.Context
import com.kevin.legion.data.local.CarDatabase
import com.kevin.legion.data.local.MaintenanceItem

/**
 * Ticket 14's populate diff (`.scratch/fleet-maintenance/issues/14-populate-from-the-factory-schedule.md`):
 * "running a populate never writes directly. It produces a diff." This file is the whole
 * implementation of that sentence - a pure comparison of a factory-schedule LOOKUP against what is
 * already on file, with no side effects anywhere in it. Every write it can lead to happens later,
 * one row at a time, only when a driver explicitly accepts that row (`ui/fleet/PopulateWrites.kt`).
 *
 * **Three categories, per the ticket's own decision**, plus a fourth this file adds because the
 * ticket's own "watch for" section demands it: a tombstoned item the factory schedule still names
 * must be its own question ("you deleted this - add it back?"), never silently folded into
 * [PopulateDiff.wouldAdd] (which would resurrect something the driver deliberately removed) and
 * never silently dropped (which would make the delete look permanent when the driver never said
 * so).
 *
 * **A fifth category, added on review (ticket 14 review, BLOCKING 1b, 2026-08-15):
 * [possibleMatch].** [VehicleController.looksLikeExistingItem] only catches a factory name whose
 * canonical form EXACTLY matches an existing item's canonical form. Ticket 02's research found 26
 * distinct factory strings against ten (now seventeen) [VehicleController.SERVICE_KEYWORDS]
 * entries, so a concept outside the table gets NO exact match and used to fall straight into
 * [wouldAdd] - the identical duplicate-row mechanism ticket 01 measured, just for phrasing gaps the
 * keyword table has not been taught yet. [VehicleController.nearMissServiceName] catches the
 * likely case via token overlap, but "likely" is not "certain": a near-miss is never folded into
 * [wouldChange] (which would assert an identity match this comparator is not confident enough to
 * assert) and never left to land in [wouldAdd] (which would create the very duplicate this exists
 * to stop) - it gets its own row, its own question, and nothing writes until the driver answers it
 * (`ui/fleet/PopulateWrites.kt`'s `writePopulateMergeMatch`/`writePopulateAddAsNew`).
 */
data class PopulateDiff(
    /** In the factory schedule, not on file at all (neither active, tombstoned, nor a near-miss). */
    val wouldAdd: List<MaintenanceItem>,
    /** On file (any provenance) with a different interval than the factory proposes. */
    val wouldChange: List<PopulateChangeRow>,
    /**
     * On file, active, and the factory schedule does not list it - a delete is OFFERED, never
     * assumed. [PopulateNotInScheduleRow.intervalSource] is what the ticket calls "wording an
     * invented row differently from one Kevin added himself": `SEEDED` means LEGION guessed this
     * item into existence and the factory schedule has now shown it was never real (ticket 02's
     * `Brake Fluid Flush`, exactly); `CONFIRMED` means the driver typed or accepted it themselves,
     * and the factory simply not naming it is not evidence it does not belong on THIS car.
     */
    val notInFactorySchedule: List<MaintenanceItem>,
    /** Tombstoned on file, and the factory schedule still names it - "you deleted this, add it back?" */
    val wouldRestore: List<PopulateRestoreRow>,
    /**
     * A factory name that [VehicleController.looksLikeExistingItem] did NOT match exactly, but
     * [VehicleController.nearMissServiceName] thinks is probably the same job as an existing active
     * item - "this looks like X already on file, same thing?" Neither a would-add nor a
     * would-change by construction; see this file's own doc for why the distinction matters.
     */
    val possibleMatch: List<PopulatePossibleMatchRow> = emptyList(),
) {
    /** True when there is nothing left to review - every category empty. Drives the empty-state copy. */
    val isEmpty: Boolean
        get() = wouldAdd.isEmpty() && wouldChange.isEmpty() && notInFactorySchedule.isEmpty() &&
            wouldRestore.isEmpty() && possibleMatch.isEmpty()
}

/**
 * One "would change" row - current value beside proposed, **and who authored the current value**
 * (ticket 14's own words, quoting ticket 06's provenance flag). [currentSource] is the row's OWN
 * [MaintenanceItem.intervalSource] at diff time - `SEEDED` or `CONFIRMED` - carried through
 * unmodified rather than re-derived, so the UI never has to re-import ticket 06's vocabulary here.
 *
 * **A `CONFIRMED` row is never silently overwritten by appearing here.** This row is a QUESTION,
 * not a write - see this file's own doc and `ui/fleet/PopulateWrites.kt` for the write that only
 * happens once a driver taps accept. Showing a `CONFIRMED` row that differs is exactly what ticket
 * 05's "only something that names the change and takes a confirmation may touch a CONFIRMED row"
 * rule requires, not an exception to it - this diff IS that naming-and-confirming mechanism.
 */
data class PopulateChangeRow(
    val serviceName: String,
    val currentMiles: Int?,
    val currentMonths: Int?,
    val currentSource: String,
    val proposedMiles: Int?,
    val proposedMonths: Int?,
)

/** One "would restore" row - a tombstoned item the factory schedule still names, with its proposed interval. */
data class PopulateRestoreRow(
    val serviceName: String,
    val proposedMiles: Int?,
    val proposedMonths: Int?,
)

/**
 * One "possible match" row (ticket 14 review, BLOCKING 1b): [factoryName] is the factory
 * lookup's OWN wording (never rewritten - same "storage/display is verbatim" posture as
 * [PopulateRestoreRow]/[PopulateChangeRow]), [existingName] is the active item
 * [VehicleController.nearMissServiceName] thinks it is probably naming. Two answers, both
 * explicit (`ui/fleet/PopulateWrites.kt`):
 *  - "same thing" writes [proposedMiles]/[proposedMonths] onto [existingName] (a [PopulateChangeRow]
 *    in every way that matters, just keyed by a name the factory lookup never actually said).
 *  - "no, add as new" inserts [factoryName] verbatim as its own row - a [wouldAdd][PopulateDiff.wouldAdd]
 *    candidate the driver has now explicitly overridden the near-miss guess on.
 */
data class PopulatePossibleMatchRow(
    val factoryName: String,
    val existingName: String,
    val existingSource: String,
    val currentMiles: Int?,
    val currentMonths: Int?,
    val proposedMiles: Int?,
    val proposedMonths: Int?,
)

/**
 * Builds [PopulateDiff] from [factoryItems] (already canonicalized and deduped - see
 * [VehicleController.fetchFactorySchedule], the only intended source) against [existingItems]
 * (EVERY row for the vehicle, tombstoned included - [com.kevin.legion.data.local.MaintenanceItemDao.getForVehicleIncludingDeleted],
 * never [com.kevin.legion.data.local.MaintenanceItemDao.getForVehicle]'s active-only view, or a
 * tombstoned item that the factory still lists could never be told apart from a genuine "would
 * add").
 *
 * **Matching reuses [VehicleController.looksLikeExistingItem] - the SAME comparator every other
 * name-matching path on this map uses** (ticket 08's `logServiceDirect`, ticket 07's hand-add
 * duplicate warning). Ticket 14's own text is explicit about why this matters: ticket 02 counted 26
 * distinct factory service names against `SERVICE_KEYWORDS`' ten (now seventeen - see that table's
 * own doc comment, ticket 14 review), and "Air Filter" / "Air Filter Replacement" / "Engine Air
 * Filter" becoming three different rows on Kevin's real phone is exactly the failure mode a weaker
 * comparator here would repeat. A near-miss (two names that canonicalize to the same thing)
 * collapses into ONE comparison - the diff either shows nothing (values already agree) or one
 * [PopulateChangeRow] - rather than two independent categorizations that would silently duplicate
 * the concept.
 *
 * **A factory name with no exact match falls to [VehicleController.nearMissServiceName] before it
 * is allowed into [PopulateDiff.wouldAdd]** (ticket 14 review, BLOCKING 1b) - a weaker,
 * token-overlap guess at the same question, for a concept the keyword table has no entry for at
 * all. A near-miss hit becomes its own [PopulatePossibleMatchRow] question, never silently folded
 * into [wouldChange] (asserting a match this comparator is not certain of) and never silently left
 * to land in [wouldAdd] (creating the exact duplicate this exists to stop).
 *
 * Since [MaintenanceItem]'s primary key is `(vehicleId, serviceName)`, [existingItems] can have at
 * most one row per exact stored name, and it is either active or tombstoned, never both - so an
 * active-vs-tombstoned split of [existingItems] partitions it cleanly with no name appearing on
 * both sides.
 *
 * `internal` for direct unit testing without Room or a [Context] - same posture as every other pure
 * builder in this codebase (`VehicleController.isDue`, `ui/fleet/FleetRows.kt`'s builders).
 */
internal fun buildPopulateDiff(factoryItems: List<MaintenanceItem>, existingItems: List<MaintenanceItem>): PopulateDiff {
    val active = existingItems.filterNot { it.deleted }
    val tombstoned = existingItems.filter { it.deleted }

    val wouldAdd = mutableListOf<MaintenanceItem>()
    val wouldChange = mutableListOf<PopulateChangeRow>()
    val wouldRestore = mutableListOf<PopulateRestoreRow>()
    val possibleMatch = mutableListOf<PopulatePossibleMatchRow>()
    val matchedActiveNames = mutableSetOf<String>()

    for (factory in factoryItems) {
        val activeMatch = VehicleController.looksLikeExistingItem(factory.serviceName, active.map { it.serviceName })
        if (activeMatch != null) {
            matchedActiveNames += activeMatch
            val current = active.first { it.serviceName == activeMatch }
            if (current.intervalMiles != factory.intervalMiles || current.intervalMonths != factory.intervalMonths) {
                wouldChange += PopulateChangeRow(
                    serviceName = current.serviceName,
                    currentMiles = current.intervalMiles,
                    currentMonths = current.intervalMonths,
                    currentSource = current.intervalSource,
                    proposedMiles = factory.intervalMiles,
                    proposedMonths = factory.intervalMonths,
                )
            }
            // Values already agree: nothing to review, this factory item is simply not shown.
            continue
        }

        val tombstoneMatch = VehicleController.looksLikeExistingItem(factory.serviceName, tombstoned.map { it.serviceName })
        if (tombstoneMatch != null) {
            wouldRestore += PopulateRestoreRow(
                serviceName = tombstoneMatch,
                proposedMiles = factory.intervalMiles,
                proposedMonths = factory.intervalMonths,
            )
            continue
        }

        // Excludes names already claimed by an exact match above, so one active row can never be
        // the target of two different diff rows in the same pass.
        val nearMissMatch = VehicleController.nearMissServiceName(
            factory.serviceName,
            active.map { it.serviceName }.filterNot { it in matchedActiveNames },
        )
        if (nearMissMatch != null) {
            matchedActiveNames += nearMissMatch
            val current = active.first { it.serviceName == nearMissMatch }
            possibleMatch += PopulatePossibleMatchRow(
                factoryName = factory.serviceName,
                existingName = current.serviceName,
                existingSource = current.intervalSource,
                currentMiles = current.intervalMiles,
                currentMonths = current.intervalMonths,
                proposedMiles = factory.intervalMiles,
                proposedMonths = factory.intervalMonths,
            )
            continue
        }

        wouldAdd += factory
    }

    // Active items the factory schedule never matched, in either direction (exact OR near-miss) -
    // ticket 14's third category, the one that finally catches an invented row like Brake Fluid Flush.
    val notInFactorySchedule = active.filterNot { it.serviceName in matchedActiveNames }

    return PopulateDiff(wouldAdd, wouldChange, notInFactorySchedule, wouldRestore, possibleMatch)
}

/**
 * The full read side of a populate, in one suspend call (ticket 14): fetches the factory lookup and
 * every existing row (tombstoned included) for [vehicle], and hands both to [buildPopulateDiff].
 * Still writes nothing - the "diff, never a direct write" rule holds all the way through this call.
 * Kept out of `VehicleController` itself (which owns [VehicleController.fetchFactorySchedule], the
 * lookup half) so the UI layer has exactly one function to call to get a reviewable diff, matching
 * `ui/fleet/MaintenanceWrites.kt`'s own "state holder calls one thin function per action" shape.
 *
 * **Returns `null` on a genuine lookup failure, propagated straight from [VehicleController.fetchFactorySchedule] -
 * never a diff built from an empty list standing in for one.** See that function's own doc for why
 * the distinction is load-bearing: an empty factory schedule and a failed network call must never
 * collapse onto the same "everything on file is not-in-schedule" diff.
 * [PopulateScreen][com.kevin.legion.ui.fleet.PopulateScreen] is the one caller and treats `null` as
 * a retryable error, never as "the manufacturer publishes nothing for this car."
 */
suspend fun loadPopulateDiff(context: Context, vehicle: com.kevin.legion.data.local.Vehicle): PopulateDiff? {
    val factory = VehicleController.fetchFactorySchedule(context, vehicle) ?: return null
    val existing = CarDatabase.getDatabase(context).maintenanceItemDao().getForVehicleIncludingDeleted(vehicle.obdMac)
    return buildPopulateDiff(factory, existing)
}
