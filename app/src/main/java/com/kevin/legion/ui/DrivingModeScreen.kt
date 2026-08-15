package com.kevin.legion.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import android.view.WindowManager
import com.kevin.legion.data.local.CarDatabase
import com.kevin.legion.service.CompanionPhase
import com.kevin.legion.ui.assistant.AssistantStripResolver
import com.kevin.legion.ui.theme.DeckChrome
import com.kevin.legion.ui.theme.LegionTheme
import com.kevin.legion.ui.theme.LegionType
import com.kevin.legion.ui.theme.LocalLegionSemantics
import com.kevin.legion.util.clockTime
import com.kevin.legion.util.relativeAge
import com.kevin.legion.vehicle.ObdBluetoothManager
import com.kevin.legion.vehicle.VehicleController
import kotlinx.coroutines.delay
import kotlin.math.cos
import kotlin.math.sin

/**
 * `driving` - the ticket-11/20 glance screen, rebuilt to the cockpit-hub mock Kevin approved
 * 2026-08-08. Full-bleed, no [ui.common.StatusLine], no hard-key row, a glance ceiling of THREE
 * readouts (the dial plus two pods) plus the Alfred line and the EXIT key - nothing else. Entry
 * is always an OFFER (ticket 11 answer §1: [FleetScreen]'s UPLINK panel `DRIVE MODE` row navigates
 * here on tap - see that file's `DriveModeOfferRow`); this screen never triggers itself.
 *
 * **No theatre** (ticket 04 answer §5: "driving mode gets NO theatre - it is exempt from even the
 * three rationed moments"). No boot sweep, no draw-in, no ambient cursor, no continuous animation
 * anywhere in this file - the dial and pods redraw only when a poll tick actually changes the
 * underlying reading, because they read plain [androidx.compose.runtime.State] values directly
 * into a `Canvas` rather than animating toward them. Entry and exit are instant `LegionShell`/
 * `NavHost` transitions, the same as any other pop; nothing in this file reads
 * [com.kevin.legion.ui.theme.deckMotionEnabled] or [com.kevin.legion.ui.theme.DRAW_IN_MS] because
 * there is nothing here to gate.
 *
 * **Readings live-poll the same way UPLINK does** (ticket 20 build brief item 2): a periodic
 * re-query of `CarDatabase`'s latest [com.kevin.legion.data.local.OdbSample] per PID, the exact
 * mechanism [FleetScreen]'s `UplinkPane` uses for its own LIVE block (`odbSampleDao().getLatest`),
 * not a raw [ObdBluetoothManager.getRpm]/`getCoolantTemp` command sent from here. Those two
 * suspend funs write straight to the same single-socket RFCOMM/BLE connection
 * [com.kevin.legion.vehicle.TelemetryRecorder] is already polling on its own loop while the
 * engine runs and the dongle is connected (traced: `ObdBluetoothManager.sendCommand` has no
 * mutex or command queue visible from this file) - sending a second, independent command stream
 * from this screen risks interleaving with that loop on the same wire. Reading the DB it already
 * writes to is the same "reuse, do not duplicate the OBD read path" posture DRIVES/UPLINK already
 * use for their own panels, and it is the literal instruction in the ticket ("the same way").
 *
 * **All three PIDs are polled every tick now, unconditionally** - a change from the original
 * two-readout build, which only queried speed when RPM had never been recorded. The cockpit mock
 * needs RPM, speed, AND coolant simultaneously (the dial shows one of RPM/speed, and whichever one
 * the dial is NOT showing still has to fill the second pod - see [selectDialSource]'s doc), so
 * there is no longer a PID this screen can skip querying. Three `getLatest(..., 1)` reads every
 * 2s is not meaningfully more expensive than the original's up-to-two, and correctness (never
 * silently missing the pod's own reading) matters more here than shaving one query.
 *
 * **Exit: EXIT key, or the link dropping, whichever comes first.** No confirmation dialog either
 * way (ticket 11 answer §2). The link-drop watch polls [ObdBluetoothManager.isConnected] on the
 * same cadence as the readouts rather than a bespoke faster timer - a driver who unplugs the
 * dongle mid-drive is not going to notice a few hundred milliseconds' difference, and a second
 * poll loop here would be motion this screen does not need.
 */
private const val POLL_MS = 2_000L

/** The clock in the HUD line only needs to be right to the minute (approved mock) - see [clockTime]'s own doc for why the deck never shows seconds. */
private const val CLOCK_POLL_MS = 60_000L

/** The three PIDs this screen ever asks for. RPM leads per ticket 11 answer §3's default; coolant is the fixed COOLANT pod; speed backs the dial when RPM is unavailable and otherwise fills the second pod. */
private const val PID_RPM = "010C"
private const val PID_SPEED = "010D"
private const val PID_COOLANT = "0105"

// Scale ceilings for the dial sweep / pod mini-bars, per the approved mock.
private const val RPM_SCALE_MAX = 8000f
private const val SPEED_SCALE_MPH_MAX = 120f
private const val COOLANT_SCALE_F_MAX = 260f

/** One raw reading plus its worded staleness (never a bare number - CLAUDE.md §4/§7, [relativeAge] discipline carried over from [com.kevin.legion.ui.fleet.buildLiveRows]). Unit conversion (km/h -> mph, C -> F) happens at the READ site below, not here - this struct always holds the PID's own native unit. */
private data class DrivingSample(val raw: Float, val age: String)

@Composable
fun DrivingModeScreen(onExit: () -> Unit) {
    val context = LocalContext.current
    var vehicleName by remember { mutableStateOf("") }
    var linkLive by remember { mutableStateOf(false) }
    var rpm by remember { mutableStateOf<DrivingSample?>(null) }
    var speed by remember { mutableStateOf<DrivingSample?>(null) }
    var coolant by remember { mutableStateOf<DrivingSample?>(null) }

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
    // tick (see file doc for why one loop covers both). Runs for as long as
    // this composable stays on screen; DisposableEffect above tears the
    // screen-on flag down independently the moment it stops, whichever exit
    // path fired.
    LaunchedEffect(Unit) {
        val vehicle = VehicleController.currentVehicle(context)
        vehicleName = vehicle.name
        val dao = CarDatabase.getDatabase(context).odbSampleDao()
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
                // of extra motion ticket 04 answer §5 rules out for this
                // screen. Entered manually without a link, the EXIT key is
                // the only way out - there is no link whose drop could mean
                // "the drive ended".
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
            delay(POLL_MS)
        }
    }

    val phase by CompanionPhase.phase.collectAsStateWithLifecycle()
    // "Static-per-minute is fine" for this screen's HUD clock - a once-a-
    // minute produceState tick, same cadence MainActivity's own shell clock
    // uses for the identical reason (ticket 04's ambient-motion ration; a
    // once-a-second read here would recompose the whole HUD row for no
    // legibility gain).
    val clock by produceState(initialValue = clockTime(System.currentTimeMillis())) {
        while (true) {
            value = clockTime(System.currentTimeMillis())
            delay(CLOCK_POLL_MS)
        }
    }

    DrivingModeContent(
        vehicleName = vehicleName,
        linkLive = linkLive,
        clock = clock,
        rpm = rpm,
        speed = speed,
        coolant = coolant,
        // The Alfred status line reuses AssistantStripResolver's own phase
        // wording (ticket 20 build brief item 2: "reuse whatever state
        // AssistantStrip reads... show the strip's existing status text") -
        // no new voice-state vocabulary invented for this one screen.
        alfredStatus = AssistantStripResolver.phaseLabel(phase),
        onExit = onExit,
    )
}

/** Plain UI: the cockpit layout with no Room/OBD reference, see the file doc's state-holder split. */
@Composable
private fun DrivingModeContent(
    vehicleName: String,
    linkLive: Boolean,
    clock: String,
    rpm: DrivingSample?,
    speed: DrivingSample?,
    coolant: DrivingSample?,
    alfredStatus: String,
    onExit: () -> Unit,
) {
    val sem = LocalLegionSemantics.current

    // Which reading owns the dial, and which reading (never the same one) owns
    // the second pod - see selectDialSource's doc for why this reads "has this
    // PID ever been recorded", not "is the link live right now".
    val dialSource = selectDialSource(hasRpm = rpm != null, hasSpeed = speed != null)
    val dialSample = when (dialSource) {
        DialSource.RPM -> rpm
        DialSource.SPEED -> speed
        DialSource.NONE -> null
    }
    // Stale means "no live link, but a last-known reading exists" - the
    // approved mock's own wording ("Stale (link down, last-known) values
    // render in muted, not amber"). A live link with a reading that is a
    // couple of poll ticks old is NOT stale under this rule; only a dropped
    // link demotes the color, same as [DrivingModeContent]'s pods below.
    val dialStale = !linkLive && dialSample != null
    val dialFractionValue = when (dialSource) {
        DialSource.RPM -> dialFraction(dialSample!!.raw, RPM_SCALE_MAX)
        DialSource.SPEED -> dialFraction(kmhToMph(dialSample!!.raw), SPEED_SCALE_MPH_MAX)
        DialSource.NONE -> 0f
    }
    val dialCenterText = when (dialSource) {
        DialSource.RPM -> "%.1f".format(dialSample!!.raw / 1000f)
        DialSource.SPEED -> "%.0f".format(kmhToMph(dialSample!!.raw))
        DialSource.NONE -> "NO LINK"
    }
    val dialUnitLabel = when (dialSource) {
        DialSource.RPM -> "RPM ×1000"
        DialSource.SPEED -> "MPH"
        DialSource.NONE -> ""
    }

    // The second pod is always whichever of RPM/speed the dial is NOT
    // currently showing - "never duplicate the dial's reading in a pod"
    // (approved mock). When the dial is NONE (neither PID ever recorded),
    // the second pod defaults to SPEED - it reads "no reading on file"
    // either way, since neither PID exists yet for this vehicle.
    val secondPodIsRpm = dialSource == DialSource.SPEED
    val secondPodSample = if (secondPodIsRpm) rpm else speed
    val secondPodStale = !linkLive && secondPodSample != null
    val secondPodValueText = secondPodSample?.let {
        if (secondPodIsRpm) "${it.raw.toInt()}" else "${kmhToMph(it.raw).toInt()} MPH"
    }
    val secondPodFraction = secondPodSample?.let {
        if (secondPodIsRpm) dialFraction(it.raw, RPM_SCALE_MAX) else dialFraction(kmhToMph(it.raw), SPEED_SCALE_MPH_MAX)
    } ?: 0f

    val coolantStale = !linkLive && coolant != null
    val coolantF = coolant?.let { celsiusToFahrenheit(it.raw) }
    val coolantValueText = coolantF?.let { "${it.toInt()}°F" }
    val coolantFraction = coolantF?.let { dialFraction(it, COOLANT_SCALE_F_MAX) } ?: 0f

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
            DrivingHudLine(vehicleName = vehicleName, linkLive = linkLive, clock = clock)
            Spacer(Modifier.height(16.dp))
            DrivingDial(
                source = dialSource,
                fraction = dialFractionValue,
                stale = dialStale,
                centerText = dialCenterText,
                unitLabel = dialUnitLabel,
                ageText = dialSample?.age,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(260.dp),
            )
            Spacer(Modifier.height(20.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                DrivingPod(
                    label = "COOLANT",
                    valueText = coolantValueText,
                    ageText = coolant?.age,
                    stale = coolantStale,
                    fraction = coolantFraction,
                    modifier = Modifier.weight(1f),
                )
                DrivingPod(
                    label = if (secondPodIsRpm) "RPM" else "SPEED",
                    valueText = secondPodValueText,
                    ageText = secondPodSample?.age,
                    stale = secondPodStale,
                    fraction = secondPodFraction,
                    modifier = Modifier.weight(1f),
                )
            }
            Spacer(Modifier.height(20.dp))
            DrivingAlfredStrip(alfredStatus)
            Spacer(Modifier.weight(1f))
        }

        // The giant EXIT hard-key: bottom, full-width, huge touch target, no
        // confirmation (ticket 11 answer §2). Approved mock: a 2dp edge-color
        // border rather than the old solid-quarantine-red fill - EXIT is a
        // control, not a failed-gate/crisis verdict, so it does not belong in
        // ticket 03's red family at all; amber letterspaced text on an
        // outlined key matches the rest of MILSPEC's bracket/outline register.
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
 * The HUD line (approved mock, top of the cockpit): link state left, vehicle name + clock right.
 * Green accent on a live link only - a dropped/never-paired link reads muted, never red (a dead
 * link during manual entry is an expected, informed state per the ticket-11 amendment, not a
 * failure), matching ticket 03's "red is exclusively a failed-gate/crisis verdict" contract.
 */
@Composable
private fun DrivingHudLine(vehicleName: String, linkLive: Boolean, clock: String) {
    val sem = LocalLegionSemantics.current
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text(
            buildAnnotatedString {
                withStyle(SpanStyle(color = sem.faint)) { append("DRIVE // ") }
                withStyle(SpanStyle(color = if (linkLive) sem.credit else sem.faint)) {
                    append(if (linkLive) "LINK LIVE" else "MANUAL · NO LINK")
                }
            },
            style = LegionType.stamp,
        )
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (vehicleName.isNotBlank()) {
                Text(vehicleName.uppercase(), style = LegionType.stamp, color = sem.faint)
            }
            Text(clock, style = LegionType.stamp, color = sem.faint)
        }
    }
}

/**
 * The center dial: a Canvas arc gauge, sweep/scale math from [DrivingDialMath.kt] (see that
 * file's doc for why the redline is drawn with the raw [DeckChrome] token rather than
 * [LocalLegionSemantics.quarantined]). No animation anywhere in this composable - the arc, the
 * center text, and the ticks are drawn directly from the current [fraction]/[stale]/[centerText]
 * values every recomposition, which only happens when the poll loop actually changes one of them
 * (screen file doc: "no theatre").
 */
@Composable
private fun DrivingDial(
    source: DialSource,
    fraction: Float,
    stale: Boolean,
    centerText: String,
    unitLabel: String,
    ageText: String?,
    modifier: Modifier = Modifier,
) {
    val sem = LocalLegionSemantics.current
    val trackColor = sem.ruleFaint
    val edgeColor = sem.rule
    val valueColor = if (stale) sem.faint else MaterialTheme.colorScheme.primary

    Box(modifier, contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val strokeWidthPx = 14.dp.toPx()
            val diameter = (kotlin.math.min(size.width, size.height) - strokeWidthPx).coerceAtLeast(0f)
            val topLeft = Offset((size.width - diameter) / 2f, (size.height - diameter) / 2f)
            val arcSize = androidx.compose.ui.geometry.Size(diameter, diameter)
            val stroke = Stroke(width = strokeWidthPx, cap = StrokeCap.Round)

            // The dead/live track, full sweep.
            drawArc(trackColor, DIAL_START_ANGLE_DEG, DIAL_SWEEP_DEG, useCenter = false, topLeft = topLeft, size = arcSize, style = stroke)

            // The redline: a fixed scale annotation, always present, drawn UNDER the
            // value arc so the amber overlays it once the reading enters the zone -
            // see DrivingDialMath.kt's file doc for why this is not a ticket-03 STATE
            // color and stays exempt from that rule.
            val redlineStart = DIAL_START_ANGLE_DEG + DIAL_SWEEP_DEG * REDLINE_START_FRACTION
            val redlineSweep = DIAL_SWEEP_DEG * (1f - REDLINE_START_FRACTION)
            drawArc(DeckChrome, redlineStart, redlineSweep, useCenter = false, topLeft = topLeft, size = arcSize, style = stroke)

            // The value arc - absent entirely when there is nothing to show
            // (source == NONE), rather than drawing a zero-length arc at the
            // dead start angle, which would read as a hairline glitch rather
            // than "no data".
            if (source != DialSource.NONE) {
                drawArc(valueColor, DIAL_START_ANGLE_DEG, sweepAngleDegrees(fraction), useCenter = false, topLeft = topLeft, size = arcSize, style = stroke)
            }

            // Three edge-color tick marks: both ends of the sweep, plus the
            // sweep's own midpoint - which, for this start angle/span, lands
            // exactly at the circle's geometric top (see DrivingDialMath.kt's
            // DIAL_START_ANGLE_DEG doc for the angle arithmetic).
            val radius = diameter / 2f
            val center = Offset(topLeft.x + radius, topLeft.y + radius)
            val tickInset = strokeWidthPx * 1.4f
            for (tickFraction in listOf(0f, 0.5f, 1f)) {
                val angleRad = Math.toRadians((DIAL_START_ANGLE_DEG + DIAL_SWEEP_DEG * tickFraction).toDouble())
                val cosA = cos(angleRad).toFloat()
                val sinA = sin(angleRad).toFloat()
                val outer = Offset(center.x + radius * cosA, center.y + radius * sinA)
                val inner = Offset(center.x + (radius - tickInset) * cosA, center.y + (radius - tickInset) * sinA)
                drawLine(edgeColor, inner, outer, strokeWidth = 3.dp.toPx())
            }
        }

        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(centerText, style = MaterialTheme.typography.displayLarge, color = valueColor)
            if (unitLabel.isNotEmpty()) {
                Text(unitLabel, style = LegionType.stamp, color = sem.faint)
            }
            if (ageText != null) {
                Text(ageText.uppercase(), style = LegionType.stamp, color = sem.faint)
            }
        }
    }
}

/**
 * One bracket-cornered pod (COOLANT or the dial's complement). A local, lightweight variant of
 * [com.kevin.legion.ui.common.DeckPane]'s corner-bracket treatment rather than a reuse of that
 * component directly - `DeckPane`'s header row (label left, optional accent clause) fights a
 * pod's centered "label above a giant value" read, per the approved mock. The mini-bar is a bare
 * `Canvas` rect-on-rect, not [com.kevin.legion.ui.common.DeckMeter] - `DeckMeter` fills over
 * [com.kevin.legion.ui.theme.DRAW_IN_MS] via `animateFloatAsState`, and this screen's own "no
 * theatre" rule (file doc) means the bar has to jump straight to its value on every poll tick,
 * never animate toward it.
 */
@Composable
private fun DrivingPod(
    label: String,
    valueText: String?,
    ageText: String?,
    stale: Boolean,
    fraction: Float,
    modifier: Modifier = Modifier,
) {
    val sem = LocalLegionSemantics.current
    val bracketColor = sem.rule
    val density = LocalDensity.current
    val bracketStroke = with(density) { 2.dp.toPx() }
    val bracketArm = with(density) { 8.dp.toPx() }
    val valueColor = if (stale) sem.faint else MaterialTheme.colorScheme.primary

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
            .padding(vertical = 14.dp, horizontal = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(label.uppercase(), style = LegionType.stamp, color = sem.faint)
        Spacer(Modifier.height(6.dp))
        // Honest "no reading" text rather than a fabricated placeholder number
        // when this install has never recorded the PID - same posture as
        // com.kevin.legion.ui.fleet.buildLiveRows and CLAUDE.md §4/§7.
        Text(valueText ?: "NO READING ON FILE", style = MaterialTheme.typography.displaySmall, color = valueColor)
        Spacer(Modifier.height(4.dp))
        Text(ageText?.uppercase() ?: " ", style = LegionType.stamp, color = sem.faint)
        Spacer(Modifier.height(8.dp))
        val miniBarFillColor = valueColor
        val miniBarTrackColor = sem.ruleFaint
        Canvas(Modifier.fillMaxWidth().height(6.dp)) {
            drawRect(miniBarTrackColor)
            drawRect(miniBarFillColor, size = size.copy(width = size.width * fraction.coerceIn(0f, 1f)))
        }
    }
}

/**
 * The Alfred strip: a green square dot + label on the left, the current voice phase on the right,
 * inside a bordered row - the approved mock's own words. [alfredStatus] is
 * [AssistantStripResolver.phaseLabel]'s output, unchanged wording from the global assistant strip
 * (ticket 20 build brief item 2), so this screen never invents its own voice-state vocabulary.
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

@Preview(name = "Driving mode: RPM live, link up", widthDp = 360, heightDp = 720)
@Composable
private fun PreviewDrivingModeRpmLive() = LegionTheme {
    DrivingModeContent(
        vehicleName = "The Wagon",
        linkLive = true,
        clock = "14:07",
        rpm = DrivingSample(2140f, "just now"),
        speed = DrivingSample(88f, "just now"),
        coolant = DrivingSample(92f, "just now"),
        alfredStatus = "Tap to talk",
        onExit = {},
    )
}

@Preview(name = "Driving mode: speed dial fallback, no RPM on file", widthDp = 360, heightDp = 720)
@Composable
private fun PreviewDrivingModeSpeedFallback() = LegionTheme {
    DrivingModeContent(
        vehicleName = "The Wagon",
        linkLive = true,
        clock = "14:07",
        rpm = null,
        speed = DrivingSample(72f, "just now"),
        coolant = DrivingSample(88f, "just now"),
        alfredStatus = "Listening…",
        onExit = {},
    )
}

@Preview(name = "Driving mode: stale, link down, manual entry", widthDp = 360, heightDp = 720)
@Composable
private fun PreviewDrivingModeStale() = LegionTheme {
    DrivingModeContent(
        vehicleName = "The Wagon",
        linkLive = false,
        clock = "22:41",
        rpm = DrivingSample(1800f, "3 days ago"),
        speed = null,
        coolant = DrivingSample(88f, "3 days ago"),
        alfredStatus = "Tap to talk",
        onExit = {},
    )
}

@Preview(name = "Driving mode: no data on file at all", widthDp = 360, heightDp = 720)
@Composable
private fun PreviewDrivingModeNoData() = LegionTheme {
    DrivingModeContent(
        vehicleName = "",
        linkLive = false,
        clock = "09:12",
        rpm = null,
        speed = null,
        coolant = null,
        alfredStatus = "Tap to talk",
        onExit = {},
    )
}
