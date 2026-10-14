package com.kevin.midnightai.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.key
import com.kevin.midnightai.backend.engine.EngineAuth
import com.kevin.midnightai.backend.engine.EngineConfig
import com.kevin.midnightai.backend.engine.MeResult
import com.kevin.midnightai.bluetooth.PhoneLinkCapability
import com.kevin.midnightai.bluetooth.PhoneLinkManager
import com.kevin.midnightai.bluetooth.PhoneLinkState
import com.kevin.midnightai.media.PlaylistsState
import com.kevin.midnightai.media.SpotifyController
import com.kevin.midnightai.media.SpotifyPlaylists
import com.kevin.midnightai.media.SpotifyWebApi
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.kevin.midnightai.ai.AriaBrain
import com.kevin.midnightai.ai.CompanionProfile
import com.kevin.midnightai.ai.CompanionReset
import com.kevin.midnightai.ai.KeyHealth
import com.kevin.midnightai.ai.DriverProfile
import com.kevin.midnightai.location.LocationController
import com.kevin.midnightai.media.NowPlayingController
import com.kevin.midnightai.service.AriaForegroundService
import com.kevin.midnightai.service.DebugSettings
import com.kevin.midnightai.service.LauncherSettings
import com.kevin.midnightai.service.MusicPreferences
import com.kevin.midnightai.ui.theme.AriaColors
import com.kevin.midnightai.ui.theme.AriaPalette
import com.kevin.midnightai.ui.theme.LightsOutSwatch
import com.kevin.midnightai.vehicle.ActiveVehicle
import com.kevin.midnightai.vehicle.ObdBluetoothManager
import com.kevin.midnightai.vehicle.ObdGauge
import com.kevin.midnightai.vehicle.VehicleController
import com.kevin.midnightai.vehicle.VehicleSpecController
import com.kevin.midnightai.vehicle.VinDecoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import com.kevin.midnightai.BuildConfig
import com.kevin.midnightai.ai.AvatarStudio
import com.kevin.midnightai.ai.EntitlementManager
import com.kevin.midnightai.ai.RuntimeMode
import com.kevin.midnightai.ui.theme.AriaDimens
import com.kevin.midnightai.ui.theme.AriaTheme
import com.kevin.midnightai.ui.theme.AriaType
import androidx.compose.ui.tooling.preview.Preview

/**
 * The app's control panel: a settings hub for the AI. The companion itself
 * lives in the background service and is driven by voice over the floating
 * button, so this screen isn't where you talk to it - it's where you configure
 * it: the AI's name and personality, your own profile,
 * saved places, and the one-time permission setup.
 *
 * Lightweight in-Activity navigation (a [Section] state + `when`) rather than a
 * nav library - the codebase favors plain composables, and there are only a
 * handful of screens. Everything edited here flows into the system instruction
 * built by [com.kevin.midnightai.ai.AriaBrain].
 */

// --- Type roles for this screen -------------------------------------------
// Settings is a NIGHT/instrument surface (Lights Out's family), never the
// aged-paper logbook, so every text style here derives from AriaType's mono
// roles (readout/neonLabel), not the serif logbook roles. Named once and
// reused rather than inlining `.copy(...)` at all 94 call sites - this was
// the only screen in the app still on MaterialTheme.typography (94 refs vs.
// 1 AriaType ref) before this pass; see CLAUDE.md sec 12's coherence law.
/** Screen/leaf title ("Settings", "Companion", "Wallpaper"...). */
internal val ScreenTitle = AriaType.readout.copy(fontSize = 22.sp)
/** Row title: a settings-row / [ResetRow] heading, a subsection heading, a switch's label. */
internal val RowTitle = AriaType.readout.copy(fontSize = 15.sp)
/** Row subtitle, help text, captions - always paired with AriaColors.TextDim. */
internal val RowSubtitle = AriaType.readout.copy(fontSize = 12.sp)
/** Small-caps affordance: "‹ BACK", chip text, chevrons, ON/grant labels. */
internal val Label = AriaType.neonLabel.copy(fontSize = 11.sp)

private enum class Section {
    CAR_DETAILS, ABOUT_YOU, LOGBOOK, PLACES,
    APPEARANCE_WALLPAPER, APPEARANCE_COLORS, APPEARANCE_GAUGES, APPEARANCE_SCREEN_DECK,
    OBD_DEVICES, PLAN, VOICE_STYLE,
    // Re-homed 2026-10-14: CarsScreen and ObdSheet lost their only (Cruise) entry points in the
    // 2026-09-27 slim pass, which left the car roster, OBD history and the drive corrector
    // unreachable by hand. They are full-screen leaves like LOGBOOK.
    CARS, OBD_DATA,
    CONN_SPOTIFY, CONN_SYNC, CONN_GARAGE, CONN_ENGINE,
    SYSTEM_PERMISSIONS, SYSTEM_PREFERENCES, SYSTEM_CLOCK,
    // Both reachable from Cruise's dock too (long-press REAR for the picker;
    // there is no in-Cruise path to diagnostics) - this is the discoverable
    // path for someone who never finds the long-press.
    SYSTEM_MEDIA_DIAGNOSTICS, SYSTEM_BLUETOOTH_DIAGNOSTICS, SYSTEM_REVERSE_CAMERA,
    CONN_PHONES,
    SYSTEM_RESET,
}

@Composable
fun ControlPanel(
    onRequestRuntimePermissions: () -> Unit,
    startInSetup: Boolean = false,
    // Lands straight on Phones & audio > Phones (Cruise's phone-link chip tap
    // target, 2026-09-27). Same shape as startInSetup; the three shortcuts are
    // mutually exclusive in practice since only one fires per entry.
    startInPhones: Boolean = false,
    // Lands on Music > Spotify (long-press on a Cruise playlist tile).
    startInSpotify: Boolean = false,
    onReset: () -> Unit = {},
    onExit: () -> Unit = {},
    onStartVoiceSetup: () -> Unit = {},
) {
    val context = LocalContext.current
    // Two-pane settings (2026-10-11): a category rail and the selected category's rows.
    // `section` is the full-screen leaf opened from a row; back from a leaf lands on
    // the same category because `selected` is not touched by opening one. The
    // startIn* entry points preserve every old deep link (Cruise's garage-setup
    // shortcut, phone-link chip, playlist long-press).
    var selected by remember {
        mutableStateOf(
            when {
                startInSetup -> SettingsCategory.SYSTEM
                startInPhones -> SettingsCategory.PHONES
                startInSpotify -> SettingsCategory.MUSIC
                else -> defaultSettingsCategory(context)
            }
        )
    }
    var section by remember {
        mutableStateOf(
            when {
                startInSetup -> Section.SYSTEM_PERMISSIONS
                startInPhones -> Section.CONN_PHONES
                startInSpotify -> Section.CONN_SPOTIFY
                else -> null
            }
        )
    }
    // COMPACT only: whether the single pane is showing a category (true) or the rail (false).
    var paneOpen by remember { mutableStateOf(startInSetup || startInPhones || startInSpotify) }
    val compact = LocalSizeClass.current == SizeClass.COMPACT ||
        LocalConfiguration.current.screenWidthDp < SETTINGS_TWO_PANE_MIN_DP

    // Back from a leaf returns to the two-pane; on a narrow screen a category pane
    // returns to the rail first; from the rail / two-pane, back to Cruise.
    BackHandler {
        when {
            section != null -> section = null
            compact && paneOpen -> paneOpen = false
            else -> onExit()
        }
    }

    val planMode by EntitlementManager.mode.collectAsState()
    val planSubtitle = when (planMode) {
        RuntimeMode.BYO_KEY -> "Your own key - full access, no limits from us"
        RuntimeMode.AI_PAUSED -> "Add your own Gemini key"
    }

    // Snapshot into a val: `when` on a delegated `var` captured by the onClick
    // lambdas below doesn't reliably smart-cast, so branch on a stable local.
    val activeSection = section

    // Hoisted above the leaf branch so the live values survive opening a leaf: returning from one
    // shows the last-known rail instantly (then refreshes) rather than flashing "Signed in" back
    // from "Signed in as <email>".
    val live = rememberSettingsStatuses(context, showing = activeSection == null)
    val statuses = live.statuses

    if (activeSection != null) {
        when (activeSection) {
            Section.CAR_DETAILS -> CarDetailsSection(context) { section = null }
            Section.ABOUT_YOU -> AboutYouSection(context) { section = null }
            Section.LOGBOOK -> LogbookScreen(context) { section = null }
            Section.PLACES -> SavedPlacesScreen(context) { section = null }
            Section.APPEARANCE_WALLPAPER -> AppearanceWallpaperSection(context) { section = null }
            Section.APPEARANCE_COLORS -> AppearanceColorsSection(context) { section = null }
            Section.APPEARANCE_GAUGES -> AppearanceGaugesSection(context) { section = null }
            Section.APPEARANCE_SCREEN_DECK -> AppearanceScreenDeckSection(context) { section = null }
            Section.OBD_DEVICES -> ObdDeviceScreen { section = null }
            Section.CARS -> CarsScreen(onBack = { section = null })
            Section.OBD_DATA -> ObdSheet(context = context, onDismiss = { section = null })
            Section.PLAN -> PlanSection { section = null }
            Section.VOICE_STYLE -> VoiceStylePicker(
                initial = CompanionProfile.voiceStyleSelections(context),
                onComplete = { selections ->
                    CompanionProfile.saveVoiceStyle(context, selections)
                    AriaBrain.get(context).invalidateBase()
                    section = null
                },
                onBack = { section = null },
            )
            Section.CONN_SPOTIFY -> ConnectionsSpotifySection { section = null }
            Section.CONN_SYNC -> ConnectionsSyncSection { section = null }
            Section.CONN_GARAGE -> ConnectionsGarageSection { section = null }
            Section.CONN_ENGINE -> ConnectionsEngineSection(context) { section = null }
            Section.SYSTEM_PERMISSIONS -> SystemPermissionsSection(context, onRequestRuntimePermissions) { section = null }
            Section.SYSTEM_PREFERENCES -> SystemPreferencesSection(context) { section = null }
            Section.SYSTEM_CLOCK -> ClockSection(context) { section = null }
            Section.SYSTEM_MEDIA_DIAGNOSTICS -> MediaDiagnosticsScreen(onBack = { section = null })
            Section.SYSTEM_BLUETOOTH_DIAGNOSTICS -> BluetoothDiagnosticsScreen(onBack = { section = null })
            Section.CONN_PHONES -> BluetoothManagerScreen(onBack = { section = null })
            Section.SYSTEM_REVERSE_CAMERA -> ReverseCameraPickerScreen(onBack = { section = null })
            Section.SYSTEM_RESET -> ResetSection(context, onReset) { section = null }
        }
    } else {
        // key(): the toggle rows hold remembered state, and the same slot is reused
        // for every category, so without a key a switch's state would leak across.
        val rows = key(selected) {
            settingsRows(
                category = selected,
                context = context,
                planSubtitle = planSubtitle,
                open = { section = it },
                onStartVoiceSetup = onStartVoiceSetup,
                spotifyStatus = statuses[SettingsCategory.MUSIC],
                spotifyFacts = live.spotify,
                byoKey = planMode == RuntimeMode.BYO_KEY,
            )
        }
        SettingsTwoPane(
            selected = selected,
            onSelect = { selected = it; paneOpen = true },
            statuses = statuses,
            rows = rows,
            compact = compact,
            paneOpen = paneOpen,
            onClosePane = { paneOpen = false },
            onExit = onExit,
            footer = if (selected == SettingsCategory.CAR) {
                {
                    Text(
                        "Which car this device is in, and your roster, live on the CARS screen - " +
                            "open it from the Cruise dock.",
                        style = RowSubtitle,
                        color = AriaColors.TextDim,
                    )
                }
            } else null,
        )
    }
}

/**
 * Where the two-pane opens when nothing deep-linked it: the first category that needs
 * attention, else Phones & audio. Reads only synchronous local state (see
 * [settingsStatusesNow]); anything that needs a network round trip cannot decide this,
 * because the screen must not open on a spinner.
 */
private fun defaultSettingsCategory(context: Context): SettingsCategory =
    settingsStatusesNow(context).entries
        .firstOrNull { it.value.dot == SettingsDot.WARN || it.value.dot == SettingsDot.ERROR }
        ?.key ?: SettingsCategory.PHONES

/**
 * The rows of one category. Every old menu entry lives here exactly once; the
 * old Group -> MenuItem lists are gone. A row is a destination (a full-screen leaf,
 * unchanged), a one-shot action, or - only where a plain boolean preference already
 * existed - an inline switch.
 */
@Composable
private fun settingsRows(
    category: SettingsCategory,
    context: Context,
    planSubtitle: String,
    open: (Section) -> Unit,
    onStartVoiceSetup: () -> Unit,
    spotifyStatus: CategoryStatus?,
    spotifyFacts: SpotifyFacts?,
    byoKey: Boolean,
): List<SettingsRowSpec> {
    fun openRow(title: String, subtitle: String, section: Section, chip: SettingsChip? = null) =
        SettingsRowSpec(title, subtitle, RowAction.Button("OPEN", ButtonWeight.QUIET) { open(section) }, chip)

    return when (category) {
        SettingsCategory.PHONES -> listOf(
            openRow("Phones", "Switch which phone plays, pair, forget, reset Bluetooth", Section.CONN_PHONES),
            openRow("Bluetooth check", "Which BT stack owns the phones - read before the two-phone switcher", Section.SYSTEM_BLUETOOTH_DIAGNOSTICS),
        )
        SettingsCategory.MUSIC -> {
            // Signed out or missing scopes is fixed by consent, so the hot button goes straight to it
            // (the same call the leaf's RE-AUTHORIZE makes). A playlist-fetch failure is not a consent
            // problem, so that case opens the leaf, where the error text and REFRESH live.
            // spotifyFacts is null until the off-main Keystore read lands; the button stays quiet until
            // then, so a HOT fix button is never shown on a guess.
            val spotifyAction = if (spotifyFacts == null) SpotifyRowAction.QUIET_OPEN else spotifyRowAction(
                status = spotifyStatus,
                hasClientId = spotifyFacts.hasClientId,
                authorized = spotifyFacts.authorized,
                needsReconnect = spotifyFacts.needsReconnect,
            )
            val reauth = spotifyAction == SpotifyRowAction.HOT_REAUTHORIZE
            val broken = spotifyAction != SpotifyRowAction.QUIET_OPEN
            var headUnitApp by remember { mutableStateOf(MusicPreferences.headUnitAppEnabled(context)) }
            listOf(
                SettingsRowSpec(
                    title = "Spotify",
                    subtitle = (spotifyStatus?.line?.plus(". ").orEmpty()) + "Optional BYO Spotify developer app",
                    action = RowAction.Button(
                        if (reauth) "RE-AUTHORIZE" else "OPEN",
                        if (broken) ButtonWeight.HOT else ButtonWeight.QUIET,
                    ) {
                        if (reauth) {
                            if (!SpotifyWebApi.beginAuthorization(context)) {
                                Toast.makeText(context, "Can't open Spotify sign-in on this unit", Toast.LENGTH_SHORT).show()
                            }
                        } else {
                            open(Section.CONN_SPOTIFY)
                        }
                    },
                    chip = settingsChip(spotifyStatus),
                ),
                SettingsRowSpec(
                    title = "Open music apps by voice",
                    subtitle = "Off by default. When on, asking to open or play something launches a music app installed on this head unit instead of just controlling your phone",
                    action = RowAction.Toggle(headUnitApp) {
                        headUnitApp = it
                        MusicPreferences.setHeadUnitAppEnabled(context, it)
                    },
                ),
                openRow("Media check", "Raw MediaSession read - settle what the phone is actually sending", Section.SYSTEM_MEDIA_DIAGNOSTICS),
            )
        }
        SettingsCategory.ENGINE -> listOf(
            openRow("Engine", "Sign in to your LEGION engine for lists, calendar, notes", Section.CONN_ENGINE),
        )
        SettingsCategory.COMPANION -> buildList {
            add(SettingsRowSpec(
                "Companion setup", "Guided setup, tap or talk - name, voice, personality, car",
                RowAction.Button("START", ButtonWeight.PRIMARY) { onStartVoiceSetup() },
            ))
            add(openRow("Voice style", "How ${companionLabel(context)} speaks - pace, tone, energy", Section.VOICE_STYLE))
            add(openRow("About you", "Your name & what ${companionLabel(context)} should know", Section.ABOUT_YOU))
            add(openRow("AI power & plan", planSubtitle, Section.PLAN))
            add(SettingsRowSpec(
                "Test voice", "Hear it speak a line",
                RowAction.Button("PLAY", ButtonWeight.PRIMARY) { testVoice(context) },
            ))
        }
        SettingsCategory.CAR -> listOf(
            openRow("Car details", "Photo, make/model/year, odometer, VIN", Section.CAR_DETAILS),
            openRow("Cars", "Switch, add or archive cars", Section.CARS),
            openRow("OBD adapter", "Pair & select OBD dongles", Section.OBD_DEVICES),
            openRow("OBD data", "Live readouts, drive history & fault codes", Section.OBD_DATA),
            openRow("Logbook", "Service & build history & mods", Section.LOGBOOK),
            openRow("Saved places", "Home, work, and other tagged spots", Section.PLACES),
            openRow("Garage & gate", "Optional Shelly relay control", Section.CONN_GARAGE),
            openRow("Reverse camera target", "Pick the vendor app the REAR dock tile hands off to", Section.SYSTEM_REVERSE_CAMERA),
        )
        SettingsCategory.LOOK -> listOf(
            // AriaPalette.isNight is Compose state, so the switch tracks the Cruise day/night button too.
            SettingsRowSpec(
                "Night mode", "The dim night version of the palette and wallpaper (same as the Cruise day/night button)",
                RowAction.Toggle(AriaPalette.isNight) { AriaPalette.setNight(context, it) },
            ),
            openRow("Colors & palette", "Showa colorways and Lights Out numeral/trim colors", Section.APPEARANCE_COLORS),
            openRow("Wallpaper", "Your car wallpaper", Section.APPEARANCE_WALLPAPER),
            openRow("Gauges", "Which OBD readouts show on Cruise and Lights Out", Section.APPEARANCE_GAUGES),
            openRow("Screen & deck", "Screen saver timing and the cassette colorway", Section.APPEARANCE_SCREEN_DECK),
        )
        SettingsCategory.SYSTEM -> listOf(
            openRow("Clock & time zone", clockRowSubtitle(), Section.SYSTEM_CLOCK),
            openRow("Permissions", "Microphone, media access, overlay", Section.SYSTEM_PERMISSIONS),
            openRow("Preferences", "Music app, home screen, subtitles, recalls", Section.SYSTEM_PREFERENCES),
            openRow("Cross-device sync", "Optional Google Drive sync between dash and phone", Section.CONN_SYNC),
            openRow("Reset", "Start over or wipe data", Section.SYSTEM_RESET),
        )
    }
}

/**
 * Everything the rail can say, assembled from already-read inputs so the same function serves
 * the first frame (no network, no Bluetooth proxy yet) and the live recompute. A null input
 * means "not known yet" and yields a static descriptor with NO dot: a dot is only ever drawn
 * from something that was actually read, never defaulted to OK.
 */
private fun buildSettingsStatuses(
    context: Context,
    playlists: PlaylistsState,
    spotify: SpotifyFacts?,
    phones: PhoneLinkState?,
    engineConfigured: Boolean,
    engineSignedIn: Boolean?,
    engineMe: MeResult?,
    carLabel: String?,
): Map<SettingsCategory, CategoryStatus> {
    return mapOf(
        SettingsCategory.PHONES to if (phones == null || phones.capability == PhoneLinkCapability.NO_BLUETOOTH) {
            CategoryStatus("Bluetooth phones")
        } else {
            phonesCategoryStatus(
                activePhoneName = phones.phones.firstOrNull { it.address == phones.activeAddress }?.name,
                pairedCount = phones.phones.size,
            )
        },
        // spotify is null until the off-main Keystore read lands: neutral, no dot.
        SettingsCategory.MUSIC to spotifyStatusFor(spotify, playlists.library.size, playlists.lastError),
        // signedIn is null until the off-main read lands (it decrypts the token via the Keystore, which
        // must not run on the main thread or on every Bluetooth/playlist emission): neutral, no dot.
        SettingsCategory.ENGINE to if (engineConfigured && engineSignedIn == null) {
            CategoryStatus("LEGION engine")
        } else {
            engineCategoryStatus(
                configured = engineConfigured,
                signedIn = engineSignedIn == true,
                verifiedEmail = (engineMe as? MeResult.Ok)?.email,
                refusal = engineMe is MeResult.Refused,
            )
        },
        SettingsCategory.COMPANION to CategoryStatus(
            // A blank name is the unnamed default companion, who calls himself Uncle Moose
            // (CompanionIdentity), so the rail shows that rather than a placeholder.
            CompanionProfile.name(context).ifBlank { CompanionProfile.DEFAULT_NAME },
        ),
        SettingsCategory.CAR to CategoryStatus(carLabel ?: "Your car"),
        SettingsCategory.LOOK to CategoryStatus("Colors, wallpaper, gauges"),
        SettingsCategory.SYSTEM to permissionsCategoryStatus(missingPermissionCount(context)),
    )
}

/**
 * How many of the three grants the Permissions leaf lists are missing. Same three checks as
 * [SystemPermissionsSection] (kept as a copy so that leaf stays untouched): mic AND location
 * count as one, media access, overlay.
 */
private fun missingPermissionCount(context: Context): Int {
    val micAndLocation = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
        PackageManager.PERMISSION_GRANTED &&
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
        PackageManager.PERMISSION_GRANTED
    return listOf(
        micAndLocation,
        NowPlayingController.hasAccess(context),
        Settings.canDrawOverlays(context),
    ).count { !it }
}

/** First-frame statuses and the default-category pick: synchronous local reads only. */
private fun settingsStatusesNow(context: Context): Map<SettingsCategory, CategoryStatus> =
    buildSettingsStatuses(
        context, SpotifyPlaylists.state.value, spotify = null, phones = null,
        // isConfigured() is a plain prefs read; signedIn is deliberately unknown here (Keystore decrypt).
        engineConfigured = EngineConfig(context).isConfigured(),
        engineSignedIn = null, engineMe = null, carLabel = null,
    )

/** Rail statuses plus the raw Spotify facts the Music rows need (null until read). */
private class SettingsLive(val statuses: Map<SettingsCategory, CategoryStatus>, val spotify: SpotifyFacts?)

/**
 * Live rail statuses. Recomputed on ON_RESUME (a grant, a Spotify consent, or a Bluetooth change
 * all happen in OTHER apps and come back through resume) and whenever a leaf closes. The engine
 * line starts as "Signed in" and upgrades to the email once the engine confirms the token; that
 * one is a network read, so it lands a moment after the screen does and never blocks it.
 */
@Composable
private fun rememberSettingsStatuses(context: Context, showing: Boolean): SettingsLive {
    var refresh by remember { mutableIntStateOf(0) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        // An observer added while already RESUMED is replayed ON_RESUME on attach; that is not a
        // return from another app, so skip it or the first fetch is issued, cancelled and reissued.
        var skipReplay = lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                if (skipReplay) skipReplay = false else refresh++
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // The same init the Phones leaf does; idempotent, and registers the BT receiver once.
    LaunchedEffect(Unit) { runCatching { PhoneLinkManager.init(context) } }
    LaunchedEffect(refresh, showing) { if (showing) runCatching { PhoneLinkManager.refresh() } }
    val phones by PhoneLinkManager.state.collectAsState()
    val playlists by SpotifyPlaylists.state.collectAsState()

    var carLabel by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(refresh, showing) {
        if (!showing) return@LaunchedEffect
        carLabel = VehicleController.displayLabel(VehicleController.currentVehicle(context))
            .ifBlank { "Your car" }
    }

    // One EngineAuth for the screen: it owns an OkHttpClient (pool + dispatcher), so building one per
    // refresh would leak a client each time.
    val engineConfig = remember { EngineConfig(context) }
    val engineAuth = remember { EngineAuth(engineConfig) }
    var engineSignedIn by remember { mutableStateOf<Boolean?>(null) }
    var engineMe by remember { mutableStateOf<MeResult?>(null) }
    LaunchedEffect(refresh, showing) {
        if (!showing) return@LaunchedEffect
        // isSignedIn() decrypts the token through the Keystore: off the main thread, once per refresh,
        // and together with the network read so the two always describe the same token.
        val (signedIn, me) = withContext(Dispatchers.IO) {
            val signed = engineConfig.isConfigured() && engineConfig.isSignedIn()
            signed to (if (signed) engineAuth.me() else null)
        }
        engineSignedIn = signedIn
        engineMe = me
    }

    // Same rule as the engine token: hasSpotifyClientId / isAuthorized decrypt through the Keystore, so
    // they are read once per refresh on IO and held here, never in a remember block or a row builder.
    var spotify by remember { mutableStateOf<SpotifyFacts?>(null) }
    // Keyed on App Remote's link too: playback connecting or failing (onResume's silent attempt,
    // CONNECT, the redirect) lands after the screen does and must recompute the row.
    val appRemoteLink by SpotifyController.link.collectAsState()
    LaunchedEffect(refresh, showing, appRemoteLink) {
        if (!showing) return@LaunchedEffect
        spotify = withContext(Dispatchers.IO) {
            SpotifyFacts(
                hasClientId = CompanionProfile.hasSpotifyClientId(context),
                authorized = SpotifyWebApi.isAuthorized(context),
                needsReconnect = SpotifyWebApi.needsReconnect(context),
                grantedScope = SpotifyWebApi.grantedScope(context),
                appRemote = SpotifyController.effectiveLink(),
                appRemoteNotInstalled = SpotifyController.lastFailureIsNotInstalled(),
            ).also {
                com.kevin.midnightai.MidnightEvents.spotifyClientIdSet(it.hasClientId)
                com.kevin.midnightai.MidnightEvents.spotifyWebAuthorized(it.authorized)
            }
        }
    }

    val base = remember(refresh, showing, playlists, spotify, phones, engineSignedIn, engineMe, carLabel) {
        buildSettingsStatuses(
            context, playlists, spotify, phones,
            engineConfigured = engineConfig.isConfigured(),
            engineSignedIn = engineSignedIn, engineMe = engineMe, carLabel = carLabel,
        )
    }
    // Outside the remember: AriaPalette's fields are Compose state, and a read inside remember's
    // lambda is not tracked, so a palette change (or the Cruise day/night button) would not refresh it.
    val palette = AriaPalette.current.label.lowercase().split(' ')
        .joinToString(" ") { w -> w.replaceFirstChar { it.uppercase() } }
    val look = CategoryStatus(if (AriaPalette.isNight) "$palette, night" else palette)
    return SettingsLive(base + (SettingsCategory.LOOK to look), spotify)
}

/** The companion's name (what the driver named their car), or a neutral fallback. */
internal fun companionLabel(context: Context): String =
    CompanionProfile.name(context).ifBlank { "your co-pilot" }

private fun testVoice(context: Context) {
    val intent = Intent(context, AriaForegroundService::class.java)
        .setAction(AriaForegroundService.ACTION_TEST_SPEAK)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        context.startForegroundService(intent)
    } else {
        context.startService(intent)
    }
    Toast.makeText(context, "${companionLabel(context)} should say hello…", Toast.LENGTH_SHORT).show()
}


/** Shared frame for a sub-screen: a back affordance, a title, and a scrolling body. */
/**
 * Appearance: wallpaper, colors, gauges, and screen/deck are four separate
 * leaves reached from the Appearance group sub-menu. Wallpaper just shows the
 * bundled seed wallpaper - in-app generation (photo/description restyle,
 * rolling-road backdrop, map puck) was removed 2026-09-27; art is generated
 * once outside the app and shipped as a seeded asset (see
 * [com.kevin.midnightai.ai.SeedArt]).
 */
@Composable
private fun AppearanceWallpaperSection(context: Context, onBack: () -> Unit) {
    val bgVersion by AppBackground.version.collectAsState()
    // loadBackground touches ActiveVehicle ->
    // ObdBluetoothManager, whose class init throws in the preview JVM.
    val inPreview = LocalInspectionMode.current
    val wallpaper = if (inPreview) null else remember(bgVersion) { AvatarStudio.loadBackground(context) }

    SectionScaffold("Wallpaper", onBack) {
        Text(
            "Your Cruise wallpaper.",
            style = RowSubtitle,
            color = AriaColors.TextDim,
        )
        Spacer(Modifier.height(14.dp))

        if (wallpaper != null) {
            Image(
                bitmap = wallpaper.asImageBitmap(),
                contentDescription = "Current wallpaper",
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio((wallpaper.width.toFloat() / wallpaper.height.toFloat()).coerceIn(0.6f, 2.4f))
                    .clip(RoundedCornerShape(8.dp)),
            )
        }
    }
}

/** Curated Showa night colorway picker plus the independent Lights Out numeral/trim colors. */
@Composable
private fun AppearanceColorsSection(context: Context, onBack: () -> Unit) {
    SectionScaffold("Colors & palette", onBack) {
        // --- Color palette: curated Showa night colorways -------------------
        Text("Color palette", style = RowTitle, color = AriaColors.TextPrimary)
        Spacer(Modifier.height(AriaDimens.s1))
        Text(
            "Four art-directed Showa night colorways. Everything in the app follows the active " +
                "palette — the logbook paper stays constant.",
            style = RowSubtitle,
            color = AriaColors.TextDim,
        )
        Spacer(Modifier.height(10.dp))
        var activePalette by remember { mutableStateOf(AriaPalette.current) }
        Column(verticalArrangement = Arrangement.spacedBy(AriaDimens.s2)) {
            AriaPalette.ALL.forEach { palette ->
                val selected = activePalette.key == palette.key
                val borderColor = if (selected) AriaColors.Amber else AriaColors.PanelBorder
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(6.dp))
                        .border(1.dp, borderColor, RoundedCornerShape(6.dp))
                        .clickable {
                            activePalette = palette
                            AriaPalette.set(context, palette)
                        }
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(AriaDimens.s3),
                ) {
                    // 4-dot swatch: background, magenta, amber, cyan
                    listOf(palette.background, palette.magenta, palette.amber, palette.cyan)
                        .forEach { swatchColor ->
                            Box(
                                modifier = Modifier
                                    .size(14.dp)
                                    .clip(CircleShape)
                                    .background(swatchColor)
                                    .border(0.5.dp, AriaColors.PanelBorder, CircleShape)
                            )
                        }
                    Spacer(Modifier.width(2.dp))
                    Text(
                        palette.label,
                        style = Label,
                        color = if (selected) AriaColors.Amber else AriaColors.TextDim,
                    )
                }
            }
        }

        // --- Lights Out colors: numerals + trim, independent of the palette ---
        Spacer(Modifier.height(24.dp))
        Text("Lights Out colors", style = RowTitle, color = AriaColors.TextPrimary)
        Spacer(Modifier.height(AriaDimens.s1))
        Text(
            "Lock the night HUD's numerals and trim to a neon of their own, independent of the " +
                "palette above. Follow palette (the mixed dot) keeps today's behavior.",
            style = RowSubtitle,
            color = AriaColors.TextDim,
        )
        Spacer(Modifier.height(10.dp))
        var loNumerals by remember { mutableStateOf(CruiseSettings.lightsOutNumeralsSwatch(context)) }
        Text(
            "Numerals — clock, gauges, captions",
            style = Label,
            color = AriaColors.TextDim,
        )
        Spacer(Modifier.height(6.dp))
        LightsOutSwatchRow(
            selected = loNumerals,
            onSelect = { loNumerals = it; CruiseSettings.setLightsOutNumeralsSwatch(context, it) },
        )
        Spacer(Modifier.height(14.dp))
        var loTrim by remember { mutableStateOf(CruiseSettings.lightsOutTrimSwatch(context)) }
        Text(
            "Trim — date, status, hint text",
            style = Label,
            color = AriaColors.TextDim,
        )
        Spacer(Modifier.height(6.dp))
        LightsOutSwatchRow(
            selected = loTrim,
            onSelect = { loTrim = it; CruiseSettings.setLightsOutTrimSwatch(context, it) },
        )
    }
}

/** Which OBD readouts show on the Cruise instrument strip and the Lights Out night HUD footer. */
@Composable
private fun AppearanceGaugesSection(context: Context, onBack: () -> Unit) {
    SectionScaffold("Gauges", onBack) {
        // --- Cruise gauges: which OBD readouts show on the Cruise instrument strip ---
        Text("Cruise gauges", style = RowTitle, color = AriaColors.TextPrimary)
        Spacer(Modifier.height(AriaDimens.s1))
        GaugePickerSection(context, GaugeSurface.CRUISE)
        // (GaugePickerSection/GaugeSurface now live in ui/GaugePicker.kt, same package.)

        // --- Lights Out gauges: the night HUD footer (separate from Cruise, above) ---
        Spacer(Modifier.height(24.dp))
        Text("Lights Out gauges", style = RowTitle, color = AriaColors.TextPrimary)
        Spacer(Modifier.height(AriaDimens.s1))
        Text(
            "Show GPS speed and pick which engine gauges appear in the night HUD footer. This is a " +
                "separate selection from the Cruise strip above. Color follows the settings above.",
            style = RowSubtitle,
            color = AriaColors.TextDim,
        )
        Spacer(Modifier.height(10.dp))
        var loSpeed by remember { mutableStateOf(CruiseSettings.lightsOutShowSpeed(context)) }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(
                checked = loSpeed,
                onCheckedChange = { loSpeed = it; CruiseSettings.setLightsOutShowSpeed(context, it) },
            )
            Spacer(Modifier.width(AriaDimens.s2))
            Text("Speed", style = RowTitle, color = AriaColors.TextPrimary)
        }
        Spacer(Modifier.height(AriaDimens.s2))
        GaugePickerSection(context, GaugeSurface.LIGHTS_OUT)
    }
}

/** Screen saver idle timeout and the deck's cassette colorway. */
@Composable
private fun AppearanceScreenDeckSection(context: Context, onBack: () -> Unit) {
    SectionScaffold("Screen & deck", onBack) {
        // --- Screen saver: idle minutes before the ambient wallpaper takes over ---
        Text("Screen saver", style = RowTitle, color = AriaColors.TextPrimary)
        Spacer(Modifier.height(AriaDimens.s1))
        Text(
            "After this long with no touching or talking, the Cruise screen dissolves into your " +
                "wallpaper with ${companionLabel(context)} in the corner. Tap to come back.",
            style = RowSubtitle,
            color = AriaColors.TextDim,
        )
        Spacer(Modifier.height(10.dp))
        var saverMin by remember { mutableStateOf(CruiseSettings.screenSaverMinutes(context)) }
        ChipRow(
            options = listOf("OFF", "1 MIN", "2 MIN", "5 MIN", "10 MIN"),
            selected = listOf(0, 1, 2, 5, 10).indexOf(saverMin),
        ) { i ->
            saverMin = listOf(0, 1, 2, 5, 10)[i]
            CruiseSettings.setScreenSaverMinutes(context, saverMin)
        }
    }
}

/**
 * One role's swatch picker for Lights Out colors: a leading "follow palette"
 * dot (mixed amber/magenta gradient, meaning "derive from the app palette"),
 * then the curated [LightsOutSwatch] set. `selected == null` means follow
 * palette.
 */
@Composable
private fun LightsOutSwatchRow(selected: LightsOutSwatch?, onSelect: (LightsOutSwatch?) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        SwatchDot(
            brush = Brush.linearGradient(listOf(AriaColors.Amber, AriaColors.NeonMagenta)),
            selected = selected == null,
            onClick = { onSelect(null) },
        )
        LightsOutSwatch.entries.forEach { s ->
            SwatchDot(brush = SolidColor(s.color), selected = selected == s, onClick = { onSelect(s) })
        }
    }
}

@Composable
private fun SwatchDot(brush: Brush, selected: Boolean, onClick: () -> Unit) {
    val borderColor = if (selected) AriaColors.Amber else AriaColors.PanelBorder
    Box(
        modifier = Modifier
            .size(28.dp)
            .clip(CircleShape)
            .background(brush)
            .border(if (selected) 2.dp else 1.dp, borderColor, CircleShape)
            .clickable(onClick = onClick),
    )
}

/**
 * A row of outlined selector chips; the selected one lights amber. On a
 * phone (COMPACT) some option sets (e.g. every discovered option) can run
 * wider than the screen - horizontally scrollable there only, so EXPANDED
 * (head unit) keeps today's plain Row byte-for-byte.
 */
@Composable
private fun ChipRow(options: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    val rowModifier = if (LocalSizeClass.current == SizeClass.COMPACT) {
        Modifier.horizontalScroll(rememberScrollState())
    } else {
        Modifier
    }
    Row(modifier = rowModifier, horizontalArrangement = Arrangement.spacedBy(AriaDimens.s2)) {
        options.forEachIndexed { i, label ->
            val c = if (i == selected) AriaColors.Amber else AriaColors.TextDim
            Text(
                label,
                style = Label,
                color = c,
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .border(1.dp, c, RoundedCornerShape(6.dp))
                    .clickable { onSelect(i) }
                    .padding(horizontal = AriaDimens.s3, vertical = AriaDimens.s2),
            )
        }
    }
}

/** Decodes a picked image Uri into a Bitmap, or null on failure. */
internal fun loadBitmapFromUri(context: Context, uri: Uri): Bitmap? = try {
    context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }
} catch (e: Exception) {
    Log.w("ControlPanel", "Couldn't read picked image: $uri", e)
    null
}

@Composable
internal fun SectionScaffold(
    title: String,
    onBack: () -> Unit,
    body: @Composable () -> Unit,
) {
    val sizeClass = LocalSizeClass.current
    val padding = if (sizeClass == SizeClass.EXPANDED) AriaDimens.s4 else AriaDimens.s3

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(AriaColors.Background)
            .padding(padding),
    ) {
        Text(
            text = "‹ BACK",
            style = Label,
            color = AriaColors.Amber,
            modifier = Modifier
                .clip(RoundedCornerShape(4.dp))
                .clickable { onBack() }
                .padding(vertical = 6.dp, horizontal = AriaDimens.s1),
        )
        Spacer(Modifier.height(10.dp))
        Text(title, style = ScreenTitle, color = AriaColors.TextPrimary)
        Spacer(Modifier.height(16.dp))
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
        ) { body() }
    }
}

@Composable
private fun CarDetailsSection(context: Context, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    var loaded by remember { mutableStateOf(false) }
    var make by remember { mutableStateOf("") }
    var model by remember { mutableStateOf("") }
    var trim by remember { mutableStateOf("") }
    var year by remember { mutableStateOf("") }
    var odometer by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        val v = VehicleController.currentVehicle(context)
        make = v.make
        model = v.model
        trim = v.trim
        year = if (v.year > 0) v.year.toString() else ""
        odometer = if (v.odometerBaseline > 0) v.odometerBaseline.toString() else ""
        loaded = true
    }

    SectionScaffold("Car Details", onBack) {
        Text("Your car", style = RowTitle, color = AriaColors.TextPrimary)
        Spacer(Modifier.height(2.dp))
        Text(
            "Make, model & year sharpen diagnostics and maintenance lookups; the odometer drives " +
                "service due-dates (GPS estimates mileage on top of it).",
            style = RowSubtitle,
            color = AriaColors.TextDim,
        )
        Spacer(Modifier.height(10.dp))

        // --- Car photo (2026-07-12 dual-target work): the driver's real
        // photo, if any, replaces the generated portrait everywhere that
        // already prefers AvatarStudio.carImage() - the Logbook cover
        // Polaroid and (when the rolling-road backdrop is on) the Cruise
        // sprite. Gallery pick works on every build; camera capture only
        // shows where a camera actually exists (phones - see hasCamera). ---
        val bgVersion by AppBackground.version.collectAsState()
        val carPhoto = remember(bgVersion) { AvatarStudio.carImage(context) }
        if (carPhoto != null) {
            Image(
                bitmap = carPhoto.asImageBitmap(),
                contentDescription = "Your car",
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(160.dp)
                    .clip(RoundedCornerShape(8.dp)),
            )
            Spacer(Modifier.height(AriaDimens.s2))
        }
        val carPhotoPicker = rememberLauncherForActivityResult(
            ActivityResultContracts.PickVisualMedia(),
        ) { uri ->
            uri?.let { u ->
                scope.launch {
                    loadBitmapFromUri(context, u)?.let { bmp ->
                        withContext(Dispatchers.IO) { AvatarStudio.saveCarPhoto(context, bmp) }
                        AppBackground.notifyChanged()
                    }
                }
            }
        }
        val carPhotoCamera = rememberCameraCaptureLauncher(context) { bmp ->
            scope.launch {
                withContext(Dispatchers.IO) { AvatarStudio.saveCarPhoto(context, bmp) }
                AppBackground.notifyChanged()
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(AriaDimens.s3)) {
            Button(onClick = {
                carPhotoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            }) { Text(if (carPhoto == null) "ADD A CAR PHOTO" else "CHANGE PHOTO") }
            if (hasCamera(context)) {
                Button(onClick = carPhotoCamera) { Text("TAKE PHOTO") }
            }
        }
        Spacer(Modifier.height(16.dp))

        OutlinedTextField(
            value = make, onValueChange = { make = it },
            label = { Text("Make") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(AriaDimens.s2))
        OutlinedTextField(
            value = model, onValueChange = { model = it },
            label = { Text("Model") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(AriaDimens.s2))
        OutlinedTextField(
            value = trim, onValueChange = { trim = it },
            label = { Text("Trim (e.g. 330i ZHP, Type R)") }, singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(AriaDimens.s2))
        Row(horizontalArrangement = Arrangement.spacedBy(AriaDimens.s3)) {
            OutlinedTextField(
                value = year, onValueChange = { year = it.filter { c -> c.isDigit() }.take(4) },
                label = { Text("Year") }, singleLine = true, modifier = Modifier.weight(1f),
            )
            OutlinedTextField(
                value = odometer, onValueChange = { odometer = it.filter { c -> c.isDigit() }.take(7) },
                label = { Text("Odometer (mi)") }, singleLine = true, modifier = Modifier.weight(1f),
            )
        }

        // OBD VIN auto-fill: optional, only when an adapter is connected. Typing
        // stays the default; this fills the fields from the car's VIN for review
        // (the driver still presses SAVE), it never overwrites silently.
        if (ObdBluetoothManager.isConnected) {
            Spacer(Modifier.height(10.dp))
            var filling by remember { mutableStateOf(false) }
            Text(
                text = if (filling) "Reading VIN…" else "🔌 Fill from car (VIN)",
                style = Label,
                color = if (filling) AriaColors.TextDim else AriaColors.Amber,
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .border(1.dp, AriaColors.Amber, RoundedCornerShape(6.dp))
                    .clickable(enabled = !filling) {
                        filling = true
                        scope.launch {
                            val decoded = VinDecoder.fromObd()
                            if (decoded != null) {
                                make = decoded.make
                                model = decoded.model
                                if (decoded.trim.isNotBlank()) trim = decoded.trim
                                if (decoded.year > 0) year = decoded.year.toString()
                                // Also fill the build-details encyclopedia (Logbook → Specs).
                                VehicleSpecController.refreshFromVin(context, decoded.vin)
                                filling = false
                                Toast.makeText(context, "Filled from VIN ${decoded.vin} — review and SAVE. Specs added to the logbook.", Toast.LENGTH_LONG).show()
                            } else {
                                filling = false
                                Toast.makeText(context, "Couldn't read a VIN from this car. Enter the details manually.", Toast.LENGTH_LONG).show()
                            }
                        }
                    }
                    .padding(horizontal = 14.dp, vertical = AriaDimens.s2),
            )
        }

        Spacer(Modifier.height(16.dp))
        Button(
            onClick = {
                scope.launch {
                    VehicleController.saveVehicleFacts(
                        context, make, model, trim, year.toIntOrNull() ?: 0, odometer.toIntOrNull() ?: 0,
                    )
                    Toast.makeText(context, "Saved.", Toast.LENGTH_SHORT).show()
                }
            },
            enabled = loaded,
        ) { Text("SAVE") }
    }
}

@Composable
private fun AboutYouSection(context: Context, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf(DriverProfile.name(context)) }
    var about by remember { mutableStateOf(DriverProfile.about(context)) }

    SectionScaffold("About You", onBack) {
        Text(
            "Tell ${companionLabel(context)} about yourself. This is added to every conversation, so it'll " +
                "address you by name and keep your context in mind.",
            style = RowSubtitle,
            color = AriaColors.TextDim,
        )
        Spacer(Modifier.height(14.dp))
        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            label = { Text("Your name") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(AriaDimens.s3))
        OutlinedTextField(
            value = about,
            onValueChange = { about = it },
            label = { Text("What should ${companionLabel(context)} know about you?") },
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 140.dp),
        )
        Spacer(Modifier.height(16.dp))
        Button(onClick = {
            scope.launch {
                DriverProfile.save(context, name, about)
                Toast.makeText(context, "Saved.", Toast.LENGTH_SHORT).show()
            }
        }) { Text("SAVE") }
    }
}

// --- System: permissions & preferences -----------------------------------
// Moved out of MainActivity's old SetupScreen so it lives as a control-panel
// section; the grant-action plumbing still belongs to MainActivity (it owns the
// permission launchers and starts the service), passed in via onRequestRuntime.
// Split into two leaves (2026-07-24): permissions (its own live-state refresh
// on ON_RESUME) and everything else (preferences).

@Composable
private fun SystemPermissionsSection(context: Context, onRequestRuntimePermissions: () -> Unit, onBack: () -> Unit) {
    var refresh by remember { mutableIntStateOf(0) }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                refresh++
                // Retry the data-stream sources in case access was just granted.
                NowPlayingController.init(context)
                LocationController.init(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val micGranted = remember(refresh) {
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
    }
    val mediaGranted = remember(refresh) { NowPlayingController.hasAccess(context) }
    val overlayGranted = remember(refresh) { Settings.canDrawOverlays(context) }

    SectionScaffold("Permissions", onBack) {
        GrantRow(
            label = "Microphone & location",
            granted = micGranted,
            actionLabel = "Grant",
            onAction = onRequestRuntimePermissions,
        )
        GrantRow(
            label = "Media access (now-playing & music control)",
            granted = mediaGranted,
            actionLabel = "Enable",
            onAction = { context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) },
        )
        GrantRow(
            label = "Display over other apps (companion badge)",
            granted = overlayGranted,
            actionLabel = "Allow",
            onAction = {
                context.startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:${context.packageName}"),
                    ),
                )
            },
        )
        Spacer(Modifier.height(16.dp))
        Text(
            text = "Music plays from your phone over Bluetooth - play it there and your co-pilot " +
                "controls play, pause, and skipping hands-free. Now-playing shows on the Cruise screen.",
            style = RowSubtitle,
            color = AriaColors.TextDim,
        )
        Spacer(Modifier.height(AriaDimens.s2))
        Text(
            text = "\"Display over other apps\" lets Midnight AI show a small floating companion " +
                "badge (tap to talk, music transport) while music has the screen.",
            style = RowSubtitle,
            color = AriaColors.TextDim,
        )
    }
}

@Composable
private fun SystemPreferencesSection(context: Context, onBack: () -> Unit) {
    // isDefaultLauncher below used to share Permissions' ON_RESUME refresh so
    // its label updates live after the "Use as home screen" system-settings
    // tap-through returns here. That refresh now lives with Permissions only
    // (it also drives NowPlayingController/LocationController re-init, which
    // has nothing to do with this leaf) - so this leaf keeps its own minimal
    // ON_RESUME bump for the same live-update behavior.
    var refresh by remember { mutableIntStateOf(0) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) refresh++
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    SectionScaffold("Preferences", onBack) {
        var headUnitMusicApp by remember { mutableStateOf(MusicPreferences.headUnitAppEnabled(context)) }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(
                checked = headUnitMusicApp,
                onCheckedChange = {
                    headUnitMusicApp = it
                    MusicPreferences.setHeadUnitAppEnabled(context, it)
                },
            )
            Spacer(Modifier.width(AriaDimens.s2))
            Column(Modifier.weight(1f)) {
                Text("Music app on this head unit", style = RowTitle, color = AriaColors.TextPrimary)
                Text(
                    "Off by default. When on, asking to open or play something launches a music app " +
                        "installed here on the head unit instead of just controlling your phone's playback.",
                    style = RowSubtitle,
                    color = AriaColors.TextDim,
                )
            }
        }

        Spacer(Modifier.height(AriaDimens.s4))

        // Launcher mode - not a toggle. An app can't make itself the system HOME;
        // Android only lets the user pick in system settings. So this is a
        // tap-through that opens that screen, and the label reflects live state
        // after the user returns. The app ships NOT set as home by default.
        var isDefaultLauncher by remember(refresh) { mutableStateOf(LauncherSettings.isDefaultLauncher(context)) }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .clickable { LauncherSettings.openLauncherSettings(context) }
                .padding(vertical = 6.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Text("Use as home screen", style = RowTitle, color = AriaColors.TextPrimary)
                Text(
                    if (isDefaultLauncher)
                        "This app is your home screen - press Home anytime to return here. Tap to change it in system settings."
                    else
                        "Tap to set this app as your home screen in system settings.",
                    style = RowSubtitle,
                    color = AriaColors.TextDim,
                )
            }
            Text(
                if (isDefaultLauncher) "HOME" else "SET",
                style = Label,
                color = AriaColors.Amber,
            )
        }

        // GPS beacon link (2026-07-25). This head unit browns out its own WiFi/BT
        // the moment its GPS antenna is connected, so the phone already providing
        // the hotspot supplies the fix instead. Three-way rather than a toggle
        // because the two ends of the link are different jobs, and OFF has to stay
        // the default - a device that never opens this screen should not be sending
        // datagrams on whatever network it happens to be joined to.
        Spacer(Modifier.height(AriaDimens.s4))
        var deviceRole by remember(refresh) {
            mutableStateOf(com.kevin.midnightai.service.DeviceRole.current(context))
        }
        val beaconLinkUp by com.kevin.midnightai.location.BeaconClient.linkUp.collectAsState()
        val beaconServing by com.kevin.midnightai.location.BeaconServer.serving.collectAsState()
        val beaconPeer by com.kevin.midnightai.location.BeaconClient.peerLabel.collectAsState()
        val beaconLocal by com.kevin.midnightai.location.BeaconServer.localAddress.collectAsState()
        Column {
            // Labelled with the car, because the answer genuinely differs per car on
            // the same device: a spare phone is the beacon in the car that has a
            // bolted-in head unit, and the app host in the car that does not.
            var activeCarLabel by remember(refresh) { mutableStateOf("this car") }
            LaunchedEffect(refresh) {
                val id = com.kevin.midnightai.vehicle.ActiveVehicle.current(context)
                val vehicle = withContext(Dispatchers.IO) {
                    com.kevin.midnightai.data.local.CarDatabase.getDatabase(context).vehicleDao().getByMac(id)
                }
                activeCarLabel = vehicle
                    ?.let { com.kevin.midnightai.vehicle.VehicleController.displayLabel(it) }
                    ?.takeIf { it.isNotBlank() }
                    ?: "this car"
            }
            Text(
                "GPS source for $activeCarLabel",
                style = RowTitle,
                color = AriaColors.TextPrimary,
            )
            Text(
                when (deviceRole) {
                    com.kevin.midnightai.service.DeviceRole.Role.OFF ->
                        "This device uses its own GPS, or has none. Pick a role below to share a " +
                            "phone's location with the head unit over your hotspot."
                    com.kevin.midnightai.service.DeviceRole.Role.HEAD_UNIT ->
                        if (beaconLinkUp) "Receiving location from ${beaconPeer ?: "your phone"}."
                        else "Waiting for a phone. Set the beacon phone to PHONE and join it to the " +
                            "same hotspot as this unit - it does not need to be the phone providing " +
                            "the hotspot."
                    com.kevin.midnightai.service.DeviceRole.Role.GPS_BEACON ->
                        // The grant is checked here, not just inside the service: without
                        // it the service refuses to start (it declares a location-typed
                        // foreground service, which Android 14 will not run unpermitted),
                        // and the driver would be left staring at a role that reports
                        // ready and does nothing.
                        if (!com.kevin.midnightai.location.LocationController.hasPermission(context))
                            "Needs location access. Grant \"Microphone & location\" under " +
                                "Permissions, then pick PHONE again."
                        else if (beaconServing)
                            "Sending this phone's location to the head unit." +
                                (beaconLocal?.let { "\nThis phone: $it" } ?: "")
                        else "Ready. Start the car - this phone sends its location only while the " +
                            "head unit is asking for it." +
                            (beaconLocal?.let { "\nThis phone: $it" } ?: "")
                },
                style = RowSubtitle,
                color = AriaColors.TextDim,
            )
            Spacer(Modifier.height(AriaDimens.s2))
            Row {
                listOf(
                    com.kevin.midnightai.service.DeviceRole.Role.OFF to "OFF",
                    com.kevin.midnightai.service.DeviceRole.Role.HEAD_UNIT to "HEAD UNIT",
                    com.kevin.midnightai.service.DeviceRole.Role.GPS_BEACON to "PHONE",
                ).forEach { (role, label) ->
                    val selected = deviceRole == role
                    Text(
                        label,
                        style = Label,
                        color = if (selected) AriaColors.Amber else AriaColors.TextDim,
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .clickable {
                                deviceRole = role
                                com.kevin.midnightai.service.DeviceRole.set(context, role)
                                // An explicit tap must always act, even on the role
                                // already selected: re-picking PHONE after granting
                                // location is the documented recovery path, and the
                                // idempotence guard would otherwise skip it.
                                com.kevin.midnightai.service.DeviceRole.invalidateApplied()
                                com.kevin.midnightai.service.DeviceRole.applyCurrent(context)
                            }
                            .padding(horizontal = AriaDimens.s3, vertical = 6.dp),
                    )
                }
            }

            // Manual address, head unit only. Hidden once the link is up, to keep the
            // common path one tap per device - EXCEPT when an address is already
            // saved. Hiding it purely on "link up" meant that if the WRONG phone
            // answered (any device on the hotspot in PHONE role can), the field the
            // driver needs to pin the right one disappeared exactly when they needed
            // it, because something was indeed connected.
            var manualPeer by remember(refresh) {
                mutableStateOf(com.kevin.midnightai.location.BeaconPreferences.manualPeer(context).orEmpty())
            }
            if (deviceRole == com.kevin.midnightai.service.DeviceRole.Role.HEAD_UNIT &&
                (!beaconLinkUp || manualPeer.isNotEmpty())
            ) {
                Spacer(Modifier.height(AriaDimens.s3))
                OutlinedTextField(
                    value = manualPeer,
                    onValueChange = {
                        manualPeer = it
                        com.kevin.midnightai.location.BeaconPreferences.setManualPeer(context, it)
                    },
                    singleLine = true,
                    label = { Text("Beacon address (optional)") },
                    placeholder = { Text("auto") },
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    "Leave blank unless the link never comes up, or the wrong phone answered. The " +
                        "beacon phone shows its own address on this same screen. Setting it here " +
                        "also pins the link to that one phone and ignores every other device.",
                    style = RowSubtitle,
                    color = AriaColors.TextDim,
                )
            }
        }

        // Only matters on a device that sleeps. A bolted-in head unit never does, so
        // this is really the "phone in a cradle as a portable head unit" setting.
        Spacer(Modifier.height(AriaDimens.s4))
        var screenAwake by remember(refresh) { mutableStateOf(ScreenAwakePreferences.isEnabled(context)) }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(
                checked = screenAwake,
                onCheckedChange = { screenAwake = it; ScreenAwakePreferences.setEnabled(context, it) },
            )
            Spacer(Modifier.width(AriaDimens.s2))
            Column(Modifier.weight(1f)) {
                Text("Keep screen awake", style = RowTitle, color = AriaColors.TextPrimary)
                Text(
                    if (ScreenAwakePreferences.isExplicit(context))
                        "The screen stays on while this app is in front. Only applies here, not to " +
                            "the rest of the phone."
                    else
                        "On by default when this app is your home screen, off otherwise. Turn it on " +
                            "for a phone running as a head unit in a cradle - otherwise it dims and " +
                            "locks mid-drive.",
                    style = RowSubtitle,
                    color = AriaColors.TextDim,
                )
            }
        }

        Spacer(Modifier.height(AriaDimens.s4))
        var subtitles by remember { mutableStateOf(DebugSettings.subtitlesEnabled(context)) }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(
                checked = subtitles,
                onCheckedChange = { subtitles = it; DebugSettings.setSubtitles(context, it) },
            )
            Spacer(Modifier.width(AriaDimens.s2))
            Column(Modifier.weight(1f)) {
                Text("${companionLabel(context)} subtitles (debug)", style = RowTitle, color = AriaColors.TextPrimary)
                Text(
                    "Show what ${companionLabel(context)} is saying as a caption under the floating button.",
                    style = RowSubtitle,
                    color = AriaColors.TextDim,
                )
            }
        }

        Spacer(Modifier.height(AriaDimens.s4))
        var recallAlerts by remember { mutableStateOf(DebugSettings.recallAlertsEnabled(context)) }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(
                checked = recallAlerts,
                onCheckedChange = { recallAlerts = it; DebugSettings.setRecallAlerts(context, it) },
            )
            Spacer(Modifier.width(AriaDimens.s2))
            Column(Modifier.weight(1f)) {
                Text("Recall alerts at startup", style = RowTitle, color = AriaColors.TextPrimary)
                Text(
                    "When you start the car, your co-pilot checks for open safety recalls and mentions them once.",
                    style = RowSubtitle,
                    color = AriaColors.TextDim,
                )
            }
        }

        // The Gemini API key lives in the "AI & Plan" section now (it's tied to the
        // plan/unlock), not here.

        // Dev-only: app-icon concept generation was removed 2026-09-27 along
        // with all other in-app image generation. Art (incl. the launcher
        // icon) is now generated once outside the app and shipped as a
        // seeded asset - see com.kevin.midnightai.ai.SeedArt.
        if (BuildConfig.DEBUG) {
            Spacer(Modifier.height(24.dp))
            Text("Dev tools", style = RowTitle, color = AriaColors.TextPrimary)
            Spacer(Modifier.height(AriaDimens.s2))
            Button(
                onClick = {
                    com.kevin.midnightai.MidnightEvents.testPing()
                    Toast.makeText(
                        context,
                        "Sent a test breadcrumb + non-fatal. Check Crashlytics > Non-fatals in a few minutes (needs real google-services.json + network).",
                        Toast.LENGTH_LONG,
                    ).show()
                },
            ) { Text("TEST CRASHLYTICS") }
        }
    }
}

// --- Connections: Spotify, cross-device sync, garage --------

@Composable
private fun ConnectionsSpotifySection(onBack: () -> Unit) {
    SectionScaffold("Spotify", onBack) {
        Text("Spotify", style = RowTitle, color = AriaColors.TextPrimary)
        Spacer(Modifier.height(AriaDimens.s1))
        Text(
            "Optional, for power users: bring your own Spotify developer app to control Spotify " +
                "directly from the dash and by voice. Needs your own Spotify Premium account and a " +
                "free dev app you register yourself. Skip this and music keeps working over phone " +
                "Bluetooth exactly as it does today.",
            style = RowSubtitle,
            color = AriaColors.TextDim,
        )
        Spacer(Modifier.height(10.dp))
        SpotifyKeyEntry()
        Spacer(Modifier.height(AriaDimens.s4))
        SpotifyPinPicker()
    }
}

@Composable
private fun ConnectionsSyncSection(onBack: () -> Unit) {
    SectionScaffold("Cross-device sync", onBack) {
        Text("Cross-device sync", style = RowTitle, color = AriaColors.TextPrimary)
        Spacer(Modifier.height(AriaDimens.s1))
        Text(
            "Optional: connect your own Google Drive so your car data rides between this dash and " +
                "your phone - review OBD history and prep mixtapes from the couch, ready when you drive. " +
                "It goes to your Drive, never our servers. Skip this and everything stays on this device.",
            style = RowSubtitle,
            color = AriaColors.TextDim,
        )
        Spacer(Modifier.height(10.dp))
        DriveSyncEntry()
    }
}

@Composable
private fun ConnectionsEngineSection(context: Context, onBack: () -> Unit) {
    SectionScaffold("Engine", onBack) {
        Text("Engine", style = RowTitle, color = AriaColors.TextPrimary)
        Spacer(Modifier.height(AriaDimens.s1))
        Text(
            "Your own LEGION engine: the household server that holds your lists (groceries included), " +
                "to-dos, calendar, notes and body logs. Sign in once and ${companionLabel(context)} can " +
                "read and add to them by voice. Until you do, those questions come back empty-handed.",
            style = RowSubtitle,
            color = AriaColors.TextDim,
        )
        Spacer(Modifier.height(AriaDimens.s3))
        EngineEntry()
    }
}

@Composable
private fun ConnectionsGarageSection(onBack: () -> Unit) {
    SectionScaffold("Garage & gate", onBack) {
        Text("Garage / gate", style = RowTitle, color = AriaColors.TextPrimary)
        Spacer(Modifier.height(AriaDimens.s1))
        Text(
            "Optional: control a garage door or gate wired through a Shelly Gen2+ relay mounted at " +
                "the garage, reached over your phone's hotspot via Shelly's cloud (BYO auth key). A " +
                "single trigger pulse only - like a handheld remote, there's no way to know if it's " +
                "open or closed.",
            style = RowSubtitle,
            color = AriaColors.TextDim,
        )
        Spacer(Modifier.height(10.dp))
        GarageSettings()
    }
}

@Composable
private fun GrantRow(label: String, granted: Boolean, actionLabel: String, onAction: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = AriaDimens.s2),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = if (granted) "✓" else "○",
            style = RowTitle,
            color = if (granted) AriaColors.Amber else AriaColors.TextDim,
            textAlign = TextAlign.Center,
            modifier = Modifier.width(28.dp),
        )
        Text(
            text = label,
            style = RowTitle,
            color = AriaColors.TextPrimary,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(AriaDimens.s3))
        if (granted) {
            Text("ON", style = Label, color = AriaColors.Amber)
        } else {
            Text(
                text = actionLabel,
                style = Label,
                color = AriaColors.ScanRed,
                modifier = Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .border(1.dp, AriaColors.ScanRed, RoundedCornerShape(4.dp))
                    .clickable { onAction() }
                    .padding(horizontal = 14.dp, vertical = 7.dp),
            )
        }
    }
}

// --- Reset (start over / wipe data) -------------------------------------

private enum class ResetKind(val title: String, val confirm: String, val cta: String) {
    MEMORIES(
        "Forget memories?",
        "This clears everything your companion has remembered. Its name, personality, voice, and " +
            "face stay. This can't be undone.",
        "Forget memories",
    ),
    COMPANION(
        "New companion?",
        "This wipes your companion's name, personality, voice, and face, and your About-You " +
            "profile, then starts onboarding over. Your memories, saved places, and car " +
            "history are kept. This can't be undone.",
        "Start over",
    ),
    FACTORY(
        "Factory reset?",
        "This erases everything — companion, memories, your profile, saved places, and " +
            "all car maintenance history — then starts onboarding over, like a fresh install. This " +
            "can't be undone.",
        "Erase everything",
    ),
}

@Composable
private fun ResetSection(context: Context, onReset: () -> Unit, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    var pending by remember { mutableStateOf<ResetKind?>(null) }

    SectionScaffold("Reset", onBack) {
        Text(
            "Start parts of your companion over. These can't be undone.",
            style = RowSubtitle,
            color = AriaColors.TextDim,
        )
        Spacer(Modifier.height(14.dp))
        ResetRow("Forget memories", "Clear everything it remembers; keep its personality.") {
            pending = ResetKind.MEMORIES
        }
        ResetRow("New companion", "Wipe its name, personality, voice & face and start fresh. Keeps " +
            "memories, places, and car history.") { pending = ResetKind.COMPANION }
        ResetRow("Factory reset", "Erase everything and start as if freshly installed.") {
            pending = ResetKind.FACTORY
        }
    }

    pending?.let { kind ->
        AlertDialog(
            onDismissRequest = { pending = null },
            title = { Text(kind.title) },
            text = { Text(kind.confirm) },
            confirmButton = {
                TextButton(onClick = {
                    pending = null
                    scope.launch {
                        when (kind) {
                            ResetKind.MEMORIES -> {
                                CompanionReset.resetMemories(context)
                                Toast.makeText(context, "Memories cleared.", Toast.LENGTH_SHORT).show()
                            }
                            ResetKind.COMPANION -> {
                                CompanionReset.resetCompanion(context)
                                OnboardingState.reset(context)
                                onReset()
                            }
                            ResetKind.FACTORY -> {
                                CompanionReset.factoryReset(context)
                                OnboardingState.reset(context)
                                onReset()
                            }
                        }
                    }
                }) { Text(kind.cta, color = AriaColors.ScanRed) }
            },
            dismissButton = {
                TextButton(onClick = { pending = null }) { Text("CANCEL") }
            },
        )
    }
}

@Composable
private fun ResetRow(title: String, subtitle: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
            .clip(RoundedCornerShape(8.dp))
            .border(1.dp, AriaColors.PanelBorder, RoundedCornerShape(8.dp))
            .background(AriaColors.Panel)
            .clickable { onClick() }
            .padding(horizontal = 16.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = RowTitle, color = AriaColors.TextPrimary)
            Spacer(Modifier.height(2.dp))
            Text(subtitle, style = RowSubtitle, color = AriaColors.TextDim)
        }
        Text("›", style = RowTitle, color = AriaColors.ScanRed)
    }
}

/**
 * The single home for how the AI is powered: the current mode and the Gemini key entry. The
 * key field is always shown (the commercial model is dead, root ADR 0004; until 2026-10-14 it
 * was hidden behind a $10 unlock, which also meant a keyless install could not enter a key).
 */
@Composable
private fun PlanSection(onBack: () -> Unit) {
    val mode by EntitlementManager.mode.collectAsState()

    SectionScaffold("AI power & plan", onBack) {
        when (mode) {
            RuntimeMode.BYO_KEY -> {
                Text("Your own key", style = RowTitle, color = AriaColors.TextPrimary)
                Spacer(Modifier.height(6.dp))
                Text(
                    "Running on your own Gemini key. Full access, nothing metered, no limits from us, " +
                        "and no monthly bill - you pay Google directly for what you use.",
                    style = RowSubtitle,
                    color = AriaColors.TextDim,
                )
            }
            RuntimeMode.AI_PAUSED -> {
                Text("${CompanionProfile.DEFAULT_NAME} is quiet", style = RowTitle, color = AriaColors.TextPrimary)
                Spacer(Modifier.height(6.dp))
                Text(
                    "${CompanionProfile.DEFAULT_NAME} notices things and says them out loud. To ask him why, " +
                        "bring your own Gemini key.",
                    style = RowSubtitle,
                    color = AriaColors.TextDim,
                )
            }
        }

        Spacer(Modifier.height(AriaDimens.s4))

        Text("Your Gemini key", style = RowTitle, color = AriaColors.TextPrimary)
        Spacer(Modifier.height(AriaDimens.s1))
        Text(
            "Stored encrypted on this device, sent only to Google. Powers voice and diagnostics.",
            style = RowSubtitle,
            color = AriaColors.TextDim,
        )
        Spacer(Modifier.height(10.dp))
        ApiKeyEntry()

        // Quiet heads-up if a recent Gemini call hit a key problem (rate limit /
        // invalid). Polled from KeyHealth, which the sub-agent + session paths set.
        val keyProblem by produceState(initialValue = KeyHealth.lastProblem) {
            while (true) {
                value = KeyHealth.lastProblem
                kotlinx.coroutines.delay(1000)
            }
        }
        keyProblem?.let { p ->
            Spacer(Modifier.height(AriaDimens.s2))
            Text(
                "Key issue seen recently: $p",
                style = Label,
                color = AriaColors.Amber,
            )
        }
    }
}

// GaugeSurface / GaugePickerSection moved to ui/GaugePicker.kt (2026-07-16, B30) so
// ObdSheet's MANAGE tab can reuse the same picker instead of duplicating it.

// --- Previews (head-unit size) -------------------------------------------
// The two-pane previews call the real, stateless SettingsTwoPane with hand-built
// statuses and rows (settingsRows itself reads MusicPreferences / companion name
// and the live statuses read BT, Spotify, and the engine, none of which belong in
// the preview JVM). AriaPalette.current can only be set through AriaPalette.set,
// which resolves ActiveVehicle -> ObdBluetoothManager (static init throws in a
// preview), so previews render the default palette, SUMMER COAST - the LIGHT one,
// which is the contrast risk. The night palettes are not previewable here. Leaves
// that read BYO credential entry composables or live device state are skipped, as
// before; the Appearance leaves and Reset call the production composables directly.

private val PreviewStatuses = mapOf(
    SettingsCategory.PHONES to CategoryStatus("Playing from Kevin's Pixel", SettingsDot.OK),
    SettingsCategory.MUSIC to CategoryStatus("Spotify missing permissions", SettingsDot.ERROR),
    SettingsCategory.ENGINE to CategoryStatus("Signed in as kevin@home", SettingsDot.OK),
    SettingsCategory.COMPANION to CategoryStatus(CompanionProfile.DEFAULT_NAME),
    SettingsCategory.CAR to CategoryStatus("1998 Jeep Cherokee"),
    SettingsCategory.LOOK to CategoryStatus("Summer Coast"),
    SettingsCategory.SYSTEM to CategoryStatus("1 permission missing", SettingsDot.WARN),
)

private fun previewRows(category: SettingsCategory): List<SettingsRowSpec> = when (category) {
    SettingsCategory.MUSIC -> listOf(
        SettingsRowSpec(
            "Spotify", "Optional BYO Spotify developer app",
            RowAction.Button("RE-AUTHORIZE", ButtonWeight.HOT) {},
            SettingsChip("ACTION NEEDED", SettingsDot.ERROR),
        ),
        SettingsRowSpec(
            "Open music apps by voice", "Off by default. Launches a music app installed on this head unit",
            RowAction.Toggle(true) {},
        ),
        SettingsRowSpec("Media check", "Raw MediaSession read", RowAction.Button("OPEN", ButtonWeight.QUIET) {}),
    )
    SettingsCategory.COMPANION -> listOf(
        SettingsRowSpec("Companion setup", "Guided setup, tap or talk", RowAction.Button("START", ButtonWeight.PRIMARY) {}),
        SettingsRowSpec("Voice style", "How your co-pilot speaks", RowAction.Button("OPEN", ButtonWeight.QUIET) {}),
        SettingsRowSpec("Test voice", "Hear it speak a line", RowAction.Button("PLAY", ButtonWeight.PRIMARY) {}),
    )
    else -> listOf(
        SettingsRowSpec(
            "Permissions", "Microphone, media access, overlay",
            RowAction.Button("OPEN", ButtonWeight.QUIET) {},
            SettingsChip("ACTION NEEDED", SettingsDot.WARN),
        ),
        SettingsRowSpec(
            "Preferences", "Music app, home screen, subtitles, recalls",
            RowAction.Button("OPEN", ButtonWeight.QUIET) {},
            SettingsChip("OK", SettingsDot.OK),
        ),
        SettingsRowSpec("Reset", "Start over or wipe data", RowAction.Button("OPEN", ButtonWeight.QUIET) {}),
    )
}

@Preview(widthDp = 1024, heightDp = 600)
@Composable
private fun PreviewSettingsTwoPaneMusic() {
    AriaTheme {
        SettingsTwoPane(
            selected = SettingsCategory.MUSIC, onSelect = {}, statuses = PreviewStatuses,
            rows = previewRows(SettingsCategory.MUSIC), compact = false, paneOpen = true,
            onClosePane = {}, onExit = {},
        )
    }
}

@Preview(widthDp = 1024, heightDp = 600)
@Composable
private fun PreviewSettingsTwoPaneSystem() {
    AriaTheme {
        SettingsTwoPane(
            selected = SettingsCategory.SYSTEM, onSelect = {}, statuses = PreviewStatuses,
            rows = previewRows(SettingsCategory.SYSTEM), compact = false, paneOpen = true,
            onClosePane = {}, onExit = {},
        )
    }
}

@Preview(widthDp = 411, heightDp = 800)
@Composable
private fun PreviewSettingsCompactRail() {
    AriaTheme {
        SettingsTwoPane(
            selected = SettingsCategory.PHONES, onSelect = {}, statuses = PreviewStatuses,
            rows = previewRows(SettingsCategory.PHONES), compact = true, paneOpen = false,
            onClosePane = {}, onExit = {},
        )
    }
}

@Preview(widthDp = 411, heightDp = 800)
@Composable
private fun PreviewSettingsCompactPane() {
    AriaTheme {
        SettingsTwoPane(
            selected = SettingsCategory.COMPANION, onSelect = {}, statuses = PreviewStatuses,
            rows = previewRows(SettingsCategory.COMPANION), compact = true, paneOpen = true,
            onClosePane = {}, onExit = {},
        )
    }
}

@Preview(widthDp = 1024, heightDp = 600)
@Composable
private fun PreviewResetSection() {
    AriaTheme {
        val context = LocalContext.current
        ResetSection(context = context, onReset = {}, onBack = {})
    }
}

@Preview(widthDp = 1024, heightDp = 600)
@Composable
private fun PreviewAppearanceWallpaperLeaf() {
    AriaTheme {
        val context = LocalContext.current
        AppearanceWallpaperSection(context = context, onBack = {})
    }
}

@Preview(widthDp = 1024, heightDp = 600)
@Composable
private fun PreviewAppearanceColorsLeaf() {
    AriaTheme {
        val context = LocalContext.current
        AppearanceColorsSection(context = context, onBack = {})
    }
}

@Preview(widthDp = 1024, heightDp = 600)
@Composable
private fun PreviewAppearanceGaugesLeaf() {
    AriaTheme {
        val context = LocalContext.current
        AppearanceGaugesSection(context = context, onBack = {})
    }
}

@Preview(widthDp = 1024, heightDp = 600)
@Composable
private fun PreviewAppearanceScreenDeckLeaf() {
    AriaTheme {
        val context = LocalContext.current
        AppearanceScreenDeckSection(context = context, onBack = {})
    }
}

// The role -> running-sockets logic lives in DeviceRole.applyCurrent, because
// switching cars must apply it too (ActiveVehicle.notifyResolutionChanged) and two
// copies of a "stop both ends, start the right one" sequence is exactly the kind of
// duplication that drifts.
