package com.kevin.legion.car

import android.content.Context
import com.kevin.legion.data.local.CarDatabase
import com.kevin.legion.data.local.OdbSample
import com.kevin.legion.ui.fleet.visibleFaultCodes
import com.kevin.legion.util.Temp
import com.kevin.legion.util.relativeAge
import com.kevin.legion.vehicle.ObdBluetoothManager
import com.kevin.legion.vehicle.VehicleController
import kotlin.math.roundToInt

/**
 * Wave 3 of the Android Auto probe (`.scratch/android-auto/issues/08-what-the-browse-tree-holds.md`,
 * built PROVISIONALLY per Kevin's direct ask - see that ticket's still-open status). One function
 * per root row, each returning a (title, subtitle) pair that IS the display - Kevin asked for "some
 * kind of UI display to see my aspects" and the row itself carries a LIVE deterministic value rather
 * than a static label, so there is no separate screen to build for this wave.
 *
 * **Every number here is read from an existing controller, never invented and never an LLM call**
 * (CLAUDE.md §4 rule 5, §7's Gemini-call checklist item, and the brief's explicit "no SubAgent,
 * ever" for a tap). Where a controller's own figure is provisional/unreconciled, that word is
 * folded into the subtitle text itself, never carried only as a colour or a tag - there is no colour
 * channel available on an Android Auto media row anyway, which makes the wording the ONLY channel.
 *
 * **CORRECTED 2026-08-18** (Kevin, live on the Desktop Head Unit: "the interface and UI while in AA
 * right now sucks" - three of four tabs rendered "No items"). [fleet] still feeds the ROOT Fleet
 * folder row's title/subtitle in [LegionMediaLibraryService.libraryRootChildren]; this file also
 * exposes [fleetRows], the tab's own ordered list of [CarRow] one level under it. `Today` and
 * `Money` row builders lived here too for one night and are gone as of the SECOND correction below.
 *
 * **RESCOPED 2026-08-18, an hour later** (Kevin, after seeing the four-tab build on the Desktop Head
 * Unit): "i think the android auto only has to show fleet data. we can leave out other stuff.
 * because we're driving. we just need 2 things. push to talk and codes/telemetry gauges." This
 * supersedes the four-tab shape above - `today`/`money`/`todayRows`/`moneyRows` and their supporting
 * `capRows`/`MAX_ROWS_PER_TAB` machinery are deleted outright (grepped first: nothing outside this
 * file and [LegionMediaLibraryService] ever called them). [fleetRows] is rebuilt from scratch around
 * the two things Kevin actually named: a live-or-not connection line, the DTCs currently on file,
 * and a small set of telemetry gauges - not the maintenance-due content the old `shapeFleetRows`
 * built, which belongs to planning-ahead rather than "while I'm driving right now".
 *
 * **No live OBD read from this file, ever.** [fleetRows] reads [ObdBluetoothManager.connectionState]
 * (a `StateFlow` snapshot, free) and Room's own cached [OdbSample]/`CodeEvent`/`CodeClearEvent`
 * tables - never [ObdBluetoothManager.getDtcCodes] or any other live port query, which blocks on
 * Bluetooth I/O that may never resolve and must never run inside a browse callback (CLAUDE.md §7's
 * "network calls degrade gracefully offline", applied to a serial port instead of a socket).
 *
 * **CORRECTED 2026-08-18, third pass** (Kevin, live on a real head unit at 800x480: "Talk to
 * LEGI..." and "Fleet · 2017 ..." both truncated in the tab bar, and a plain text list with no
 * icons underneath). The tab bar is no longer fed by this file's title strings at all -
 * [LegionMediaLibraryService.libraryRootChildren] now hardcodes "Talk" and "Fleet" directly, short
 * enough to survive an 800x480 tab bar - [fleet]'s richer "Fleet · $label" return is kept only in
 * case a future caller wants it, not deleted, but nothing reads it for the tab label anymore.
 * [shapeGaugeRow] now leads its title with the NUMBER, not the label ("1800 rpm - RPM · live", not
 * "RPM 1800 rpm - live") - a driver's eye lands on the figure first. Icons are added per row in
 * [LegionMediaLibraryService] itself (this file stays a plain-JVM string builder with no Android
 * resource IDs in it, so `shapeFleetRows`' tests keep running with no Robolectric).
 */
object CarAspectSummaries {

    /** One row under a tab: title/subtitle IS the whole display, per [CarRow]'s callers - see this
     * object's class doc for why a row is playable rather than browsable, and why a tap is a no-op. */
    data class CarRow(val title: String, val subtitle: String)

    /**
     * Fleet ROOT row: the active vehicle's display name plus the soonest not-yet-due maintenance item
     * on either axis (miles or time) - the same [VehicleController.nextService] read `ui/TodayScreen.kt`
     * and `ui/FleetScreen.kt` already build their own DUE rows from, not a new query. This is the
     * FOLDER row text (what the "Fleet" tab itself is labelled before a driver opens it) - unaffected
     * by the rescope, which is about what is INSIDE the tab. See [fleetRows] for that.
     *
     * **The `next.byMiles`/`next.byTime` branches carry a "- guess, unconfirmed" suffix off
     * [VehicleController.ServiceCandidate.isGuess]** (mission-control ticket 16,
     * `.scratch/fleet-maintenance/issues/16-ticket-06-audited-a-dead-surface-and-missed-a-live-one.md`)
     * - this row's subtitle is spoken aloud on some head units (see the mileageLabel comment above),
     * and the whole point of ticket 06 is that a caveat only a screen can see does not survive being
     * read out. Same reasoning as [VehicleController.mileageLabel]'s own caveat one line up: there is
     * no second line here to carry it separately, so it rides inline in the one subtitle string.
     */
    suspend fun fleet(context: Context): Pair<String, String> {
        val vehicle = VehicleController.currentVehicle(context)
        // Ticket 04's label rule: the one rule, every surface, including this Android Auto row -
        // see VehicleController.label's own doc. Not verified on a head unit (this probe has never
        // touched one - see the file doc), so this is traced-correct rather than confirmed live.
        val label = VehicleController.label(vehicle)
        // Ticket 10: any mileage not the driver's own confirmed reading says so, in words, on every
        // surface that renders OR speaks it - Android Auto reads this row's subtitle aloud on some
        // head units, and there is no second line here to carry a caveat separately (unlike
        // ui/FleetScreen's DeckRow, this row is one string), so the whole [mileageLabel] - bare
        // reading or "about N mi - estimated, last confirmed ..." - is used rather than splitting
        // it or dropping the caveat for brevity.
        val mileageLabel = VehicleController.mileageLabel(vehicle).ifBlank { "odometer not set" }
        val next = VehicleController.nextService(context, vehicle)
        val subtitle = when {
            next == null -> "$mileageLabel · no maintenance schedule yet"
            next.odometerUnset -> "odometer not set · say your mileage to enable due-dates"
            next.byMiles != null ->
                "$mileageLabel · ${next.byMiles.serviceName} in ${next.byMiles.remaining} mi" +
                    (if (next.byMiles.isGuess) " - guess, unconfirmed" else "")
            next.byTime != null ->
                "$mileageLabel · ${next.byTime.serviceName} in ${next.byTime.remaining} days" +
                    (if (next.byTime.isGuess) " - guess, unconfirmed" else "")
            next.allDue -> "$mileageLabel · everything scheduled is already due"
            else -> "$mileageLabel · nothing due yet"
        }
        // CORRECTED 2026-08-18, second pass (Kevin, live: two tabs read "Talk to LEGI..." and
        // "Fleet · 2017 ..." on an 800x480 head unit - both truncated). The tab BAR has almost no
        // room; a static "Fleet" is what LegionMediaLibraryService.libraryRootChildren now titles
        // the root row with directly (it no longer reads this Pair's first element for the tab
        // label). This function's title return is kept only for a caller that still wants the
        // richer "Fleet · <vehicle>" form - none exists today - so it is returned as-is rather than
        // deleted outright; the vehicle name and the live due-date/mileage figure live in the tab's
        // OWN rows now ([fleetRows]' shapeConnectionRow), which is where a driver who opened the tab
        // actually reads them, not in a label they see for a fraction of a second before tapping in.
        return "Fleet · $label" to subtitle
    }

    // ------------------------------------------------------------------------------- tab rows

    /**
     * PIDs [fleetRows] shows as gauges, in display order - RPM, coolant temp, speed. The exact set
     * [TelemetryRecorder.kt] polls every tick is wider (fuel trims, MAF, intake air...); these three
     * are the ones a driver glances at, matching the sketch Kevin approved before tonight's revision
     * ("Oil life / Next service / No trouble codes" was the OLD sketch - gauges are the new one).
     */
    private val GAUGE_PIDS = listOf(
        GaugeSpec("010C", "RPM"),
        GaugeSpec("0105", "Coolant temp"),
        GaugeSpec("010D", "Speed"),
    )

    /** One PID this tab reads a gauge for. `internal` so the test can build its own tiny set rather
     * than depending on [GAUGE_PIDS]'s exact membership. */
    internal data class GaugeSpec(val pid: String, val label: String)

    /** A single gauge's raw ingredients, already read from Room by [fleetRows] - `internal` so
     * [shapeFleetRows] stays pure (no `Context`, no Room) and directly unit testable. [formattedValue]
     * is `null` exactly when there is no cached sample at all, never a fabricated placeholder. */
    internal data class GaugeReading(
        val label: String,
        val sampleTimestampMs: Long? = null,
        val formattedValue: String? = null,
    )

    /**
     * A 30 s-tick reading is "live" for up to three missed ticks (90 s) before this tab calls it
     * stale - one dropped Bluetooth poll should not flip a gauge from LIVE to STALE, but a car that
     * has been sitting disconnected for five minutes should. [TelemetryRecorder]'s own cadence is
     * the anchor for this number, not a guess.
     */
    private const val GAUGE_FRESH_WINDOW_MS = 90_000L

    /**
     * Fleet tab's rows, Kevin's 2026-08-18 rescope ("codes/telemetry gauges", nothing else): a
     * connection line, the DTCs currently on file, and [GAUGE_PIDS]'s three telemetry gauges - five
     * rows, fixed, no truncation logic needed (unlike the old maintenance-due content this replaces,
     * the count here never grows with the data). **Room + one `StateFlow` snapshot only, no network,
     * no live OBD read** - see the class doc's "No live OBD read from this file, ever." Purely a
     * thin Context wrapper; [shapeFleetRows] carries the actual shaping and is unit tested.
     */
    suspend fun fleetRows(context: Context): List<CarRow> {
        val vehicle = VehicleController.currentVehicle(context)
        val label = VehicleController.label(vehicle)
        val connected = ObdBluetoothManager.connectionState.value == ObdBluetoothManager.ConnectionState.CONNECTED
        val nowMs = System.currentTimeMillis()

        val db = CarDatabase.getDatabase(context)
        val odbDao = db.odbSampleDao()
        val gauges = GAUGE_PIDS.map { spec ->
            val sample = odbDao.getLatest(vehicle.obdMac, spec.pid, 1).firstOrNull()
            if (sample == null) {
                GaugeReading(spec.label)
            } else {
                GaugeReading(spec.label, sample.timestamp, formatGaugeValue(context, spec.pid, sample))
            }
        }

        // D7's union rule (ui/fleet/FleetRows.kt's visibleFaultCodes, reused rather than
        // re-derived - see the class doc's "cross-package pure logic" precedent, the same one
        // advisor/digest/FleetDigestBuilder.kt already established for distinctFaultsByFirstSeen):
        // codes newer than the latest full CLEARED clear, unioned with any later clear attempt's
        // own surviving codes. This is a Room read, never a live Mode 03/04 port query.
        val codeEvents = db.codeEventDao().getAll(vehicle.obdMac)
        val clearEvents = db.codeClearEventDao().getAll(vehicle.obdMac)
        val (activeCodes, _) = visibleFaultCodes(codeEvents, clearEvents)
        val latestCodeCheckMs = codeEvents.maxByOrNull { it.timestamp }?.timestamp

        return shapeFleetRows(
            vehicleLabel = label,
            connected = connected,
            gauges = gauges,
            hasEverReadCodes = codeEvents.isNotEmpty(),
            activeCodes = activeCodes.toList(),
            latestCodeCheckMs = latestCodeCheckMs,
            nowMs = nowMs,
        )
    }

    /** Pure row-shaping for [fleetRows] - `internal` for direct unit testing, no `Context`/Room. */
    internal fun shapeFleetRows(
        vehicleLabel: String,
        connected: Boolean,
        gauges: List<GaugeReading>,
        hasEverReadCodes: Boolean,
        activeCodes: List<String>,
        latestCodeCheckMs: Long?,
        nowMs: Long,
    ): List<CarRow> {
        val rows = mutableListOf<CarRow>()
        rows += shapeConnectionRow(vehicleLabel, connected)
        rows += shapeCodesRow(hasEverReadCodes, activeCodes, latestCodeCheckMs, nowMs)
        gauges.forEach { gauge ->
            rows += shapeGaugeRow(gauge.label, connected, gauge.sampleTimestampMs, gauge.formattedValue, nowMs)
        }
        return rows
    }

    /**
     * The tab's first row: which car, and whether the OBD link is live right now. **Everything a
     * driver needs is in the TITLE** (rule 1 from tonight's head-unit session - Android Auto did not
     * draw a subtitle for the Money tab's bare figures, so nothing here may depend on the subtitle
     * being seen). `internal` for direct unit testing.
     */
    internal fun shapeConnectionRow(vehicleLabel: String, connected: Boolean): CarRow =
        if (connected) {
            CarRow("$vehicleLabel - connected", "OBD link is live")
        } else {
            CarRow("$vehicleLabel - not connected", "OBD adapter is not linked right now")
        }

    /**
     * The tab's second row: what is currently on file for trouble codes, read from Room's own
     * [com.kevin.legion.data.local.CodeEvent]/[com.kevin.legion.data.local.CodeClearEvent] history -
     * never a live Mode 03 read (see [fleetRows]' own doc). Three honest states, CLAUDE.md §4's own
     * posture applied to a car row instead of a bank statement:
     *  - **never scanned** ([hasEverReadCodes] false) - "codes not read", not a fabricated "no
     *    trouble codes". A silence in the data is not the same fact as a clean scan and must never
     *    read as one.
     *  - **scanned, nothing active** - "no active codes", with the age of that scan stated in words,
     *    because a clean scan from three days ago is a different fact from one from three minutes ago.
     *  - **scanned, codes active** - the codes themselves plus the same age.
     * `internal` for direct unit testing.
     */
    internal fun shapeCodesRow(
        hasEverReadCodes: Boolean,
        activeCodes: List<String>,
        latestCodeCheckMs: Long?,
        nowMs: Long,
    ): CarRow {
        if (!hasEverReadCodes || latestCodeCheckMs == null) {
            return CarRow("Codes not read", "no OBD trouble-code scan on file yet")
        }
        val age = relativeAge(latestCodeCheckMs, nowMs)
        return if (activeCodes.isEmpty()) {
            CarRow("No active codes - checked $age", "last checked $age")
        } else {
            val codesText = activeCodes.sorted().joinToString(", ")
            CarRow("$codesText - checked $age", "${activeCodes.size} code(s), last checked $age")
        }
    }

    /**
     * One telemetry gauge row. Four honest states, all in the TITLE (same rule 1 as
     * [shapeConnectionRow]).
     *
     * **CORRECTED 2026-08-18, second pass** (Kevin, on a real head unit's browse list, rule 3 of
     * this ticket's brief: "row titles ... keep them tight and put the number first, since that is
     * what a driver's eye lands on"). The two states with an actual number now lead with it -
     * "$formattedValue - $label · live" - rather than burying it after the label the way the first
     * pass did ("RPM 1800 rpm - live"). The two states with nothing to read yet still lead with the
     * label, because there is no number to promote:
     *  - **not connected, nothing cached** - "$label - not connected".
     *  - **connected, nothing cached yet** - "$label - waiting for first reading" (the adapter just
     *    linked and [TelemetryRecorder] has not ticked yet).
     *  - **fresh cached sample** ([GAUGE_FRESH_WINDOW_MS]) - "$value - $label · live".
     *  - **stale cached sample** - "$value - $label · $age, not live". A driver reading a 20-minute-
     *    old RPM figure while the car is stopped at a light must never mistake it for a live gauge.
     * `internal` for direct unit testing.
     */
    internal fun shapeGaugeRow(
        label: String,
        connected: Boolean,
        sampleTimestampMs: Long?,
        formattedValue: String?,
        nowMs: Long,
    ): CarRow {
        if (sampleTimestampMs == null || formattedValue == null) {
            return if (connected) {
                CarRow("$label - waiting for first reading", "connected, no sample yet")
            } else {
                CarRow("$label - not connected", "connect the OBD adapter to read this")
            }
        }
        val ageMs = (nowMs - sampleTimestampMs).coerceAtLeast(0L)
        val age = relativeAge(sampleTimestampMs, nowMs)
        return if (ageMs <= GAUGE_FRESH_WINDOW_MS) {
            CarRow("$formattedValue - $label · live", "updated $age")
        } else {
            CarRow("$formattedValue - $label · $age, not live", "last read $age")
        }
    }

    /**
     * [OdbSample.value]/[OdbSample.unit] rendered for a driver, per PID. Coolant temp goes through
     * [Temp.text] (the ONE place a stored Celsius reading turns into text, per that file's own class
     * doc - never a second inline `* 9/5 + 32`). Speed's km/h->mph factor is the SAME inline
     * `0.621371` every other human-facing speed surface in the app already uses
     * (`ui/fleet/FleetRows.kt`, `ui/fleet/FleetDrilldowns.kt`, `vehicle/CarToolbelt.kt`) - distance/
     * speed has no shared converter the way temperature now does (`util/Units.kt`'s own doc: "Not
     * swept: distance and speed"), so matching the existing convention is correct here, not a gap.
     */
    private fun formatGaugeValue(context: Context, pid: String, sample: OdbSample): String = when (pid) {
        "010C" -> "${sample.value.roundToInt()} rpm"
        "0105" -> Temp.text(context, sample.value)
        "010D" -> "${(sample.value * 0.621371).roundToInt()} mph"
        else -> "${sample.value} ${sample.unit}"
    }
}
