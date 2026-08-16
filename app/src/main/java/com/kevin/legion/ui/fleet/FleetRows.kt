package com.kevin.legion.ui.fleet

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.kevin.legion.data.local.CodeEvent
import com.kevin.legion.data.local.DailyDriveLog
import com.kevin.legion.data.local.MaintenanceItem
import com.kevin.legion.data.local.MonthlyRecap
import com.kevin.legion.data.local.OdbSample
import com.kevin.legion.data.local.intervalIsUnconfirmed
import com.kevin.legion.data.local.provenanceWords as entityProvenanceWords
import com.kevin.legion.data.local.provenanceWordsForSource as entityProvenanceWordsForSource
import com.kevin.legion.ui.theme.LegionType
import com.kevin.legion.ui.theme.LocalLegionSemantics
import com.kevin.legion.util.relativeAge
import com.kevin.legion.util.shortDate
import com.kevin.legion.vehicle.VehicleController
import org.json.JSONArray
import java.time.LocalDate
import java.time.ZoneId

/**
 * FLEET-specific pure logic and rows for ticket 09 (resolution §1: LIVE / DUE
 * / FAULTS / NOT BUILT YET). The shared, aspect-agnostic furniture
 * (`SectionHeader`, `Hairline`, `ReadingRow`, `NotBuiltRow`) lives in
 * `ui/common/CommonRows.kt` - see that file's doc comment. Everything here is
 * either a pure function (unit-tested in `FleetRowsTest`, no Android/Room
 * dependency) or a thin display-only Composable; nothing here writes to the
 * database, matching this ticket's read-only scope.
 */

// --------------------------------------------------------------- LIVE (pure)

/**
 * One row of the LIVE block: a label, its last-seen value, and how stale that value is.
 *
 * [pid] (mission-control ticket 16 follow-up, "get FLEET's tile row above the fold") is the raw
 * OBD PID this reading came from - "0105", "ATRV", "0107" - carried through so [UplinkPane] can
 * render these as [com.kevin.legion.ui.common.DeckFeedRow]'s `code` column, same PID-as-code shape
 * [ThemePreview.kt]'s own "Live PIDs" section already demonstrates. Defaults to `""` so every
 * pre-existing 3-arg `LiveRowView(label, value, sub)` construction site (previews, tests) keeps
 * compiling unchanged.
 */
data class LiveRowView(val label: String, val value: String, val sub: String, val pid: String = "")

/**
 * The fixed, small set of slow-changing PIDs [com.kevin.legion.vehicle.TelemetryRecorder]
 * writes to `obd_samples` that are worth a driver glancing at (coolant temp,
 * battery voltage, long-term fuel trim). Deliberately not the full PID set
 * TelemetryRecorder samples (RPM, MAF, speed, short-term trim) - those move
 * every tick and belong to a trend chart this ticket does not build, not a
 * static "last seen" readout. Raw PID codes match [OdbSample.pid] exactly as
 * TelemetryRecorder writes them - "0105", "ATRV", "0107" - see that object's
 * `run` loop.
 */
private data class LiveGauge(val pid: String, val label: String, val format: (Double) -> String)

private val LIVE_GAUGES = listOf(
    LiveGauge("0105", "Coolant") { v -> "${v.toInt()} C" },
    LiveGauge("ATRV", "Battery") { v -> "%.1f V".format(v) },
    LiveGauge("0107", "Fuel trim, long") { v -> "%+.1f %%".format(v) },
)

/** PIDs [buildLiveRows] wants a latest sample for - drives the DAO reads in the state holder. */
internal val LIVE_GAUGE_PIDS: List<String> = LIVE_GAUGES.map { it.pid }

/**
 * Formats each gauge's latest sample (or omits the row entirely if this
 * install has never recorded that PID - never a fabricated "no data" value
 * standing in for a number). `internal` for direct unit testing.
 */
internal fun buildLiveRows(samplesByPid: Map<String, OdbSample?>, now: Long): List<LiveRowView> =
    LIVE_GAUGES.mapNotNull { gauge ->
        val sample = samplesByPid[gauge.pid] ?: return@mapNotNull null
        LiveRowView(gauge.label, gauge.format(sample.value), relativeAge(sample.timestamp, now), pid = gauge.pid)
    }

// ------------------------------------------------------------- DUE (pure)

/** One row of the DUE block, already resolved to display strings. */
data class DueRowView(
    val label: String,
    val value: String,
    val sub: String,
    val overdue: Boolean,
    /**
     * elapsed/interval on the row's own axis (miles when [toDueRow] chose the
     * miles axis, else time), for [com.kevin.legion.ui.common.DeckMeter] drawn
     * under the row's text on `FleetScreen`'s MAINTENANCE panel (quant-viz
     * ticket 05 part C). `null` when the item has no interval on file to
     * divide by - no meter is drawn, never a meter frozen at zero, matching
     * [toDueRow]'s own "-" for a value with the same cause. See [dueFraction]
     * for the math. Defaults to `null` so the many `DueRowView(...)` preview/
     * test construction sites that predate this field keep compiling.
     */
    val fraction: Float? = null,
    /**
     * True whenever the driver did not state this interval themselves ([MaintenanceItem.intervalSource]
     * is anything but `CONFIRMED`, so `SEEDED` or `LOOKUP` - widened by ticket 18, this doc said
     * `SEEDED` only until then) AND the item carries an interval on at least one axis (ticket 06
     * refinement c, `.scratch/fleet-maintenance/issues/06-a-seeded-interval-is-a-guess.md`:
     * "the tag renders only when there is an interval to qualify" - a null interval already reads
     * "no interval on file", and tagging that `[GUESS]` would be a claim about a number that does
     * not exist). See [isGuessTag]. The tag itself is deliberately coarse; where WHICH kind of
     * unconfirmed matters, callers read [provenanceWords] alongside it. Defaults to `false` for the
     * same preview/test-compat reason [fraction] defaults to `null`.
     */
    val isGuess: Boolean = false,
)

/**
 * Builds the DUE block's rows from a vehicle's [MaintenanceItem]s. Items with
 * no anchor at all ([VehicleController.isUnknown]) are excluded - they are
 * not "due", they are "we don't know yet", a different state this block does
 * not speak to (see [VehicleController.unknownItems]'s doc, and ticket 09's
 * FULL SCHEDULE / [buildScheduleRows], which is where they are counted on
 * MAINTENANCE's triage screen and then actually listed, rather than silently
 * dropped the way this function's own output always has been).
 *
 * **Ordering.** Overdue items first (stable order, not re-sorted among
 * themselves), then not-yet-due items in their original order. Deliberately
 * NOT sorted by "soonest remaining" across items that mix a miles-remaining
 * candidate against a days-remaining one - [VehicleController.computeNextService]'s
 * doc comment explains at length why any such cross-axis comparison is really
 * a smuggled-in rate estimate ("miles per day"), which Kevin explicitly
 * rejected. This block reports each item's own remaining value on its own
 * axis and lets the reader compare, rather than picking a winner for them.
 *
 * `internal` rather than `private` so [FleetRowsTest] can drive it directly.
 *
 * [odometerUnset] is the caller's own `vehicle.odometerBaseline == 0` (see [chooseDueAxis]'s doc for
 * why this, and never `currentMileage > 0`, is the real "driver has never confirmed an odometer"
 * signal) - threaded through explicitly rather than re-derived from [currentMileage] here, since
 * [currentMileage] alone can read positive (accumulated trip miles against a still-zero baseline)
 * while the odometer itself is still unconfirmed.
 */
internal fun buildDueRows(items: List<MaintenanceItem>, currentMileage: Int, odometerUnset: Boolean, now: Long): List<DueRowView> {
    val anchored = items.filterNot { VehicleController.isUnknown(it) }
    val (overdue, upcoming) = anchored.partition { VehicleController.isDue(it, currentMileage, odometerUnset, now) }
    return overdue.map { toDueRow(it, currentMileage, odometerUnset, now, overdue = true) } +
        upcoming.map { toDueRow(it, currentMileage, odometerUnset, now, overdue = false) }
}

/**
 * True whenever the driver did NOT state this interval themselves AND the item carries an interval
 * on at least one axis - ticket 06 refinement c, WIDENED by ticket 18
 * (`.scratch/fleet-maintenance/issues/18-the-factory-lookup-is-not-stable-enough-to-diff-against.md`).
 *
 * **Moved onto [MaintenanceItem] itself as [com.kevin.legion.data.local.intervalIsUnconfirmed]**
 * (mission-control ticket 16,
 * `.scratch/fleet-maintenance/issues/16-ticket-06-audited-a-dead-surface-and-missed-a-live-one.md`):
 * `vehicle/` and `advisor/` both need this rule now, and `vehicle/` must never import `ui.fleet`
 * (this file already imports [VehicleController], so the reverse import would be a dependency
 * cycle). This function is now a thin delegate kept under its original name/signature so no UI call
 * site here or in `FleetDigestBuilder`/`PopulateDrilldown.kt` had to change - see the entity
 * property's own doc for the full "why three-way, never `== SEEDED`" reasoning. `internal` for
 * direct unit testing, same posture as every other pure builder in this file.
 */
internal fun isGuessTag(item: MaintenanceItem): Boolean = item.intervalIsUnconfirmed

/**
 * Words a screen can put beside a guess-tagged item's value - now a thin delegate onto
 * [com.kevin.legion.data.local.provenanceWords] (mission-control ticket 16, same move as
 * [isGuessTag]). Kept under its original name/signature so no call site changed. See the entity
 * property's own doc for the SEEDED-vs-LOOKUP wording rationale. `internal` for direct unit
 * testing, same posture as [isGuessTag].
 */
internal fun provenanceWords(item: MaintenanceItem): String? = item.entityProvenanceWords

/**
 * [provenanceWords]'s actual logic, keyed on the raw `intervalSource` string rather than a full
 * [MaintenanceItem] - [PopulateChangeRow][com.kevin.legion.vehicle.PopulateChangeRow] and
 * [PopulatePossibleMatchRow][com.kevin.legion.vehicle.PopulatePossibleMatchRow] (`PopulateDrilldown.kt`'s
 * `WouldChangeRow`/`PossibleMatchRow`) only ever carry the ON-FILE row's `currentSource`/
 * `existingSource` as a bare string, not the row itself, so [provenanceWords] delegates here rather
 * than forcing either of those call sites to fabricate a throwaway [MaintenanceItem] just to read one
 * field back off it. Now a thin delegate onto
 * [com.kevin.legion.data.local.provenanceWordsForSource] (mission-control ticket 16), kept under its
 * original name/signature. `internal`, not `private` - Kotlin's top-level `private` is file-scoped,
 * and `PopulateDrilldown.kt` (a different file in this same package) is exactly the caller that
 * needs it.
 */
internal fun provenanceWordsForSource(intervalSource: String): String? = entityProvenanceWordsForSource(intervalSource)

/**
 * [fromEpochMs] plus [months] calendar months, via [java.time.ZonedDateTime.plusMonths] in the
 * device zone - ticket 09's mandated fix for the old `months * 30 days` approximation, which "stops
 * being cosmetic once months can drive due-ness": a 6-month interval computed as 180 days drifts
 * almost 6 days a year against a real calendar, and every month length (28/29/30/31 days) and DST
 * transition is handled correctly by [java.time] rather than approximated. [fromEpochMs] is read as
 * a LOCAL-midnight instant, matching [MaintenanceItem.lastDoneDate]'s own convention
 * (`playbook-coding.md`'s "Date handling and zone conversions" already names this exact field as
 * local-midnight), so the same device zone interprets it on both ends of this round trip.
 */
internal fun addCalendarMonths(fromEpochMs: Long, months: Int): Long =
    java.time.Instant.ofEpochMilli(fromEpochMs)
        .atZone(ZoneId.systemDefault())
        .plusMonths(months.toLong())
        .toInstant()
        .toEpochMilli()

/** Which of [MaintenanceItem]'s two clocks a row's headline value/meter is drawn from. */
internal enum class DueAxis { MILES, TIME }

/**
 * The axis CLOSER to being due, not whichever axis merely happens to be non-null (ticket 09: "a row
 * shows the axis that is closer to due... the sub-line names it"). "Closer" is measured on the SAME
 * 0..1 elapsed/interval scale [dueFraction] itself computes for a single axis - a mileage clock 90%
 * through its own interval is closer than a time clock 40% through its own, even though the raw
 * units are incomparable. This is NOT the cross-ITEM "miles per day" rate estimate
 * [VehicleController.NextService]'s doc rejects - both fractions here are already normalized to the
 * SAME item's own two intervals, so no rate conversion happens anywhere in this comparison.
 *
 * Falls back to whichever single axis actually exists when only one does; `null` when neither does
 * (mirrors [dueFraction]'s own three-way split - [dueFraction] calls this function directly so the
 * two can never silently disagree about which axis a row is reporting).
 *
 * **The miles axis requires `!odometerUnset`** - [odometerUnset] is the caller's own
 * `vehicle.odometerBaseline == 0`, the SAME signal [VehicleController.computeNextService] already
 * gates its own `odometerUnset` on, threaded in here rather than re-derived from [currentMileage].
 * **`currentMileage > 0` is NOT the same signal and must never stand in for it**: `currentMileage` is
 * `odometerBaseline + tripMilesSinceBaseline.roundToInt()`
 * ([VehicleController.currentMileage]), and [com.kevin.legion.vehicle.TelemetryRecorder]'s trip-mile
 * accumulation loop runs unconditionally on `odometerBaseline` (gated only on connected + rpm>0 +
 * not-in-conversation) - so a car can accumulate real trip miles against a still-zero, never-confirmed
 * baseline, reading `currentMileage > 0` while the odometer itself remains unset. On a car in exactly
 * that state, `item.intervalMiles - (currentMileage - item.lastDoneMileage)` still computes a remaining
 * figure against an odometer nobody ever confirmed - a smaller-magnitude instance of the same "due in
 * 121,450 miles" absurdity named in ticket 09's own constraints section, this fix's whole reason for
 * being (senior-dev review, mission-control ticket 09 follow-up: the two signals coincide only by
 * accident of a device's current snapshot having both fields at zero, not as a property of the code).
 * A time-only item, or an item that also carries a time axis, is unaffected - only the miles figure
 * itself is untrustworthy while the odometer is unset, not the whole row.
 */
internal fun chooseDueAxis(item: MaintenanceItem, currentMileage: Int, odometerUnset: Boolean, now: Long): DueAxis? {
    val milesAxis = item.intervalMiles != null && item.lastDoneMileage != null && !odometerUnset
    val timeAxis = item.intervalMonths != null && item.lastDoneDate != null
    return when {
        milesAxis && timeAxis -> {
            val milesFraction = (currentMileage - item.lastDoneMileage!!).toFloat() / item.intervalMiles!!.toFloat()
            val dueAt = addCalendarMonths(item.lastDoneDate!!, item.intervalMonths!!)
            val timeFraction = (now - item.lastDoneDate!!).toFloat() / (dueAt - item.lastDoneDate!!).toFloat()
            if (milesFraction >= timeFraction) DueAxis.MILES else DueAxis.TIME
        }
        milesAxis -> DueAxis.MILES
        timeAxis -> DueAxis.TIME
        else -> null
    }
}

/**
 * One item's DUE row. The headline [DueRowView.value] and the axis named in [DueRowView.sub] both
 * come from [chooseDueAxis] - the axis closer to due, never a hardcoded miles-first preference
 * (ticket 09's rewrite of this function's old, admittedly-cosmetic bias). The sub-line states BOTH
 * intervals when the item carries both - `"every 7,500 mi or 6 mo - due in 1,100 mi"`, ticket 09's
 * own example, reproduced here verbatim - because Kevin ruled due = whichever comes first and a row
 * now has to express two clocks without hiding either one. Falls back to naming just the one
 * interval the item has, then to "never logged"/"no interval on file" when there is nothing to name
 * at all - the exact wording ticket 06 refinement c relies on to mean "no number to doubt".
 *
 * **A mileage-only item with no odometer reading says so, in words** (ticket 09's constraints
 * section): [chooseDueAxis] already refuses the miles axis when [odometerUnset], but a bare
 * `"no interval on file"` in that state would be a LIE - there IS an interval, the app just cannot
 * currently compute a due figure against it. [milesBlockedByOdometer] catches exactly that case (an
 * item that carries a real miles interval+anchor, has no time axis to fall back to, and would
 * otherwise read as if it had no schedule at all) and names the real reason.
 */
private fun toDueRow(item: MaintenanceItem, currentMileage: Int, odometerUnset: Boolean, now: Long, overdue: Boolean): DueRowView {
    val axis = chooseDueAxis(item, currentMileage, odometerUnset, now)
    val intervalPhrase = intervalWords(item)
    val milesBlockedByOdometer = axis == null && odometerUnset &&
        item.intervalMiles != null && item.lastDoneMileage != null

    val value = when {
        overdue -> "OVERDUE"
        axis == DueAxis.MILES -> {
            val remaining = (item.intervalMiles!! - (currentMileage - item.lastDoneMileage!!)).toLong()
            "in " + VehicleController.formatRemaining(remaining, VehicleController.ScheduleUnit.MILES)
        }
        axis == DueAxis.TIME -> {
            val dueAt = addCalendarMonths(item.lastDoneDate!!, item.intervalMonths!!)
            val remainingDays = (dueAt - now) / (24 * 60 * 60 * 1000)
            "in " + VehicleController.formatRemaining(remainingDays, VehicleController.ScheduleUnit.DAYS)
        }
        else -> "-"
    }

    val sub = when {
        intervalPhrase != null && axis != null -> "$intervalPhrase - " + if (overdue) "overdue" else "due $value"
        intervalPhrase != null && milesBlockedByOdometer -> "$intervalPhrase - odometer not set"
        intervalPhrase != null -> intervalPhrase
        item.neverDone -> "never logged"
        else -> "no interval on file"
    }

    return DueRowView(item.serviceName, value, sub, overdue, dueFraction(item, currentMileage, odometerUnset, now, overdue), isGuessTag(item))
}

/**
 * The schedule's own words for an item's interval(s), independent of whether either axis has an
 * anchor to compute a due figure from - `"every 7,500 mi or 6 mo"` / `"every 7,500 mi"` /
 * `"every 6 mo"` / `null` when neither [MaintenanceItem.intervalMiles] nor
 * [MaintenanceItem.intervalMonths] is set. Shared by [toDueRow] (anchored rows) and
 * [toScheduleRow] (FULL SCHEDULE's unknown-anchor rows, which have an interval to name but nothing
 * to compute a due figure from at all).
 */
internal fun intervalWords(item: MaintenanceItem): String? {
    val parts = listOfNotNull(
        item.intervalMiles?.let { "${groupThousands(it)} mi" },
        item.intervalMonths?.let { "$it mo" },
    )
    return if (parts.isEmpty()) null else "every " + parts.joinToString(" or ")
}

/**
 * [DueRowView.fraction]'s pure math (quant-viz ticket 05 part C, corrected by ticket 09): elapsed
 * over interval, on [chooseDueAxis]'s chosen axis - never a hardcoded miles-first preference, so the
 * meter drawn under a row always matches the axis its own value/sub line names. Delegates its axis
 * choice to [chooseDueAxis] directly so the two can never independently drift out of sync, and its
 * time-axis math to [addCalendarMonths] rather than the old `months * 30 days` approximation.
 *
 * [overdue] short-circuits to `1f` rather than letting the ratio run past 1.0 for a badly-overdue
 * item - a meter drawing past its own right edge would read as a bug, not as "very overdue";
 * [DueRowView.overdue]'s existing flag and wording already carry that signal, so the meter only
 * needs to stop where its track ends. `internal` for direct unit testing, same posture as
 * [buildDueRows].
 */
internal fun dueFraction(item: MaintenanceItem, currentMileage: Int, odometerUnset: Boolean, now: Long, overdue: Boolean): Float? {
    if (overdue) return 1f
    return when (chooseDueAxis(item, currentMileage, odometerUnset, now)) {
        DueAxis.MILES -> {
            val elapsed = (currentMileage - item.lastDoneMileage!!).toFloat()
            (elapsed / item.intervalMiles!!.toFloat()).coerceIn(0f, 1f)
        }
        DueAxis.TIME -> {
            val dueAt = addCalendarMonths(item.lastDoneDate!!, item.intervalMonths!!)
            val elapsedMs = (now - item.lastDoneDate!!).toFloat()
            (elapsedMs / (dueAt - item.lastDoneDate!!).toFloat()).coerceIn(0f, 1f)
        }
        null -> null
    }
}

/** "132400" -> "132,400". No currency, no decimals - a mileage/interval figure, not money. */
internal fun groupThousands(n: Int): String =
    n.toString().reversed().chunked(3).joinToString(",").reversed()

// ----------------------------------------------------- FULL SCHEDULE (pure, ticket 09)

/** Which of the FULL SCHEDULE inventory's three sections a row belongs to. */
enum class ScheduleGroup { OVERDUE, UPCOMING, UNKNOWN }

/**
 * One row of the FULL SCHEDULE inventory - every non-deleted item, unlike [DueRowView] which only
 * ever covers the anchored subset [buildDueRows] keeps.
 */
data class ScheduleRowView(
    val serviceName: String,
    val group: ScheduleGroup,
    val value: String,
    val sub: String,
    val isGuess: Boolean,
    val fraction: Float?,
)

/**
 * Every non-deleted [MaintenanceItem] for a vehicle (ticket 09's FULL SCHEDULE), grouped OVERDUE /
 * UPCOMING / UNKNOWN - the third group [buildDueRows] deliberately excludes and MAINTENANCE (triage)
 * only ever counts, never lists. [items] is expected already filtered to `deleted = 0`
 * ([com.kevin.legion.data.local.MaintenanceItemDao.getForVehicle] does this at the query, not here -
 * this function has no opinion about tombstones). `internal` for direct unit testing, same posture
 * as every other pure builder in this file.
 */
internal fun buildScheduleRows(items: List<MaintenanceItem>, currentMileage: Int, odometerUnset: Boolean, now: Long): List<ScheduleRowView> {
    val unknown = items.filter { VehicleController.isUnknown(it) }
    val known = items.filterNot { VehicleController.isUnknown(it) }
    val (overdue, upcoming) = known.partition { VehicleController.isDue(it, currentMileage, odometerUnset, now) }
    return overdue.map { toScheduleRow(it, currentMileage, odometerUnset, now, ScheduleGroup.OVERDUE) } +
        upcoming.map { toScheduleRow(it, currentMileage, odometerUnset, now, ScheduleGroup.UPCOMING) } +
        unknown.map { toScheduleRow(it, currentMileage, odometerUnset, now, ScheduleGroup.UNKNOWN) }
}

/**
 * An UNKNOWN-group row has an interval to name (usually - most seeded items keep their interval
 * even with no anchor logged yet) but nothing to compute a due figure from, so [ScheduleRowView.value]
 * is always "-" and [ScheduleRowView.fraction] is always `null` - there is nothing to meter. OVERDUE/
 * UPCOMING rows reuse [toDueRow] wholesale rather than re-deriving the same due-figure math a second
 * time.
 */
private fun toScheduleRow(item: MaintenanceItem, currentMileage: Int, odometerUnset: Boolean, now: Long, group: ScheduleGroup): ScheduleRowView {
    if (group == ScheduleGroup.UNKNOWN) {
        return ScheduleRowView(
            serviceName = item.serviceName,
            group = group,
            value = "-",
            sub = intervalWords(item) ?: "no interval on file",
            isGuess = isGuessTag(item),
            fraction = null,
        )
    }
    val row = toDueRow(item, currentMileage, odometerUnset, now, overdue = group == ScheduleGroup.OVERDUE)
    return ScheduleRowView(item.serviceName, group, row.value, row.sub, row.isGuess, row.fraction)
}

/**
 * Every item CONFIRM-ALL is allowed to bless in one pass - ticket 06 decision 2: "a list of what is
 * about to be blessed, read before agreeing... a plain accept-all was declined, correctly."
 * [isGuessTag]'s own "an interval must exist" rule applies here too: there is nothing to confirm on
 * an unconfirmed row with no interval, so it never appears in the review list.
 *
 * **Renamed from `confirmableSeededItems` when ticket 18 widened [isGuessTag] past `SEEDED`.** It
 * now returns `LOOKUP` rows too, which is deliberate - confirming is how a factory-lookup value
 * becomes one the driver actually vouches for, and that path must exist. But the old name asserted a
 * provenance this no longer filters on, and a name that says `SEEDED` while returning `LOOKUP` is
 * the same species of lie the rest of this ticket is about. The dialog that renders this list is
 * what carries the per-row distinction, via [provenanceWords].
 */
internal fun confirmableItems(items: List<MaintenanceItem>): List<MaintenanceItem> =
    items.filter { isGuessTag(it) }

// -------------------------------------------------------- ITEM DETAIL (pure, ticket 09/07)

/**
 * The three-way anchor picker (ticket 07, `.scratch/fleet-maintenance/issues/07-hand-added-items-and-what-delete-means.md`):
 * `never done on this car` / `don't know` / `done at mileage/date`. Maps directly onto
 * [MaintenanceItem]'s existing three logical states - see that entity's own doc comment -
 * [NEVER_DONE] sets `neverDone = true` and clears both anchors, [DONT_KNOW] clears both anchors and
 * leaves `neverDone = false` (a legitimate "I don't know" state,
 * [com.kevin.legion.data.local.MaintenanceItemDao.setAnchor]'s own doc names this explicitly), and
 * [DONE_AT] sets one or both anchors. `neverDone` is `true` on 0 of 54 rows on Kevin's real phone as
 * of ticket 01's audit **because no control has ever been able to set it** - this enum, and the
 * picker built on it, is that control.
 */
enum class AnchorMode { NEVER_DONE, DONT_KNOW, DONE_AT }

// ---------------------------------------------------------- FAULTS (pure)

/** One distinct stored code, first seen at [firstSeenMs]. */
data class FaultRow(val code: String, val firstSeenMs: Long)

/**
 * Flattens [CodeEvent]'s `codesJson` (several codes can trip in one event)
 * into one row per DISTINCT code, keeping the EARLIEST timestamp any event
 * carried it - "first seen" means first, not most recent. `internal` for
 * direct unit testing, same reasoning as [buildDueRows].
 */
internal fun distinctFaultsByFirstSeen(events: List<CodeEvent>): List<FaultRow> {
    val firstSeen = mutableMapOf<String, Long>()
    for (event in events) {
        val codes = runCatching { JSONArray(event.codesJson) }.getOrNull() ?: continue
        for (i in 0 until codes.length()) {
            val code = codes.optString(i).takeIf { it.isNotBlank() } ?: continue
            val existing = firstSeen[code]
            if (existing == null || event.timestamp < existing) firstSeen[code] = event.timestamp
        }
    }
    // Newest-first: a code first seen yesterday is more likely to still be
    // relevant to the driver than one first seen a year ago.
    return firstSeen.entries.sortedByDescending { it.value }.map { FaultRow(it.key, it.value) }
}

/** The visible slice of the UPLINK STORED CODES list plus a worded overflow count - same shape
 * [com.kevin.legion.ui.AlertsSummary]/`capAlertRows` uses for HOME's ALERTS pane. */
data class FaultRowsSummary(val visible: List<Pair<FaultRow, String?>>, val overflowCount: Int)

/**
 * Caps STORED CODES at [max] (two, mission-control ticket 16 - Kevin's call). UPLINK's list was
 * unbounded and on his real car (6 DTCs) overran the whole FLEET root, pushing MAINTENANCE,
 * DRIVES and CARS below the fold - breaking ticket 05's "a root shows its hero plus one full row
 * of tiles without scrolling". Never a silent truncation: [FaultRowsSummary.overflowCount] is
 * rendered as a worded "AND N MORE" row (CLAUDE.md §4's "said in words" rule - never a bare count
 * badge), same "reported, never silent" posture `capAlertRows` already set for HOME. `internal`
 * for direct unit testing, same reasoning as [distinctFaultsByFirstSeen].
 */
internal fun capFaultRows(faults: List<Pair<FaultRow, String?>>, max: Int = 2): FaultRowsSummary =
    if (faults.size <= max) FaultRowsSummary(faults, 0) else FaultRowsSummary(faults.take(max), faults.size - max)

// ------------------------------------------------------------- DRIVES (pure)

/**
 * The DRIVES panel's fixed reading, built from [com.kevin.legion.data.local.DailyDriveLogDao.getRecent]
 * (ticket 18: "reuse existing data loading" - [com.kevin.legion.vehicle.DailyDriveLogController]
 * already aggregates TRIP_MILES/MPG_TRIP into this table every hour, so this
 * panel adds no new query, only a display shape over rows that already exist).
 */
data class DriveSummaryView(val headline: String, val sub: String, val hasData: Boolean)

/**
 * The most recent day with at least one finished drive - a day with a
 * [DailyDriveLog] row but `driveCount == 0` (the hourly refresh writes one for
 * every day, driven or not, see that controller's own doc) is not a "last
 * drive" and is skipped rather than reported as one.
 *
 * [now] anchors [relativeAge] against the day's own local midnight, not
 * [DailyDriveLog.generatedAt] - the driver cares how long ago they drove, not
 * how long ago the rollup last recomputed itself.
 */
internal fun buildLastDriveSummary(logsNewestFirst: List<DailyDriveLog>, now: Long): DriveSummaryView {
    val last = logsNewestFirst.firstOrNull { it.driveCount > 0 }
        ?: return DriveSummaryView("NO DRIVES LOGGED", "nothing recorded yet", hasData = false)
    val dayMs = LocalDate.of(last.year, last.month, last.day)
        .atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
    val mpgPart = last.avgMpg?.let { " · %.1f mpg".format(it) }.orEmpty()
    val driveWord = if (last.driveCount == 1) "drive" else "drives"
    return DriveSummaryView(
        headline = "${last.milesDriven.toInt()} mi$mpgPart",
        sub = "${last.driveCount} $driveWord · ${relativeAge(dayMs, now)}",
        hasData = true,
    )
}

/**
 * The DRIVES panel's MPG trend, oldest-first (matches [com.kevin.legion.ui.common.DeckSparkline]'s
 * index-ordered contract - see that composable's doc for why a sparkline
 * never carries timestamps). [logsNewestFirst] comes straight off
 * `getRecent`'s own ordering, so this only reverses it, no new query and no
 * new aggregation (ticket 18 scope: MPG history reuses what
 * [com.kevin.legion.vehicle.TelemetryRecorder]'s per-drive `MPG_TRIP` write
 * already rolled into the daily log, not a fresh average).
 *
 * A day that logged driving but never finished a fuel-integrated trip (too
 * short - see [DailyDriveLog.avgMpg]'s own nullability) is a GAP, not a zero,
 * same rule [DeckSparkline]'s file doc states for every deck chart.
 */
internal fun buildMpgSparkline(logsNewestFirst: List<DailyDriveLog>): List<Float?> =
    logsNewestFirst.asReversed().map { it.avgMpg?.toFloat() }

/**
 * The DRIVES panel's second, sibling sparkline (quant-viz ticket 12): daily
 * [DailyDriveLog.milesDriven], oldest-first, off the exact same [logsNewestFirst]
 * rows [buildMpgSparkline] already receives - no second query, matching that
 * function's own doc.
 *
 * Unlike [buildMpgSparkline]'s `avgMpg` (nullable - a day can log driving with
 * no fuel-integrated trip finished), [DailyDriveLog.milesDriven] is a
 * non-null `Double` that the hourly rollup writes for every day whether or
 * not it was driven (see [buildLastDriveSummary]'s doc), so a day inside this
 * window is never a genuine gap here - `0.0` on an undriven day is a real
 * zero, the same "gap-vs-zero" distinction CLAUDE.md §4 rule 6 states for
 * money, read onto miles: nothing in [logsNewestFirst] is missing, so nothing
 * here is `null`. The `Float?` return type still matches [DeckSparkline]'s
 * general contract rather than narrowing to `Float`, so a future caller that
 * feeds a genuinely sparse window (e.g. a car with days it did not exist yet)
 * is not silently miscompiled into treating an absent day as zero.
 */
internal fun buildMilesSparkline(logsNewestFirst: List<DailyDriveLog>): List<Float?> =
    logsNewestFirst.asReversed().map { it.milesDriven.toFloat() }

// ------------------------------------------------------------- RECAPS (pure)

/**
 * One calendar month's slot in the RECAPS trend charts (quant-viz ticket 05
 * part A). [milesDriven]/[avgMpg] are `null` when no [MonthlyRecap] exists
 * for that month - a GAP, never a `0f`, matching [DeckChartData.kt]'s file
 * doc invariant applied here without going through `dailyBuckets` (recaps are
 * monthly, not daily, so this module builds its own month axis rather than
 * reusing that day-grained helper).
 */
internal data class RecapMonthSlot(val year: Int, val month: Int, val milesDriven: Float?, val avgMpg: Float?)

/**
 * Every calendar month from the EARLIEST recap on file through the LATEST,
 * inclusive, one [RecapMonthSlot] per month - a month with no [MonthlyRecap]
 * row (the generator skipped a month, or the car did not exist yet) is a
 * `null`-valued gap slot rather than being omitted from the axis, so the
 * chart's x-spacing stays evenly monthly. `internal` for direct unit testing
 * (ticket 05's "month-slot builder (missing month -> null)").
 */
internal fun buildRecapMonthSlots(recaps: List<MonthlyRecap>): List<RecapMonthSlot> {
    if (recaps.isEmpty()) return emptyList()
    val byKey = recaps.associateBy { it.year * 12 + (it.month - 1) }
    val minKey = byKey.keys.min()
    val maxKey = byKey.keys.max()
    return (minKey..maxKey).map { key ->
        val year = key / 12
        val month = key % 12 + 1
        val recap = byKey[key]
        RecapMonthSlot(year, month, recap?.milesDriven?.toFloat(), recap?.avgMpg?.toFloat())
    }
}

/**
 * Maps [slots] into [com.kevin.legion.ui.common.DeckPoint]`?` for
 * [com.kevin.legion.ui.common.DeckLineChart], picking [value] per slot ([RecapMonthSlot.milesDriven]
 * or [RecapMonthSlot.avgMpg]) - a `null` field stays a `null` point, the same
 * gap the slot itself already carries. `xMs` is each month's local
 * calendar-day-1 start; [DeckLineChart] plots by index, not by this
 * timestamp, but every other [com.kevin.legion.ui.common.DeckPoint] producer
 * in the kit carries a real one and this keeps the type honest rather than
 * stuffing in a sentinel.
 */
internal fun recapMonthPoints(slots: List<RecapMonthSlot>, value: (RecapMonthSlot) -> Float?): List<com.kevin.legion.ui.common.DeckPoint?> =
    slots.map { slot ->
        val y = value(slot) ?: return@map null
        val xMs = LocalDate.of(slot.year, slot.month, 1).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        com.kevin.legion.ui.common.DeckPoint(xMs = xMs, y = y)
    }

/** "JAN" style short month name for [recapMonthXLabels] - `java.time.Month` avoids a manual 12-entry table. */
private fun monthAbbrev(month: Int): String =
    java.time.Month.of(month).getDisplayName(java.time.format.TextStyle.SHORT, java.util.Locale.US).uppercase()

/**
 * [slots]' x-axis labels, thinned to January and July (ticket 05 part A) -
 * every other index is blank, per [com.kevin.legion.ui.common.DeckLineChart]'s
 * own "callers thin their own labels" contract.
 */
internal fun recapMonthXLabels(slots: List<RecapMonthSlot>): List<String> =
    slots.map { slot -> if (slot.month == 1 || slot.month == 7) "${monthAbbrev(slot.month)} ${slot.year}" else "" }

// ------------------------------------------------------------------ rows

/**
 * One stored fault: code, description, first-seen. **Mandated fix from the
 * prototype render (ticket 09 resolution §1):** the description sits in
 * plain ink, never [com.kevin.legion.ui.theme.LegionSemantics.quarantined] -
 * red is reserved for the code itself, which is the actual alarm token. A
 * description is prose explaining the code, not a second alarm.
 *
 * [description] is null when neither [com.kevin.legion.vehicle.DtcDescriptions.loadSeed]
 * nor `loadLearned` has an entry for [row]'s code - rendered honestly as
 * "not identified locally" in faint ink rather than a blank line, so the row
 * never reads as broken.
 */
@Composable
fun FaultRowView(row: FaultRow, description: String?) {
    val sem = LocalLegionSemantics.current
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 9.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(row.code, style = LegionType.reading, color = sem.quarantined)
            Text(
                description ?: "not identified locally",
                style = MaterialTheme.typography.bodyMedium,
                color = if (description != null) MaterialTheme.colorScheme.onSurface else sem.faint,
            )
            Text("first seen ${shortDate(row.firstSeenMs)}", style = LegionType.stamp, color = sem.faint)
        }
    }
}
