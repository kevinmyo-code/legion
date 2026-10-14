package com.kevin.midnightai.ui

import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kevin.midnightai.media.AppRemoteLink
import com.kevin.midnightai.media.MediaEmptyState
import com.kevin.midnightai.media.MusicDeckLogic
import com.kevin.midnightai.media.MusicRouter
import com.kevin.midnightai.media.MusicSource
import com.kevin.midnightai.media.NowPlayingInfo
import com.kevin.midnightai.media.Source
import com.kevin.midnightai.media.SpotifyController
import com.kevin.midnightai.media.VolumeController
import com.kevin.midnightai.media.openNotificationAccessSettings
import com.kevin.midnightai.ui.theme.AriaColors
import com.kevin.midnightai.ui.theme.AriaDimens
import com.kevin.midnightai.ui.theme.AriaPalette
import com.kevin.midnightai.ui.theme.AriaType
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * Every size the split music deck uses, resolved once per layout pass. On the head unit they
 * all derive from one unit `u` = 1% of the frame width (the approved prototype is specified in
 * those units), so a 1024x600 and a 1280x720 unit get the same composition at different scale.
 * Phone portrait gets fixed values instead ([compact]): 1% of a 360dp phone is too small to read.
 */
@Immutable
internal data class DeckMetrics(
    val gap: Dp,
    val cardRadius: Dp,
    val npPad: Dp,
    val listPad: Dp,
    val artRadius: Dp,
    val label: TextUnit,
    val labelTracking: TextUnit,
    val title: TextUnit,
    val subtitle: TextUnit,
    val times: TextUnit,
    val progressHeight: Dp,
    val big: Dp,
    val mid: Dp,
    val small: Dp,
    val tileGap: Dp,
    val tileRadius: Dp,
    val tilePad: Dp,
    val thumb: Dp,
    val tileName: TextUnit,
    val tileCount: TextUnit,
    val ring: Dp,
    val eqHeight: Dp,
    val eqWidth: Dp,
) {
    companion object {
        /** Head-unit metrics from the prototype's cqw values; text floors keep a small unit readable. */
        fun fromUnit(u: Dp, density: Density): DeckMetrics {
            fun sp(dp: Dp, floor: TextUnit): TextUnit = with(density) {
                val v = dp.toSp()
                if (v.value < floor.value) floor else v
            }
            return DeckMetrics(
                gap = u * 1.1f,
                cardRadius = u * 1.6f,
                npPad = u * 1.8f,
                listPad = u * 1.6f,
                artRadius = u * 1.2f,
                label = sp(u * 1.05f, 9.sp),
                labelTracking = sp(u * 0.28f, 1.sp),
                title = sp(u * 2.5f, 16.sp),
                subtitle = sp(u * 1.5f, 11.sp),
                times = sp(u * 1.05f, 9.sp),
                progressHeight = u * 0.55f,
                big = (u * 8.2f).coerceAtLeast(56.dp),
                mid = (u * 6f).coerceAtLeast(48.dp),
                small = (u * 4.2f).coerceAtLeast(40.dp),
                tileGap = u * 0.9f,
                tileRadius = u * 1.2f,
                tilePad = u * 1f,
                thumb = u * 3.6f,
                tileName = sp(u * 1.3f, 11.sp),
                tileCount = sp(u * 0.95f, 8.sp),
                ring = u * 0.35f,
                eqHeight = u * 1.4f,
                eqWidth = u * 1.6f,
            )
        }

        val compact = DeckMetrics(
            gap = 8.dp,
            cardRadius = 14.dp,
            npPad = 14.dp,
            listPad = 12.dp,
            artRadius = 10.dp,
            label = 10.sp,
            labelTracking = 2.sp,
            title = 20.sp,
            subtitle = 13.sp,
            times = 10.sp,
            progressHeight = 4.dp,
            big = 64.dp,
            mid = 48.dp,
            small = 40.dp,
            tileGap = 8.dp,
            tileRadius = 10.dp,
            tilePad = 8.dp,
            thumb = 40.dp,
            tileName = 13.sp,
            tileCount = 9.sp,
            ring = 3.dp,
            eqHeight = 12.dp,
            eqWidth = 14.dp,
        )
    }
}

internal val DeckBold = FontWeight.Bold

/** A tappable control's hit area: at least [AriaDimens.driveTouchMin] both ways (glove-and-bump size). */
internal fun Modifier.driveTarget(): Modifier =
    this.heightIn(min = AriaDimens.driveTouchMin).widthIn(min = AriaDimens.driveTouchMin)

/** Card fill: the palette's panel (cream by day, navy by night) at the prototype's alphas. */
@Composable
internal fun deckCardColor(): Color = AriaColors.Panel.copy(alpha = if (AriaPalette.isNight) 0.78f else 0.86f)

/** Tile fill, a touch more opaque than its card so tiles read as objects on it. */
@Composable
internal fun deckTileColor(): Color = AriaColors.Panel.copy(alpha = if (AriaPalette.isNight) 0.9f else 0.95f)

/** The 1px hairline around cards, tiles and the mid transport keys. */
@Composable
internal fun deckLineColor(): Color = AriaColors.TextPrimary.copy(alpha = if (AriaPalette.isNight) 0.16f else 0.14f)

@Composable
internal fun Modifier.deckCard(m: DeckMetrics): Modifier {
    val shape = RoundedCornerShape(m.cardRadius)
    return this
        .clip(shape)
        .background(deckCardColor())
        .border(1.dp, deckLineColor(), shape)
}

/**
 * Cruise's NOW PLAYING card (2026-10-13 split deck, prototype A). Replaces the cassette deck,
 * its colorway switcher and the separate transport row: label row, album art square, title,
 * "artist ・ playlist/album", seeker, then the transport keys (shuffle, previous, play/pause,
 * next, like) and volume. The keys are laid out to fit the card BY CONSTRUCTION
 * ([MusicDeckLogic.transportMode]): one, two or three rows depending on the measured width, every
 * hit area at least [AriaDimens.driveTouchMin].
 *
 * Works for every source through [MusicRouter] (phone Bluetooth, mixtape, head-unit Spotify).
 * Shuffle and like are App Remote calls, so they are live only on connected Spotify and dimmed
 * otherwise. The seeker hides when the session gives no length. With nothing playing the card
 * keeps its shape: placeholder art, "Nothing playing" (or which of the three blank reasons it
 * is, see [MediaEmptyState]), and play still asks the source to start.
 *
 * [fixedArt] null = the art takes whatever height is left (head unit); a value = fixed edge
 * (phone portrait, inside a scrolling column).
 */
@Composable
internal fun NowPlayingCard(
    m: DeckMetrics,
    info: NowPlayingInfo?,
    art: Bitmap?,
    contextName: String?,
    emptyState: MediaEmptyState,
    modifier: Modifier = Modifier,
    fixedArt: Dp? = null,
    debugLine: String? = null,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val source by MusicSource.current.collectAsState()
    val spotifyState by SpotifyController.playerState.collectAsState()
    // The link flow recomposes on connect/drop; isConnected catches a silent drop it missed.
    val link by SpotifyController.link.collectAsState()
    val spotifyConnected = link == AppRemoteLink.CONNECTED && SpotifyController.isConnected
    val extras = MusicDeckLogic.spotifyExtrasEnabled(source, spotifyConnected)
    val playing = info?.isActive == true
    val scope = rememberCoroutineScope()

    Column(modifier.deckCard(m).padding(m.npPad)) {
        // --- Label row: "NOW PLAYING ・ SOURCE", or the PHONE | SPOTIFY switch when there is
        // something to switch between (head-unit Spotify connected, no mixtape loaded).
        val canSwitch = spotifyConnected && source != Source.MIXTAPE
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (canSwitch) "NOW PLAYING" else "NOW PLAYING ・ ${MusicDeckLogic.sourceLabel(source)}",
                style = AriaType.neonLabel.copy(fontSize = m.label, letterSpacing = m.labelTracking),
                color = AriaColors.TextDim,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (canSwitch) SourceSwitch(m, source, context)
        }
        Spacer(Modifier.height(m.gap))

        // --- Album art: square, centred, as big as the space allows. Swipe to skip, like the
        // cassette it replaces.
        val artBox = if (fixedArt != null) Modifier.fillMaxWidth().height(fixedArt) else Modifier.fillMaxWidth().weight(1f)
        BoxWithConstraints(artBox, contentAlignment = Alignment.Center) {
            val edge = minOf(maxWidth, maxHeight)
            val shape = RoundedCornerShape(m.artRadius)
            Box(
                Modifier
                    .size(edge)
                    .shadow(elevation = m.artRadius, shape = shape, clip = false)
                    .clip(shape)
                    .background(placeholderBrush())
                    .pointerInput(Unit) {
                        horizontalSwipe(
                            onSwipeLeft = { MusicRouter.next(context) },
                            onSwipeRight = { MusicRouter.previous(context) },
                        )
                    },
                contentAlignment = Alignment.Center,
            ) {
                if (art != null) {
                    Image(
                        bitmap = art.asImageBitmap(),
                        contentDescription = info?.album?.takeIf { it.isNotBlank() } ?: "Album art",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("♪", style = AriaType.readout.copy(fontSize = m.title * 2), color = AriaColors.Panel)
                        Text(
                            "ミュージック",
                            style = AriaType.neonLabel.copy(fontSize = m.label, letterSpacing = 2.sp),
                            color = AriaColors.Panel,
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(m.gap))

        // --- Title + subtitle, or the idle sentence.
        val title = when {
            info != null -> info.title
            emptyState == MediaEmptyState.NEEDS_NOTIFICATION_ACCESS -> "Can't see music"
            emptyState == MediaEmptyState.METADATA_MISSING -> "No track info"
            else -> "Nothing playing"
        }
        Text(
            title,
            style = AriaType.readout.copy(fontSize = m.title, letterSpacing = 0.sp, fontWeight = DeckBold),
            color = AriaColors.TextPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (info != null) {
            Text(
                MusicDeckLogic.subtitle(info.artist, contextName ?: info.album),
                style = AriaType.readout.copy(fontSize = m.subtitle, letterSpacing = 0.sp),
                color = AriaColors.TextDim,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (MusicDeckLogic.showProgress(info.duration)) {
                Spacer(Modifier.height(m.gap))
                Seeker(m, info)
            }
        } else {
            IdleCaption(m, emptyState, source, context)
        }
        if (debugLine != null) {
            Text(debugLine, style = AriaType.neonLabel.copy(fontSize = 8.sp), color = AriaColors.TextDim)
        }
        Spacer(Modifier.height(m.gap))

        // --- Transport: shuffle (small), previous, play/pause (big, coral), next, like (small),
        // volume. Like reads the track's real saved state from App Remote when it can.
        val trackUri = spotifyState?.track?.uri?.takeIf { extras }
        var liked by remember(trackUri) { mutableStateOf(false) }
        LaunchedEffect(trackUri) {
            liked = trackUri?.let { SpotifyController.isSaved(it) } ?: false
        }
        val shuffling = spotifyState?.playbackOptions?.isShuffling == true
        var volumePct by remember { mutableStateOf(VolumeController.current(context)) }
        LaunchedEffect(Unit) {
            // Keep the meter in sync if volume is changed elsewhere (hardware knob, voice).
            while (true) { volumePct = VolumeController.current(context); delay(1000) }
        }
        val keys = TransportKeys(
            playing = playing,
            extras = extras,
            shuffling = shuffling,
            liked = liked,
            volumePct = volumePct,
            onShuffle = {
                scope.launch {
                    if (SpotifyController.toggleShuffle(context) == null) {
                        Toast.makeText(context, SpotifyController.notConnectedMessage(), Toast.LENGTH_SHORT).show()
                    }
                }
            },
            onLike = {
                scope.launch {
                    val action = if (liked) SpotifyController.LibraryAction.UNLIKE else SpotifyController.LibraryAction.LIKE
                    val outcome = if (liked) SpotifyController.unlike(context) else SpotifyController.like(context)
                    if (SpotifyController.succeeded(outcome)) {
                        liked = action == SpotifyController.LibraryAction.LIKE
                    } else {
                        Toast.makeText(context, SpotifyController.message(outcome, action), Toast.LENGTH_SHORT).show()
                    }
                }
            },
            onPrevious = { MusicRouter.previous(context) },
            onPlayPause = { if (playing) MusicRouter.pause(context) else MusicRouter.play(context) },
            onNext = { MusicRouter.next(context) },
            onVolumeDown = { volumePct = VolumeController.lower(context) },
            onVolumeUp = { volumePct = VolumeController.raise(context) },
        )
        TransportBlock(m, keys)
    }
}

/** Everything the transport block shows and does, so its layout can be chosen by width alone. */
private class TransportKeys(
    val playing: Boolean,
    val extras: Boolean,
    val shuffling: Boolean,
    val liked: Boolean,
    val volumePct: Int,
    val onShuffle: () -> Unit,
    val onLike: () -> Unit,
    val onPrevious: () -> Unit,
    val onPlayPause: () -> Unit,
    val onNext: () -> Unit,
    val onVolumeDown: () -> Unit,
    val onVolumeUp: () -> Unit,
)

/**
 * The transport keys, arranged for the card's measured width ([MusicDeckLogic.transportMode]):
 * every row is checked to fit before it is chosen, so no key is ever squeezed or clipped.
 */
@Composable
private fun TransportBlock(m: DeckMetrics, k: TransportKeys) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val w = maxWidth.value
        val gap = MusicDeckLogic.KEY_GAP.dp
        val mode = MusicDeckLogic.transportMode(w)
        val avail = when (mode) {
            MusicDeckLogic.TransportMode.ONE_ROW -> w - 2 * MusicDeckLogic.TOUCH_MIN - 4 * MusicDeckLogic.KEY_GAP
            else -> w - 2 * MusicDeckLogic.KEY_GAP
        }
        val sizes = MusicDeckLogic.keySizes(avail, m.mid.value, m.big.value)
        val mid = sizes.mid.dp
        val big = sizes.big.dp
        val shuffle: @Composable () -> Unit = {
            SmallKey(m, "⇄", on = k.extras && k.shuffling, enabled = k.extras, description = "Shuffle", onClick = k.onShuffle)
        }
        val like: @Composable () -> Unit = {
            SmallKey(m, if (k.extras && k.liked) "♥" else "♡", on = k.extras && k.liked, enabled = k.extras, description = "Like", onClick = k.onLike)
        }
        val volDown: @Composable () -> Unit = {
            SmallKey(m, "−", on = false, enabled = true, description = "Volume down", onClick = k.onVolumeDown)
        }
        val volUp: @Composable () -> Unit = {
            SmallKey(m, "＋", on = false, enabled = true, description = "Volume up", onClick = k.onVolumeUp)
        }
        val meter: @Composable () -> Unit = { VolumeBars(percent = k.volumePct, height = m.small * 0.45f) }
        val core: @Composable () -> Unit = {
            MidKey(m, mid, "◀◀", "Previous", k.onPrevious)
            BigKey(m, big, if (k.playing) "❚❚" else "▶", if (k.playing) "Pause" else "Play", k.onPlayPause)
            MidKey(m, mid, "▶▶", "Next", k.onNext)
        }
        Column(verticalArrangement = Arrangement.spacedBy(m.gap)) {
            when (mode) {
                MusicDeckLogic.TransportMode.ONE_ROW -> {
                    KeyRow(Arrangement.SpaceBetween) { shuffle(); core(); like() }
                    KeyRow(Arrangement.spacedBy(gap, Alignment.CenterHorizontally)) {
                        volDown(); if (MusicDeckLogic.meterFits(w, 2)) meter(); volUp()
                    }
                }
                MusicDeckLogic.TransportMode.TWO_ROWS -> {
                    KeyRow(Arrangement.spacedBy(gap, Alignment.CenterHorizontally)) { core() }
                    KeyRow(Arrangement.SpaceBetween) {
                        shuffle(); volDown(); if (MusicDeckLogic.meterFits(w, 4)) meter(); volUp(); like()
                    }
                }
                MusicDeckLogic.TransportMode.THREE_ROWS -> {
                    KeyRow(Arrangement.spacedBy(gap, Alignment.CenterHorizontally)) { core() }
                    KeyRow(Arrangement.SpaceBetween) { shuffle(); like() }
                    KeyRow(Arrangement.spacedBy(gap, Alignment.CenterHorizontally)) {
                        volDown(); if (MusicDeckLogic.meterFits(w, 2)) meter(); volUp()
                    }
                }
            }
        }
    }
}

@Composable
private fun KeyRow(arrangement: Arrangement.Horizontal, content: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = arrangement, verticalAlignment = Alignment.CenterVertically) {
        content()
    }
}

/** The seeker: bar + elapsed / remaining, run forward on a 1s tick while playing. */
@Composable
private fun Seeker(m: DeckMetrics, info: NowPlayingInfo) {
    var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    // Lenient isActive, same as play/pause: Bluetooth AVRCP often never reports STATE_PLAYING.
    LaunchedEffect(info.isActive, info.positionUpdatedAt) {
        now = SystemClock.elapsedRealtime()
        while (info.isActive) {
            delay(1000)
            now = SystemClock.elapsedRealtime()
        }
    }
    val pos = MusicDeckLogic.livePosition(
        info.position, info.positionUpdatedAt, now, info.playbackSpeed, info.isActive, info.duration,
    )
    Column(Modifier.fillMaxWidth()) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(m.progressHeight)
                .clip(CircleShape)
                .background(deckLineColor()),
        ) {
            Box(
                Modifier
                    .fillMaxWidth(MusicDeckLogic.fraction(pos, info.duration))
                    .fillMaxHeight()
                    .clip(CircleShape)
                    .background(AriaColors.NeonMagenta),
            )
        }
        Row(Modifier.fillMaxWidth().padding(top = 3.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            val style = AriaType.readout.copy(fontSize = m.times, letterSpacing = 0.sp, fontFeatureSettings = "tnum")
            Text(MusicDeckLogic.formatClock(pos), style = style, color = AriaColors.TextDim)
            Text(MusicDeckLogic.formatRemaining(pos, info.duration), style = style, color = AriaColors.TextDim)
        }
    }
}

/**
 * What the card says under "Nothing playing": the honest reason when it can tell (CLAUDE.md
 * sec 1: unreadable and empty are different sentences), else where music comes from.
 */
@Composable
private fun IdleCaption(m: DeckMetrics, emptyState: MediaEmptyState, source: Source, context: Context) {
    val body = AriaType.readout.copy(fontSize = m.subtitle, letterSpacing = 0.sp)
    when (emptyState) {
        MediaEmptyState.NEEDS_NOTIFICATION_ACCESS -> Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Notification access is off.",
                style = body,
                color = AriaColors.TextDim,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            Box(
                Modifier
                    .driveTarget()
                    .clip(RoundedCornerShape(4.dp))
                    .clickable(onClickLabel = "Grant notification access") { openNotificationAccessSettings(context) }
                    .padding(horizontal = 6.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "GRANT ACCESS",
                    style = AriaType.neonLabel.copy(fontSize = m.label, letterSpacing = 1.sp),
                    color = AriaColors.accentText(AriaColors.NeonMagenta),
                )
            }
        }
        MediaEmptyState.METADATA_MISSING -> Text(
            "Connected, but the phone isn't sending track info.",
            style = body,
            color = AriaColors.TextDim,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        MediaEmptyState.NOTHING_PLAYING, MediaEmptyState.HAS_INFO -> Text(
            MusicDeckLogic.idleCaption(source),
            style = body,
            color = AriaColors.TextDim,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** PHONE | SPOTIFY: the graceful source switch (pause outgoing, start incoming). */
@Composable
private fun SourceSwitch(m: DeckMetrics, source: Source, context: Context) {
    Row(
        Modifier
            .clip(RoundedCornerShape(m.tileRadius))
            .border(1.dp, deckLineColor(), RoundedCornerShape(m.tileRadius)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SwitchLabel(m, "PHONE", active = source == Source.PHONE) { MusicRouter.switchToPhone(context) }
        SwitchLabel(m, "SPOTIFY", active = source == Source.SPOTIFY) { MusicRouter.switchToSpotify(context) }
    }
}

@Composable
private fun SwitchLabel(m: DeckMetrics, label: String, active: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .driveTarget()
            .background(if (active) AriaColors.TextPrimary else Color.Transparent)
            .clickable(enabled = !active, onClickLabel = "Switch to $label") { onClick() }
            .padding(horizontal = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            style = AriaType.neonLabel.copy(fontSize = m.label, letterSpacing = 1.sp),
            color = if (active) AriaColors.Panel else AriaColors.TextDim,
        )
    }
}

@Composable
private fun BigKey(m: DeckMetrics, size: Dp, glyph: String, description: String, onClick: () -> Unit) {
    Box(
        Modifier
            .size(size)
            .shadow(elevation = size * 0.08f, shape = CircleShape, clip = false)
            .clip(CircleShape)
            .background(AriaColors.NeonMagenta)
            .clickable(onClickLabel = description) { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Text(glyph, style = AriaType.readout.copy(fontSize = m.title * 1.1f, letterSpacing = 0.sp), color = AriaColors.Panel)
    }
}

@Composable
private fun MidKey(m: DeckMetrics, size: Dp, glyph: String, description: String, onClick: () -> Unit) {
    Box(
        Modifier
            .size(size)
            .clip(CircleShape)
            .background(deckTileColor())
            .border(1.dp, deckLineColor(), CircleShape)
            .clickable(onClickLabel = description) { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Text(glyph, style = AriaType.readout.copy(fontSize = m.subtitle, letterSpacing = 0.sp), color = AriaColors.TextPrimary)
    }
}

@Composable
private fun SmallKey(
    m: DeckMetrics,
    glyph: String,
    on: Boolean,
    enabled: Boolean,
    description: String,
    onClick: () -> Unit,
) {
    // Hit area is the full drive-size target; the visual stays the prototype's small key.
    Box(
        Modifier
            .size(AriaDimens.driveTouchMin)
            .alpha(if (enabled) 1f else 0.35f)
            .clip(CircleShape)
            .clickable(enabled = enabled, onClickLabel = description) { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            glyph,
            style = AriaType.readout.copy(fontSize = m.title, letterSpacing = 0.sp),
            color = if (on) AriaColors.NeonMagenta else AriaColors.TextPrimary.copy(alpha = 0.75f),
        )
    }
}

/** Ten rising bars; lit ones in ink, the top two in the coral accent. A readout only. */
@Composable
private fun VolumeBars(percent: Int, height: Dp, segments: Int = 10) {
    val lit = (percent.coerceIn(0, 100) * segments + 50) / 100
    Row(
        Modifier.height(height),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        for (i in 0 until segments) {
            val color = when {
                i >= lit -> deckLineColor()
                i >= segments - 2 -> AriaColors.NeonMagenta
                else -> AriaColors.TextDim
            }
            Box(
                Modifier
                    .width(4.dp)
                    .fillMaxHeight(0.4f + 0.6f * i / (segments - 1))
                    .clip(RoundedCornerShape(1.dp))
                    .background(color),
            )
        }
    }
}

/**
 * Horizontal swipe on the album art skips tracks, once the drag clears a small threshold.
 * Horizontal-only, so a vertical drag (the phone layout's scroll) is never claimed here.
 */
private suspend fun PointerInputScope.horizontalSwipe(onSwipeLeft: () -> Unit, onSwipeRight: () -> Unit) {
    var total = 0f
    detectHorizontalDragGestures(
        onDragStart = { total = 0f },
        onHorizontalDrag = { change, dx -> change.consume(); total += dx },
        onDragEnd = {
            if (abs(total) > 48f) {
                if (total < 0) onSwipeLeft() else onSwipeRight()
            }
        },
    )
}
