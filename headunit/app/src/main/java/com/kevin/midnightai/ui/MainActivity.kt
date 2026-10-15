package com.kevin.midnightai.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.windowsizeclass.ExperimentalMaterial3WindowSizeClassApi
import androidx.compose.material3.windowsizeclass.calculateWindowSizeClass
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.kevin.midnightai.media.SpotifyWebApi
import androidx.lifecycle.lifecycleScope
import com.kevin.midnightai.StartupTrace
import com.kevin.midnightai.ai.GeminiKeyProvider
import com.kevin.midnightai.service.AriaForegroundService
import com.kevin.midnightai.sync.SyncEngine
import com.kevin.midnightai.ui.theme.AriaColors
import com.kevin.midnightai.ui.theme.AriaTheme
import kotlinx.coroutines.launch

/**
 * The app's one activity: hosts the Cruise HUD as the head unit's home surface
 * (plus onboarding, settings, and the logbook). The Gemini Live session itself
 * lives in [AriaForegroundService] so voice and the proactive engine survive
 * backgrounding; this activity grants the permissions that service needs
 * (mic/location, media access for now-playing) and provides the tap-to-talk
 * companion.
 */
class MainActivity : ComponentActivity() {

    // Set true when the first-run key gate just completed, so the greeting fires
    // AFTER the one permission moment rather than on top of the brand cold-open.
    private var pendingGreetAfterPermissions = false

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        if (permissions.all { it.value }) {
            startAriaServiceAfterPermissions()
        } else {
            // Don't block the greeting on a denial - the tap-time notices handle a
            // missing mic. Start the service (with the greet if one was pending).
            val who = com.kevin.midnightai.ai.CompanionProfile.name(this).ifBlank { "the app" }
            Toast.makeText(this, "Permissions required for $who to function", Toast.LENGTH_LONG).show()
            startAriaServiceAfterPermissions()
        }
    }

    /** Starts the service, delivering the first-run greeting if one is pending. */
    private fun startAriaServiceAfterPermissions() {
        if (pendingGreetAfterPermissions) {
            pendingGreetAfterPermissions = false
            startAriaService(AriaForegroundService.ACTION_GREET)
        } else {
            startAriaService()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        StartupTrace.activityCreateStart()
        handleSpotifyRedirect(intent)

        // Load the active Gemini key (user-entered, else BuildConfig) before
        // setContent, so the onboarding screen's key check is correct on first run.
        StartupTrace.span("main_gemini_key_init") { com.kevin.midnightai.ai.GeminiKeyProvider.init(this) }
        StartupTrace.span("main_palette_init") { com.kevin.midnightai.ui.theme.AriaPalette.init(this) }
        // Resolves whether this install has a Gemini key saved (BYO_KEY) or is
        // paused. Only Gemini-billed actions consult it. (Used to be framed as
        // the free/unlock tier check; the commercial model is dead.)
        StartupTrace.span("main_entitlement_init") { com.kevin.midnightai.ai.EntitlementManager.init(this) }

        // Make the app "edge-to-edge" and hide system bars for a total OS takeover look.
        window.setFlags(
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        )
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        // Hide only the navigation bar for the takeover look, but KEEP the status
        // bar so the OS notification shade is always one-swipe pullable. Hiding the
        // full system bars + sticky-transient behavior made the shade unreliable to
        // pull down on the head unit (you'd get transient bars instead of the shade).
        controller.hide(WindowInsetsCompat.Type.navigationBars())
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE

        // The app opens straight to Cruise for everyone now - there's no
        // cold-open key gate to protect from a stacked
        // permission dialog, so ask up front unconditionally, same as the
        // key-already-present path always did. A genuinely fresh install (no
        // key yet, onboarding not yet marked complete) still gets the one-time
        // greet-after-permissions moment that FirstRunScreen.onComplete used to
        // trigger - now decided here since there's no first-run screen to fire it.
        if (!GeminiKeyProvider.hasKey() && !OnboardingState.isComplete(this)) {
            pendingGreetAfterPermissions = true
        }
        StartupTrace.span("main_permissions") { checkAndRequestPermissions() }

        // On a clean install (or after a reset), pre-fill safe Zero/Sulafat
        // defaults so the onboarding wizard below starts from sensible values
        // instead of blank fields. Idempotent (only writes if fields are blank)
        // and does NOT mark onboarding complete - the wizard still runs.
        if (!OnboardingState.isComplete(this)) {
            OnboardingState.seedDefaults(this)
        }

        StartupTrace.mark("activity_pre_setcontent")
        setContent {
            AriaTheme {
                // One-shot: records first-composition and (two vsyncs later) first-frame time.
                SideEffect {
                    StartupTrace.firstCompositionDone()
                }
                // Dual-target responsive infra (2026-07-12): computed once per
                // setContent recomposition of the Activity's own config change
                // (rotation), NOT a full Activity recreate - configChanges
                // above keeps the live Gemini session alive across it. A head
                // unit that only ever reports landscape always lands in
                // EXPANDED (today's byte-for-byte layout); a phone reflows to
                // COMPACT/MEDIUM. See ui/WindowSize.kt.
                @OptIn(ExperimentalMaterial3WindowSizeClassApi::class)
                val sizeClass = calculateWindowSizeClass(this@MainActivity).widthSizeClass.toSizeClass()

                CompositionLocalProvider(LocalSizeClass provides sizeClass) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    // Two tiers only (CLAUDE.md sec 2, 2026-07-16 rewrite): the base
                    // app is always usable, no entry gate. Everyone (BYO key or
                    // paused) lands on Cruise immediately with the bundled default
                    // MIDNIGHT companion; a Gemini key is only needed to unlock BYO
                    // mode (Setup's "AI key" section / the paywall's one-time-unlock
                    // path), never to open the app. This replaces the old
                    // FirstRunScreen cold-open gate (needsKey), which used to be the
                    // very first thing a fresh install saw.
                    //
                    // 2026-07-08: voice onboarding used to gate the whole app -
                    // Cruise never showed until the spoken (or typed-fallback)
                    // wizard finished. That meant the app's very first moment
                    // was always a live mic session racing the just-fired
                    // permission request, which was implicated in startup
                    // crashes. Decoupled: the app is usable immediately with
                    // seeded Zero/Sulafat defaults, and voice setup becomes an
                    // explicit, optional action from a Cruise prompt (or
                    // Settings) - never something that has to race anything.
                    var onboarded by remember { mutableStateOf(OnboardingState.isComplete(this@MainActivity)) }
                    if (!onboarded) {
                        OnboardingState.seedDefaults(this@MainActivity)
                        OnboardingState.markComplete(this@MainActivity)
                        onboarded = true
                    }
                    // Foreground default is the ambient Cruise screen; the gear
                    // opens the control panel. Voice setup is a full-screen
                    // overlay reachable from Cruise's setup prompt or Settings -
                    // never blocks reaching the app itself.
                    var inSettings by remember { mutableStateOf(false) }
                    // When true, the control panel opens straight to the Setup
                    // section (where garage setup lives) instead of the menu.
                    var settingsInSetup by remember { mutableStateOf(false) }
                    // When true, opens straight to Connections > Phones (the
                    // Cruise phone-link chip's tap target, 2026-09-27 slim pass -
                    // CARS/logbook no longer have a Cruise entry point at all).
                    var settingsInPhones by remember { mutableStateOf(false) }
                    // Cruise playlist tile long-press: straight to Connections > Spotify.
                    var settingsInSpotify by remember { mutableStateOf(false) }
                    var showOnboarding by remember { mutableStateOf(false) }
                    val onVoiceSetupDone = {
                        OnboardingState.markVoiceSetupDone(this@MainActivity)
                        showOnboarding = false
                        startAriaService(AriaForegroundService.ACTION_GREET)
                    }
                    // Companion setup opens the unified OnboardingManager. Tap and
                    // type always work; the spoken option lights up per step only
                    // when voice can actually run (canStartVoice + mic granted), so a
                    // trial user with no broker is no longer shunted to a typed-only
                    // wizard and never hits a doomed empty-key connect.
                    val startCompanionSetup = { showOnboarding = true }
                    when {
                        showOnboarding -> OnboardingManager(
                            onComplete = onVoiceSetupDone,
                            onExit = { showOnboarding = false },
                        )
                        inSettings -> ControlPanel(
                            onRequestRuntimePermissions = { checkAndRequestPermissions() },
                            startInSetup = settingsInSetup,
                            startInPhones = settingsInPhones,
                            startInSpotify = settingsInSpotify,
                            onReset = { onboarded = false },
                            onExit = { inSettings = false; settingsInSetup = false; settingsInPhones = false; settingsInSpotify = false },
                            onStartVoiceSetup = { inSettings = false; settingsInSetup = false; settingsInPhones = false; settingsInSpotify = false; startCompanionSetup() },
                        )
                        // Cruise is home. It was briefly displaced by a purpose-built
                        // DriveHome launcher screen (2026-09), which Kevin reverted:
                        // Cruise already had the cassette now-playing widget, the app
                        // tray, Lights Out and the gauges DriveHome reinvented a
                        // plainer version of. The reverse camera and media-diagnostics
                        // reach are now inside Cruise/ControlPanel themselves (the
                        // dock's REAR tile and a Settings row) rather than a second
                        // home screen.
                        else -> CruiseScreen(
                            onOpenSettings = { settingsInSetup = false; settingsInPhones = false; settingsInSpotify = false; inSettings = true },
                            onOpenPhoneLink = { settingsInSetup = false; settingsInPhones = true; settingsInSpotify = false; inSettings = true },
                            onOpenSpotifySettings = { settingsInSetup = false; settingsInPhones = false; settingsInSpotify = true; inSettings = true },
                            onStartVoiceSetup = { startCompanionSetup() },
                        )
                    }
                    // Cross-device sync (S1): a genuine "two different companions
                    // met for the first time" moment - see
                    // SyncEngine.CompanionSync.decideCompanion. Non-blocking: the
                    // rest of the app (and sync itself) keeps working while this
                    // sits open; it only ever appears once per real mismatch.
                    val companionClash by SyncEngine.pendingCompanionClash.collectAsState()
                    companionClash?.let { clash ->
                        AlertDialog(
                            onDismissRequest = { /* Requires an explicit choice - no free dismiss. */ },
                            title = { Text("Two companions found") },
                            text = {
                                Text(
                                    "This device: ${clash.local.name}. Your other device: " +
                                        "${clash.remote.name}. Keep which one everywhere?",
                                )
                            },
                            confirmButton = {
                                TextButton(onClick = {
                                    lifecycleScope.launch {
                                        SyncEngine.resolveCompanionClash(this@MainActivity, keepLocal = true)
                                    }
                                }) { Text("KEEP ${clash.local.name}") }
                            },
                            dismissButton = {
                                TextButton(onClick = {
                                    lifecycleScope.launch {
                                        SyncEngine.resolveCompanionClash(this@MainActivity, keepLocal = false)
                                    }
                                }) { Text("SWITCH TO ${clash.remote.name}") }
                            },
                            containerColor = AriaColors.Panel,
                            titleContentColor = AriaColors.TextPrimary,
                            textContentColor = AriaColors.TextPrimary,
                        )
                    }
                }
                }
            }
        }
    }

    /**
     * The Spotify PKCE consent redirect lands here - the manifest intent-filter for
     * `SpotifyController.REDIRECT_URI` is on this Activity (it predates this flow,
     * added for App Remote).
     *
     * This now genuinely holds: the manifest declares `singleTask` (added 2026-07-29),
     * so an already-running MainActivity receives the redirect through [onNewIntent]
     * rather than a second instance being created. The comment here previously
     * asserted that while NO launchMode was declared at all, so the activity was
     * "standard" and the browser really did build a duplicate launcher instance.
     * Both entry points ([onCreate] for a cold start, this for a warm one) funnel
     * into [handleSpotifyRedirect]. Non-Spotify intents fall through untouched.
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleSpotifyRedirect(intent)
    }

    private fun handleSpotifyRedirect(intent: Intent?) {
        val data = intent?.data ?: return
        // Only our own redirect gets feedback - any other deep link falls through
        // silently, which is why this check happens before the toast rather than
        // relying on handleRedirect's return alone (it returns false for both
        // "not ours" and "ours but failed").
        if (!data.toString().startsWith(com.kevin.midnightai.media.SpotifyController.REDIRECT_URI)) return

        // Consume it: onNewIntent stores this via setIntent, so it stays on the
        // Activity and onCreate would re-run it on any later recreation (process
        // death, a config change outside our configChanges set). The PKCE verifier
        // is single-use and already taken by then, so the replay fails and toasts
        // "sign-in didn't complete" over a connection that actually succeeded.
        // singleTask makes this more reachable, not less - the instance now
        // survives much longer holding this intent.
        intent.data = null

        lifecycleScope.launch {
            if (SpotifyWebApi.handleRedirect(this@MainActivity, data)) {
                com.kevin.midnightai.MidnightEvents.spotifyWebAuthorized(true)
                // Fresh scopes: let the Cruise playlist row re-fetch now rather than
                // waiting out its 10-minute interval (also clears "reconnect").
                com.kevin.midnightai.media.SpotifyPlaylists.refreshIfStale(this@MainActivity, force = true)
                // Connect App Remote too - authorizing the Web API is the driver saying
                // "use Spotify", and playback goes through App Remote once search returns
                // a URI. Awaited, and with the consent sheet: onResume's silent attempt is
                // usually already in flight by now (it fires as we come back from the
                // browser); connectWithConsent joins it and, if it fails, retries with
                // Spotify's own consent sheet, which is the grant App Remote needs and the
                // browser flow does not give. Bounded so a wedged Spotify app cannot hold
                // the toast forever; the attempt itself keeps going past the bound.
                val playback = kotlinx.coroutines.withTimeoutOrNull(REDIRECT_PLAYBACK_WAIT_MS) {
                    com.kevin.midnightai.media.SpotifyController.connectWithConsent(this@MainActivity)
                }
                val toast = com.kevin.midnightai.media.SpotifyController.redirectToast(
                    playback,
                    com.kevin.midnightai.media.SpotifyController.lastFailure,
                )
                Toast.makeText(this@MainActivity, toast, if (playback == true) Toast.LENGTH_SHORT else Toast.LENGTH_LONG).show()
            } else {
                // Without this the driver approves in the browser, lands back on
                // Cruise, and has no idea it didn't take - the Setup status line
                // isn't recomposed by a redirect either.
                Toast.makeText(
                    this@MainActivity,
                    "Spotify sign-in didn't complete - try CONNECT again",
                    Toast.LENGTH_LONG,
                ).show()
            }
        }
    }

    /**
     * Volume keys that reach us while we're in front go down the same AudioManager
     * path as the on-screen volume keys ([com.kevin.midnightai.media.VolumeController]),
     * the one known to reach the head unit's speakers (2026-10-13 field report: the
     * hardware keys moved the meter but not the sound). Keys handled below us (MCU,
     * system) are caught after the fact by [com.kevin.midnightai.media.VolumeSync].
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val up = event.keyCode == KeyEvent.KEYCODE_VOLUME_UP
        if (!up && event.keyCode != KeyEvent.KEYCODE_VOLUME_DOWN) return super.dispatchKeyEvent(event)
        if (event.action == KeyEvent.ACTION_DOWN) {
            if (up) com.kevin.midnightai.media.VolumeController.raise(this)
            else com.kevin.midnightai.media.VolumeController.lower(this)
        }
        return true
    }

    override fun onPause() {
        super.onPause()
        // Spotify's consent sheet is only retried while we are in front (SpotifyController).
        com.kevin.midnightai.media.SpotifyController.setActivityResumed(false)
        com.kevin.midnightai.MidnightEvents.launcherLifecycle("paused")
        com.kevin.midnightai.service.CompanionPhase.setUiInFront(false)
    }

    override fun onResume() {
        super.onResume()
        com.kevin.midnightai.media.SpotifyController.setActivityResumed(true)
        com.kevin.midnightai.MidnightEvents.launcherLifecycle("resumed")
        com.kevin.midnightai.service.CompanionPhase.setUiInFront(true)
        // Re-read on every resume, not once in onCreate: the driver can change the
        // setting (or set the app as HOME, which moves the default) and come
        // straight back here without the Activity being recreated - configChanges
        // deliberately keeps this Activity alive to protect the live Gemini socket.
        if (ScreenAwakePreferences.isEnabled(this)) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        // Re-attach App Remote if it dropped while we were away (Spotify killed,
        // or backgrounded long enough for it to let go). connectSilently (not
        // connect) is required here: onResume fires on essentially every
        // foreground return (back from nav, back from a call, screen unlock, any
        // full-screen app exit), so a version that can pop Spotify's auth sheet
        // would surface an unprompted consent dialog on almost any return to the
        // app. connectSilently no-ops when already connected, already
        // connecting, or when no client ID is saved, so this costs nothing for a
        // driver who doesn't use Spotify.
        com.kevin.midnightai.media.SpotifyController.connectSilently(this)
        // Make sure the service (session + proactive engine) is up whenever the
        // Cruise screen comes back in front.
        val micGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        if (micGranted) {
            startAriaService()
        }
        // Cross-device sync (S1): pull anything prepped on the other device when we
        // come to the foreground. Throttled + no-op unless Drive is connected.
        SyncEngine.maybeAutoSync(this, lifecycleScope)
    }

    private fun checkAndRequestPermissions() {
        val permissions = mutableListOf<String>()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions.add(Manifest.permission.BLUETOOTH_SCAN)
            permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
        }

        permissions.add(Manifest.permission.RECORD_AUDIO)
        permissions.add(Manifest.permission.ACCESS_FINE_LOCATION)
        permissions.add(Manifest.permission.ACCESS_COARSE_LOCATION)
        // Optional: lets Zero announce incoming phone calls (phone paired over BT).
        permissions.add(Manifest.permission.READ_PHONE_STATE)

        val missingPermissions = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (missingPermissions.isEmpty()) {
            startAriaServiceAfterPermissions()
        } else {
            requestPermissionLauncher.launch(missingPermissions.toTypedArray())
        }
    }

    private fun startAriaService(action: String? = null) {
        val serviceIntent = Intent(this, AriaForegroundService::class.java)
        if (action != null) serviceIntent.action = action
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }
    }
}

/** How long the redirect toast waits on App Remote (incl. the driver answering its consent sheet). */
private const val REDIRECT_PLAYBACK_WAIT_MS = 60_000L
