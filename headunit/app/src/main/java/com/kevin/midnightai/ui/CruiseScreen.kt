package com.kevin.midnightai.ui

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
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
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
import com.kevin.midnightai.location.LocationController
import com.kevin.midnightai.media.MediaDiagnostics
import com.kevin.midnightai.media.MixtapePlayer
import com.kevin.midnightai.media.MusicSource
import com.kevin.midnightai.media.NowPlayingController
import com.kevin.midnightai.media.Source
import com.kevin.midnightai.media.SpotifyController
import com.kevin.midnightai.media.SpotifyPlaylists
import com.kevin.midnightai.media.selectMediaEmptyState
import com.kevin.midnightai.service.AriaForegroundService
import com.kevin.midnightai.service.CompanionPhase
import com.kevin.midnightai.service.DebugSettings
import com.kevin.midnightai.service.LauncherSettings
import com.kevin.midnightai.service.Phase
import com.kevin.midnightai.service.ProactivePreferences
import com.kevin.midnightai.ui.theme.AriaColors
import com.kevin.midnightai.ui.theme.AriaDimens
import com.kevin.midnightai.ui.theme.AriaPalette
import com.kevin.midnightai.ui.theme.AriaType
import com.kevin.midnightai.ui.theme.LightsOutSwatch
import com.kevin.midnightai.vehicle.ObdBluetoothManager
import com.kevin.midnightai.vehicle.ObdGauge
import com.kevin.midnightai.weather.WeatherController
import com.kevin.midnightai.time.TrustedClock
import java.io.File
import java.util.Locale
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.withContext

/** Edge (px) album art / tape covers are sampled to: sharp at the card's largest art on a 1280x720 unit. */
private const val DECK_ART_PX = 512

/**
 * The in-car ambient "Cruise screen" - the app's foreground default. A glanceable,
 * lofi-summer city-pop dashboard people leave up.
 *
 * **Split deck, 2026-10-13** (Kevin picked prototype A): on the head unit a quiet console
 * column on the left (clock, companion portrait, telemetry chips, phone chip, dock) and the
 * music area on the right ~72% - a NOW PLAYING card (album art, title, seeker, transport;
 * ui/MusicDeck.kt) beside a 2 x 5 grid of pinned Spotify playlists (ui/PinnedPlaylistGrid.kt).
 * This replaced the cassette deck, its colorway switcher, the separate transport row and the
 * 5-cover playlist strip. Phone portrait stacks the same two cards.
 *
 * **Slimmed 2026-09-27** (the head-unit-only pass): the embedded nav map, the CODES/GARAGE/
 * CARS tabs, the logbook binder, and the trivia scoreboard are not shown here - none of those
 * surfaces were deleted, Cruise just stopped being their entry point. `dtcCount` is still
 * computed because Lights Out's HUD footer reads it.
 */
internal object CruiseSettings {
    private const val PREFS = "cruise"
    private const val KEY_SAVER_MIN = "saver_minutes"

    /** Idle minutes before the ambient screen saver starts; 0 = off. */
    fun screenSaverMinutes(context: Context): Int =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(KEY_SAVER_MIN, 5)

    fun setScreenSaverMinutes(context: Context, minutes: Int) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putInt(KEY_SAVER_MIN, minutes).apply()

    // --- Lights Out gauge visibility (U4) -----------------------------------
    // Which telemetry fields show in the night HUD footer. Color stays on the
    // existing curated-palette system above, not a separate free picker.

    private fun flag(context: Context, key: String): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(key, true)

    private fun setFlag(context: Context, key: String, value: Boolean) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(key, value).apply()

    fun lightsOutShowSpeed(context: Context) = flag(context, "lo_show_speed")
    fun setLightsOutShowSpeed(context: Context, v: Boolean) = setFlag(context, "lo_show_speed", v)

    // --- OBD instrument gauges (auto-discovered per car; see ObdGauge) --------
    // Separate selections for the Cruise strip and the Lights Out footer, each a
    // set of enabled ObdGauge keys; unset = the defaults.
    fun enabledCruiseGaugeKeys(context: Context): Set<String> = gaugeKeys(context, "cruise_gauges")
    fun setCruiseGaugeEnabled(context: Context, gauge: ObdGauge, enabled: Boolean) =
        setGaugeKey(context, "cruise_gauges", gauge, enabled)

    fun enabledLightsOutGaugeKeys(context: Context): Set<String> = gaugeKeys(context, "lo_gauges")
    fun setLightsOutGaugeEnabled(context: Context, gauge: ObdGauge, enabled: Boolean) =
        setGaugeKey(context, "lo_gauges", gauge, enabled)

    private fun gaugeKeys(context: Context, key: String): Set<String> =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getStringSet(key, null) ?: ObdGauge.DEFAULT_KEYS

    private fun setGaugeKey(context: Context, key: String, gauge: ObdGauge, enabled: Boolean) {
        val current = gaugeKeys(context, key).toMutableSet()
        if (enabled) current.add(gauge.key) else current.remove(gauge.key)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putStringSet(key, current).apply()
    }

    // --- Lights Out colors: numerals + trim, independent of the app palette -
    // Null = "follow palette" (today's behavior: derive from AriaColors.Amber /
    // AriaColors.NeonMagenta). A saved swatch locks that role's color so it
    // stops tracking the app-wide palette switch above.

    private fun swatch(context: Context, key: String): LightsOutSwatch? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(key, null)
            ?.let { saved -> runCatching { LightsOutSwatch.valueOf(saved) }.getOrNull() }

    private fun setSwatch(context: Context, key: String, value: LightsOutSwatch?) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(key, value?.name).apply()

    fun lightsOutNumeralsSwatch(context: Context) = swatch(context, "lo_numerals_swatch")
    fun setLightsOutNumeralsSwatch(context: Context, v: LightsOutSwatch?) =
        setSwatch(context, "lo_numerals_swatch", v)

    fun lightsOutTrimSwatch(context: Context) = swatch(context, "lo_trim_swatch")
    fun setLightsOutTrimSwatch(context: Context, v: LightsOutSwatch?) =
        setSwatch(context, "lo_trim_swatch", v)
}

/** A single instrument-strip / Lights Out gauge reading: gauge key + caption + formatted value ("––" if unavailable). */
internal data class GaugeReading(val key: String, val label: String, val value: String)

/** Live gauge values for the instrument strip + Lights Out footer. */
internal data class CruiseTelemetry(
    val speedMph: Int? = null,                        // GPS-derived, works without OBD
    val obdGauges: List<GaugeReading> = emptyList(),  // driver-selected, per-car (see ObdGauge)
)

@Composable
fun CruiseScreen(
    onOpenSettings: () -> Unit,
    // Opens the control panel straight to the Phones section (Bluetooth
    // manager). Defaults to the generic settings open so previews/other
    // callers still compile.
    onOpenPhoneLink: () -> Unit = onOpenSettings,
    onStartVoiceSetup: () -> Unit = {},
    // Long-press on a playlist tile: the pin picker (Connections > Spotify).
    onOpenSpotifySettings: () -> Unit = onOpenSettings,
) {
    val context = LocalContext.current
    // Dual-target reflow (2026-07-12): a head unit only ever reports EXPANDED
    // (unchanged, byte-for-byte today's layout); a phone in portrait reports
    // COMPACT and gets the restructure below. MEDIUM (phone landscape) rides
    // with EXPANDED for now - see ui/WindowSize.kt.
    val sizeClass = LocalSizeClass.current

    // Voice setup is now optional (2026-07-08 - see MainActivity) - this
    // prompt is the "tap a box" entry point until the driver either
    // completes it or dismisses it. Settings offers the same action
    // regardless, so dismissing here doesn't hide it forever.
    var showSetupPrompt by remember {
        mutableStateOf(!OnboardingState.voiceSetupDone(context) && !OnboardingState.voiceSetupDismissed(context))
    }

    val bgVersion by AppBackground.version.collectAsState()
    val wallpaper = remember(bgVersion) { AvatarStudio.loadBackground(context) }
    val avatars = remember(bgVersion) { AvatarStudio.loadStates(context, AvatarStudio.readAvatarId(context)) }

    // Live conversation phase → which face + ring + status to show.
    val phase by CompanionPhase.phase.collectAsState()
    val face = avatars?.let { it[faceKey(phase)] ?: it[AvatarStudio.STATE_IDLE] }

    // Live caption of what Zero is saying. Streams in token-by-token; auto-hide 6s
    // after the last update (re-keyed on each change, so it only clears once Zero stops).
    val caption by CompanionPhase.caption.collectAsState()
    val crisis by CompanionPhase.crisis.collectAsState()
    var shownCaption by remember { mutableStateOf("") }
    // Skip the value the flow retained from a previous turn, so re-entering Cruise
    // doesn't flash the last reply - only show captions that arrive live.
    var sawFirstCaption by remember { mutableStateOf(false) }
    LaunchedEffect(caption) {
        if (!sawFirstCaption) {
            sawFirstCaption = true
            return@LaunchedEffect
        }
        if (caption.isNotBlank()) {
            shownCaption = caption
            delay(6000)
            shownCaption = ""
        }
    }

    // Transient failure notice (failed tap, dropped connection) - flashed in the
    // avatar status slot so a failure is never silent. collectLatest restarts the
    // 4s window when a new notice lands (incl. the same string re-fired on a
    // frustrated double-tap; the SharedFlow doesn't conflate duplicates).
    var shownNotice by remember { mutableStateOf("") }
    LaunchedEffect(Unit) {
        CompanionPhase.notice.collectLatest { n ->
            if (n.isNotBlank()) {
                shownNotice = n
                delay(4000)
                shownNotice = ""
            }
        }
    }

    val nowPlaying by NowPlayingController.state.collectAsState()
    // Lenient "active" drives play/pause and the grid's EQ bars: Bluetooth AVRCP often never
    // reports STATE_PLAYING, so strict isPlaying would freeze everything (see
    // NowPlayingController.isActive).
    val playing = nowPlaying?.isActive == true
    val track = nowPlaying?.title?.takeIf { it.isNotBlank() }
    val artist = nowPlaying?.artist?.takeIf { it.isNotBlank() }
    // Which of the three reasons the NOW PLAYING card can be blank, so it can
    // say which one in words instead of an identical-looking blank card for "never granted", "nothing playing" and "connected but no
    // metadata" (CLAUDE.md sec 1: unreadable and empty are different
    // sentences). NowPlayingController.state collapses the last two to the
    // same null (see its kdoc), which is right for driving the card's
    // own idle look and wrong for telling them apart here - so, same as the
    // now-deleted DriveHome and MediaDiagnosticsScreen, this polls
    // MediaDiagnostics directly rather than trying to recover the distinction
    // from the StateFlow alone.
    var hasNotificationAccess by remember { mutableStateOf(NowPlayingController.hasAccess(context)) }
    var hasActiveSession by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        while (true) {
            hasNotificationAccess = NowPlayingController.hasAccess(context)
            hasActiveSession = if (hasNotificationAccess) MediaDiagnostics.hasAnyActiveSession(context) else false
            delay(1500)
        }
    }
    val mediaEmptyState = selectMediaEmptyState(
        hasNotificationAccess = hasNotificationAccess,
        hasActiveSession = hasActiveSession,
        hasMetadata = nowPlaying != null,
    )

    // Album art for the NOW PLAYING card, decoded at render size (sampled) so no large
    // bitmap rides a StateFlow. Re-runs only when the URI changes (track change).
    val albumArtUri = nowPlaying?.albumArtUri
    var albumArt by remember { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(albumArtUri) {
        albumArt = albumArtUri?.let { loadAlbumArt(context, it, sizePx = DECK_ART_PX) }
    }

    // While a mixtape plays, its generated cover is the card's art and its name the
    // subtitle's context. Sampled decode off the main thread, same rule as the album art.
    val activeMixtape by MixtapePlayer.activeMixtape.collectAsState()
    val mixtapeCover by produceState<Bitmap?>(null, activeMixtape?.coverImagePath) {
        val path = activeMixtape?.coverImagePath
        value = path?.let { withContext(Dispatchers.IO) { runCatching { decodeSampled(File(it), DECK_ART_PX) }.getOrNull() } }
    }
    val deckArt = mixtapeCover ?: albumArt
    // "artist ・ <this>": the tape's name, else - while Spotify owns the speakers - the
    // playlist/album Spotify reports playing from (App Remote player context), else (null) the
    // track's own album.
    val musicSource by MusicSource.current.collectAsState()
    val spotifyContext by SpotifyController.playingContext.collectAsState()
    val deckContextName = activeMixtape?.name
        ?: spotifyContext?.title?.takeIf { musicSource == Source.SPOTIFY && it.isNotBlank() }

    // --- Live telemetry -------------------------------------------------------
    // GPS at nav cadence (1s / 1m) while the Cruise screen is up, so the speed
    // readout is live; reverts to the battery-friendly cadence on exit.
    DisposableEffect(Unit) {
        LocationController.startNavMode(context)
        onDispose { LocationController.stopNavMode() }
    }
    val location by LocationController.state.collectAsState()
    val speedMph = location?.takeIf { it.hasSpeed() && System.currentTimeMillis() - it.time < 15_000 }
        ?.let { (it.speed * 2.23694f).roundToInt() }

    // OBD gauges: the driver-selected, per-car set (see ObdGauge + the Settings
    // gauge picker). Only slow-changing PIDs are offered, polled on a gentle 15s
    // cadence - RPM and other fast PIDs were removed because polling them every
    // few seconds starved the head unit's shared Bluetooth radio and made phone
    // A2DP music stutter (field call 2026-07-12). While disconnected the enabled
    // gauges still show with dashes, like real gauges with the key off.
    var obdGauges by remember { mutableStateOf<List<GaugeReading>>(emptyList()) }
    LaunchedEffect(Unit) {
        while (true) {
            val connected = ObdBluetoothManager.isConnected
            val supported = ObdBluetoothManager.supportedPids.value
            // Read the union of both surfaces' selections so the Cruise strip and
            // the Lights Out footer can each filter to their own (separate) picks.
            val wanted = CruiseSettings.enabledCruiseGaugeKeys(context) +
                CruiseSettings.enabledLightsOutGaugeKeys(context)
            obdGauges = ObdGauge.entries
                .filter { it.key in wanted && it.isSupported(supported) }
                .map { g ->
                    val value = if (connected) runCatching { g.read() }.getOrNull() else null
                    GaugeReading(g.key, g.label, value ?: "––")
                }
            delay(if (connected) 15_000L else 5_000L)
        }
    }
    val telemetry = CruiseTelemetry(speedMph, obdGauges)

    // Stored-code count. No longer a Cruise dock tile (the CODES/GARAGE/CARS
    // tabs and the logbook binder moved off the home screen entirely - see this
    // file's header kdoc), but Lights Out's HUD footer still reads this to show
    // "N CODES STORED" vs "ALL SYSTEMS NOMINAL", so it's still polled here.
    var dtcCount by remember { mutableStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            dtcCount = if (ObdBluetoothManager.isConnected) {
                runCatching { ObdBluetoothManager.getDtcCodes().size }.getOrDefault(0)
            } else 0
            delay(60_000)
        }
    }

    var showAppTray by remember { mutableStateOf(false) }
    var lightsOut by remember { mutableStateOf(false) }
    // Long-press on the REAR dock tile; the same target picker is also a
    // discoverable row in Settings > System, for anyone who never finds the
    // long-press (see ControlPanelScreen.Section.SYSTEM_REVERSE_CAMERA).
    var showReverseCameraPicker by remember { mutableStateOf(false) }

    // --- Ambient screen saver: fires after the configured idle stretch. Any
    // touch anywhere resets the timer (observed pre-children, nothing consumed);
    // a live conversation also counts as activity so it never interrupts one.
    var screenSaver by remember { mutableStateOf(false) }
    var lastInteraction by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(10_000)
            val idleMin = CruiseSettings.screenSaverMinutes(context)
            if (phase != Phase.IDLE) lastInteraction = System.currentTimeMillis()
            if (!screenSaver && idleMin > 0 && !lightsOut && !showAppTray &&
                System.currentTimeMillis() - lastInteraction >= idleMin * 60_000L
            ) {
                screenSaver = true
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(AriaColors.Background)
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        awaitPointerEvent(PointerEventPass.Initial)
                        lastInteraction = System.currentTimeMillis()
                    }
                }
            },
    ) {
        // --- Backdrop: the animated city-pop wallpaper (Ken Burns + sheen +
        // drifting specks; frame-clock so it runs at animator-scale 0). The
        // background layer is an approved exception to "driving surfaces spare". ---
        if (wallpaper != null) {
            AnimatedWallpaper(wallpaper, Modifier.fillMaxSize(), scrimAlpha = 0.55f)
        }

        val debugLine = nowPlaying?.takeIf { DebugSettings.subtitlesEnabled(context) }?.let {
            "MEDIA state=${it.playbackStateRaw} active=${it.isActive} art=${it.artSource}"
        }
        // Spotify playlists for the pinned grid: loaded on Cruise entry, throttled inside
        // SpotifyPlaylists to ~10 min.
        LaunchedEffect(Unit) { SpotifyPlaylists.refreshIfStale(context) }
        // Wired composite/RCA into the head unit's own video input - the vendor firmware owns
        // the picture, so this is a handoff to whichever activity was configured for it
        // (ui/ReverseCameraTarget.kt), never a camera this app opens itself. Not-configured /
        // not-found / denied each say in words what didn't happen (CLAUDE.md sec 7).
        val dismissSetupPrompt: () -> Unit = {
            OnboardingState.dismissVoiceSetupPrompt(context)
            showSetupPrompt = false
        }
        val openRearCamera: () -> Unit = {
            val target = ReverseCameraSettings.get(context)
            val result = resolveReverseCameraLaunch(target) { launchReverseCameraTarget(context, it) }
            reverseCameraLaunchMessage(result)?.let { CompanionPhase.showNotice(it) }
        }

        if (sizeClass == SizeClass.COMPACT) {
            // --- COMPACT (phone portrait): clock + avatar share the top row, then the split
            // deck STACKED (now playing card, then the 2 x 5 pinned grid), then the phone chip
            // and the dock.
            //
            // The column SCROLLS. It has to: the deck alone is taller than a phone screen,
            // and a reflow that "fits without scrolling" only fits the window it was measured
            // in - a shorter screen, a larger system font, or a split window would silently
            // clip the bottom controls with no way to reach them.
            val m = DeckMetrics.compact
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(top = 16.dp, bottom = 12.dp, start = 16.dp, end = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                // Top Row: Clock + Avatar.
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.Top
                ) {
                    Clock(context = context)
                    AvatarVibe(
                        avatar = face,
                        ring = ringColor(phase),
                        status = statusLabel(phase),
                        notice = shownNotice,
                        caption = shownCaption,
                        onTap = { talk(context) },
                        width = 100.dp,
                        height = 100.dp,
                    )
                }

                Spacer(Modifier.height(AriaDimens.s3))

                NowPlayingCard(
                    m = m,
                    info = nowPlaying,
                    art = deckArt,
                    contextName = deckContextName,
                    emptyState = mediaEmptyState,
                    modifier = Modifier.fillMaxWidth(),
                    fixedArt = 220.dp,
                    debugLine = debugLine,
                )

                Spacer(Modifier.height(AriaDimens.s3))

                PinnedPlaylistGrid(
                    m = m,
                    playing = playing,
                    onOpenPicker = onOpenSpotifySettings,
                    modifier = Modifier.fillMaxWidth(),
                    rowHeight = 52.dp,
                )

                Spacer(Modifier.height(AriaDimens.s3))

                // Bluetooth phone-link chip: which phone is carrying audio, tap
                // to open the Phones manager.
                PhoneLinkChip(
                    onClick = onOpenPhoneLink,
                    modifier = Modifier.fillMaxWidth().heightIn(min = AriaDimens.driveTouchMin),
                )

                Spacer(Modifier.height(AriaDimens.s2))

                // Fixed, non-scrolling dock: one idiom (DockTile) for the five
                // remaining controls, all reachable at a glance.
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(AriaDimens.s2),
                ) {
                    DockTile(
                        accent = AriaColors.Amber,
                        filled = true,
                        modifier = Modifier.weight(1f),
                        onClick = { lightsOut = true },
                    ) {
                        DockTileLabel("◐", "NIGHT", AriaColors.Background)
                    }
                    val muted by ProactivePreferences.muted.collectAsState()
                    DockTile(
                        accent = AriaColors.Amber,
                        filled = muted,
                        modifier = Modifier.weight(1f),
                        onClick = { ProactivePreferences.setMuted(context, !muted) },
                    ) {
                        DockTileLabel(
                            if (muted) "🔕" else "🔔",
                            if (muted) "MUTED" else "MUTE",
                            if (muted) AriaColors.Background else AriaColors.TextDim,
                        )
                    }
                    DockTile(
                        accent = AriaColors.TextDim,
                        modifier = Modifier.weight(1f),
                        onClick = onOpenSettings,
                    ) {
                        DockTileLabel("⚙", "SETUP", AriaColors.Background)
                    }
                    DockTile(
                        accent = AriaColors.TextDim,
                        modifier = Modifier.weight(1f),
                        onClick = { showAppTray = true },
                    ) {
                        DockTileLabel("⊞", "APPS", AriaColors.Background)
                    }
                    DockTile(
                        accent = AriaColors.Amber,
                        modifier = Modifier.weight(1f),
                        onClick = openRearCamera,
                        onLongClick = { showReverseCameraPicker = true },
                    ) {
                        DockTileLabel("⛛", "REAR", AriaColors.Background)
                    }
                }
            }
        } else {
            // --- Head unit (EXPANDED, and MEDIUM phone landscape): the split deck, prototype A
            // "Split deck" (Kevin, 2026-10-13). A quiet console column on the left (clock,
            // avatar, telemetry chips, phone chip, dock), and the music area on the right ~72%:
            // the NOW PLAYING card and the PINNED grid side by side at 1 : 1.18.
            //
            // Every size comes from u = 1% of the frame width, the unit the prototype is drawn
            // in, capped by height (58.6u = a 1024x600 frame) so a wider-than-16:10 window
            // keeps the composition instead of overflowing vertically.
            BoxWithConstraints(Modifier.fillMaxSize()) {
                val u = minOf(maxWidth / 100f, maxHeight / 58.6f)
                val density = LocalDensity.current
                val m = remember(u, density) { DeckMetrics.fromUnit(u, density) }
                Row(
                    Modifier
                        .fillMaxSize()
                        .padding(horizontal = u * 2.2f, vertical = u * 2.6f),
                ) {
                    CruiseConsole(
                        u = u,
                        telemetry = telemetry,
                        avatar = face,
                        phase = phase,
                        notice = shownNotice,
                        caption = shownCaption,
                        onLightsOut = { lightsOut = true },
                        onOpenSettings = onOpenSettings,
                        onOpenPhoneLink = onOpenPhoneLink,
                        onOpenAppTray = { showAppTray = true },
                        onRearCamera = openRearCamera,
                        onRearCameraPicker = { showReverseCameraPicker = true },
                        setupPrompt = if (showSetupPrompt) {
                            { SetupPromptChip(onStartVoiceSetup, dismissSetupPrompt, Modifier.fillMaxWidth()) }
                        } else null,
                        modifier = Modifier.width(u * 24f).fillMaxHeight(),
                    )
                    Spacer(Modifier.width(u * 1.8f))
                    Row(
                        Modifier.weight(1f).fillMaxHeight(),
                        horizontalArrangement = Arrangement.spacedBy(u * 1.6f),
                    ) {
                        NowPlayingCard(
                            m = m,
                            info = nowPlaying,
                            art = deckArt,
                            contextName = deckContextName,
                            emptyState = mediaEmptyState,
                            modifier = Modifier.weight(1f).fillMaxHeight(),
                            debugLine = debugLine,
                        )
                        PinnedPlaylistGrid(
                            m = m,
                            playing = playing,
                            onOpenPicker = onOpenSpotifySettings,
                            modifier = Modifier.weight(1.18f).fillMaxHeight(),
                        )
                    }
                }
            }
        }

        // --- Voice setup prompt: the "tap a box" entry point, shown until
        // completed or dismissed. Rendered AFTER (on top of) the fillMaxSize
        // deck+avatar Column above - that Column is wrapped in verticalScroll,
        // whose drag-gesture detector claims touches across its FULL screen
        // bounds for scroll arbitration, not just where its visible content
        // sits. With the prompt drawn earlier (lower z-order) it was a dead
        // tap target: touches at its screen position hit-tested against the
        // scrollable Column above it first and never reached the card below. ---
        // Quiet, opt-in chip (field-test call 2026-07-12: the old two-line
        // banner felt like the app was forcing onboarding). A small dismissible
        // pill tucked in the top-right corner - nothing is spoken or started
        // until the driver explicitly taps it; the ✕ dismisses it for good.
        // Phone portrait only: on the head unit the chip rides in the console column instead
        // (see CruiseConsole's setupPrompt), where its 56dp targets cannot cover the grid.
        if (showSetupPrompt && sizeClass == SizeClass.COMPACT) {
            SetupPromptChip(
                onStart = onStartVoiceSetup,
                onDismiss = dismissSetupPrompt,
                modifier = Modifier.align(Alignment.TopEnd).padding(top = 8.dp, end = 8.dp),
            )
        }

        // --- Lights Out: the phosphor night HUD (tap anywhere to exit) ---
        if (lightsOut) {
            val npLine = track?.let { t -> artist?.let { a -> "$t ・ $a" } ?: t }
            LightsOut(
                context = context,
                telemetry = telemetry,
                dtcCount = dtcCount,
                nowPlayingLine = if (playing) npLine else null,
                phase = phase,
                caption = shownCaption,
                notice = shownNotice,
                onTalk = { talk(context) },
                onExit = { lightsOut = false },
            )
        }

        // The OBD sheet (LIVE/HISTORY/CODES/MANAGE) and the Garage sheet are no
        // longer opened from Cruise (2026-09-27 slim pass) - ObdSheet.kt and
        // GarageSheet.kt are untouched, they just lost their Cruise entry point.
        // (2026-10-14: ObdSheet and CarsScreen are reachable again from Settings > Your car;
        // the garage relay lives in Settings > Your car > Garage & gate. GarageSheet.kt and the
        // DtcSheet wrapper were deleted that day.)

        // --- App tray overlay (frame-clock slide-up, same reason) ---
        if (showAppTray) {
            val enter = rememberRamp(220)
            Box(
                Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        translationY = size.height * (1f - enter)
                        alpha = enter
                    }
            ) {
                AppTrayOverlay(
                    onDismiss = { showAppTray = false },
                    onEscapeToOS = { LauncherSettings.escapeToOS(context) },
                )
            }
        }

        // --- Reverse camera target picker (long-press REAR tile, same reason) ---
        if (showReverseCameraPicker) {
            val enter = rememberRamp(220)
            Box(
                Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        translationY = size.height * (1f - enter)
                        alpha = enter
                    }
            ) {
                ReverseCameraPickerScreen(onBack = { showReverseCameraPicker = false })
            }
        }

        // --- Ambient screen saver (idle timeout; tap anywhere to come back) ---
        if (screenSaver) {
            val npLine = track?.let { t -> artist?.let { a -> "♪ $t ・ $a" } ?: "♪ $t" }
            ScreenSaver(
                context = context,
                wallpaper = wallpaper,
                avatar = face,
                trackLine = if (playing) npLine else null,
                caption = shownCaption,
                onExit = {
                    screenSaver = false
                    lastInteraction = System.currentTimeMillis()
                },
            )
        }

        // Both overlays below are ALSO rendered by LightsOut, which is composed
        // above (still inside this Box, not instead of it). Without this guard both
        // copies stack, and Cruise's - drawn later, so on top - wins: the bright
        // amber Cruise palette lands over the night HUD and blows the 4% backlight
        // budget the dim palette exists to protect (§12). Render them here only
        // when Lights Out is NOT showing.
        if (!lightsOut) {
            // Trivia scoreboard overlay removed from Cruise's home screen
            // (2026-09-27 slim pass) - the game itself (TriviaController) is
            // untouched, it just no longer has a Cruise-side scoreboard.

            // --- Glance card: voice tool data, large and glanceable (.scratch/glance-cards) ---
            GlanceCardOverlay(
                accent = AriaColors.Amber,
                dimAccent = AriaColors.AmberDim,
                panel = AriaColors.Panel,
                scrim = AriaColors.Background.copy(alpha = 0.55f),
                textColor = AriaColors.TextPrimary,
            )
        }

        // --- Crisis card (CLAUDE.md sec 9.1) ---
        // LAST in the Box, so it draws over every other overlay including the app
        // tray and the screen saver. Nothing outranks this: if the detector fired,
        // there is no state of the app in which something else should be on top.
        if (crisis) {
            CrisisCard(onDismiss = { CompanionPhase.clearCrisis() })
        }
    }
}

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
private fun Clock(context: Context, modifier: Modifier = Modifier, timeSize: TextUnit = 34.sp) {
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
 * The head unit's left console column (2026-10-13 split deck): clock + weekday and the
 * companion portrait (tap to talk) on top; telemetry chips, the phone-link chip and a 2 x 3
 * dock pinned to the bottom. The dock takes over everything the old bottom dash rows carried
 * (Lights Out, day/night, mute, REAR CAM, app tray, Setup), since the music deck now runs to
 * the bottom edge on the right.
 *
 * Fits by construction, no scroll: the clock and the bottom group (chips, phone chip, dock)
 * are fixed, and the portrait takes what is left, up to 13.5u - so a short frame (800x480)
 * gets a smaller portrait instead of a dock pushed off the bottom.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CruiseConsole(
    u: Dp,
    telemetry: CruiseTelemetry,
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
    setupPrompt: (@Composable () -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val gap = u * 1.4f
    Column(modifier) {
        // Clock digits scale with the frame; 3.3u = today's 34sp at 1024dp wide.
        val timeSize = with(density) { (u * 3.3f).toSp() }
        Clock(context = context, timeSize = timeSize)
        BoxWithConstraints(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(vertical = gap),
            contentAlignment = Alignment.TopStart,
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
        Column(verticalArrangement = Arrangement.spacedBy(u * 0.8f)) {
            setupPrompt?.invoke()
            TelemetryChips(u, telemetry)
            PhoneLinkChip(
                onClick = onOpenPhoneLink,
                modifier = Modifier.fillMaxWidth().heightIn(min = AriaDimens.driveTouchMin),
            )
            val tileHeight = (u * 5.5f).coerceAtLeast(AriaDimens.driveTouchMin)
            val muted by ProactivePreferences.muted.collectAsState()
            val night = AriaPalette.isNight
            Row(horizontalArrangement = Arrangement.spacedBy(u * 0.8f)) {
                // Lights Out keeps a full-strength amber ring: a driver flagged the old dim
                // glyph as hard to find by feel at night, exactly when it matters most.
                ConsoleTile(u, "◐", "LIGHTS OUT", tileHeight, ring = AriaColors.Amber, modifier = Modifier.weight(1f), onClick = onLightsOut)
                // Day/night wallpaper + palette switch; shows what a tap switches TO.
                ConsoleTile(
                    u, if (night) "☀" else "☾", if (night) "DAY" else "NIGHT", tileHeight,
                    modifier = Modifier.weight(1f),
                ) { AriaPalette.setNight(context, !night) }
                // Silences proactive chatter/alerts, never driver-initiated speech.
                ConsoleTile(
                    u, if (muted) "🔕" else "🔔", if (muted) "MUTED" else "MUTE", tileHeight,
                    active = muted, modifier = Modifier.weight(1f),
                ) { ProactivePreferences.setMuted(context, !muted) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(u * 0.8f)) {
                ConsoleTile(
                    u, "⛛", "REAR CAM", tileHeight, modifier = Modifier.weight(1f),
                    onLongClick = onRearCameraPicker, onClick = onRearCamera,
                )
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
private fun SetupPromptChip(onStart: () -> Unit, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
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

/**
 * GPS speed plus the driver's Cruise gauge picks (Settings gauge picker), as small cards. At
 * most three, one row: the column has no room for a second, and Lights Out's footer still
 * shows the full selection.
 */
@Composable
private fun TelemetryChips(u: Dp, telemetry: CruiseTelemetry) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val cruiseKeys = remember { CruiseSettings.enabledCruiseGaugeKeys(context) }
    val chips = listOf(GaugeReading("speed", "MPH", telemetry.speedMph?.toString() ?: "––")) +
        telemetry.obdGauges.filter { it.key in cruiseKeys }.take(2)
    // Labels never below 11sp (glanceable at arm's length); values scale with the frame.
    val labelSize = with(density) { (u * 1.0f).toSp() }.let { if (it.value < 11f) 11.sp else it }
    val valueSize = with(density) { (u * 2.1f).toSp() }
    val shape = RoundedCornerShape(u)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(u * 0.8f)) {
        chips.forEach { g ->
            Column(
                Modifier
                    .weight(1f)
                    .clip(shape)
                    .background(deckCardColor())
                    .border(1.dp, deckLineColor(), shape)
                    .padding(horizontal = u * 0.6f, vertical = u * 0.6f),
            ) {
                Text(
                    g.label,
                    style = AriaType.neonLabel.copy(fontSize = labelSize, letterSpacing = 0.5.sp),
                    color = AriaColors.TextDim,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Clip,
                )
                Text(
                    g.value,
                    style = AriaType.readout.copy(fontSize = valueSize, letterSpacing = 0.sp),
                    color = AriaColors.TextPrimary,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Clip,
                )
            }
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

// CodesTab, CarsTab, GarageTab, TelemetryStrip/Gauge/GaugeDivider, and
// LogbookObject (the dash-object widgets for the removed CODES/CARS/GARAGE
// tabs, the OBD gauge strip, and the logbook binder tile) were deleted here
// 2026-09-27 - none of them have a Cruise call site any more. The screens
// they used to open (ObdSheet.kt, GarageSheet.kt, CarsScreen.kt,
// LogbookScreen.kt) are untouched; Cruise just stopped being their door.
// (Corrected 2026-10-14: ObdSheet and CarsScreen now have Settings > Your car rows;
// GarageSheet.kt was deleted, its job is Settings > Garage & gate.)

/**
 * The companion, in a tall portrait window (waist-up city-pop illustration, not
 * a button-sized face). The phase ring becomes a window border; status sits in a
 * small header tab; the live caption overlays the bottom of the window so the
 * whole thing stays one object. Tap anywhere on it to talk.
 */
@Composable
private fun AvatarVibe(
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

private fun statusLabel(phase: Phase): String? = when (phase) {
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
private fun DockTile(
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
private fun DockTileLabel(icon: String, label: String, color: Color) {
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
