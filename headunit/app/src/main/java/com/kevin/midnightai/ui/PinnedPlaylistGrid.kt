package com.kevin.midnightai.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kevin.midnightai.media.MusicDeckLogic
import com.kevin.midnightai.media.MusicSource
import com.kevin.midnightai.media.PinSlot
import com.kevin.midnightai.media.PlayRequests
import com.kevin.midnightai.media.SpotifyController
import com.kevin.midnightai.media.SpotifyPinLogic
import com.kevin.midnightai.media.SpotifyPins
import com.kevin.midnightai.media.SpotifyPlaylist
import com.kevin.midnightai.media.SpotifyPlaylistLogic
import com.kevin.midnightai.media.SpotifyPlaylists
import com.kevin.midnightai.media.SpotifyWebApi
import com.kevin.midnightai.service.SpotifyVoice
import com.kevin.midnightai.ui.theme.AriaColors
import com.kevin.midnightai.ui.theme.AriaPalette
import com.kevin.midnightai.ui.theme.AriaType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File

/**
 * Cruise's PINNED card (2026-10-13 split deck, prototype A): the driver's pinned Spotify
 * playlists as 2 columns x 5 rows of equal row-tiles, each a cover thumb on the left edge plus
 * the name and track count. Always ten cells ([MusicDeckLogic.slots]): an unused cell is a
 * dashed "+ PIN PLAYLIST" tile, so the grid never reflows as pins come and go.
 *
 * Tap a tile = play it (the graceful phone-to-Spotify switch, then shuffle-play). Long-press a
 * tile, or tap an empty one = [onOpenPicker], the Settings pin picker, which is where pins are
 * swapped, reordered and removed. With nothing pinned the grid shows the first ten playlists
 * of the library (same fallback the old row had) so day one is not ten empty tiles.
 *
 * [rowHeight] null = the five rows split the card's height (head unit); a value = fixed rows
 * (phone portrait, where the card sits in a scrolling column with no height to split).
 */
@Composable
internal fun PinnedPlaylistGrid(
    m: DeckMetrics,
    playing: Boolean,
    onOpenPicker: () -> Unit,
    modifier: Modifier = Modifier,
    rowHeight: Dp? = null,
) {
    val context = LocalContext.current
    val state by SpotifyPlaylists.state.collectAsState()
    val pins by SpotifyPins.pinned.collectAsState()
    val source by MusicSource.current.collectAsState()
    val playingContext by SpotifyController.playingContext.collectAsState()
    val scope = rememberCoroutineScope()
    var loadingId by remember { mutableStateOf<String?>(null) }
    // (playlist id, short reason) of the last tap that did not start; cleared after a few seconds.
    var failed by remember { mutableStateOf<Pair<String, String>?>(null) }
    LaunchedEffect(failed) {
        if (failed != null) {
            delay(TILE_FAILURE_SHOW_MS)
            failed = null
        }
    }

    LaunchedEffect(Unit) {
        SpotifyPins.load(context)
        SpotifyPlaylists.refreshIfStale(context)
    }

    val playingId = MusicDeckLogic.playingPlaylistId(source, playingContext?.uri, state.playlists)
    val slots = MusicDeckLogic.slots(state.playlists)
    val showingPins = pins.isNotEmpty() && state.playlists.isNotEmpty()
    val label = when {
        showingPins && pins.size >= SpotifyPinLogic.MAX_PINS -> "PINNED ・ ${SpotifyPinLogic.MAX_PINS}"
        showingPins -> "PINNED ・ ${pins.size} OF ${SpotifyPinLogic.MAX_PINS}"
        state.playlists.isNotEmpty() -> "YOUR PLAYLISTS ・ HOLD TO PIN"
        else -> "PINNED ・ 0 OF ${SpotifyPinLogic.MAX_PINS}"
    }

    // Not authorized (definite false, never the unknown start-up state): one calm card instead
    // of ten empty slots that could not be filled anyway.
    if (state.authorized == false) {
        ConnectSpotifyCard(m, onOpenSettings = onOpenPicker, fillHeight = rowHeight == null, modifier = modifier)
        return
    }

    Column(modifier.deckCard(m).padding(m.listPad)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                label,
                style = AriaType.neonLabel.copy(fontSize = m.label, letterSpacing = m.labelTracking),
                color = AriaColors.TextDim,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            // The one fixable state, said in words: the grant predates the playlist/library
            // scopes. Tiles stay usable while it shows.
            if (state.needsReconnect) {
                Box(
                    Modifier
                        .driveTarget()
                        .clip(RoundedCornerShape(m.tileRadius))
                        .clickable(onClickLabel = "Reconnect Spotify") { reconnect(context) }
                        .padding(horizontal = 8.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        "RECONNECT",
                        style = AriaType.neonLabel.copy(fontSize = m.label, letterSpacing = 1.sp),
                        color = AriaColors.accentText(AriaColors.NeonMagenta),
                    )
                }
            }
        }
        Spacer(Modifier.height(m.gap))
        // Before the first authorization check lands, nothing but the label: neither empty
        // slots nor a "Connect" prompt that may be wrong a moment later.
        if (state.authorized == null) return@Column
        Column(
            modifier = if (rowHeight == null) Modifier.weight(1f).fillMaxWidth() else Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(m.tileGap),
        ) {
            slots.chunked(2).forEach { pair ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .then(if (rowHeight == null) Modifier.weight(1f) else Modifier.height(rowHeight)),
                    horizontalArrangement = Arrangement.spacedBy(m.tileGap),
                ) {
                    pair.forEach { slot ->
                        val cell = Modifier.weight(1f).fillMaxHeight()
                        when (slot) {
                            is PinSlot.Pinned -> {
                                val p = slot.playlist
                                PlaylistTile(
                                    m = m,
                                    playlist = p,
                                    coverStamp = state.coverStamps[p.id] ?: 0L,
                                    isPlaying = p.id == playingId,
                                    animate = playing,
                                    loading = loadingId == p.id,
                                    failure = failed?.takeIf { it.first == p.id }?.second,
                                    onLongClick = onOpenPicker,
                                    modifier = cell,
                                ) {
                                    if (loadingId != null) return@PlaylistTile
                                    scope.launch {
                                        loadingId = p.id
                                        failed = null
                                        val why = try { playPlaylistBounded(context, p) } finally { loadingId = null }
                                        if (why != null) {
                                            // On the tile itself (toasts are easy to miss, or
                                            // suppressed on some units) and as a toast.
                                            failed = p.id to why
                                            Toast.makeText(context, "${p.name}: $why", Toast.LENGTH_LONG).show()
                                        }
                                    }
                                }
                            }
                            PinSlot.Empty -> EmptyPinTile(m, onClick = onOpenPicker, modifier = cell)
                        }
                    }
                }
            }
        }
    }
}

/**
 * The grid area while Spotify is not authorized: one line saying what pinning needs, and one
 * button (a full drive-size target) into Settings, Music, Spotify.
 */
@Composable
private fun ConnectSpotifyCard(
    m: DeckMetrics,
    onOpenSettings: () -> Unit,
    fillHeight: Boolean,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier.deckCard(m).padding(m.listPad),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            "PINNED PLAYLISTS",
            style = AriaType.neonLabel.copy(fontSize = m.label, letterSpacing = m.labelTracking),
            color = AriaColors.TextDim,
            modifier = Modifier.fillMaxWidth(),
        )
        Column(
            (if (fillHeight) Modifier.weight(1f) else Modifier).fillMaxWidth().padding(vertical = m.gap * 2),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                "Connect Spotify to pin playlists",
                style = AriaType.readout.copy(fontSize = m.subtitle, letterSpacing = 0.sp, fontWeight = DeckBold),
                color = AriaColors.TextPrimary,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(m.gap))
            Box(
                Modifier
                    .driveTarget()
                    .clip(RoundedCornerShape(m.tileRadius))
                    .background(AriaColors.NeonMagenta)
                    .clickable(onClickLabel = "Open Spotify settings") { onOpenSettings() }
                    .padding(horizontal = 16.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "SPOTIFY SETTINGS",
                    style = AriaType.neonLabel.copy(fontSize = m.label, letterSpacing = 1.sp),
                    color = AriaColors.Panel,
                )
            }
        }
    }
}

/** Opens Spotify's consent page; says so when nothing could (no browser, or no client ID saved). */
private fun reconnect(context: Context) {
    if (!SpotifyWebApi.beginAuthorization(context)) {
        Toast.makeText(context, "Can't open Spotify sign-in on this unit", Toast.LENGTH_SHORT).show()
    }
}

/**
 * Starts [p] shuffled, through the same sequence `play_music` uses
 * ([SpotifyVoice.playResolvedUri]): a [PlayRequests] token taken on tap (so a slow voice play,
 * or this tap if the driver moves on, stands down), the outgoing source quieted, connect,
 * switch playback to this head unit, then play. The tile is marked playing by App Remote's
 * player context, not by this call. Returns the short reason when it did not start (shown on
 * the tile and in a toast); null when it started or was superseded. With App Remote down the
 * play fails with the specific reason: App Remote is the only playback path (2026-10-15).
 */
private suspend fun playPlaylist(context: Context, p: SpotifyPlaylist): String? {
    // Captured before any connect: a connect can flip MusicSource to SPOTIFY by itself when the
    // phone is idle, and the outgoing mixtape/phone to quiet is judged from what it was before.
    val sourceBefore = MusicSource.current.value
    val token = PlayRequests.begin(sourceBefore)
    val outcome = SpotifyVoice.playResolvedUri(
        context = context,
        uri = p.uri,
        pickedLabel = p.name,
        sourceBeforeConnect = sourceBefore,
        stillWanted = { PlayRequests.shouldProceed(token) },
        shuffle = true,
    )
    return tileFailureMessage(outcome, p.name)
}

/**
 * Runs [playPlaylist] under [TILE_PLAY_TIMEOUT_MS] so the tile's pending pulse always ends, and
 * returns the short failure line to show (null = started or superseded).
 */
private suspend fun playPlaylistBounded(context: Context, p: SpotifyPlaylist): String? {
    val result = withTimeoutOrNull(TILE_PLAY_TIMEOUT_MS) { playPlaylist(context, p) ?: "" }
    return when (result) {
        null -> "Spotify didn't answer - try again"
        "" -> null
        else -> result
    }
}

/**
 * Upper bound on one tile tap: the bounded App Remote connect (4s), the device switch (5s), shuffle
 * and the play itself (8s each at worst). It used to also cover opening the Spotify app and waiting
 * for it to register; that path is gone (2026-10-15), so the bound dropped from 30s.
 */
private const val TILE_PLAY_TIMEOUT_MS = 22_000L

/** How long a tile shows its failure line before going back to the track count. */
private const val TILE_FAILURE_SHOW_MS = 6_000L

/** The short line for a tile tap that did not start, or null (started, or superseded). */
private fun tileFailureMessage(outcome: SpotifyController.PlayOutcome, name: String): String? = when (outcome) {
    is SpotifyController.PlayOutcome.Started, SpotifyController.PlayOutcome.Superseded -> null
    SpotifyController.PlayOutcome.PlayRejected -> "Spotify wouldn't start $name"
    SpotifyController.PlayOutcome.StillConnecting -> "Spotify is still connecting - try again"
    SpotifyController.PlayOutcome.NotSetUp -> "Spotify isn't set up - add the client ID in Settings"
    SpotifyController.PlayOutcome.ClientIdUnreadable -> "Can't read the saved Spotify setup - re-enter the client ID"
    else -> "Can't reach Spotify: ${SpotifyController.playbackReasonShort(SpotifyController.lastFailure)}"
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PlaylistTile(
    m: DeckMetrics,
    playlist: SpotifyPlaylist,
    coverStamp: Long,
    isPlaying: Boolean,
    animate: Boolean,
    loading: Boolean,
    failure: String?,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(m.tileRadius)
    // Frame-clock pulse while the tap is in flight (App Remote connect can take seconds);
    // animPhase with running=false does no frame work at all.
    val pulse = triangle(animPhase(900, running = loading))

    Box(
        modifier = modifier
            .alpha(if (loading) 0.45f + 0.55f * pulse else 1f)
            .then(if (isPlaying) Modifier.border(m.ring, AriaColors.Amber, shape) else Modifier)
            .clip(shape)
            .background(deckTileColor())
            .border(1.dp, deckLineColor(), shape)
            .combinedClickable(enabled = !loading, onClick = onClick, onLongClick = onLongClick),
    ) {
        Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
            CoverThumb(m, playlist.id, coverStamp)
            Column(
                Modifier
                    .weight(1f)
                    .padding(start = m.tilePad, end = if (isPlaying) m.tilePad + m.eqWidth else m.tilePad),
            ) {
                Text(
                    playlist.name,
                    style = AriaType.readout.copy(fontSize = m.tileName, letterSpacing = 0.sp, fontWeight = DeckBold),
                    color = AriaColors.TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (failure != null) {
                    // The failed tap's reason, in place of the track count until it clears.
                    Text(
                        failure,
                        style = AriaType.neonLabel.copy(fontSize = m.tileCount, letterSpacing = 0.sp),
                        color = AriaColors.NeonMagenta,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                } else {
                    playlist.trackCount?.let { n ->
                        Text(
                            MusicDeckLogic.trackCountLabel(n),
                            style = AriaType.neonLabel.copy(fontSize = m.tileCount, letterSpacing = 1.sp),
                            color = AriaColors.TextDim,
                            maxLines = 1,
                        )
                    }
                }
            }
        }
        if (isPlaying) {
            EqBars(
                m = m,
                running = animate,
                modifier = Modifier.align(Alignment.TopEnd).padding(top = m.tilePad, end = m.tilePad),
            )
        }
    }
}

/**
 * The tile's cover strip (thumb wide, tile tall), decoded off the main thread at the size it
 * is actually drawn: the square cover is cropped to fill the strip, so it must be at least the
 * strip's TALLER edge. The Bitmap lives only in this composable's state, never in a StateFlow.
 * Keyed on this playlist's own [coverStamp], so another tile's download does not re-decode it.
 */
@Composable
private fun CoverThumb(m: DeckMetrics, playlistId: String, coverStamp: Long) {
    val context = LocalContext.current
    BoxWithConstraints(
        Modifier
            .width(m.thumb)
            .fillMaxHeight()
            .background(placeholderBrush()),
        contentAlignment = Alignment.Center,
    ) {
        val targetPx = with(LocalDensity.current) { maxOf(maxWidth, maxHeight).roundToPx() }.coerceAtLeast(1)
        val cover by produceState<Bitmap?>(null, playlistId, coverStamp, targetPx) {
            value = withContext(Dispatchers.IO) { decodeSampled(SpotifyPlaylists.coverFile(context, playlistId), targetPx) }
        }
        val bmp = cover
        if (bmp != null) {
            Image(
                bitmap = bmp.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Text("♪", style = AriaType.readout.copy(fontSize = m.tileName), color = AriaColors.Panel)
        }
    }
}

/** Dashed "+ PIN PLAYLIST" cell; opens the pin picker. */
@Composable
private fun EmptyPinTile(m: DeckMetrics, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val line = deckLineColor().copy(alpha = 0.45f)
    val radius = m.tileRadius
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(radius))
            .drawBehind {
                val w = 1.5.dp.toPx()
                drawRoundRect(
                    color = line,
                    cornerRadius = CornerRadius(radius.toPx()),
                    style = Stroke(width = w, pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 4.dp.toPx()))),
                )
            }
            .clickable { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            "+ PIN PLAYLIST",
            style = AriaType.neonLabel.copy(fontSize = m.tileCount, letterSpacing = 1.sp),
            color = AriaColors.TextDim,
            textAlign = TextAlign.Center,
            maxLines = 1,
        )
    }
}

/**
 * The playing tile's three-bar EQ. Frame-clock ([animPhase]) so it moves on animator-scale-0
 * head units; with [running] false (paused) the bars hold still and no frame work happens.
 */
@Composable
private fun EqBars(m: DeckMetrics, running: Boolean, modifier: Modifier = Modifier) {
    // Read in the draw lambda only: each frame redraws the bars without recomposing the tile.
    val phase = animPhaseState(900, running = running)
    val color = AriaColors.NeonMagenta
    Canvas(modifier.height(m.eqHeight).width(m.eqWidth)) {
        val gap = size.width * 0.12f
        val barW = (size.width - 2 * gap) / 3f
        val r = CornerRadius(1.dp.toPx())
        for (i in 0 until 3) {
            val h = if (running) 0.25f + 0.75f * wave(phase.value, i / 3f) else 0.7f
            val barH = size.height * h
            drawRoundRect(
                color = color,
                topLeft = Offset(i * (barW + gap), size.height - barH),
                size = Size(barW, barH),
                cornerRadius = r,
            )
        }
    }
}

/**
 * The no-cover fill for thumbs and the idle album art: the palette's own warm-to-cool sweep,
 * held back at night so an empty card is not the brightest thing in the cabin.
 */
@Composable
internal fun placeholderBrush(): Brush {
    val a = if (AriaPalette.isNight) 0.55f else 1f
    return Brush.linearGradient(
        listOf(AriaColors.NeonMagenta.copy(alpha = a), AriaColors.Amber.copy(alpha = a), AriaColors.NeonCyan.copy(alpha = a)),
    )
}

/** Decodes [file] with a power-of-two inSampleSize so it is no smaller than [targetPx]. Null if absent/corrupt. */
internal fun decodeSampled(file: File, targetPx: Int): Bitmap? {
    if (!file.exists()) return null
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.path, bounds)
    if (bounds.outWidth <= 0) return null
    val opts = BitmapFactory.Options().apply {
        inSampleSize = SpotifyPlaylistLogic.sampleSizeFor(minOf(bounds.outWidth, bounds.outHeight), targetPx)
    }
    return BitmapFactory.decodeFile(file.path, opts)
}
