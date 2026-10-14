package com.kevin.midnightai.ui

import android.content.Context
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.style.TextAlign
import com.kevin.midnightai.ui.theme.AriaColors
import com.kevin.midnightai.ui.theme.LightsOutSwatch
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kevin.midnightai.service.Phase
import com.kevin.midnightai.service.ProactivePreferences
import com.kevin.midnightai.ui.theme.AriaType
import kotlinx.coroutines.delay
import com.kevin.midnightai.time.TrustedClock
import java.util.Locale

/**
 * "Lights Out": the night-drive display — an F16 HUD at 2am, not a neon sign.
 * Corner brackets frame the safe area; speed and the top OBD gauge sit in
 * bracketed instrument readouts left/right instead of a caption-sized text
 * row; the clock shrinks to a HUD mode readout instead of owning the screen.
 * Monochrome CRT phosphor green by default (CLASSIC_GREEN) — the same color
 * family as the 1998 Jeep Cherokee XJ's real dash lighting, not a two-tone
 * neon-sign palette. A driver can still lock either role to a different
 * [LightsOutSwatch] independent of the app-wide palette (Control Panel >
 * Appearance > Lights Out colors); this only changes the DEFAULT.
 *
 *  - Clock, small, top-center — a HUD mode readout, not the hero element
 *  - CENTER is a system-status readout instead (2026-07-22, Kevin: "the clock
 *    shouldn't be the center"): "ALL SYSTEMS NOMINAL" in calm phosphor, or a
 *    brighter "N CODES STORED" when [dtcCount] is nonzero, or "NO OBD LINK"
 *    when nothing's connected. The real jet-HUD parallel is a Master Caution
 *    annunciator, not a clock - and it's the single most useful glanceable
 *    fact for THIS app specifically, since live OBD health is the actual
 *    differentiator. Reuses CruiseScreen's existing 1-minute dtcCount poll
 *    rather than a second one.
 *  - Bracketed speed readout, left; top enabled OBD gauge, right — both
 *    flanked by small tick marks so they read as instruments, not text
 *  - Any further enabled OBD gauges: a small overflow row beneath center
 *  - Now-playing line while music is up, directly beneath the status box
 *    (2026-07-22, moved off the bottom edge and sized up per Kevin's ask)
 *  - Conversation stays visible: when Zero is listening/speaking, a status
 *    word and the live caption appear beneath the clock, same phosphor
 *
 * Also drops the screen backlight while active and restores it on exit.
 * Tap anywhere to leave; long-press anywhere to talk without leaving the dark.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun LightsOut(
    context: Context,
    telemetry: CruiseTelemetry,
    dtcCount: Int,
    nowPlayingLine: String?,
    phase: Phase,
    caption: String,
    notice: String = "",
    onTalk: () -> Unit,
    onExit: () -> Unit,
) {
    // Drop the host window's backlight while in Lights Out; always restore on exit.
    DisposableEffect(Unit) {
        val window = (context as? android.app.Activity)?.window
        val previous = window?.attributes?.screenBrightness
        window?.attributes = window?.attributes?.apply { screenBrightness = 0.04f }
        onDispose {
            window?.attributes = window?.attributes?.apply {
                screenBrightness = previous ?: android.view.WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
            }
        }
    }

    // The app's own clock and zone, never the head unit's (trusted clock, 2026-10-13).
    var now by remember { mutableStateOf(TrustedClock.nowMs()) }
    val clockStatus by TrustedClock.status.collectAsState()
    LaunchedEffect(Unit) {
        while (true) { now = TrustedClock.nowMs(); delay(10_000) }
    }
    val time = remember(now / 60_000, clockStatus) { TrustedClock.formatClock(context, now) }
    val date = remember(now / 60_000, clockStatus) {
        java.time.Instant.ofEpochMilli(now).atZone(TrustedClock.zone())
            .format(java.time.format.DateTimeFormatter.ofPattern("EEE, MMM d", Locale.getDefault()))
    }

    // Numerals (clock/gauges/captions) and trim (date/status/hint) each default
    // to CLASSIC_GREEN (2026-07-22 redesign: monochrome F16/XJ-dash phosphor,
    // not the old amber+magenta two-tone neon-sign look) - but a driver can
    // still lock either role to a fixed swatch in Appearance, independent of
    // the app-wide palette (Control Panel > Lights Out colors).
    val numeralsSwatch = CruiseSettings.lightsOutNumeralsSwatch(context)
    val trimSwatch = CruiseSettings.lightsOutTrimSwatch(context)
    val classicGreen = LightsOutSwatch.CRT_GREEN.color
    val nightAmber = lerp(numeralsSwatch?.color ?: classicGreen, Color.Black, 0.30f)
    val nightMagenta = lerp(trimSwatch?.color ?: classicGreen, Color.Black, 0.30f)

    // Neon shimmer: one dim glow drifting along the clock underline. Driven by
    // the frame clock (animPhase) so it runs even on head units that disable
    // system animations. Kept faint — ambiance, not glare.
    val scanX = triangle(animPhase(5600))

    // COMPACT (phone portrait): the bracketed side readouts need real width to
    // sit left/right of center: on a narrow phone they'd collide with the
    // clock, so compact drops them into the same overflow row every gauge
    // beyond the first already uses, and the clock keeps more of its size
    // since there's no side furniture competing with it.
    val compact = LocalSizeClass.current == SizeClass.COMPACT
    val clockSize = if (compact) 44.sp else 52.sp
    val underlineWidth: Modifier = if (compact) Modifier.fillMaxWidth(0.6f) else Modifier.width(200.dp)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .combinedClickable(onClick = onExit, onLongClick = onTalk),
    ) {
        HudCornerBrackets(color = nightMagenta.copy(alpha = 0.35f))

        // --- Side instrument readouts: speed (left), top OBD gauge (right) ---
        val loKeys = CruiseSettings.enabledLightsOutGaugeKeys(context)
        val loGauges = telemetry.obdGauges.filter { it.key in loKeys && it.value != "––" }
        val sideGauge = loGauges.firstOrNull()
        val overflowGauges = if (sideGauge != null) loGauges.drop(1) else emptyList()

        if (!compact) {
            if (CruiseSettings.lightsOutShowSpeed(context) && telemetry.speedMph != null) {
                HudReadout(
                    value = telemetry.speedMph.toString(),
                    label = "MPH",
                    color = nightAmber,
                    tickColor = nightMagenta,
                    modifier = Modifier.align(Alignment.CenterStart).padding(start = 28.dp),
                )
            }
            sideGauge?.let { g ->
                HudReadout(
                    value = g.value,
                    label = g.label,
                    color = nightAmber,
                    tickColor = nightMagenta,
                    modifier = Modifier.align(Alignment.CenterEnd).padding(end = 28.dp),
                )
            }
        }

        // Clock: small, top-center, a HUD mode readout - not the hero element.
        // Date rides along beneath it, same as before, just shrunk.
        Column(
            modifier = Modifier.align(Alignment.TopCenter).padding(top = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(time, style = AriaType.readout.copy(fontSize = clockSize), color = nightAmber.copy(alpha = 0.8f))
            Text(
                date.uppercase(Locale.getDefault()),
                style = AriaType.neonLabel.copy(fontSize = 9.sp),
                color = nightMagenta.copy(alpha = 0.5f),
            )
        }

        Column(
            modifier = Modifier.align(Alignment.Center),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // The center: a system-status readout, not the clock (2026-07-22).
            // "ALL SYSTEMS NOMINAL" is deliberately calm - never claim a fault
            // that isn't there. dtcCount > 0 gets the same green family at full
            // brightness/a filled box rather than a new warning hue - the
            // monochrome commitment holds, emphasis carries the urgency instead.
            val connected = com.kevin.midnightai.vehicle.ObdBluetoothManager.isConnected
            val (statusTitle, statusSub, statusBright) = when {
                !connected -> Triple("NO OBD", "LINK", false)
                dtcCount == 0 -> Triple("ALL SYSTEMS", "NOMINAL", false)
                else -> Triple("$dtcCount CODE${if (dtcCount == 1) "" else "S"}", "STORED", true)
            }
            Column(
                modifier = Modifier
                    .border(1.dp, nightMagenta.copy(alpha = if (statusBright) 0.6f else 0.4f))
                    .padding(horizontal = 22.dp, vertical = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    statusTitle,
                    style = AriaType.readout.copy(fontSize = 22.sp),
                    color = nightAmber.copy(alpha = if (statusBright) 1f else 0.85f),
                )
                Text(
                    statusSub,
                    style = AriaType.neonLabel.copy(fontSize = 10.sp, letterSpacing = 3.sp),
                    color = nightMagenta.copy(alpha = if (statusBright) 0.9f else 0.6f),
                )
            }

            // Now-playing: below the status box (2026-07-22, moved off the
            // bottom edge and sized up - it's a night-drive HUD, not a footnote).
            if (nowPlayingLine != null) {
                Spacer(Modifier.height(10.dp))
                Text(
                    "♪  $nowPlayingLine",
                    style = AriaType.readout.copy(fontSize = 18.sp),
                    color = nightAmber.copy(alpha = 0.7f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = 480.dp),
                )
            }

            Spacer(Modifier.height(12.dp))
            Canvas(underlineWidth.height(2.dp)) {
                // Faint fixed tube...
                drawRect(color = nightMagenta.copy(alpha = 0.14f))
                // ...with a brighter glow drifting along it.
                val cx = size.width * scanX
                val half = size.width * 0.20f
                drawRect(
                    brush = androidx.compose.ui.graphics.Brush.horizontalGradient(
                        colors = listOf(
                            Color.Transparent,
                            nightMagenta.copy(alpha = 0.6f),
                            Color.Transparent,
                        ),
                        startX = cx - half,
                        endX = cx + half,
                    ),
                )
            }

            // Compact/overflow gauges: speed + side gauge move here on a phone
            // (no room for side readouts); any gauge beyond the first always
            // lands here regardless of size class.
            val overflowRow = buildList {
                if (compact) {
                    if (CruiseSettings.lightsOutShowSpeed(context)) telemetry.speedMph?.let { add("$it MPH") }
                    sideGauge?.let { add("${it.value} ${it.label}") }
                }
                overflowGauges.forEach { add("${it.value} ${it.label}") }
            }
            if (overflowRow.isNotEmpty()) {
                Spacer(Modifier.height(14.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    overflowRow.forEachIndexed { i, f ->
                        if (i > 0) Text("・", style = AriaType.neonLabel, color = nightMagenta.copy(alpha = 0.3f))
                        Text(f, style = AriaType.readout.copy(fontSize = 14.sp), color = nightAmber.copy(alpha = 0.55f))
                    }
                }
            }

            // Conversation, without leaving the dark: status word + live caption in
            // the same phosphor. Long-press anywhere starts a turn.
            val status = when (phase) {
                Phase.CONNECTING -> "CONNECTING…"
                Phase.LISTENING -> "LISTENING…"
                Phase.THINKING -> "…"
                Phase.SPEAKING -> "SPEAKING…"
                Phase.IDLE -> null
            }
            if (status != null) {
                Spacer(Modifier.height(16.dp))
                Text(status, style = AriaType.neonLabel, color = nightMagenta.copy(alpha = 0.75f))
            }
            // A transient failure notice overrides the caption while it's showing,
            // a touch brighter than a caption so it reads at 4% backlight.
            if (notice.isNotBlank()) {
                Spacer(Modifier.height(10.dp))
                Text(
                    notice,
                    style = AriaType.neonLabel,
                    color = nightAmber.copy(alpha = 0.85f),
                    textAlign = TextAlign.Center,
                    modifier = Modifier.widthIn(max = 460.dp),
                )
            } else if (caption.isNotBlank()) {
                Spacer(Modifier.height(10.dp))
                Text(
                    caption,
                    style = AriaType.readout.copy(fontSize = 15.sp),
                    color = nightAmber.copy(alpha = 0.65f),
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = 460.dp),
                )
            }
        }

        Text(
            "TAP TO WAKE ・ HOLD TO TALK",
            style = AriaType.neonLabel.copy(fontSize = 10.sp),
            color = nightMagenta.copy(alpha = 0.3f),
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(20.dp),
        )

        // Mute toggle, dim like the rest of this HUD - its own clickable claims
        // taps in its small bounds before they reach the full-screen wake/talk
        // gesture behind it. Same choke point as Cruise's dash icon
        // (ProactivePreferences), silences unsolicited chatter only.
        val muted by ProactivePreferences.muted.collectAsState()
        Text(
            if (muted) "🔕" else "🔔",
            style = AriaType.neonLabel.copy(fontSize = 14.sp),
            color = if (muted) nightAmber.copy(alpha = 0.7f) else nightMagenta.copy(alpha = 0.3f),
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(20.dp)
                .clickable { ProactivePreferences.setMuted(context, !muted) },
        )

        // Trivia scoreboard overlay (.scratch/trivia-game) - BottomEnd since TopEnd/TopStart
        // are already claimed by the wake hint and mute toggle. Dim palette matches this
        // screen's low-photon rule rather than Cruise's brighter amber.
        TriviaOverlay(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(20.dp),
            accent = nightMagenta,
            dimAccent = nightMagenta.copy(alpha = 0.5f),
            panel = Color.Black.copy(alpha = 0.6f),
            textColor = nightAmber,
        )

        // Glance card (.scratch/glance-cards) - dim palette, same reasoning as Trivia above:
        // this screen's backlight is fixed at 0.04 the whole time it's active (see the
        // DisposableEffect up top), so there's no separate "wake" state to key off.
        GlanceCardOverlay(
            accent = nightMagenta,
            dimAccent = nightMagenta.copy(alpha = 0.5f),
            panel = Color.Black.copy(alpha = 0.75f),
            scrim = Color.Black.copy(alpha = 0.3f),
            textColor = nightAmber,
        )
    }
}

/**
 * Thin L-shaped corner marks framing the safe area — the jet-canopy-reticle
 * cue that reads as "cockpit HUD" at a glance. Static (no frame-clock cost);
 * purely decorative, never intercepts touch.
 */
@Composable
private fun HudCornerBrackets(color: Color, modifier: Modifier = Modifier) {
    val inset = 18.dp
    val armLength = 26.dp
    val stroke = 1.5.dp
    Canvas(modifier.fillMaxSize().padding(inset)) {
        val arm = armLength.toPx()
        val w = stroke.toPx()
        // Top-left
        drawLine(color, androidx.compose.ui.geometry.Offset(0f, 0f), androidx.compose.ui.geometry.Offset(arm, 0f), w)
        drawLine(color, androidx.compose.ui.geometry.Offset(0f, 0f), androidx.compose.ui.geometry.Offset(0f, arm), w)
        // Top-right
        drawLine(color, androidx.compose.ui.geometry.Offset(size.width, 0f), androidx.compose.ui.geometry.Offset(size.width - arm, 0f), w)
        drawLine(color, androidx.compose.ui.geometry.Offset(size.width, 0f), androidx.compose.ui.geometry.Offset(size.width, arm), w)
        // Bottom-left
        drawLine(color, androidx.compose.ui.geometry.Offset(0f, size.height), androidx.compose.ui.geometry.Offset(arm, size.height), w)
        drawLine(color, androidx.compose.ui.geometry.Offset(0f, size.height), androidx.compose.ui.geometry.Offset(0f, size.height - arm), w)
        // Bottom-right
        drawLine(color, androidx.compose.ui.geometry.Offset(size.width, size.height), androidx.compose.ui.geometry.Offset(size.width - arm, size.height), w)
        drawLine(color, androidx.compose.ui.geometry.Offset(size.width, size.height), androidx.compose.ui.geometry.Offset(size.width, size.height - arm), w)
    }
}

/**
 * One bracketed instrument readout: a big numeral in a thin rectangular
 * frame, flanked by small tick marks above/below — evokes an F16 tape-gauge
 * bracket without the plumbing a literal scrolling scale would need (no
 * numeric min/max exists per [com.kevin.midnightai.vehicle.ObdGauge] today,
 * only a pre-formatted display string). Static ticks, no animation cost.
 */
@Composable
private fun HudReadout(value: String, label: String, color: Color, tickColor: Color, modifier: Modifier = Modifier) {
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        TickRow(tickColor)
        Column(
            modifier = Modifier
                .border(1.dp, tickColor.copy(alpha = 0.4f))
                .padding(horizontal = 18.dp, vertical = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(value, style = AriaType.readout.copy(fontSize = 34.sp), color = color)
            Text(label, style = AriaType.neonLabel.copy(fontSize = 10.sp), color = tickColor.copy(alpha = 0.7f))
        }
        TickRow(tickColor)
    }
}

@Composable
private fun TickRow(color: Color) {
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        repeat(3) {
            Box(
                Modifier
                    .width(1.dp)
                    .height(6.dp)
                    .background(color.copy(alpha = 0.35f)),
            )
        }
    }
}
