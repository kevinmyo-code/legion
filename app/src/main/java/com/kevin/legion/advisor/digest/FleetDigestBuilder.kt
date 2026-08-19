package com.kevin.legion.advisor.digest

import android.content.Context
import com.kevin.legion.advisor.AdvisorAspect
import com.kevin.legion.advisor.DigestBuilder
import com.kevin.legion.advisor.DigestText
import com.kevin.legion.data.local.CarDatabase
import com.kevin.legion.data.local.CodeEvent
import com.kevin.legion.data.local.MaintenanceItem
import com.kevin.legion.data.local.ServiceRecord
import com.kevin.legion.data.local.Vehicle
import com.kevin.legion.plan.TrustTier
import com.kevin.legion.ui.fleet.DueRowView
import com.kevin.legion.ui.fleet.buildDueRows
import com.kevin.legion.ui.fleet.distinctFaultsByFirstSeen
import com.kevin.legion.ui.fleet.groupThousands
import com.kevin.legion.util.compactDate
import com.kevin.legion.vehicle.VehicleController

/**
 * FLEET's deterministic digest (ticket 17, same shape/window/tier rules as ticket 16's
 * non-negotiables). Read-only over [VehicleController] and the maintenance/DTC/service-record
 * DAOs - never writes, never blocks on network.
 *
 * **Reuses `ui/fleet/FleetRows.kt`'s already-built pure logic rather than re-deriving it**
 * ([buildDueRows]/[distinctFaultsByFirstSeen]/[groupThousands] are `internal`, module-visible from
 * this package without a shared-helper file in `advisor/digest/` - ticket 17's scope note is about
 * not inventing a new shared file in THIS package, not about refusing an existing one).
 * [buildDueRows] already resolves "whichever comes first, miles or date" per item (it prefers the
 * miles axis for display when an item is anchored on both, matches [VehicleController.isDue]'s own
 * dual-axis semantics exactly, and reports a `neverDone` item's line as "never logged" rather than
 * inventing an axis for it) - reimplementing that here would be a second place to get
 * [MaintenanceItem]'s due-axis semantics wrong, which is exactly what this ticket's instructions
 * warned against.
 *
 * **DTC severity tiers are NOT [TrustTier].** The FLEET playbook (`FleetPlaybook.TEXT`, "OBD-II DTC
 * TRIAGE") already ships a three-tier vocabulary - STOP-NOW / CHECK-SOON / DRIVE-ON - keyed off the
 * code family, and ticket 08's "recent DTCs with severity tier" means THAT vocabulary, not the
 * proven/reported trust tier (which is also carried, separately, on every figure per the
 * non-negotiable). [classifyDtcSeverity] is a small, code-family-only classifier built directly off
 * the literal code lists the playbook already states (misfire P0300-P0308, lean P0171/P0174,
 * catalyst P0420/P0430, small evap P0440/P0442/P0455/P0456) - it cannot see the MIL's flashing/
 * steady behaviour or the driver's own symptoms, so a code outside those named families reads
 * `unclassified` rather than guessing a tier the digest has no basis for. This is a narrowing of an
 * already-shipped playbook taxonomy applied to stored data, not a new domain judgment invented here.
 *
 * **Every figure derived from [Vehicle.odometerBaseline]/[Vehicle.tripMilesSinceBaseline] is
 * [TrustTier.REPORTED].** Nothing reconciles a car's odometer against an outside document (there is
 * no printed statement to check it against, unlike ledger/pantry) - it is the driver's own stated
 * baseline plus an on-device GPS/OBD trip estimate layered on top, so it carries the same "driver
 * said so" tier CLAUDE.md §4 rule 7's worked examples already use for a spend category. DTC codes
 * are treated the same way: [CodeEvent] rows are written by [com.kevin.legion.service.AriaForegroundService]
 * straight off the ELM327 adapter with no reconciliation step of their own, so REPORTED is the
 * honest tier for them too, not PROVEN (traced - `AriaForegroundService.kt` line ~557 constructs the
 * row directly from the adapter's own polled response, no cross-check against any second source).
 */
object FleetDigestBuilder : DigestBuilder {
    override val aspect = AdvisorAspect.FLEET

    /** One 30-day bucket width, matching [VehicleController]'s own private `MONTH_MS` (that
     * constant is `private`, so this is a second literal of the same value rather than a shared
     * import - see this file's class doc on why a second literal here is preferred to reaching into
     * `VehicleController`'s private internals). */
    private const val MONTH_MS = 30L * 24 * 60 * 60 * 1000
    private const val WINDOW_PERIODS = 4
    private const val MAX_DTC_LINES = 5
    private const val MAX_LAST_SERVICE_LINES = 3

    override suspend fun build(context: Context): String {
        val vehicle = VehicleController.currentVehicle(context)
        val db = CarDatabase.getDatabase(context)
        val now = System.currentTimeMillis()
        val currentMileage = VehicleController.currentMileage(vehicle)
        // Same value [maintenanceLines]/[odometerTrendLine]'s DUE-axis math uses, formatted for the
        // two lines this digest RENDERS rather than computes with (ticket 10: bare only when it is
        // the driver's own confirmed reading, "about N mi - estimated, last confirmed ..." otherwise
        // - a bare figure here would be the same laundering ticket 06 already named, one layer up
        // from AdvisorBriefs' own consumer).
        val mileageLabel = VehicleController.mileageLabel(vehicle, now)

        val items = db.maintenanceItemDao().getForVehicle(vehicle.obdMac)
        val unknownNames = items.filter { VehicleController.isUnknown(it) }.map { it.serviceName }
        val nextService = VehicleController.nextService(context, vehicle)

        val windowStart = now - WINDOW_PERIODS * MONTH_MS
        val codeEvents = db.codeEventDao().getInRange(vehicle.obdMac, windowStart, now)
        val recentServices = db.serviceRecordDao().getRecentForVehicle(vehicle.obdMac, 20)

        return buildDigestText(
            vehicle = vehicle,
            currentMileage = currentMileage,
            mileageLabel = mileageLabel,
            items = items,
            unknownNames = unknownNames,
            nextService = nextService,
            codeEvents = codeEvents,
            recentServices = recentServices,
            now = now,
        )
    }

    /**
     * Pure assembly, no [Context]/Room - unit-testable directly, same "pure decision logic" split
     * [VehicleController.isDue]/[VehicleController.computeNextService] already use (CLAUDE.md §11:
     * unit tests run on a plain JVM with an unmocked `android.jar`).
     */
    internal fun buildDigestText(
        vehicle: Vehicle,
        currentMileage: Int,
        items: List<MaintenanceItem>,
        unknownNames: List<String>,
        nextService: VehicleController.NextService?,
        codeEvents: List<CodeEvent>,
        recentServices: List<ServiceRecord>,
        now: Long,
        // Default "" so the many pure-logic tests exercising currentMileage's ARITHMETIC (due-axis
        // math via buildDueRows, which stays an Int - see the class doc's "do not touch" list) don't
        // all need updating for a param they aren't testing. "" renders as [DigestText.notLogged] in
        // both places below, same as every other genuinely-absent figure in this file.
        mileageLabel: String = "",
    ): String {
        val lines = mutableListOf<String>()

        lines += DigestText.withTier(
            DigestText.line("VEHICLE ${vehicleLabel(vehicle)} odometer", mileageLabel.ifBlank { DigestText.notLogged() }),
            TrustTier.REPORTED,
        )

        lines += maintenanceLines(items, currentMileage, vehicle.odometerBaseline == 0, now)
        if (unknownNames.isNotEmpty()) {
            lines += DigestText.line("UNKNOWN ${unknownNames.size} items no anchor:", unknownNames.joinToString(", "))
        }
        lines += nextServiceLine(nextService)
        lines += dtcLines(codeEvents)
        lines += odometerTrendLine(recentServices, mileageLabel, now)
        lines += lastServiceLines(recentServices)

        return lines.joinToString("\n")
    }

    // Ticket 04's label rule: the one rule, every surface, including this digest - see
    // VehicleController.label's own doc. Pure, no Context/Room, safe to call from this file's own
    // "pure assembly" posture (see buildDigestText's doc).
    private fun vehicleLabel(vehicle: Vehicle): String = VehicleController.label(vehicle)

    /** "MAINTENANCE DUE" section - see class doc for why [buildDueRows] is reused rather than
     * re-derived. `neverDone` items read "overdue-now (never logged)" per the ticket's explicit
     * wording; anchored overdue items name the axis that fired via [DueRowView.sub]. An empty
     * [items] reads [DigestText.notLogged] - no schedule was ever seeded for this car.
     *
     * **`[row.isGuess]` carries a guess suffix, in words** (ticket 15 gap 2,
     * `.scratch/fleet-maintenance/issues/15-isdue-and-the-digest-inherit-the-same-two-gaps.md`).
     * Ticket 06 required this on six surfaces and missed this one - the audit grepped for
     * `intervalMiles|intervalMonths`, and this function consumes [DueRowView.sub], an
     * already-formatted string, so it could never have matched. [DueRowView.isGuess] reuses
     * [com.kevin.legion.ui.fleet.isGuessTag]'s exact rule (`intervalSource != "CONFIRMED"` AND an
     * interval exists - widened ticket 18 to also catch `LOOKUP`, a factory lookup shown to
     * disagree with itself run to run, never just `SEEDED`) rather than reinventing it. This digest
     * feeds [com.kevin.legion.advisor.
     * AdvisorBriefs] (via `FleetDigestBuilder` being its FLEET `digestBuilder`), i.e. a model's
     * OWN context - ticket 06's stated reason this matters more than the screen: "feeding an
     * unlabelled guess into a model that then states it back confidently is how an estimate
     * launders itself into a fact." A text digest has no tag/colour channel to begin with, so the
     * word is inline in the line's own value, not a bracket - matching this file's existing
     * value-then-tier composition (see [DigestText.estimate]'s own doc for the same two-layer
     * shape used elsewhere in this package).
     */
    private fun maintenanceLines(items: List<MaintenanceItem>, currentMileage: Int, odometerUnset: Boolean, now: Long): List<String> {
        if (items.isEmpty()) return listOf(DigestText.line("MAINTENANCE", DigestText.notLogged()))
        val overdue = buildDueRows(items, currentMileage, odometerUnset, now).filter { it.overdue }
        if (overdue.isEmpty()) return listOf(DigestText.line("MAINTENANCE DUE", "none"))
        val neverDoneNames = items.filter { it.neverDone }.map { it.serviceName }.toSet()
        return overdue.map { row ->
            // ticket 09 rewrote DueRowView.sub to name the axis(es) AND the due-ness in one phrase
            // ("every 5,000 mi - overdue"), so wrapping it in a second "overdue (...)" here would say
            // it twice - [row.sub] is used bare now, not composed into a second sentence.
            val phrase = if (row.label in neverDoneNames) "overdue-now (never logged)" else row.sub
            val guessed = if (row.isGuess) "$phrase - guess, unconfirmed" else phrase
            DigestText.withTier(DigestText.line("DUE ${row.label}", guessed), TrustTier.REPORTED)
        }
    }

    /** "NEXT" line: the soonest not-yet-due item on each axis, phrased per [VehicleController
     * .NextService]'s own doc comment ("whichever comes first" when the same item leads both axes,
     * both leaders named when they differ). Null (no schedule at all) or [VehicleController
     * .NextService.allDue] (a fully-logged schedule where everything is already due) both read as
     * distinct, honest states - neither collapses into the other or into a bare zero.
     *
     * **`[byMiles.isGuess]`/`[byTime.isGuess]` each carry their own "- guess, unconfirmed" suffix**
     * (mission-control ticket 16,
     * `.scratch/fleet-maintenance/issues/16-ticket-06-audited-a-dead-surface-and-missed-a-live-one.md`),
     * same inline-word convention [maintenanceLines] already uses for [DueRowView.isGuess] - this
     * line feeds [com.kevin.legion.advisor.AdvisorBriefs] i.e. a model's own context, and a "NEXT" it
     * states back confidently is exactly the laundering ticket 06 named. The two candidates are
     * independent items when they differ, so each gets its own suffix rather than one flag for the
     * whole line. */
    private fun nextServiceLine(next: VehicleController.NextService?): String {
        if (next == null) return DigestText.line("NEXT", DigestText.notLogged())
        if (next.allDue) return DigestText.line("NEXT", "everything anchored is already due")
        val byMiles = next.byMiles
        val byTime = next.byTime
        val milesPhrase = byMiles?.let { VehicleController.formatRemaining(it.remaining, VehicleController.ScheduleUnit.MILES) }
        val timePhrase = byTime?.let { VehicleController.formatRemaining(it.remaining, VehicleController.ScheduleUnit.DAYS) }
        val milesGuess = if (byMiles?.isGuess == true) " - guess, unconfirmed" else ""
        val timeGuess = if (byTime?.isGuess == true) " - guess, unconfirmed" else ""
        val phrase = when {
            byMiles != null && byTime != null && byMiles.serviceName == byTime.serviceName ->
                // Same item leads both axes, so it is one candidate with one provenance - the suffix
                // fires once, off byMiles (== byTime's own isGuess by construction, since both
                // candidates were built from the same MaintenanceItem in computeNextService).
                "${byMiles.serviceName} in $milesPhrase or $timePhrase, whichever comes first$milesGuess"
            byMiles != null && byTime != null ->
                "${byMiles.serviceName} in $milesPhrase$milesGuess; ${byTime.serviceName} in $timePhrase$timeGuess"
            byMiles != null -> "${byMiles.serviceName} in $milesPhrase$milesGuess"
            byTime != null -> "${byTime.serviceName} in $timePhrase$timeGuess"
            else -> "nothing anchored on either axis"
        }
        return DigestText.withTier(DigestText.line("NEXT", phrase), TrustTier.REPORTED)
    }

    /** Recent DTCs, distinct by code, newest-first, capped at [MAX_DTC_LINES] named exemplars - see
     * class doc for [classifyDtcSeverity]. An empty window reads [DigestText.notLogged], never "0
     * codes" (CLAUDE.md §4 rule 5's cousin for a domain with genuinely nothing recorded). */
    private fun dtcLines(codeEvents: List<CodeEvent>): List<String> {
        if (codeEvents.isEmpty()) return listOf(DigestText.line("DTC", DigestText.notLogged()))
        val faults = distinctFaultsByFirstSeen(codeEvents).take(MAX_DTC_LINES)
        return faults.map { fault ->
            val tier = classifyDtcSeverity(fault.code)
            DigestText.withTier(
                DigestText.line("DTC ${fault.code} [$tier] first seen", compactDate(fault.firstSeenMs)),
                TrustTier.REPORTED,
            )
        }
    }

    /** Code-family-only severity classification lifted directly from `FleetPlaybook.TEXT`'s "OBD-II
     * DTC TRIAGE" section - see this file's class doc for why this is a narrowing of an
     * already-shipped taxonomy, not a new one, and why an unmatched code reads `unclassified`
     * rather than a guessed tier. `internal` for direct unit testing. */
    internal fun classifyDtcSeverity(code: String): String {
        val normalized = code.trim().uppercase()
        val misfire = normalized.length == 5 && normalized.startsWith("P030") &&
            normalized.last().digitToIntOrNull()?.let { it in 0..8 } == true
        return when {
            misfire -> "stop-now"
            normalized in setOf("P0171", "P0174", "P0420", "P0430") -> "check-soon"
            normalized.startsWith("P01") -> "check-soon"
            normalized == "P0128" -> "check-soon"
            normalized in setOf("P0440", "P0442", "P0455", "P0456") -> "drive-on"
            else -> "unclassified"
        }
    }

    /** Odometer as of each of the last [WINDOW_PERIODS] 30-day buckets (the MAX mileage any
     * [ServiceRecord] logged in that bucket, mileage being monotonic across a car's life), plus one
     * older-trend figure derived from the earliest and latest mileage/date pair in the full
     * [recentServices] read - never a rate estimate beyond what two real anchors give directly. A
     * bucket with nothing logged reads [DigestText.notLogged], never `0` (there is no such thing as
     * zero miles on an odometer that has moved). */
    private fun odometerTrendLine(recentServices: List<ServiceRecord>, mileageLabel: String, now: Long): String {
        if (recentServices.isEmpty()) return DigestText.line("ODOMETER TREND", DigestText.notLogged())
        val buckets = (0 until WINDOW_PERIODS).map { periodIndex ->
            val bucketEnd = now - periodIndex * MONTH_MS
            val bucketStart = bucketEnd - MONTH_MS
            val inBucket = recentServices.filter { it.date in bucketStart..bucketEnd }
            inBucket.maxOfOrNull { it.mileage }
        }
        val bucketText = buckets.mapIndexed { i, mileage ->
            val label = if (i == 0) "current" else "-${i}mo"
            "$label ${mileage?.let { groupThousands(it) } ?: DigestText.notLogged()}"
        }.joinToString(", ")

        val oldest = recentServices.minByOrNull { it.date }
        val newest = recentServices.maxByOrNull { it.date }
        val olderTrend = if (oldest != null && newest != null && oldest.date < now - WINDOW_PERIODS * MONTH_MS && oldest.id != newest.id) {
            val milesDelta = newest.mileage - oldest.mileage
            val monthsSpan = ((newest.date - oldest.date).toDouble() / MONTH_MS).coerceAtLeast(1.0)
            "%,d mi over %.1f mo (avg %.0f mi/mo)".format(milesDelta, monthsSpan, milesDelta / monthsSpan)
        } else {
            DigestText.notLogged()
        }

        val currentPhrase = mileageLabel.ifBlank { DigestText.notLogged() }
        return DigestText.withTier(
            DigestText.line("ODOMETER TREND", "$bucketText, older-trend $olderTrend (current $currentPhrase)"),
            TrustTier.REPORTED,
        )
    }

    /** Up to [MAX_LAST_SERVICE_LINES] most recent logged services, newest-first (the DAO's own
     * ordering) - [DigestText.notLogged] if the car has never had a service logged at all. */
    private fun lastServiceLines(recentServices: List<ServiceRecord>): List<String> {
        if (recentServices.isEmpty()) return listOf(DigestText.line("LAST SERVICE", DigestText.notLogged()))
        return recentServices.take(MAX_LAST_SERVICE_LINES).map { record ->
            DigestText.withTier(
                DigestText.line(
                    "LAST SERVICE ${record.serviceName}",
                    "${compactDate(record.date)} at ${groupThousands(record.mileage)} mi",
                ),
                TrustTier.REPORTED,
            )
        }
    }
}
