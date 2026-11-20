package com.kevin.midnightai.ui.cruise

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Build
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kevin.midnightai.ai.AvatarStudio
import com.kevin.midnightai.service.AriaForegroundService
import com.kevin.midnightai.service.Phase
import com.kevin.midnightai.service.ProactivePreferences
import com.kevin.midnightai.time.TrustedClock
import com.kevin.midnightai.ui.PhoneLinkChip
import com.kevin.midnightai.ui.deckCardColor
import com.kevin.midnightai.ui.deckLineColor
import com.kevin.midnightai.ui.driveTarget
import com.kevin.midnightai.ui.theme.AriaColors
import com.kevin.midnightai.ui.theme.AriaDimens
import com.kevin.midnightai.ui.theme.AriaPalette
import com.kevin.midnightai.ui.theme.AriaType
import com.kevin.midnightai.weather.WeatherController
import java.util.Locale
import kotlinx.coroutines.delay

/*
 * Cruise's head-unit console column and the pieces it shares with the phone-portrait layout:
 * clock, companion portrait (tap to talk), telemetry chips, the setup chip and the dock tiles.
 * Split out of ui/CruiseScreen.kt (HU-1 T0, no behaviour change).
 */

/**
 * Top-left clock + date, with the weekday kanji as the little Showa charm mark,
 * and the current weather beneath it.
 *
 * Weather rides this composable's existing 10s tick rather than bringing its own
 * timer: [WeatherController] is a plain volatile cache refreshed on the service's
 * 30-min loop, not a flow, so there is nothing to collect and a second ticker
 * would just be a second wakeup for data that changes twice an hour.
 */
@Composable
internal fun Clock(context: Context, modifier: Modifier = Modifier, timeSize: TextUnit = 34.sp) {
    // The app's own clock and zone, never the head unit's (trusted clock, 2026-10-13: the unit's
    // clock resets itself and the weekday stamp read the wrong day).
    var now by remember { mutableStateOf(TrustedClock.nowMs()) }
    var weather by remember { mutableStateOf(WeatherController.current()) }
    val clockStatus by TrustedClock.status.collectAsState()
    LaunchedEffect(Unit) {
        while (true) {
            now = TrustedClock.nowMs()
            weather = WeatherController.current()
            delay(10_000)
        }
    }
    // Re-format when the minute rolls over, or when a sync / zone change lands.
    val time = remember(now / 60_000, clockStatus) { TrustedClock.formatClock(context, now) }
    val zoned = remember(now / 60_000, clockStatus) {
        java.time.Instant.ofEpochMilli(now).atZone(TrustedClock.zone())
    }
    val date = remember(zoned) {
        zoned.format(java.time.format.DateTimeFormatter.ofPattern("EEE, MMM d", Locale.getDefault()))
    }
    val kanji = remember(zoned) { weekdayKanji(zoned.dayOfWeek) }
    Column(modifier = modifier) {
        Row(verticalAlignment = Alignment.Bottom) {
            // TextPrimary, not an accent: the clock sits straight on the wallpaper with no
            // panel, and butter-yellow digits vanished on the summer palette.
            Text(time, style = AriaType.readout.copy(fontSize = timeSize), color = AriaColors.TextPrimary)
            Spacer(Modifier.width(8.dp))
            // Weekday kanji in a small stamped box, like a Japanese desk calendar.
            Text(
                kanji,
                style = AriaType.readout.copy(fontSize = 15.sp),
                color = AriaColors.accentText(AriaColors.NeonMagenta),
                modifier = Modifier
                    .padding(bottom = 4.dp)
                    .border(1.dp, AriaColors.NeonMagenta.copy(alpha = 0.6f), RoundedCornerShape(3.dp))
                    .padding(horizontal = 5.dp, vertical = 1.dp),
            )
        }
        Text(date.uppercase(Locale.getDefault()), style = AriaType.neonLabel, color = AriaColors.TextDim)
        // Weather. Null until the first fetch lands (no GPS yet, or offline), and
        // the row simply isn't there in that case rather than showing a placeholder
        // - an empty slot reads as "nothing to say", a dash reads as broken.
        weather?.let { w ->
            Text(
                "${w.tempF}° ・ ${w.description.uppercase(Locale.getDefault())}",
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = AriaType.neonLabel,
                // Rough conditions borrow the magenta the kanji stamp uses, so a
                // storm is legible at a glance without adding a new colour or an
                // icon set. Driving surfaces stay SPARE (sec 12): this is one line
                // of text that changes twice an hour, not a weather panel.
                color = if (w.caution) AriaColors.accentText(AriaColors.NeonMagenta) else AriaColors.TextDim,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}

/** The weekday kanji (日月火水木金土), Japanese calendar style. */
private fun weekdayKanji(day: java.time.DayOfWeek): String =
    when (day) {
        java.time.DayOfWeek.SUNDAY -> "日"
        java.time.DayOfWeek.MONDAY -> "月"
        java.time.DayOfWeek.TUESDAY -> "火"
        java.time.DayOfWeek.WEDNESDAY -> "水"
        java.time.DayOfWeek.THURSDAY -> "木"
        java.time.DayOfWeek.FRIDAY -> "金"
        java.time.DayOfWeek.SATURDAY -> "土"
    }

/**
 * The head unit's left console rail (HU-1 T1, Kevin's approved prototype 2026-11-19: "left keep
 * as is with the avatar and the buttons"): clock + weekday and weather, the companion portrait
 * (tap to talk), the phone-link chip and the six dock tiles, two per row (LIGHTS OUT, DAY/NIGHT,
 * MUTE, REAR CAM, APPS, SETUP). The telemetry chips moved into the middle CAR panel (ruling 4),
 * and the setup / update chips ride at the top of the middle panel, so the rail's 480 dp keeps
 * room for the portrait.
 *
 * Fits by construction, no scroll: the clock and the bottom group (phone chip, dock) are fixed,
 * and the portrait takes what is left, up to 13.5u.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun CruiseConsole(
    u: Dp,
    avatar: Bitmap?,
    phase: Phase,
    notice: String,
    caption: String,
    onLightsOut: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenPhoneLink: () -> Unit,
    onOpenAppTray: () -> Unit,
    onRearCamera: () -> Unit,
    onRearCameraPicker: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val gap = u
    Column(modifier) {
        // Clock digits scale with the frame; 3.3u = 34sp at 1024dp wide.
        val timeSize = with(density) { (u * 3.3f).toSp() }
        Clock(context = context, timeSize = timeSize)
        BoxWithConstraints(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(vertical = gap),
            contentAlignment = Alignment.TopCenter,
        ) {
            // The status line under the portrait (TAP TO TALK / LISTENING...) is ~28dp.
            val edge = minOf(u * 13.5f, maxHeight - 28.dp, maxWidth)
            if (edge >= AriaDimens.driveTouchMin) {
                AvatarVibe(
                    avatar = avatar,
                    ring = ringColor(phase),
                    status = statusLabel(phase),
                    notice = notice,
                    caption = caption,
                    onTap = { talk(context) },
                    width = edge,
                    height = edge,
                )
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(gap)) {
            PhoneLinkChip(
                onClick = onOpenPhoneLink,
                modifier = Modifier.fillMaxWidth().heightIn(min = AriaDimens.driveTouchMin),
            )
            // The prototype's 58 dp tile at 800 wide.
            val tileHeight = (u * 7.25f).coerceAtLeast(AriaDimens.driveTouchMin)
            val muted by ProactivePreferences.muted.collectAsState()
            val night = AriaPalette.isNight
            Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
                // Lights Out keeps a full-strength amber ring: a driver flagged the old dim
                // glyph as hard to find by feel at night, exactly when it matters most.
                ConsoleTile(u, "◐", "LIGHTS OUT", tileHeight, ring = AriaColors.Amber, modifier = Modifier.weight(1f), onClick = onLightsOut)
                // Day/night wallpaper + palette switch; shows what a tap switches TO.
                ConsoleTile(
                    u, if (night) "☀" else "☾", if (night) "DAY" else "NIGHT", tileHeight,
                    modifier = Modifier.weight(1f),
                ) { AriaPalette.setNight(context, !night) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
                // Silences proactive chatter/alerts, never driver-initiated speech.
                ConsoleTile(
                    u, if (muted) "🔕" else "🔔", if (muted) "MUTED" else "MUTE", tileHeight,
                    active = muted, modifier = Modifier.weight(1f),
                ) { ProactivePreferences.setMuted(context, !muted) }
                ConsoleTile(
                    u, "⛛", "REAR CAM", tileHeight, modifier = Modifier.weight(1f),
                    onLongClick = onRearCameraPicker, onClick = onRearCamera,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
                ConsoleTile(u, "⊞", "APPS", tileHeight, modifier = Modifier.weight(1f), onClick = onOpenAppTray)
                ConsoleTile(u, "⚙", "SETUP", tileHeight, modifier = Modifier.weight(1f), onClick = onOpenSettings)
            }
        }
    }
}

/**
 * The quiet, opt-in "SET UP COMPANION" chip (field-test call 2026-07-12: the old two-line
 * banner felt like the app was forcing onboarding). Nothing is spoken or started until the
 * driver taps it; the ✕ dismisses it for good. Both targets are drive-size (56dp).
 */
@Composable
internal fun SetupPromptChip(onStart: () -> Unit, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(8.dp)
    Row(
        modifier = modifier
            .heightIn(min = AriaDimens.driveTouchMin)
            .clip(shape)
            .background(AriaColors.Panel.copy(alpha = 0.82f))
            .border(1.dp, AriaColors.Amber.copy(alpha = 0.3f), shape)
            .clickable(onClickLabel = "Set up companion") { onStart() }
            .padding(start = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "SET UP COMPANION",
            style = AriaType.neonLabel.copy(fontSize = 11.sp, letterSpacing = 1.sp),
            color = AriaColors.TextPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        Box(
            Modifier
                .driveTarget()
                .clickable(onClickLabel = "Dismiss") { onDismiss() },
            contentAlignment = Alignment.Center,
        ) {
            Text("✕", style = AriaType.neonLabel.copy(fontSize = 14.sp), color = AriaColors.TextDim)
        }
    }
}

/** One console dock tile: card-coloured, icon over label; [active] fills it with the coral accent. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ConsoleTile(
    u: Dp,
    icon: String,
    label: String,
    height: Dp,
    modifier: Modifier = Modifier,
    active: Boolean = false,
    ring: Color? = null,
    onLongClick: (() -> Unit)? = null,
    onClick: () -> Unit,
) {
    val density = LocalDensity.current
    val shape = RoundedCornerShape(u * 1.1f)
    val ink = if (active) AriaColors.Panel else AriaColors.TextPrimary
    Box(
        modifier = modifier
            .height(height)
            .clip(shape)
            .background(if (active) AriaColors.NeonMagenta else deckCardColor())
            .border(if (ring != null) 2.dp else 1.dp, ring ?: deckLineColor(), shape)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(icon, style = AriaType.readout.copy(fontSize = with(density) { (u * 1.6f).toSp() }, letterSpacing = 0.sp), color = ink)
            // 11sp floor; a narrow tile wraps "LIGHTS OUT" / "REAR CAM" onto two lines rather
            // than shrinking the type.
            Text(
                label,
                style = AriaType.stamp.copy(fontSize = 11.sp, lineHeight = 12.sp, letterSpacing = 0.5.sp),
                color = ink,
                maxLines = 2,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/**
 * The companion, in a tall portrait window (waist-up city-pop illustration, not
 * a button-sized face). The phase ring becomes a window border; status sits in a
 * small header tab; the live caption overlays the bottom of the window so the
 * whole thing stays one object. Tap anywhere on it to talk.
 */
@Composable
internal fun AvatarVibe(
    avatar: Bitmap?,
    ring: Color,
    status: String?,
    notice: String = "",
    caption: String,
    onTap: () -> Unit,
    width: Dp = 216.dp,
    height: Dp = 268.dp,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier
                .width(width)
                .height(height)
                .clip(RoundedCornerShape(8.dp))
                .background(AriaColors.Panel.copy(alpha = 0.7f))
                .border(2.dp, ring, RoundedCornerShape(8.dp))
                .clickable { onTap() },
            contentAlignment = Alignment.Center,
        ) {
            if (avatar != null) {
                // Generated portraits are tall (216x268 default) but this box
                // is often squared off on COMPACT (100x100) - a plain Crop
                // defaults to Alignment.Center and centre-crops the head
                // clean off, showing torso instead of face. Anchor the crop
                // to the top so head + shoulders survive any aspect the box
                // is given.
                Image(
                    bitmap = avatar.asImageBitmap(),
                    contentDescription = "Tap to talk",
                    contentScale = ContentScale.Crop,
                    alignment = Alignment.TopCenter,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Text("●", style = AriaType.readout.copy(fontSize = 64.sp), color = ring)
            }
            // Live subtitle of what Zero is saying, overlaid at the window's
            // base. Capped at a fixed height (not maxLines/ellipsis, which cut
            // text off with no way to see the rest, especially once the
            // avatar shrank for the U6 deck rework) and scrollable instead -
            // pinned to the newest text via scrollTo (not animateScrollTo:
            // frame-clock motion only, no tween-based APIs in ui/, and a
            // spring animation would just silently freeze on animator-scale-0 head units).
            if (caption.isNotBlank()) {
                val captionScroll = rememberScrollState()
                LaunchedEffect(caption) { captionScroll.scrollTo(captionScroll.maxValue) }
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .heightIn(max = 92.dp)
                        .background(AriaColors.Background.copy(alpha = 0.78f))
                        .verticalScroll(captionScroll)
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                ) {
                    Text(
                        text = caption,
                        style = AriaType.readout.copy(fontSize = 13.sp),
                        color = AriaColors.TextPrimary,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        // A transient notice (amber) overrides the phase status while it's showing. This
        // sits straight on the wallpaper, so on the light palette every case is ink (the
        // ring above still carries the phase colour): coral/turquoise/butter text was
        // unreadable over the summer coast (Kevin, 2026-10-13).
        Text(
            notice.ifBlank { status ?: "TAP TO TALK" },
            style = AriaType.neonLabel,
            color = AriaColors.accentText(
                when {
                    notice.isNotBlank() -> AriaColors.Amber
                    status != null -> ring
                    // Full TextDim: the old 0.7 alpha sat at ~4.6:1 on the night ground.
                    else -> AriaColors.TextDim
                },
            ),
        )
    }
}

/** Avatar face for the current phase (CONNECTING borrows the thinking face). */
internal fun faceKey(phase: Phase): String = when (phase) {
    Phase.IDLE -> AvatarStudio.STATE_IDLE
    Phase.CONNECTING, Phase.THINKING -> AvatarStudio.STATE_THINKING
    Phase.LISTENING -> AvatarStudio.STATE_LISTENING
    Phase.SPEAKING -> AvatarStudio.STATE_SPEAKING
}

internal fun ringColor(phase: Phase): Color = when (phase) {
    Phase.IDLE -> AriaColors.NeonMagenta
    Phase.CONNECTING, Phase.THINKING -> AriaColors.Amber
    Phase.LISTENING -> AriaColors.NeonCyan
    Phase.SPEAKING -> AriaColors.NeonMagenta
}

internal fun statusLabel(phase: Phase): String? = when (phase) {
    Phase.IDLE -> null
    Phase.CONNECTING -> "CONNECTING…"
    Phase.LISTENING -> "LISTENING…"
    Phase.THINKING -> "…"
    Phase.SPEAKING -> "SPEAKING…"
}

/**
 * The one dock-tile idiom for COMPACT's fixed bottom control dock (2026-07-29
 * UI coherence pass, step 5). Nine controls used to be built from five
 * different idioms (a raw glyph Text, [CruiseButton], [NightModeButton],
 * [MuteButton], and the `*Tab`/[LogbookObject] family) in a horizontally-
 * scrolling strip that hid half of them off-screen at ~360dp. This replaces
 * all of that on COMPACT only: one container (fixed min touch height, accent
 * fill, rounded corner), arranged as two equal-width, non-scrolling rows -
 * [filled] mirrors the pre-existing "quiet dim accent at rest, solid accent
 * when the tile has something to say" pattern from the old CodesTab (stored
 * code count) and MuteButton (muted state). EXPANDED/MEDIUM keep their
 * original components untouched (see the calls further down this file).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun DockTile(
    accent: Color,
    modifier: Modifier = Modifier,
    filled: Boolean = false,
    // Optional: the REAR/CAM tile long-presses into the target picker rather
    // than adding a second visible control, since a settings row for the
    // same picker already exists in System (discoverability without knowing
    // about the long-press). No-op default keeps every other tile's plain
    // single-tap behavior identical to before.
    onLongClick: () -> Unit = {},
    onClick: () -> Unit,
    content: @Composable () -> Unit,
) {
    Box(
        modifier = modifier
            .heightIn(min = AriaDimens.driveTouchMin)
            .clip(RoundedCornerShape(6.dp))
            .background(accent.copy(alpha = if (filled) 0.9f else 0.35f))
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
        contentAlignment = Alignment.Center,
    ) {
        content()
    }
}

/** Icon-over-label content for a [DockTile] in the toggle/action row. */
@Composable
internal fun DockTileLabel(icon: String, label: String, color: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(icon, style = AriaType.readout.copy(fontSize = 18.sp), color = color)
        Text(label, style = AriaType.stamp.copy(fontSize = 9.sp), color = color, maxLines = 1, softWrap = false)
    }
}

internal fun talk(context: Context) {
    val intent = Intent(context, AriaForegroundService::class.java).setAction(AriaForegroundService.ACTION_TALK)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent)
    else context.startService(intent)
}
