package com.kevin.legion.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import android.view.WindowManager
import com.kevin.legion.data.local.CarDatabase
import com.kevin.legion.data.local.Drive
import com.kevin.legion.service.CompanionPhase
import com.kevin.legion.ui.assistant.AssistantStripResolver
import com.kevin.legion.ui.theme.DeckChrome
import com.kevin.legion.ui.theme.LegionTheme
import com.kevin.legion.ui.theme.LegionType
import com.kevin.legion.ui.theme.LocalLegionSemantics
import com.kevin.legion.ui.theme.deckMotionEnabled
import com.kevin.legion.util.Temp
import com.kevin.legion.util.clockTime
import com.kevin.legion.util.relativeAge
import com.kevin.legion.vehicle.ObdBluetoothManager
import com.kevin.legion.vehicle.VehicleController
import kotlinx.coroutines.delay

/**
 * `driving` - the retro-instrument-panel rebuild (drive-ui ticket 04, direction approved by
 * Kevin 2026-08-16 off a reference 80s dashboard photo: "look at the bars and shit. retro
 * futuristic vibes. think akira, think evangelion"). Full-bleed, no [ui.common.StatusLine], no
 * hard-key row. Entry is always an OFFER (ticket 11 answer §1, carried over unchanged: [FleetScreen]'s
 * UPLINK panel `DRIVE MODE` row navigates here on tap - see that file's `DriveModeOfferRow`); this
 * screen never triggers itself.
 *
 * **The centrepiece is a segmented-column instrument, not a swept arc.** SPEED is the primary
 * readout (drive-ui ticket 04 answer Q12 - RPM no longer wins by default; this screen used to have
 * an RPM-leads-speed-fallback dial-selection ladder, [selectDialSource]/[DialSource] in the old
 * `DrivingDialMath.kt`, and that whole mechanism is GONE, not merely renamed). RPM is the second
 * instrument, same segmented form, drawn smaller. COOLANT is a fader - a continuous filled track,
 * not segments, "exactly like the reference's `COLD ▬▬ HOT` slider" (ticket 04's own words).
 * A trip block (ELAPSED/DISTANCE) fills what used to be a third of the screen sitting empty - see
 * [TripBlock]'s own doc for what it actually shows (the last FINISHED drive, never a fabricated
 * "so far" figure for one in progress) and why.
 *
 * **Motion is ALLOWED here now, deliberately, not merely un-banned.** This file used to carry six
 * doc comments forbidding all animation, citing a retired head-unit "ambient-motion ration" that
 * CLAUDE.md has lifted twice over (§2, §7) - those comments are gone, not reworded. Ticket 06
 * (Kevin, overruling the no-interpolation recommendation): **"interpolate its ok. we are adults we
 * know the gauges are slow."** So the segment fill fraction for SPEED/RPM/COOLANT eases toward
 * each new poll reading over [BAR_TRANSITION_MS] via `animateFloatAsState`, and because the
 * instrument is discrete blocks rather than a continuous needle, the eased fraction only ever
 * changes WHICH segments are lit, not a fabricated in-between value drawn as if it were real - see
 * [DrivingDialMath.kt]'s file doc for the fuller argument. One ambient liveness signal (a small
 * dot beside the HUD's link status, [DrivingHudLine]) breathes once per poll tick so a ~2 Hz screen
 * does not read as frozen. **All of this is gated on [deckMotionEnabled] AND on the link being
 * live** (ticket 06 Q21) - a manual no-link entry gets zero motion of any kind, same as before.
 *
 * **Readings live-poll the same way UPLINK does** (ticket 20 build brief item 2, unchanged by this
 * rebuild): a periodic re-query of `CarDatabase`'s latest [com.kevin.legion.data.local.OdbSample]
 * per PID, the exact mechanism [FleetScreen]'s `UplinkPane` uses for its own LIVE block
 * (`odbSampleDao().getLatest`), not a raw [ObdBluetoothManager.getRpm]/`getCoolantTemp` command
 * sent from here. Those two suspend funs write straight to the same single-socket RFCOMM/BLE
 * connection [com.kevin.legion.vehicle.TelemetryRecorder] is already polling on its own loop while
 * the engine runs and the dongle is connected (traced: `ObdBluetoothManager.sendCommand` has no
 * mutex or command queue visible from this file) - sending a second, independent command stream
 * from this screen risks interleaving with that loop on the same wire. Reading the DB it already
 * writes to is the same "reuse, do not duplicate the OBD read path" posture DRIVES/UPLINK already
 * use for their own panels. `POLL_MS` is unchanged at 2000 - live cadence is drive-ui ticket 03's
 * job, blocked on measurement, and out of scope here.
 *
 * **All three PIDs are polled every tick, unconditionally** - SPEED and RPM are both always-shown
 * instruments now (not a dial that picks one), and COOLANT's fader needs its own reading regardless.
 *
 * **Exit: EXIT key, or the link dropping, whichever comes first.** No confirmation dialog either
 * way (ticket 11 answer §2, layout ticket 08 Q27 re-affirms it). The link-drop watch polls
 * [ObdBluetoothManager.isConnected] on the same cadence as the readouts rather than a bespoke
 * faster timer - a driver who unplugs the dongle mid-drive is not going to notice a few hundred
 * milliseconds' difference.
 */
private const val POLL_MS = 2_000L

/**
 * What the Alfred strip says instead of a phase word while the capture is silenced.
 * Deliberately short - this line is glanced at from a mount, not read.
 */
private const val SILENCED_STATUS = "CAN'T HEAR YOU"

/** The clock in the HUD line only needs to be right to the minute (approved mock) - see [clockTime]'s own doc for why the deck never shows seconds. */
private const val CLOCK_POLL_MS = 60_000L

/** The three PIDs this screen ever asks for. */
private const val PID_RPM = "010C"
private const val PID_SPEED = "010D"
private const val PID_COOLANT = "0105"

/**
 * Per-vehicle in spirit, NOT yet actually per-vehicle in code, for BOTH scales below -
 * [com.kevin.legion.data.local.Vehicle] has no redline/top-speed-ceiling column, and adding one
 * is a Room migration this ticket did not scope (CLAUDE.md §5's migration checklist: verbatim
 * generated SQL, `exportSchema`, a migration test - none of that belongs in a UI rebuild). Both
 * constants are the correct values for the one car on this install today; making them real
 * per-car fields is follow-up work, tracked back to ticket 04 rather than invented here. Do not
 * read their being `private const val`s as "this is fine forever" - it is "this has no home yet".
 *
 * [RPM_SCALE_MAX] (drive-ui ticket 04 answer Q10: "a 4.0L XJ redlines around 4600-5000... per-
 * vehicle maximum, defaulting to ~5500 for the Jeep").
 *
 * [SPEED_SCALE_MPH_MAX] was found still wrong on-device after the RPM fix landed - 120mph on a
 * Jeep that realistically tops out near 80-85 is the exact same "a scale that can never fill is a
 * scale that wastes its range" defect ticket 04 already named for RPM, missed one gauge over. Set
 * to 90: comfortably above the car's real ceiling (so a genuine top-end reading never pins the
 * bar at full) without spending most of the segment column on speeds this Jeep will never see.
 */
private const val RPM_SCALE_MAX = 5500f
private const val SPEED_SCALE_MPH_MAX = 90f

/**
 * Coolant renders in Celsius now (ticket 07 answer #2: "it already matches two of the three
 * screens... DRIVE MODE's `177 F` pod is the outlier and moves"). 130C is roughly the old 260F
 * ceiling's equivalent ((260-32)*5/9 = 126.7C), rounded to a clean scale value rather than
 * preserved to the decimal - the old ceiling was never a measured limit, just a scale that had to
 * clear a hot-running engine.
 */
private const val COOLANT_SCALE_C_MAX = 130f

/** Segment resolution for the two segmented columns - SPEED (primary) reads finer than RPM (secondary), matching "same segmented form, smaller" rather than just a shorter bar at the same resolution. */
private const val SEGMENTS_SPEED = 20
private const val SEGMENTS_RPM = 14

/** How long a segment column or the coolant fader eases toward a newly-polled reading, when motion is allowed at all (see file doc). Well under [POLL_MS] so one transition always finishes before the next reading can start a new one. */
private const val BAR_TRANSITION_MS = 650

/** One raw reading plus its worded staleness (never a bare number - CLAUDE.md §4/§7, [relativeAge] discipline carried over from [com.kevin.legion.ui.fleet.buildLiveRows]). [raw] is always the PID's own native unit - km/h for speed (converted to mph at the read site below via [kmhToMph]), and now Celsius for coolant too (ticket 07: no conversion needed, the PID and the display finally agree). */
private data class DrivingSample(val raw: Float, val age: String)

@Composable
fun DrivingModeScreen(onExit: () -> Unit) {
    val context = LocalContext.current
    var vehicleName by remember { mutableStateOf("") }
    var linkLive by remember { mutableStateOf(false) }
    var rpm by remember { mutableStateOf<DrivingSample?>(null) }
    var speed by remember { mutableStateOf<DrivingSample?>(null) }
    var coolant by remember { mutableStateOf<DrivingSample?>(null) }
    // The trip block's one input - the vehicle's last FINISHED drive, or null if it has none yet.
    // Fetched once below, not on the POLL_MS loop - see that fetch site's own comment.
    var lastDrive by remember { mutableStateOf<Drive?>(null) }
    // Increments on every poll iteration, live or not - the sole input to the HUD's liveness
    // pulse (file doc: "one ambient liveness signal... per poll tick"). A plain counter rather
    // than deriving "did a reading change" from the samples themselves, because the signal this
    // exists to give is "the loop is still running", not "a value moved" - those are different
    // claims and only the first one is what a frozen-looking screen needs.
    var tickCounter by remember { mutableStateOf(0) }

    // Keep-screen-on while this destination is composed - the ticket's own
    // instruction ("keep-screen-on while active"). Tied to DisposableEffect,
    // not a one-shot LaunchedEffect flag set/never cleared, so the flag comes
    // off the window the instant this screen leaves composition (EXIT tap or
    // the automatic link-drop exit below), rather than bleeding into every
    // other screen for the rest of the activity's life.
    val view = LocalView.current
    DisposableEffect(Unit) {
        // LocalView's context is the Activity that actually owns a Window -
        // MainActivity is single-activity-shell (ui/MainActivity.kt's own
        // doc), so this is always the one window the app draws into, unlike
        // LocalContext which can be wrapped by a theme/config ContextWrapper.
        val window = (view.context as? android.app.Activity)?.window
        window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose {
            window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    // The live/last-known poll loop, and the automatic-exit watch on the same
    // tick. Runs for as long as this composable stays on screen; DisposableEffect
    // above tears the screen-on flag down independently the moment it stops,
    // whichever exit path fired.
    LaunchedEffect(Unit) {
        val vehicle = VehicleController.currentVehicle(context)
        // Ticket 04's label rule: the one rule, every surface - see VehicleController.label's doc.
        // This used to be raw Vehicle.name, which is how the seedVehicle placeholder's literal
        // "this car" reached this screen and got shouted by the .uppercase() below.
        vehicleName = VehicleController.label(vehicle)
        val dao = CarDatabase.getDatabase(context).odbSampleDao()
        // The trip block's read (ticket 05/08): the last FINISHED drive only, fetched ONCE here
        // rather than on the POLL_MS loop below. Unlike RPM/speed/coolant, a finished Drive row
        // never changes while this screen is open - DriveDao's own doc: "no update, no delete" -
        // so re-querying it every 2s would just repeat the same read for zero benefit. If a drive
        // finalises WHILE this screen happens to be open (engine off, link lost), the trip block
        // keeps showing whichever drive was last finished when the screen was entered; it is
        // labelled "LAST DRIVE" precisely because it is not claimed to be live.
        lastDrive = CarDatabase.getDatabase(context).driveDao().getRecent(vehicle.obdMac, 1).firstOrNull()
        // Manual override (Kevin, 2026-08-08): driving mode can be entered
        // with NO dongle paired at all, so "the link dropped" only means
        // anything if there was a link when we arrived. Without this latch a
        // manual no-link entry would satisfy `!isConnected` on the first tick
        // and eject the driver before the screen ever drew.
        val enteredWithLink = ObdBluetoothManager.isConnected
        while (true) {
            if (enteredWithLink && !ObdBluetoothManager.isConnected) {
                // Ticket 11 answer §2: the link dropping exits instantly, no
                // confirmation. This is the ONLY place that condition is
                // checked - a bespoke faster poll would be exactly the kind
                // of extra motion this screen does not need for it. Entered
                // manually without a link, the EXIT key is the only way out -
                // there is no link whose drop could mean "the drive ended".
                onExit()
                return@LaunchedEffect
            }
            linkLive = ObdBluetoothManager.isConnected
            val now = System.currentTimeMillis()
            val rpmSample = dao.getLatest(vehicle.obdMac, PID_RPM, 1).firstOrNull()
            val speedSample = dao.getLatest(vehicle.obdMac, PID_SPEED, 1).firstOrNull()
            val coolantSample = dao.getLatest(vehicle.obdMac, PID_COOLANT, 1).firstOrNull()
            rpm = rpmSample?.let { DrivingSample(it.value.toFloat(), relativeAge(it.timestamp, now)) }
            speed = speedSample?.let { DrivingSample(it.value.toFloat(), relativeAge(it.timestamp, now)) }
            coolant = coolantSample?.let { DrivingSample(it.value.toFloat(), relativeAge(it.timestamp, now)) }
            tickCounter++
            delay(POLL_MS)
        }
    }

    val phase by CompanionPhase.phase.collectAsStateWithLifecycle()
    // "Static-per-minute is fine" for this screen's HUD clock - a once-a-
    // minute produceState tick, same cadence MainActivity's own shell clock
    // uses for the identical reason - a once-a-second read here would
    // recompose the whole HUD row for no legibility gain.
    val clock by produceState(initialValue = clockTime(System.currentTimeMillis())) {
        while (true) {
            value = clockTime(System.currentTimeMillis())
            delay(CLOCK_POLL_MS)
        }
    }

    // Fix 4's footer band needs a real protocol string, not an invented one - this is the same
    // StateFlow ObdDeviceScreen already reads for its own "Protocol" row
    // (`ObdBluetoothManager.adapterInfo`), so this screen adds a second collector onto state that
    // already exists rather than plumbing a new read path. `null` until the adapter's ATI/ATDP
    // handshake actually completes, which [TechnicalFooterBand] treats as "leave it out", never
    // as "print a placeholder".
    val adapterInfo by ObdBluetoothManager.adapterInfo.collectAsStateWithLifecycle()

    // Ticket 15's signal. False below API 29 means "not known to be silenced", never
    // "confirmed hearing you" - the platform offers no equivalent signal there.
    val silenced by CompanionPhase.silenced.collectAsStateWithLifecycle()

    DrivingModeContent(
        vehicleName = vehicleName,
        linkLive = linkLive,
        clock = clock,
        rpm = rpm,
        speed = speed,
        coolant = coolant,
        lastDrive = lastDrive,
        // The Alfred status line reuses AssistantStripResolver's own phase
        // wording (ticket 20 build brief item 2): no new voice-state
        // vocabulary invented for this one screen.
        //
        // A silenced capture overrides the phase word here for the same reason it does
        // on the phone strip (ticket 15) - and it matters MORE here, because the case
        // that silences LEGION is another app taking a privacy-sensitive capture in the
        // car, and a driver reading "Listening..." on a mounted phone has no other way
        // to find out nothing is on the wire.
        alfredStatus = if (silenced) SILENCED_STATUS else AssistantStripResolver.phaseLabel(phase),
        tickCounter = tickCounter,
        protocolName = adapterInfo?.protocolName,
        onExit = onExit,
    )
}

/** Plain UI: the instrument-panel layout with no Room/OBD reference, see the file doc's state-holder split. */
@Composable
private fun DrivingModeContent(
    vehicleName: String,
    linkLive: Boolean,
    clock: String,
    rpm: DrivingSample?,
    speed: DrivingSample?,
    coolant: DrivingSample?,
    lastDrive: Drive?,
    alfredStatus: String,
    tickCounter: Int,
    protocolName: String?,
    onExit: () -> Unit,
) {
    val sem = LocalLegionSemantics.current
    val context = LocalContext.current
    // The single motion gate for this whole screen (file doc): the OS accessibility signal AND
    // the link actually being live. A manual no-link entry - or reduced-motion - gets a screen
    // that jumps straight to every value, never eases toward it.
    val allowMotion = deckMotionEnabled() && linkLive
    val transitionSpec = if (allowMotion) tween<Float>(BAR_TRANSITION_MS) else snap()

    // ---- SPEED: the primary readout (ticket 04 answer Q12 - RPM no longer wins by default) ----
    val speedHasReading = speed != null
    val speedStale = !linkLive && speedHasReading
    val speedMph = speed?.let { kmhToMph(it.raw) }
    val speedRawFraction = speedMph?.let { dialFraction(it, SPEED_SCALE_MPH_MAX) } ?: 0f
    val speedFraction by animateFloatAsState(targetValue = speedRawFraction, animationSpec = transitionSpec, label = "speed-fraction")
    val speedValueText = speedMph?.let { "%.0f".format(it) } ?: "NO READING ON FILE"

    // ---- RPM: the second instrument, same segmented form, smaller ----
    val rpmHasReading = rpm != null
    val rpmStale = !linkLive && rpmHasReading
    val rpmRawFraction = rpm?.let { dialFraction(it.raw, RPM_SCALE_MAX) } ?: 0f
    val rpmFraction by animateFloatAsState(targetValue = rpmRawFraction, animationSpec = transitionSpec, label = "rpm-fraction")
    val rpmValueText = rpm?.let { "%.1f".format(it.raw / 1000f) } ?: "NO READING ON FILE"

    // ---- COOLANT: a fader, not segments (ticket 04: "literally what that control is in the photo") ----
    val coolantHasReading = coolant != null
    val coolantStale = !linkLive && coolantHasReading
    val coolantRawFraction = coolant?.let { dialFraction(it.raw, COOLANT_SCALE_C_MAX) } ?: 0f
    val coolantFraction by animateFloatAsState(targetValue = coolantRawFraction, animationSpec = transitionSpec, label = "coolant-fraction")
    val coolantValueText = coolant?.let { Temp.text(context, it.raw.toDouble()) } ?: "NO READING ON FILE"

    // ---- The one ambient liveness signal (ticket 06 Q19/Q21) ----
    // Flips every poll tick, live or not; the alpha animation it drives is what actually gates on
    // allowMotion (below) - a dead link freezes the flag but the HUD never reads it because
    // DrivingHudLine only draws the dot at all when linkLive is true (see that composable).
    var pulsePhase by remember { mutableStateOf(false) }
    LaunchedEffect(tickCounter) { pulsePhase = !pulsePhase }
    val pulseAlpha by animateFloatAsState(
        targetValue = if (!allowMotion) 1f else if (pulsePhase) 1f else 0.3f,
        animationSpec = if (allowMotion) tween(POLL_MS.toInt() - 200) else snap(),
        label = "liveness-pulse",
    )

    // Full-bleed ground, no StatusLine, no hard-key row - a destination
    // outside the shell chrome (ticket 20 scope note; LegionShell branches on
    // the current route to skip both, see ui/MainActivity.kt).
    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        Column(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 16.dp),
        ) {
            DrivingHudLine(vehicleName = vehicleName, linkLive = linkLive, clock = clock, pulseAlpha = pulseAlpha)
            Spacer(Modifier.height(12.dp))

            // The two segmented columns. Fix 2 (both installed on the real phone read as
            // accidentally misaligned - different panel tops AND different panel heights): both
            // [SegmentColumn]s now get an equal `.fillMaxHeight()` PANEL, full stop, so the
            // bracket panels themselves share a top and a baseline. RPM staying visually shorter
            // is preserved as the deliberate hierarchy it always was - it just moved from "a
            // shorter panel" to a narrower column with fewer segments. It does NOT mean a shorter
            // bar face: bottom-aligning RPM inside an equal-height panel was tried and left a void
            // above it (Kevin, on-device 2026-08-16). `verticalAlignment` no longer does
            // any real work now that both children fill the same height, but is left as `Bottom`
            // rather than removed - it costs nothing and keeps this Row's behaviour well-defined
            // if a future caller ever gives one column a shorter modifier again.
            Row(
                Modifier.weight(1f).fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalAlignment = Alignment.Bottom,
            ) {
                SegmentColumn(
                    label = "SPEED",
                    pidCode = PID_SPEED,
                    valueText = speedValueText,
                    unitLabel = "MPH",
                    ageText = speed?.age,
                    valueStyle = MaterialTheme.typography.displayLarge,
                    fraction = speedFraction,
                    totalSegments = SEGMENTS_SPEED,
                    // No redline on speed - the annotation belongs to RPM's own scale only
                    // (ticket 04: "top segments of the RPM scale"), so the zone start is pinned
                    // past the last valid index and no segment ever matches it.
                    redlineStartIndex = SEGMENTS_SPEED,
                    stale = speedStale,
                    hasReading = speedHasReading,
                    ticks = scaleTicks(SPEED_SCALE_MPH_MAX, 30f),
                    tickFormatter = { "%.0f".format(it) },
                    modifier = Modifier.weight(1.3f).fillMaxHeight(),
                )
                SegmentColumn(
                    label = "RPM",
                    pidCode = PID_RPM,
                    valueText = rpmValueText,
                    unitLabel = "×1000",
                    ageText = rpm?.age,
                    valueStyle = MaterialTheme.typography.displayMedium,
                    fraction = rpmFraction,
                    totalSegments = SEGMENTS_RPM,
                    redlineStartIndex = redlineSegmentStartIndex(SEGMENTS_RPM),
                    stale = rpmStale,
                    hasReading = rpmHasReading,
                    ticks = scaleTicks(RPM_SCALE_MAX, 1000f),
                    tickFormatter = { "%.0f".format(it / 1000f) },
                    // The panel is full height now (see this Row's own comment) - this is the ONE
                    // remaining place "RPM reads smaller" lives, as the fraction of that full-
                    // height panel the printed bar face actually occupies, bottom-aligned.
                    // Fix 2b (Kevin, on-device 2026-08-16): barHeightFraction is GONE, not
                    // just changed. Bottom-aligning RPM's bar inside an equal-height panel left a
                    // visible void above it that read as a missing element rather than as
                    // hierarchy - and the reference photo's EQ display has uniform-height columns
                    // of differing content, never one column that stops short. Hierarchy now comes
                    // from WIDTH (speed's 1.3f vs this 1f) and from segment count (20 vs 14).
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                )
            }
            Spacer(Modifier.height(16.dp))
            CoolantFader(
                pidCode = PID_COOLANT,
                valueText = coolantValueText,
                ageText = coolant?.age,
                stale = coolantStale,
                hasReading = coolantHasReading,
                fraction = coolantFraction,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(12.dp))
            TripBlock(drive = lastDrive, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(6.dp))
            // Fix 4's technical footer band - one thin row of printed facts, real values only
            // (this composable's own instruction: nothing this screen cannot actually source).
            // Link state duplicates the HUD line on purpose - the HUD line is chrome ("is Alfred
            // hearing the car"), this line is the instrument face's own technical strip ("what
            // protocol, how often") - see [TechnicalFooterBand]'s own doc.
            TechnicalFooterBand(
                linkLive = linkLive,
                protocolName = protocolName,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        // Alfred moves to thumb level, just above EXIT (layout ticket 08 Q28) - it used to sit
        // mid-screen between the pods and the dead third; the dead third is gone and so is the
        // reason Alfred was floating above it.
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp)) {
            DrivingAlfredStrip(alfredStatus)
        }

        // The giant EXIT hard-key: bottom, full-width, huge touch target, no
        // confirmation (ticket 11 answer §2, layout ticket 08 Q27). A 2dp
        // edge-color border rather than a solid quarantine-red fill - EXIT is
        // a control, not a failed-gate/crisis verdict, so it does not belong
        // in ticket 03's red family at all; amber letterspaced text on an
        // outlined key matches the rest of the deck's bracket/outline register.
        Box(
            Modifier
                .fillMaxWidth()
                .height(EXIT_KEY_HEIGHT)
                .border(2.dp, sem.rule)
                .clickable(onClick = onExit),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                "EXIT",
                style = MaterialTheme.typography.displaySmall.copy(letterSpacing = 6.sp),
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

private val EXIT_KEY_HEIGHT = 72.dp

/**
 * The HUD line (top of the panel): link state left, vehicle name + clock right. Green-family
 * accent ([LegionSemantics.credit], mint) on a live link only - a dropped/never-paired link reads
 * muted, never red (a dead link during manual entry is an expected, informed state, not a
 * failure), matching ticket 03's "red is exclusively a failed-gate/crisis verdict" contract.
 *
 * [pulseAlpha] drives the one small dot beside "LINK LIVE" - the screen's single ambient liveness
 * signal (ticket 06 Q19/Q21). The dot is only drawn AT ALL when [linkLive] is true: a no-link
 * screen does not merely get the dot held static, it never gets a dot, which is the simplest way
 * to satisfy "a no-link screen does not animate" without a second gate to keep in sync with
 * [DrivingModeContent]'s `allowMotion`.
 */
@Composable
private fun DrivingHudLine(vehicleName: String, linkLive: Boolean, clock: String, pulseAlpha: Float) {
    val sem = LocalLegionSemantics.current
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                buildAnnotatedString {
                    withStyle(SpanStyle(color = sem.faint)) { append("DRIVE // ") }
                    withStyle(SpanStyle(color = if (linkLive) sem.credit else sem.faint)) {
                        append(if (linkLive) "LINK LIVE" else "MANUAL · NO LINK")
                    }
                },
                style = LegionType.stamp,
            )
            if (linkLive) {
                Box(Modifier.size(6.dp).background(sem.credit.copy(alpha = pulseAlpha)))
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (vehicleName.isNotBlank()) {
                // NOT .uppercase() (ticket 04's label rule) - LegionType.stamp is chrome styling,
                // uppercasing is a chrome concern, and a car's name is DATA the driver typed.
                Text(vehicleName, style = LegionType.stamp, color = sem.faint)
            }
            Text(clock, style = LegionType.stamp, color = sem.faint)
        }
    }
}

/**
 * A local, lightweight variant of [com.kevin.legion.ui.common.DeckPane]'s corner-bracket
 * treatment (unchanged reasoning from the pre-rebuild file: `DeckPane`'s header row fights this
 * screen's centered "label above a giant value" read), factored out here because the rebuild now
 * has three callers - [SegmentColumn], [CoolantFader], [TripBlock] - instead of the original's
 * one, and CLAUDE.md's density-without-duplication posture says three copies of the same four
 * `drawLine` calls is the wrong amount of copy-paste even for a screen-local helper.
 */
@Composable
private fun BracketPanel(
    modifier: Modifier = Modifier,
    horizontalAlignment: Alignment.Horizontal = Alignment.Start,
    content: @Composable ColumnScope.() -> Unit,
) {
    val sem = LocalLegionSemantics.current
    val bracketColor = sem.rule
    val density = LocalDensity.current
    val bracketStroke = with(density) { 2.dp.toPx() }
    val bracketArm = with(density) { 8.dp.toPx() }
    Column(
        modifier
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, sem.ruleFaint)
            .drawBehind {
                drawLine(bracketColor, Offset(0f, 0f), Offset(bracketArm, 0f), bracketStroke)
                drawLine(bracketColor, Offset(0f, 0f), Offset(0f, bracketArm), bracketStroke)
                drawLine(bracketColor, Offset(size.width, size.height), Offset(size.width - bracketArm, size.height), bracketStroke)
                drawLine(bracketColor, Offset(size.width, size.height), Offset(size.width, size.height - bracketArm), bracketStroke)
            }
            .padding(vertical = 12.dp, horizontal = 10.dp),
        horizontalAlignment = horizontalAlignment,
        content = content,
    )
}

/**
 * One segmented-column instrument (SPEED or RPM). Discrete blocks, not a swept needle - see
 * [DrivingDialMath.kt]'s file doc for why that is the honesty property of this whole rebuild.
 *
 * **[pidCode]** (ticket 04 density pass): prints beside [label] exactly the way [FleetScreen]'s
 * UPLINK pane already prints `0105 COOLANT`/`ATRV BATTERY` via `DeckFeedRow` - reusing an idiom
 * this app already ships elsewhere rather than inventing a new one, and it is free density because
 * the PID is a real constant this file already holds ([PID_SPEED]/[PID_RPM]), not a fabricated
 * readout.
 *
 * **The printed grid** ([ticks]) is now drawn ONCE, behind the entire bar area - both the narrow
 * tick-number gutter AND the segment canvas beside it - by a `drawBehind` on the outer `Box` below,
 * not inside the `Canvas` alone. On-device this read as two adjacent components rather than one
 * printed instrument face, because the old gridlines stopped at the canvas's own left edge and
 * never crossed the gutter the numbers sit in; ticket 04's own words are "across the full panel
 * width", and the gutter is part of the panel.
 *
 * **[barHeightFraction]** is fix 2: [DrivingModeContent]'s two `SegmentColumn`s used to get
 * different HEIGHT modifiers on the whole composable (a shorter RPM panel entirely), which on the
 * real phone read as two panels with different tops and different bottoms - "accidental", not
 * "hierarchy". The panel passed in via [modifier] is now always the same `.fillMaxHeight()` for
 * both instruments, so the bracket panels themselves share a top and a baseline; RPM staying
 * visually smaller is preserved as the one deliberate difference, expressed instead as the
 * fraction of that equal-height panel's bar area the printed ticks-and-canvas row actually fills,
 * bottom-aligned within it. `1f` (the default) is the old, unchanged, full-height behaviour.
 *
 * **The redline zone now carries a printed `REDLINE` micro-label** (ticket 04 density pass) rather
 * than relying on colour alone to read as a marked region - see the file doc's [DrivingDialMath.kt]
 * argument for why the zone's COLOUR stays a static scale marking, never a state signal; the label
 * is the same kind of annotation, just spelled out in words the way the reference photo spells out
 * everything on its instrument faces. Shown only when [redlineStartIndex] is actually inside
 * `[0, totalSegments)` - SPEED's call site pins it past the last valid index specifically so this
 * checks false and no redline ever gets drawn OR labelled on an instrument that has none.
 *
 * [redlineStartIndex] is a SCALE property, not a function of the current reading - pass
 * [SEGMENTS_SPEED] itself for an instrument with no redline (speed) so no valid segment index ever
 * matches, and [redlineSegmentStartIndex] for one that has a real zone (RPM). Redline segments
 * stay visible even unlit (a dim [LegionSemantics.chromeDim], not the fully-transparent normal
 * track) because the zone is annotation baked into the instrument face - see
 * [DrivingDialMath.kt]'s file doc, carried over unchanged from the old arc dial.
 *
 * [hasReading] false means this PID has never been recorded for this vehicle - the segment bar
 * draws with nothing lit and [valueText] renders in a smaller fallback style so "NO READING ON
 * FILE" does not try to set in [valueStyle] (a hero display size) and overflow the column.
 */
@Composable
private fun SegmentColumn(
    label: String,
    pidCode: String,
    valueText: String,
    unitLabel: String,
    ageText: String?,
    valueStyle: androidx.compose.ui.text.TextStyle,
    fraction: Float,
    totalSegments: Int,
    redlineStartIndex: Int,
    stale: Boolean,
    hasReading: Boolean,
    ticks: List<Pair<Float, Float>>,
    tickFormatter: (Float) -> String,
    barHeightFraction: Float = 1f,
    modifier: Modifier = Modifier,
) {
    val sem = LocalLegionSemantics.current
    val liveColor = MaterialTheme.colorScheme.primary
    val valueColor = if (!hasReading || stale) sem.faint else liveColor
    val litCount = litSegmentCount(fraction, totalSegments)
    val litNormalColor = if (stale) sem.faint else liveColor
    val litRedlineColor = if (stale) sem.faint else DeckChrome
    val unlitNormalColor = sem.ruleFaint
    val unlitRedlineColor = sem.chromeDim
    val gridColor = sem.ruleFaint
    val hasRedlineZone = redlineStartIndex in 0 until totalSegments
    val gridStroke = with(LocalDensity.current) { 1.dp.toPx() }

    BracketPanel(modifier = modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Text("$pidCode ${label.uppercase()}", style = LegionType.stamp, color = sem.faint)
        Spacer(Modifier.height(4.dp))
        Text(
            valueText,
            style = if (hasReading) valueStyle else MaterialTheme.typography.labelLarge,
            color = valueColor,
            textAlign = TextAlign.Center,
            maxLines = if (hasReading) 1 else 2,
            modifier = Modifier.fillMaxWidth(),
        )
        if (hasReading && unitLabel.isNotEmpty()) {
            Text(unitLabel, style = LegionType.stamp, color = sem.faint)
        }
        Spacer(Modifier.height(8.dp))
        // The full-height bar AREA - equal between SPEED and RPM now (see this composable's own
        // doc). The grid is drawn here, spanning this Box's FULL width (gutter + canvas), at each
        // tick's true position within the shorter bottom-aligned bar below when
        // [barHeightFraction] < 1f, so the printed grid and the printed bar never disagree about
        // where a tick actually sits.
        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .drawBehind {
                    val barTop = size.height * (1f - barHeightFraction)
                    val barHeight = size.height * barHeightFraction
                    for ((tickFraction, _) in ticks) {
                        val y = barTop + barHeight * (1f - tickFraction.coerceIn(0f, 1f))
                        drawLine(gridColor, Offset(0f, y), Offset(size.width, y), strokeWidth = gridStroke)
                    }
                },
        ) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(barHeightFraction)
                    .align(Alignment.BottomStart),
            ) {
                Box(Modifier.width(20.dp).fillMaxHeight()) {
                    for ((tickFraction, tickValue) in ticks) {
                        Text(
                            tickFormatter(tickValue),
                            style = LegionType.stamp,
                            color = sem.faint,
                            modifier = Modifier.align(BiasAlignment(horizontalBias = 1f, verticalBias = (1f - 2f * tickFraction).coerceIn(-1f, 1f))),
                        )
                    }
                }
                Spacer(Modifier.width(6.dp))
                Box(Modifier.weight(1f).fillMaxHeight()) {
                    Canvas(Modifier.fillMaxSize()) {
                        drawSegmentColumn(
                            litCount = litCount,
                            totalSegments = totalSegments,
                            redlineStartIndex = redlineStartIndex,
                            litColor = litNormalColor,
                            litRedlineColor = litRedlineColor,
                            unlitColor = unlitNormalColor,
                            unlitRedlineColor = unlitRedlineColor,
                        )
                    }
                    if (hasRedlineZone) {
                        // Sits ABOVE the zone's first segment rather than inside it. Printed on top
                        // of the segment it names, the label crowded its own bar and fought the
                        // fill for contrast (seen on device 2026-08-16). Offset up by one segment
                        // height so it annotates the boundary, which is what it actually marks.
                        Text(
                            "REDLINE",
                            style = LegionType.stamp,
                            color = litRedlineColor,
                            maxLines = 1,
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .offset(y = (-11).dp)
                                .padding(end = 2.dp),
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(ageText?.uppercase() ?: " ", style = LegionType.stamp, color = sem.faint)
    }
}

/**
 * Draws one segmented column's face: [totalSegments] discrete rectangles stacked bottom-up with a
 * small gap between each - the shape of a physical LED bargraph, which is exactly the register the
 * reference photo's EQ display is drawn in. The printed grid used to be drawn first, in here -
 * ticket 04's density pass moved it to a `drawBehind` on [SegmentColumn]'s outer `Box` instead, so
 * it can span the FULL bar width (the tick-number gutter included, not just this `Canvas`); this
 * function now only ever draws the segments themselves.
 */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawSegmentColumn(
    litCount: Int,
    totalSegments: Int,
    redlineStartIndex: Int,
    litColor: Color,
    litRedlineColor: Color,
    unlitColor: Color,
    unlitRedlineColor: Color,
) {
    if (totalSegments <= 0) return
    val cellHeight = size.height / totalSegments
    val gap = cellHeight * 0.16f
    for (index in 0 until totalSegments) {
        val lit = index < litCount
        val redline = isRedlineSegment(index, redlineStartIndex)
        val color = when {
            lit && redline -> litRedlineColor
            lit -> litColor
            redline -> unlitRedlineColor
            else -> unlitColor
        }
        val top = size.height - (index + 1) * cellHeight + gap / 2f
        val bottom = size.height - index * cellHeight - gap / 2f
        drawRect(color, topLeft = Offset(0f, top), size = Size(size.width, (bottom - top).coerceAtLeast(0f)))
    }
}

/**
 * COOLANT as a fader - "literally what that control is in the photo" (ticket 04's own words for
 * the reference's `COLD ▬▬ HOT` slider). A continuous filled track, not segments: coolant has no
 * discrete-step reading the way RPM/speed do, and the reference's own fader is a plain analog fill.
 * Quarter-mark ticks are drawn but unlabelled - the reference prints no numbers on this control
 * either, only the two end words, so a numeric scale here would be inventing precision the source
 * photo does not claim.
 */
@Composable
private fun CoolantFader(
    pidCode: String,
    valueText: String,
    ageText: String?,
    stale: Boolean,
    hasReading: Boolean,
    fraction: Float,
    modifier: Modifier = Modifier,
) {
    val sem = LocalLegionSemantics.current
    val context = LocalContext.current
    val liveColor = MaterialTheme.colorScheme.primary
    val valueColor = if (!hasReading || stale) sem.faint else liveColor
    val fillColor = valueColor
    val trackColor = sem.ruleFaint
    val tickColor = sem.chromeDim

    BracketPanel(modifier = modifier) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("$pidCode COOLANT", style = LegionType.stamp, color = sem.faint)
            Text(valueText, style = MaterialTheme.typography.displaySmall, color = valueColor)
        }
        Spacer(Modifier.height(8.dp))
        Canvas(Modifier.fillMaxWidth().height(20.dp)) {
            drawFaderTrack(fraction = fraction, fillColor = fillColor, trackColor = trackColor, tickColor = tickColor)
        }
        Spacer(Modifier.height(4.dp))
        // Fix 4's scale endpoint labels: the reference dashboard's own fader control prints
        // numbers on its scale, not just the two end words - "COLD"/"HOT" alone is precisely the
        // gap ticket 04 named. `0°C` is the PID's own real floor (Celsius cannot read negative
        // coolant on this instrument's scale); [COOLANT_SCALE_C_MAX] is the same real ceiling
        // constant [dialFraction]/[CoolantFader]'s own fill already reads off. Both endpoints are
        // Celsius constants and render through [Temp.text] like every other coolant figure, per
        // ticket 07's amendment - the SCALE stays fixed in Celsius (it is the real PID floor/
        // ceiling), only its printed label follows the driver's chosen unit.
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("COLD · ${Temp.text(context, 0.0)}", style = LegionType.stamp, color = sem.faint)
            Text("HOT · ${Temp.text(context, COOLANT_SCALE_C_MAX.toDouble())}", style = LegionType.stamp, color = sem.faint)
        }
        Spacer(Modifier.height(4.dp))
        Text(ageText?.uppercase() ?: " ", style = LegionType.stamp, color = sem.faint)
    }
}

/** Draws the fader's track, quarter-mark ticks, and fill - see [CoolantFader]'s own doc for why the ticks carry no numbers. */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawFaderTrack(fraction: Float, fillColor: Color, trackColor: Color, tickColor: Color) {
    drawRect(trackColor, size = size)
    for (quarter in listOf(0f, 0.25f, 0.5f, 0.75f, 1f)) {
        val x = size.width * quarter
        drawLine(tickColor, Offset(x, 0f), Offset(x, size.height), strokeWidth = 1.dp.toPx())
    }
    drawRect(fillColor, size = size.copy(width = size.width * fraction.coerceIn(0f, 1f)))
}

/**
 * The trip block (layout ticket 08 Q26, trip content ticket 05 Q13) - fills what used to be the
 * screen's empty third. **The doc that used to sit here was wrong, and is corrected rather than
 * reworded**: it claimed no drive-boundary object existed. Commit `61a62b0` added one three commits
 * later the same evening - the `drives` table (Room v23, now v24) plus
 * [com.kevin.legion.data.local.Drive] and [com.kevin.legion.data.local.DriveDao], written by
 * [com.kevin.legion.vehicle.TelemetryRecorder.finalizeDrive] whenever a drive ends
 * ([Drive.endReason]: `ENGINE_OFF` or `LINK_LOST`).
 *
 * **What this block can honestly show is narrower than "the current drive".** `drives` only ever
 * holds FINISHED drives - `finalizeDrive` writes a row on the way OUT of a drive, never on the way
 * in, so there is no in-progress row to poll toward. A live "so far" figure would have to be
 * derived some other way - e.g. reaching into `AriaForegroundService`'s private `driveStartedAt`
 * and computing elapsed against `System.currentTimeMillis()` - and that was rejected: that local is
 * owned by the service's own drive-monitor loop, not this screen, and reading it here would be a
 * second, competing notion of "when did this drive start" alongside the one [Drive.startedAt]
 * already is, with no reconciliation between the two if they ever disagreed. So this block shows
 * **the LAST FINISHED drive** ([com.kevin.legion.data.local.DriveDao.getRecent], `limit = 1`) and
 * is labelled **"LAST DRIVE"**, never "TRIP" or anything implying "so far" - it can be minutes or
 * days old by the time it is glanced at, and the age is printed alongside it
 * ([com.kevin.legion.util.relativeAge] on [Drive.endedAt]) so nobody mistakes a stale figure for a
 * live one. Implying a finished drive's numbers belong to the one happening right now would be the
 * same class of mistake CLAUDE.md's estimates rule guards against for a NUMBER, just applied to a
 * claim about WHICH drive a number belongs to.
 *
 * **MPG is deliberately not a third figure here**, unchanged by this fix. Trip content ticket 05
 * found LEGION's own mpg integration is off by roughly 1.7x on this car (`TelemetryRecorder`'s
 * MAF-based gallons math, filed as ticket 09, [com.kevin.legion.vehicle.MpgTrust.SHOW_MPG] `false`)
 * - shipping a figure known to be wrong is the estimates rule violated outright, not merely an
 * unlabelled one. [Drive.gallons] is read by nothing in this block, including when it is `null`
 * (see [lastDriveSummary]'s own doc).
 *
 * **The empty state stays worded, never a fabricated number**: no finished drive yet (fresh
 * install, or the very first drive still in progress) reads `"TRIP // NO DRIVE ON FILE YET"` - the
 * same "worded absence, never a fabricated placeholder" posture this file already holds for
 * `"NO READING ON FILE"` (CLAUDE.md §4/§7), collapsed to one line rather than two empty [TripStat]
 * tiles for the same "an absence does not deserve two bracket panels' worth of screen" reasoning
 * the pre-rebuild fix-3 comment made about the old, permanent `NOT TRACKING` state.
 */
@Composable
private fun TripBlock(drive: Drive?, modifier: Modifier = Modifier) {
    val sem = LocalLegionSemantics.current
    val summary = lastDriveSummary(drive)
    if (drive != null && summary != null) {
        Column(modifier) {
            Text(
                "LAST DRIVE · ${relativeAge(drive.endedAt).uppercase()}",
                style = LegionType.stamp,
                color = sem.faint,
            )
            Spacer(Modifier.height(6.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TripStat(label = "ELAPSED", valueText = summary.elapsedText, modifier = Modifier.weight(1f))
                TripStat(label = "DISTANCE", valueText = summary.distanceText, modifier = Modifier.weight(1f))
            }
        }
    } else {
        Text(
            "TRIP // NO DRIVE ON FILE YET",
            style = LegionType.stamp,
            color = sem.faint,
            modifier = modifier,
        )
    }
}

@Composable
private fun TripStat(label: String, valueText: String, modifier: Modifier = Modifier) {
    val sem = LocalLegionSemantics.current
    BracketPanel(modifier = modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label.uppercase(), style = LegionType.stamp, color = sem.faint)
        Spacer(Modifier.height(6.dp))
        Text(
            valueText,
            style = MaterialTheme.typography.labelLarge,
            color = sem.faint,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/**
 * Fix 4's technical footer band - "one thin row of printed micro-caps facts: protocol/link state,
 * and the poll cadence. Real values only." Both facts are things this file already, actually
 * knows:
 * - **Link state** reuses [linkLive], the same source of truth [DrivingHudLine] renders at the top
 *   of the screen - this row does not re-derive it a second way.
 * - **[protocolName]** is [com.kevin.legion.vehicle.ObdBluetoothManager.AdapterInfo.protocolName],
 *   read off the same `adapterInfo` `StateFlow` `ObdDeviceScreen`'s own "Protocol" row already
 *   surfaces - gated on `linkLive` the identical way that screen gates its row
 *   (`if (connected && state.adapterProtocol != null)`), because a link that is currently down can
 *   still be holding a STALE protocol string from the last successful connect (traced: nothing in
 *   `ObdBluetoothManager` clears `_adapterInfo` on disconnect, only at the START of the next
 *   connect attempt) and printing that next to "the poll cadence" would read as a live fact when it
 *   is not one.
 * - **Poll cadence** is [POLL_MS] itself, printed rather than left as an implicit constant nobody
 *   on the outside can see - it is always knowable (a compile-time value, not a reading), so unlike
 *   the protocol segment it is never conditionally omitted.
 *
 * Deliberately NOT included: anything this screen cannot source from state it already holds. No
 * fabricated bus-utilization percentage, no invented signal-quality figure - the ticket's own
 * instruction is "if something is not knowable from state the screen already holds, leave it out".
 */
@Composable
private fun TechnicalFooterBand(linkLive: Boolean, protocolName: String?, modifier: Modifier = Modifier) {
    val sem = LocalLegionSemantics.current
    val pollSeconds = POLL_MS / 1000f
    val text = buildString {
        append(if (linkLive) "LINK LIVE" else "NO LINK")
        if (linkLive && protocolName != null) {
            append(" · ")
            append(protocolName.uppercase())
        }
        append(" · POLL %.1fS".format(pollSeconds))
    }
    Text(text, style = LegionType.stamp, color = sem.faint, modifier = modifier)
}

/**
 * The Alfred strip: a green square dot + label on the left, the current voice phase on the right,
 * inside a bordered row - unchanged wording and shape from the pre-rebuild file, only its position
 * on screen moved (layout ticket 08 Q28: thumb level, just above EXIT). [alfredStatus] is
 * [AssistantStripResolver.phaseLabel]'s output (ticket 20 build brief item 2), so this screen never
 * invents its own voice-state vocabulary.
 */
@Composable
private fun DrivingAlfredStrip(alfredStatus: String) {
    val sem = LocalLegionSemantics.current
    Row(
        Modifier
            .fillMaxWidth()
            .border(1.dp, sem.ruleFaint)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.size(8.dp).background(sem.credit))
            Text("ALFRED", style = LegionType.stamp, color = sem.faint)
        }
        Text(alfredStatus, style = LegionType.stamp, color = sem.faint)
    }
}

// ------------------------------------------------------------------------ previews

@Preview(name = "Driving mode: all readings live, RPM in the redline zone", widthDp = 384, heightDp = 832)
@Composable
private fun PreviewDrivingModeAllLive() = LegionTheme {
    val now = System.currentTimeMillis()
    DrivingModeContent(
        vehicleName = "The Wagon",
        linkLive = true,
        clock = "14:07",
        rpm = DrivingSample(5100f, "just now"),
        speed = DrivingSample(88f, "just now"),
        coolant = DrivingSample(92f, "just now"),
        // The trip block's "a real last drive" case (ticket 05/08) - gallons null on purpose,
        // proving the block never needs it (see [lastDriveSummary]'s own doc).
        lastDrive = Drive(
            vehicleId = "preview",
            startedAt = now - 42 * 60_000L,
            endedAt = now - 5 * 60_000L,
            miles = 18.4,
            gallons = null,
            endReason = "ENGINE_OFF",
        ),
        alfredStatus = "Tap to talk",
        tickCounter = 1,
        // Fix 4's footer band, the "known protocol, link live" case.
        protocolName = "ISO 15765-4 CAN (11-bit, 500K) (AUTO)",
        onExit = {},
    )
}

@Preview(name = "Driving mode: link live, RPM never recorded on this install", widthDp = 384, heightDp = 832)
@Composable
private fun PreviewDrivingModeRpmMissing() = LegionTheme {
    DrivingModeContent(
        vehicleName = "The Wagon",
        linkLive = true,
        clock = "14:07",
        rpm = null,
        speed = DrivingSample(72f, "just now"),
        coolant = DrivingSample(88f, "just now"),
        // The trip block's empty state (no finished drive on file) - see [TripBlock]'s own doc.
        lastDrive = null,
        alfredStatus = "Listening…",
        tickCounter = 3,
        // Link live but the ATDP handshake hasn't resolved a protocol yet - the footer band
        // leaves the protocol segment out entirely rather than printing a placeholder.
        protocolName = null,
        onExit = {},
    )
}

@Preview(name = "Driving mode: stale, link down, manual entry", widthDp = 384, heightDp = 832)
@Composable
private fun PreviewDrivingModeStale() = LegionTheme {
    DrivingModeContent(
        vehicleName = "The Wagon",
        linkLive = false,
        clock = "22:41",
        rpm = DrivingSample(1800f, "3 days ago"),
        speed = null,
        coolant = DrivingSample(88f, "3 days ago"),
        lastDrive = null,
        alfredStatus = "Tap to talk",
        tickCounter = 0,
        // A non-null value here on purpose: this is the STALE-protocol case
        // (`ObdBluetoothManager` never clears `adapterInfo` on disconnect, only at the next
        // connect attempt) - the footer band's own `linkLive` gate must hide it even though a
        // value is present, exactly as [TechnicalFooterBand]'s doc says it will.
        protocolName = "ISO 15765-4 CAN (11-bit, 500K) (AUTO)",
        onExit = {},
    )
}

@Preview(name = "Driving mode: no data on file at all", widthDp = 384, heightDp = 832)
@Composable
private fun PreviewDrivingModeNoData() = LegionTheme {
    DrivingModeContent(
        vehicleName = "",
        linkLive = false,
        clock = "09:12",
        rpm = null,
        speed = null,
        coolant = null,
        lastDrive = null,
        alfredStatus = "Tap to talk",
        tickCounter = 0,
        protocolName = null,
        onExit = {},
    )
}
