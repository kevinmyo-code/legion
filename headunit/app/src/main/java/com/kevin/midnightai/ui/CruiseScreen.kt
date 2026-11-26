package com.kevin.midnightai.ui

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
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
import com.kevin.midnightai.service.CompanionPhase
import com.kevin.midnightai.service.DebugSettings
import com.kevin.midnightai.service.LauncherSettings
import com.kevin.midnightai.service.Phase
import com.kevin.midnightai.service.ProactivePreferences
import com.kevin.midnightai.ui.theme.AriaColors
import com.kevin.midnightai.ui.theme.AriaDimens
import com.kevin.midnightai.ui.theme.LightsOutSwatch
import com.kevin.midnightai.vehicle.DtcDescriptions
import com.kevin.midnightai.vehicle.ObdBluetoothManager
import com.kevin.midnightai.vehicle.ObdGauge
import com.kevin.midnightai.ui.cruise.AvatarVibe
import com.kevin.midnightai.ui.cruise.Clock
import com.kevin.midnightai.ui.cruise.CruiseConsole
import com.kevin.midnightai.ui.cruise.CruiseLayoutLogic
import com.kevin.midnightai.ui.cruise.CruiseMiddlePanel
import com.kevin.midnightai.ui.cruise.MiddlePanelText
import com.kevin.midnightai.ui.cruise.MusicHeroCard
import com.kevin.midnightai.ui.cruise.DockTile
import com.kevin.midnightai.ui.cruise.DockTileLabel
import com.kevin.midnightai.ui.cruise.SetupPromptChip
import com.kevin.midnightai.ui.cruise.faceKey
import com.kevin.midnightai.ui.cruise.ringColor
import com.kevin.midnightai.ui.cruise.statusLabel
import com.kevin.midnightai.ui.cruise.talk
import java.io.File
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
 * **HU-1 layout, 2026-11-19** (Kevin approved the prototype): on the head unit the console rail
 * on the left (clock, companion portrait, phone chip, the six dock tiles; ui/cruise/Console.kt),
 * the CAR / NAV panel in the middle (big GPS speed, the Cruise gauges, the stored-code line, or
 * the NAV face T5 fills; ui/cruise/MiddlePanelView.kt) and ONE music card on the right that
 * flips between NOW PLAYING and the pinned PLAYLISTS (ui/cruise/MusicHero.kt). Phone portrait
 * keeps the 2026-10-13 split deck: the NOW PLAYING card stacked over the pinned grid.
 *
 * **Slimmed 2026-09-27** (the head-unit-only pass): the CODES/GARAGE/CARS tabs, the logbook
 * binder, and the trivia scoreboard are not shown here - none of those surfaces were deleted,
 * Cruise just stopped being their entry point.
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
    // The "TAP FOR WHAT'S NEW" chip: System > What's new.
    onOpenWhatsNew: () -> Unit = onOpenSettings,
) {
    val context = LocalContext.current
    // Dual-target reflow (2026-07-12): a head unit only ever reports EXPANDED
    // (unchanged, byte-for-byte today's layout); a phone in portrait reports
    // COMPACT and gets the restructure below. MEDIUM (phone landscape) rides
    // with EXPANDED for now - see ui/WindowSize.kt.
    val sizeClass = LocalSizeClass.current
    // "UPDATE READY · TAP TO INSTALL" (alfred-ota ticket 08); null while the car is moving.
    val updatePrompt = rememberUpdatePrompt()

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
    val telemetry = CruiseTelemetry(obdGauges)

    // Stored codes, polled once a minute (the same single Mode 03 read as before HU-1, no new OBD
    // traffic): Lights Out's HUD footer shows the count, the CAR panel the first code by name.
    var dtcCodes by remember { mutableStateOf<List<String>>(emptyList()) }
    LaunchedEffect(Unit) {
        while (true) {
            dtcCodes = if (ObdBluetoothManager.isConnected) {
                runCatching { ObdBluetoothManager.getDtcCodes() }.getOrDefault(emptyList())
            } else emptyList()
            delay(60_000)
        }
    }
    val dtcCount = dtcCodes.size
    // Code names from the bundled seed + this install's learned list: disk reads, off the main thread.
    val dtcNames by produceState(emptyMap<String, String>(), dtcCodes) {
        if (dtcCodes.isEmpty()) return@produceState
        value = withContext(Dispatchers.IO) {
            runCatching {
                (DtcDescriptions.loadSeed(context) + DtcDescriptions.loadLearned(context)).mapValues { it.value.first }
            }.getOrDefault(emptyMap())
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
            // --- Head unit (EXPANDED, and MEDIUM phone landscape): HU-1 layout. Console rail |
            // one music card | CAR / NAV panel, the two cards at one width (Kevin, 2026-11-23: "i
            // want the music controls to be center and the map/car data to be on right. and their
            // width should be equal"). This supersedes the 2026-11-19 prototype's "car in the
            // middle song on the right"; its "left keep as is with the avatar and the buttons"
            // still stands, which is why the rail keeps its own width.
            //
            // Every size comes from u = 1% of the frame width, the unit the prototype is drawn
            // in, capped by height (58.6u = a 1024x600 frame); CruiseLayoutLogic holds the numbers.
            // The status bar stays visible (MainActivity hides only the navigation bar), so the
            // layout starts below it: the CAR / NAV and NOW / LISTS keys sit in the top row, and a
            // touch under the bar would pull the shade instead (review L1, 2026-11-20).
            BoxWithConstraints(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.statusBars)) {
                val cols = CruiseLayoutLogic.columns(maxWidth.value, maxHeight.value)
                val u = CruiseLayoutLogic.unit(maxWidth.value, maxHeight.value).dp
                val density = LocalDensity.current
                val m = remember(u, density) { DeckMetrics.fromUnit(u, density) }
                val cruiseKeys = remember { CruiseSettings.enabledCruiseGaugeKeys(context) }
                Row(
                    Modifier
                        .fillMaxSize()
                        .padding(cols.pad.dp),
                    horizontalArrangement = Arrangement.spacedBy(cols.gap.dp),
                ) {
                    // Left to right in CruiseLayoutLogic.ORDER, the one place the order is stated
                    // (Kevin, 2026-11-23: music in the centre, the map/car panel on the right).
                    CruiseLayoutLogic.ORDER.forEach { pane ->
                        val paneModifier = Modifier.width(cols.width(pane).dp).fillMaxHeight()
                        when (pane) {
                            CruiseLayoutLogic.Pane.RAIL -> CruiseConsole(
                                u = u,
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
                                modifier = paneModifier,
                            )
                            CruiseLayoutLogic.Pane.MUSIC -> MusicHeroCard(
                                u = u,
                                m = m,
                                info = nowPlaying,
                                art = deckArt,
                                contextName = deckContextName,
                                emptyState = mediaEmptyState,
                                playing = playing,
                                onOpenPicker = onOpenSpotifySettings,
                                modifier = paneModifier,
                                debugLine = debugLine,
                            )
                            CruiseLayoutLogic.Pane.CAR -> CruiseMiddlePanel(
                                u = u,
                                m = m,
                                location = location,
                                gauges = MiddlePanelText.carGauges(obdGauges, cruiseKeys),
                                storedCodes = MiddlePanelText.storedCodes(dtcCodes, dtcNames),
                                modifier = paneModifier,
                            ) {
                                // The setup and update chips used to sit in the console rail; in the
                                // HU-1 rail they would push the portrait (the talk target) off a
                                // 480 dp frame.
                                if (showSetupPrompt) SetupPromptChip(onStartVoiceSetup, dismissSetupPrompt, Modifier.fillMaxWidth())
                                updatePrompt?.let { UpdateChip(it, Modifier.fillMaxWidth(), onWhatsNew = onOpenWhatsNew) }
                            }
                        }
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
        // Phone portrait only: on the head unit the chip rides at the top of the middle panel
        // (CruiseMiddlePanel's prompts), where its 56dp targets cannot cover the music card.
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
                location = location,
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

// CodesTab, CarsTab, GarageTab, TelemetryStrip/Gauge/GaugeDivider, and
// LogbookObject (the dash-object widgets for the removed CODES/CARS/GARAGE
// tabs, the OBD gauge strip, and the logbook binder tile) were deleted here
// 2026-09-27 - none of them have a Cruise call site any more. The screens
// they used to open (ObdSheet.kt, GarageSheet.kt, CarsScreen.kt,
// LogbookScreen.kt) are untouched; Cruise just stopped being their door.
// (Corrected 2026-10-14: ObdSheet and CarsScreen now have Settings > Your car rows;
// GarageSheet.kt was deleted, its job is Settings > Garage & gate.)
