package com.kevin.legion.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.kevin.legion.ai.GeminiKeyProvider
import com.kevin.legion.ledger.LedgerController
import com.kevin.legion.media.SpotifyController
import com.kevin.legion.media.SpotifyWebApi
import com.kevin.legion.service.ReminderAlarmReceiver
import com.kevin.legion.sync.SyncCapability
import com.kevin.legion.sync.SyncEngine
import com.kevin.legion.ui.assistant.AssistantStrip
import com.kevin.legion.ui.common.DeckBezel
import com.kevin.legion.ui.common.StatusLine
import com.kevin.legion.ui.companions.MemoryScreen
import com.kevin.legion.ui.companions.PlaybookScreen
import com.kevin.legion.ui.sync.GoogleAccessScreen
import com.kevin.legion.ui.theme.LegionTheme
import com.kevin.legion.ui.theme.LocalLegionSemantics
import com.kevin.legion.util.clockTime
import com.kevin.legion.vehicle.ObdBluetoothManager
import kotlinx.coroutines.delay

/**
 * Single-activity shell (ticket 07 resolution). Everything the app shows -
 * fleet, ledger, pantry, settings, and the sub-routes absorbed from the three
 * orphan activities - lives inside one [NavHost] here. There is no second
 * `<activity>` left in the manifest for any of that content; see the
 * deletions of `SavedPlacesActivity`, `LedgerImportActivity` and
 * `PantryImportActivity` (their composable content moved into [ui] screen
 * files, only the hosting changed) and `BootReceiver` (deleted outright -
 * ignition is a user toggle now, see [com.kevin.legion.service.AssistantIgnition]).
 *
 * **`LegionTheme`, not `MaterialTheme`** (resolution "specified, not asked").
 * The Instrument theme (ticket 02) was built and previously unused by the
 * only screen that existed.
 *
 * **No key wall** (resolution §3). The shell renders with all four tabs live
 * on a completely fresh install; a Gemini key is requested only at the
 * point of use (the assistant toggle, or a future LLM-fallback spend gate),
 * never as a gate on opening the app.
 */
class MainActivity : ComponentActivity() {
    // Held as Compose state, not read once in onCreate: MainActivity is
    // launchMode="singleTask" (kept for the Spotify OAuth redirect - see the
    // manifest comment), so a voice tool's second startActivity call while
    // this Activity is already on top delivers onNewIntent, not a fresh
    // onCreate. deepLinkNonce (not deepLinkRoute) is what LegionShell's
    // LaunchedEffect keys on, so a repeat "open my saved places" while the
    // app is already foregrounded re-navigates instead of being skipped as
    // an unchanged key - deepLinkRoute alone could arrive at the same value
    // twice in a row.
    private var deepLinkRoute by mutableStateOf<String?>(null)
    private var deepLinkNonce by mutableStateOf(0)

    // The notification-tap deep link (ticket 12: "tapping the notification opens the item").
    // ReminderAlarmReceiver.postNotification sets both EXTRA_ROUTE (= LegionRoute.NOTES, so the
    // bottom nav actually lands on Notes) and EXTRA_OPEN_ITEM_ID on the same Intent - deepLinkRoute
    // above drives the navigation, openItemId drives what NotesScreen does once it's there. Nonce-
    // keyed for the same reason deepLinkNonce is: a REPEAT tap on the same item's notification while
    // the app is already foregrounded delivers onNewIntent with an unchanged extra value, and a
    // plain state read would be skipped as a no-op change.
    private var openItemId by mutableStateOf<Long?>(null)
    private var openItemNonce by mutableStateOf(0)

    // The Spotify OAuth redirect (2026-08-12). Unlike the two deep links above this arrives as
    // the intent's DATA on an ACTION_VIEW, not as an extra - the manifest's
    // com.kevin.legion://spotify-callback intent-filter routes Spotify's browser redirect here,
    // and singleTask means it lands in onNewIntent while the app is already up. Nothing read
    // intent.data at all before this, so the redirect was delivered and silently dropped.
    //
    // Nulled by [LegionShell] once consumed, because the PKCE code verifier behind the exchange
    // is single-use: a re-fired LaunchedEffect on a URI already exchanged would take a verifier
    // that is no longer there and report a spurious failure over a grant that actually worked.
    private var spotifyRedirect by mutableStateOf<Uri?>(null)
    private var spotifyRedirectNonce by mutableStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        readDeepLinkExtras(intent)
        setContent {
            LegionTheme {
                LegionShell(
                    deepLinkRoute = deepLinkRoute, deepLinkNonce = deepLinkNonce,
                    openItemId = openItemId, openItemNonce = openItemNonce,
                    spotifyRedirect = spotifyRedirect, spotifyRedirectNonce = spotifyRedirectNonce,
                    onSpotifyRedirectConsumed = { spotifyRedirect = null },
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        readDeepLinkExtras(intent)
    }

    private fun readDeepLinkExtras(intent: Intent?) {
        deepLinkRoute = intent?.getStringExtra(EXTRA_ROUTE)
        deepLinkNonce++
        val itemId = intent?.getLongExtra(ReminderAlarmReceiver.EXTRA_OPEN_ITEM_ID, -1L) ?: -1L
        openItemId = if (itemId >= 0) itemId else null
        openItemNonce++

        // Only a URI that is actually ours is carried forward. SpotifyWebApi.handleRedirect
        // re-checks the same prefix and is safe to call with anything, but filtering here keeps
        // an ordinary launcher intent from bumping the nonce and waking the exchange effect at all.
        val data = intent?.data
        if (data != null && data.toString().startsWith(SpotifyController.REDIRECT_URI)) {
            spotifyRedirect = data
            spotifyRedirectNonce++
        }
    }

    /**
     * Foreground-triggered auto-sync, decoupled from the voice assistant
     * (ticket 10, `.scratch/ledger-drive-ingestion/issues/10-does-ledger-data-sync.md`,
     * plus ticket 07 §1's ruling that ledger/pantry/fleet all work regardless of
     * the assistant toggle). [AriaForegroundService]'s own engine-on gated
     * `SyncEngine.maybeAutoSync` call is left in place - it is not wrong, just
     * insufficient: that service only ever starts from the Settings assistant
     * toggle, which defaults OFF, so a driver who never enables the assistant
     * would otherwise never sync ledger or pantry data across devices at all.
     * This is the same shape as L12 (a feature silently gated behind an
     * unrelated toggle).
     *
     * Deliberately NOT `LedgerIngestService` either - that runs the folder
     * scan, a different lifecycle entirely, and coupling sync to it would tie
     * cross-device sync to whether the ledger scanner happens to be running.
     *
     * [SyncEngine.maybeAutoSync] is itself the safety net here, not this call
     * site: it no-ops silently when [com.kevin.legion.sync.SyncCapability]
     * says sync isn't available (no Play Services, or the driver never
     * connected Drive), when its own 5-minute throttle hasn't elapsed, and
     * [com.kevin.legion.sync.DriveAuth] fails soft (null token) with no
     * network or no consent yet - `syncNow` never throws past its own
     * try/catch. The launch is fire-and-forget on a short-lived
     * `Dispatchers.Default` scope, so this callback returns immediately and
     * never blocks the UI thread, including on a cold start where Drive has
     * never been touched.
     */
    override fun onResume() {
        super.onResume()
        // Promote AriaForegroundService's foreground-service type set back up to include
        // `microphone` now that the app is visibly foreground (2026-08-17). If the service was
        // started by BootReceiver it came up WITHOUT the microphone type - see
        // AriaForegroundService.isInForegroundEligibleState's doc - and this is the documented
        // "call startForeground() again to add a type" pattern from the Android docs, reached via
        // AssistantIgnition.resumeIfEnabled rather than a new IPC mechanism: it's a plain
        // ContextCompat.startForegroundService call with no extras, which lands in
        // onStartCommand and re-runs startForegroundCompat() unconditionally on every call
        // (see that method's own doc, "cheap and idempotent"). No-op when the flag is off, same
        // as every other call site of this function - never a consent bypass.
        // Guarded: onResume fires on essentially every foreground return, so an exception from
        // the startForegroundService IPC here would take the Activity down on something as
        // ordinary as unlocking the screen. A failed type promotion must degrade to "no mic
        // this time", never to a crash on resume.
        runCatching {
            com.kevin.legion.service.AssistantIgnition.resumeIfEnabled(applicationContext)
        }.onFailure { com.kevin.legion.MidnightEvents.appStartWorkFailed("resume_promote_mic", it) }
        // Re-run LocationController.init on every foreground return, not just at service
        // creation (2026-08-17 fix). init() early-returns without latching `initialized` when
        // permission isn't granted yet, and its doc has always promised "safe to call
        // repeatedly - retries until location permission is granted" - but AriaForegroundService
        // .onCreate was the ONLY place in the app that ever called it, so a driver who granted
        // location from Android's Settings screen and came straight back here got nothing until
        // the service happened to restart. onResume is exactly the moment that grant becomes
        // observable (returning from Settings always resumes this Activity), and init() is a
        // no-op once already initialized, so this costs nothing on every other resume. Same
        // guarded-runCatching shape as the mic promotion above: a permission plumbing failure
        // here must degrade to "still no location", never crash a screen unlock.
        runCatching {
            com.kevin.legion.location.LocationController.init(applicationContext)
        }.onFailure { com.kevin.legion.MidnightEvents.appStartWorkFailed("resume_init_location", it) }
        SyncEngine.maybeAutoSync(applicationContext)
        // Silent App Remote re-attach (2026-08-12). App Remote drops on its own whenever the
        // Spotify app is killed or backgrounded long enough, and nothing reconnected it - so
        // after the first drop the link stayed dead for the rest of the process and `play_music`
        // paid a wasted round trip through ensureConnected on every request.
        //
        // connectSilently, never connect: onResume fires on essentially every foreground return
        // (back from a call, screen unlock, any full-screen app exit), and popping Spotify's auth
        // sheet on one of those is exactly the failure mode SpotifyController's own doc warns
        // about. No-ops immediately when no client ID is saved, so a driver who never set Spotify
        // up pays nothing here. SpotifyController's doc comment has always DESCRIBED this call
        // site (its @Synchronized rationale names the race between it and the voice tool
        // dispatch); it just never existed until now.
        SpotifyController.connectSilently(applicationContext)
    }

    companion object {
        /**
         * Intent extra a caller with no Compose nav graph of its own can set to
         * land the shell directly on a sub-route, e.g. [LegionRoute.FLEET_PLACES].
         * The only caller today is [com.kevin.legion.service.LiveSessionController]'s
         * `show_saved_places` / `import_statement` / `import_receipt` voice
         * tools - they used to `startActivity` the three orphan Activities this
         * ticket deleted; now they start this Activity with a route instead.
         */
        const val EXTRA_ROUTE = "route"
    }
}

@Composable
private fun LegionShell(
    deepLinkRoute: String? = null,
    deepLinkNonce: Int = 0,
    openItemId: Long? = null,
    openItemNonce: Int = 0,
    spotifyRedirect: Uri? = null,
    spotifyRedirectNonce: Int = 0,
    onSpotifyRedirectConsumed: () -> Unit = {},
) {
    val navController = rememberNavController()
    val context = LocalContext.current

    // Today's category drill-down link (Kevin, 2026-08-07: "let me press it and drill down
    // transactions there"). Lives HERE, above the NavHost, not inside either destination's own
    // composable - the same reason [deepLinkRoute]/[openItemId] do: the Today->Money hop crosses a
    // NavHost boundary that disposes each destination's own `remember`ed state, so the instruction
    // has to be held somewhere that survives the navigation itself. See [LedgerScreen]'s
    // `openCategory` parameter doc comment for why this needs a nonce AND a consumed-reset (the
    // Notes deep link gets away with nonce-only because a null payload there already means "nothing
    // to do"; here `null` is the uncategorised bucket, a real request).
    var pendingMoneyCategory by remember { mutableStateOf<String?>(null) }
    var pendingMoneyCategoryNonce by remember { mutableStateOf(0) }

    // Keyed on the nonce, not the route string, so a repeat deep link to the
    // same sub-route (onNewIntent while already on top) still re-navigates -
    // see MainActivity.deepLinkNonce's doc comment. Does nothing for the
    // ordinary launcher-icon path, where deepLinkRoute is null.
    LaunchedEffect(deepLinkNonce) {
        deepLinkRoute?.let { navController.navigate(it) }
    }

    // The Spotify OAuth token exchange (2026-08-12). Runs HERE, above the NavHost, not inside
    // SpotifyScreen: the approval happens in a browser, so the app is backgrounded for the whole
    // round trip and can be killed during it. On return there is no guarantee `settings/spotify`
    // is still in the back stack - on a cold restart it certainly is not - and an exchange owned
    // by a composable that no longer exists is an exchange that never happens, leaving the
    // verifier stranded and CONNECT looking broken for no visible reason.
    //
    // SpotifyWebApi.handleRedirect is suspend and does real network I/O; LaunchedEffect's scope
    // is the composition's, so a user who navigates away mid-exchange cancels it rather than
    // leaking it. The outcome is handed to SpotifyScreen through spotifyAuthOk/Nonce below,
    // which is the same nonce-carried-payload shape the notes deep link already uses.
    var spotifyAuthOk by remember { mutableStateOf<Boolean?>(null) }
    var spotifyAuthNonce by remember { mutableStateOf(0) }
    LaunchedEffect(spotifyRedirectNonce) {
        val uri = spotifyRedirect ?: return@LaunchedEffect
        val ok = SpotifyWebApi.handleRedirect(context, uri)
        onSpotifyRedirectConsumed()
        spotifyAuthOk = ok
        spotifyAuthNonce++
        // Navigate AFTER the exchange, so the screen composes with the answer already in hand
        // rather than rendering a stale "Not approved" for one frame and then correcting itself.
        navController.navigate(LegionRoute.SETTINGS_SPOTIFY) { launchSingleTop = true }
    }

    // Global StatusLine state (cyberdeck-ui ticket 13). Polled rather than
    // pushed - none of [shellStatusLine]'s three reads is exposed as a
    // Flow/StateFlow today, and all three are cheap, synchronous, on-device
    // reads (see that function's own doc for exactly what each one is and is
    // not claiming), so a short poll is honest and costs nothing worth
    // avoiding. STATUS_POLL_MS is well under the OBD/Drive/key state going
    // stale in a way anyone would notice on a status line, not a live meter.
    //
    // Mission-control ticket 04 build: [ShellStatus.alarmCount] rides the SAME poll rather than a
    // second timer (ticket 04 build section 4) - one more cheap, synchronous-from-this-coroutine's
    // point of view suspend read (a `COUNT(*)` over `ingested_files`) alongside the three that were
    // already here.
    val shellStatus by produceState(initialValue = ShellStatus(shellStatusLine(context), 0)) {
        while (true) {
            value = ShellStatus(shellStatusLine(context), LedgerController.quarantinedCount(context))
            delay(STATUS_POLL_MS)
        }
    }
    // Once a minute, not once a second (this ticket's build brief) - a status
    // line clock answers "what minute is it", not a stopwatch, and ticket
    // 04's "ambient motion is exactly ONE element" rule (StatusLine's own
    // cursor, which is draw-phase-only) is exactly the discipline a
    // per-second recomposition of the whole shell's top row would violate.
    val clock by produceState(initialValue = clockTime(System.currentTimeMillis())) {
        while (true) {
            value = clockTime(System.currentTimeMillis())
            delay(CLOCK_POLL_MS)
        }
    }

    // Ticket 20: DRIVING is "a destination outside the shell chrome" (build
    // brief item 2) - no StatusLine, no hard-key row, no AssistantStrip. Read
    // here, once, above the Scaffold, rather than inside DrivingModeScreen
    // itself: the chrome this route hides belongs to LegionShell, not to any
    // one destination, and DrivingModeScreen has no way to reach into
    // Scaffold's own bottomBar/content slots from inside the NavHost. Mirrors
    // [LegionHardKeyRow]'s own `currentBackStackEntryAsState` read below,
    // which still runs (for its own highlight logic) on every OTHER route.
    val shellBackStackEntry by navController.currentBackStackEntryAsState()
    val isDrivingMode = shellBackStackEntry?.destination?.route == LegionRoute.DRIVING

    // Mission-control ticket 07's uplink sweep ("the cursor yields"): [FleetScreen]'s own
    // `UplinkPane` reports whether ITS sweep is genuinely animating right now - see that pane's
    // doc for the two-effect mechanism (a value-changed report while mounted, plus a guaranteed
    // `false` the instant it leaves composition, which covers both "any FLEET drilldown opened"
    // and "navigated off FLEET entirely"). Held HERE, above the NavHost, for the same reason
    // [statusLeft]/[clock] are: [StatusLine] is mounted once above the NavHost, not inside any one
    // destination, so the one composable that could ever own `UplinkPane`'s live state is
    // [FleetScreen] itself, reporting up through a plain callback - there is no shared ViewModel
    // or singleton flow this shell already reads that carries "is a specific pane's own ambient
    // element on screen right now", and inventing one would be more machinery than a single
    // boolean threaded down one nav entry needs.
    var fleetSweepActive by remember { mutableStateOf(false) }

    // Outer Box, not the Scaffold directly, so [GlanceCardOverlay] can be drawn
    // LAST - on top of the Scaffold's bottom bar and whatever destination is
    // showing - rather than occupying a slot inside the layout flow. Boot is
    // a full-screen takeover (ticket 04 answer #1), not a panel.
    Box(Modifier.fillMaxSize()) {
        // Mission-control ticket 14: the whole Scaffold - content AND the
        // pinned status line / Alfred strip / hard-key row inside it - sits
        // inside ONE [DeckBezel], drawn once at shell level (ticket 03's
        // charting decision: "one global bezel drawn once in the shell").
        // Deliberately NOT gated on [isDrivingMode] - ticket 08 answer #1
        // ruled driving mode gets the full deck language too, bezel included,
        // unlike the StatusLine/bottomBar carve-outs below which ARE gated
        // (those are ticket 20's earlier, narrower ruling: no status line, no
        // Alfred strip, no hard keys while driving - the bezel was not yet
        // built when that call was made).
        //
        // Insets: this app is NOT edge-to-edge (`themes.xml` sets opaque
        // `android:statusBarColor`/`android:navigationBarColor`, and there is
        // no `enableEdgeToEdge`/`WindowCompat` call anywhere in the tree -
        // grep-confirmed) and `targetSdk` is 34, below the API 35 level where
        // Android starts enforcing edge-to-edge regardless. So the system
        // status bar (with the notch) and the 3-button nav bar are drawn by
        // Android OUTSIDE this Compose tree entirely, in their own opaque
        // bars - `DeckBezel` never has the option of drawing under either one
        // and needs no `windowInsetsPadding` of its own to stay clear of them.
        DeckBezel(Modifier.fillMaxSize()) {
        Scaffold(
            // Explicit fillMaxSize (2026-08-14 fix, coordinator-reported defect): without this,
            // Scaffold - given only BOUNDED/loose constraints by DeckBezel's Box, since Box does
            // not force a child to fill unless the child asks to - sized itself by its own content
            // (bottomBar height + NavHost's wrapped height) rather than by the space DeckBezel
            // actually gave it, so the bottomBar (AssistantStrip + LegionHardKeyRow) rendered at
            // its own natural height flush against the OUTER Box's bottom edge, past DeckBezel's
            // 12dp bottom content padding entirely. Confirmed on-device: the hard-key row's own
            // opaque background was painting directly over the bezel's bottom line (drawn earlier
            // in DeckBezel's modifier chain, so it sits BEHIND anything Scaffold draws), reading as
            // the frame passing behind the keys. Forcing Scaffold to fillMaxSize makes it occupy
            // EXACTLY the constraints DeckBezel's padding already computed, so its bottomBar is
            // placed relative to that padded box, not the unpadded one.
            modifier = Modifier.fillMaxSize(),
            // AssistantStrip sits ABOVE the hard-key row inside this one
            // slot, rather than in the Scaffold's main `content` lambda - the
            // strip occupies zero space when the assistant is off (see its
            // own doc), and a Scaffold's `content` padding is sized for a
            // bottomBar whose height doesn't change; anchoring the strip to
            // the bottomBar slot instead keeps that padding correct in both
            // states. Assistant is still NOT a tab (ticket 07 resolution §5)
            // - nothing here adds a NavHost destination or changes the back
            // stack.
            //
            // Renders nothing at all on DRIVING (ticket 20) - same "occupies
            // zero space" shape AssistantStrip already uses for its own off
            // state, applied to the whole bottomBar slot rather than one row
            // inside it.
            bottomBar = {
                if (!isDrivingMode) {
                    Column {
                        AssistantStrip(onOpenSettings = {
                            navController.navigate(LegionRoute.SETTINGS) { launchSingleTop = true }
                        })
                        LegionHardKeyRow(navController)
                    }
                }
            },
        ) { innerPadding ->
            // The StatusLine is mounted HERE, once, above the NavHost - not
            // inside any one destination's own composable - so every screen
            // in the NavHost below shows it (ticket 13's build brief) without
            // any of the nine data-surface screen files changing. The Column
            // absorbs Scaffold's own innerPadding (system bars, the bottom
            // bar's measured height) exactly as the bare NavHost used to;
            // NavHost itself now just weights to fill what's left under the
            // status line instead of taking the whole padded area.
            //
            // Skipped on DRIVING (ticket 20 build brief item 2: "full-bleed
            // ground color, NO status line") - innerPadding still applies
            // (with an empty bottomBar it collapses to just the system bars),
            // so DrivingModeScreen still respects the status/nav bar insets,
            // it just never draws the SYNC/OBD/KEY/clock row above itself.
            Column(Modifier.padding(innerPadding).fillMaxSize()) {
                // The SETUP stamp is the app's only way into settings/ - see StatusLine's own
                // doc for the closed loop it breaks. Absent on DRIVING along with the rest of
                // the status line, which is correct: nothing about driving mode should invite
                // you into a settings tree.
                if (!isDrivingMode) {
                    StatusLine(
                        left = shellStatus.parts.left,
                        clock = clock,
                        onOpenSettings = {
                            navController.navigate(LegionRoute.SETTINGS) { launchSingleTop = true }
                        },
                        // Ticket 04 build section 3: KEY survives an alarm, riding alongside the
                        // alarm pill instead of folding into [left].
                        keySegment = shellStatus.parts.keySegment,
                        alarmCount = shellStatus.alarmCount,
                        // Ticket 04 answer §6: "tapping the segment navigates to TODAY" - the
                        // ALERTS pane there lists every alarm, not just this one; see
                        // [ShellStatus]'s own doc for why Money is not the target.
                        onOpenAlarm = { navController.navigate(LegionRoute.TODAY) { launchSingleTop = true } },
                        // Ticket 07 answer §1, "the cursor yields": solid, not blinking, for
                        // exactly as long as FLEET's own uplink sweep is genuinely running -
                        // see [fleetSweepActive]'s own doc above for how that boolean gets here -
                        // OR (ticket 04 answer §8's precedence stack: "while an alarm pane is on
                        // screen the shell's cursor stops") while an alarm is live anywhere in the
                        // app, not just on the surface currently in view.
                        cursorSolid = fleetSweepActive || shellStatus.alarmCount > 0,
                    )
                }
                NavHost(
                    navController = navController,
                    // Today is the start destination (2026-08-07 brief) - was FLEET
                    // under ticket 07's original four-tab shape. See LegionRoute's
                    // doc comment for the full before/after route map.
                    startDestination = LegionRoute.TODAY,
                    modifier = Modifier.weight(1f),
                ) {
            composable(LegionRoute.TODAY) {
                TodayScreen(
                    onOpenNotes = {
                        navController.navigate(LegionRoute.NOTES) { launchSingleTop = true }
                    },
                    onOpenCategory = { category ->
                        pendingMoneyCategory = category
                        pendingMoneyCategoryNonce++
                        navController.navigate(LegionRoute.MONEY) { launchSingleTop = true }
                    },
                    // Ticket 06 answer #4: every home pane taps through to its module.
                    // Wired at the ticket-15 merge; the build agent stopped at the
                    // screen boundary and named this gap rather than editing here.
                    onOpenBody = {
                        navController.navigate(LegionRoute.BODY) { launchSingleTop = true }
                    },
                    onOpenFleet = {
                        navController.navigate(LegionRoute.FLEET) { launchSingleTop = true }
                    },
                    // Ticket 16: ALERTS' "no Gemini key" advisory row - the same
                    // settings/key screen KeyScreen already renders, reached everywhere
                    // else in the app only through Settings' SETUP stamp.
                    onOpenKeySettings = {
                        navController.navigate(LegionRoute.SETTINGS_KEY) { launchSingleTop = true }
                    },
                )
            }

            composable(LegionRoute.BODY) {
                BodyScreen()
            }

            composable(LegionRoute.NOTES) {
                NotesScreen(openItemId = openItemId, openItemNonce = openItemNonce)
            }

            composable(LegionRoute.FLEET) {
                // Ticket 18: FLEET absorbed TELEMETRY as an in-screen drilldown off the
                // UPLINK panel (ticket 09 answer §1) - FleetScreen no longer takes an
                // onOpenTelemetry callback at all, see FLEET_TELEMETRY's own comment below
                // for where the old nav entry point now lands.
                FleetScreen(
                    onOpenPlaces = { navController.navigate(LegionRoute.FLEET_PLACES) },
                    onOpenCars = { navController.navigate(LegionRoute.FLEET_CARS) },
                    // Ticket 20: the UPLINK panel's DRIVE MODE row, inert since ticket 18,
                    // gets its click wired here - ticket 11 answer §1's OFFER, never auto.
                    onOpenDrivingMode = { navController.navigate(LegionRoute.DRIVING) { launchSingleTop = true } },
                    // Ticket 07: feeds [fleetSweepActive] above, which [StatusLine]'s
                    // `cursorSolid` reads.
                    onSweepActiveChanged = { fleetSweepActive = it },
                )
            }
            // Ticket 20: full-bleed, no shell chrome (see isDrivingMode above) - a plain
            // popBackStack covers both exit paths DrivingModeScreen itself drives (the
            // EXIT key, and the automatic exit when ObdBluetoothManager.isConnected drops).
            composable(LegionRoute.DRIVING) {
                DrivingModeScreen(onExit = { navController.popBackStack() })
            }
            composable(LegionRoute.FLEET_PLACES) {
                SavedPlacesScreen(onBack = { navController.popBackStack() })
            }
            composable(LegionRoute.FLEET_CARS) {
                CarsScreen(onBack = { navController.popBackStack() })
            }
            // Ticket 18: FLEET's UPLINK panel now opens this exact composable as an
            // in-screen drilldown (no nav hop) - see ui/FleetScreen.kt's file doc. This route
            // stays wired to the SAME [TelemetryScreen] rather than being deleted, so an old
            // EXTRA_ROUTE deep link (grep-confirmed 2026-08-08: none exist today, but the
            // route itself is public API on MainActivity per its own doc comment) still lands
            // on real content instead of a 404. onBack pops the nav stack here (this is a
            // route, not the in-screen drilldown's `onBack = { drilldown = null }`).
            composable(LegionRoute.FLEET_TELEMETRY) {
                TelemetryScreen(onBack = { navController.popBackStack() })
            }

            composable(LegionRoute.MONEY) {
                LedgerScreen(
                    onOpenImport = { navController.navigate(LegionRoute.MONEY_IMPORT) },
                    // The spend gate (ticket 08 Part 6 item 3) routes here when no
                    // Gemini key is stored, rather than failing silently.
                    onOpenKeySettings = { navController.navigate(LegionRoute.SETTINGS_KEY) },
                    // A grocery receipt is a purchase (2026-08-07 brief) - the
                    // pantry read screen moved under Money as a reachable
                    // sub-route rather than staying its own tab.
                    onOpenGroceries = { navController.navigate(LegionRoute.MONEY_PANTRY) },
                    openCategory = pendingMoneyCategory,
                    openCategoryNonce = pendingMoneyCategoryNonce,
                    onCategoryDrilldownConsumed = { pendingMoneyCategoryNonce = 0 },
                )
            }
            composable(LegionRoute.MONEY_IMPORT) {
                LedgerImportScreen(onBack = { navController.popBackStack() })
            }

            composable(LegionRoute.MONEY_PANTRY) {
                PantryScreen(onOpenImport = { navController.navigate(LegionRoute.MONEY_PANTRY_IMPORT) })
            }
            composable(LegionRoute.MONEY_PANTRY_IMPORT) {
                PantryImportScreen(onBack = { navController.popBackStack() })
            }

            composable(LegionRoute.SETTINGS) {
                SettingsScreen(
                    onOpenKeyScreen = { navController.navigate(LegionRoute.SETTINGS_KEY) },
                    onOpenCompanions = { navController.navigate(LegionRoute.SETTINGS_COMPANIONS) },
                    onOpenGoogleAccess = { navController.navigate(LegionRoute.SETTINGS_GOOGLE) },
                    onOpenSpotify = { navController.navigate(LegionRoute.SETTINGS_SPOTIFY) },
                    onOpenCarProbe = { navController.navigate(LegionRoute.SETTINGS_CAR_PROBE) },
                    onOpenPlaybooks = { navController.navigate(LegionRoute.SETTINGS_PLAYBOOKS) },
                    onOpenMemory = { navController.navigate(LegionRoute.SETTINGS_MEMORY) },
                )
            }
            composable(LegionRoute.SETTINGS_KEY) {
                KeyScreen(onBack = { navController.popBackStack() })
            }
            composable(LegionRoute.SETTINGS_COMPANIONS) {
                CompanionsScreen(onBack = { navController.popBackStack() })
            }
            // The GOOGLE row's destination (ticket 12) - one place to read the status of all
            // three Google grants. Drive's own action still opens SETTINGS_DRIVE_SYNC, which
            // keeps owning the actual connect/disconnect/backup flow.
            composable(LegionRoute.SETTINGS_GOOGLE) {
                GoogleAccessScreen(
                    onBack = { navController.popBackStack() },
                    onOpenDriveSync = { navController.navigate(LegionRoute.SETTINGS_DRIVE_SYNC) },
                )
            }
            composable(LegionRoute.SETTINGS_DRIVE_SYNC) {
                DriveSyncScreen(onBack = { navController.popBackStack() })
            }
            // Android Auto probe harness readout (`.scratch/android-auto/map.md` wave 1) -
            // see CarProbeScreen's own doc for why this exists at all.
            composable(LegionRoute.SETTINGS_CAR_PROBE) {
                CarProbeScreen(onBack = { navController.popBackStack() })
            }
            // Playbook/memory build (2026-08-18): both are single-screen, no sub-routes of their
            // own - the list-to-editor drill-down inside PlaybookScreen is internal Compose state,
            // see LegionRoute.SETTINGS_PLAYBOOKS's own doc comment.
            composable(LegionRoute.SETTINGS_PLAYBOOKS) {
                PlaybookScreen(onBack = { navController.popBackStack() })
            }
            composable(LegionRoute.SETTINGS_MEMORY) {
                MemoryScreen(onBack = { navController.popBackStack() })
            }
            // authOk/authNonce carry the browser round trip's outcome down from the exchange
            // effect above - see its own comment for why the exchange cannot live in this screen.
            composable(LegionRoute.SETTINGS_SPOTIFY) {
                SpotifyScreen(
                    onBack = { navController.popBackStack() },
                    authOk = spotifyAuthOk,
                    authNonce = spotifyAuthNonce,
                )
            }
                }
            }
        }
        } // closes DeckBezel's content lambda opened above the Scaffold call - the intervening
          // ~200 lines are the unchanged Scaffold/NavHost tree, left at their original indent
          // rather than re-flowed a level deeper for this diff.

        // Drawn LAST inside the outer Box (see its own comment above) so it
        // paints over the Scaffold - bottom bar, status line, and whatever
        // destination is currently showing - rather than taking a slot in
        // the layout flow.
        // Above the Scaffold so it paints over the bottom bar and the status
        // line rather than taking a slot in the layout flow. It used to be
        // ordered against BootOverlay; boot was dropped 2026-08-14 and this is
        // now the only overlay.
        GlanceCardOverlay()
    }
}

/**
 * Global StatusLine's left segment (cyberdeck-ui ticket 13): `SYNC`, `OBD`,
 * `KEY`, each a worded state, never colour-only (CLAUDE.md §4/§7).
 *
 * All three reads are cheap, synchronous, and on-device - no network call is
 * made here, matching the ticket's "read-only, no network ping" instruction:
 *  - **SYNC** reads [SyncCapability.syncAvailable] - Play Services present
 *    AND the driver has connected their own Drive. This is a CONNECTED/NOT
 *    CONNECTED signal, not a last-sync-succeeded signal - [SyncEngine] has no
 *    persisted "last sync outcome" today (`lastAutoSyncAt` is an in-memory,
 *    private, process-lifetime volatile, not a state anything durable can
 *    read), and inventing a stronger claim than "sync is connected" here
 *    would violate the same worded-truth discipline this line exists to
 *    surface. See this ticket's report for the follow-up this leaves open.
 *  - **OBD** reads [ObdBluetoothManager.isConnected] directly - a plain
 *    Boolean, not a Flow, so this function is re-invoked by the poll in
 *    [LegionShell] rather than observed.
 *  - **KEY** reads [GeminiKeyProvider.hasKey] - the same process-cached
 *    check the assistant and every LLM call path already uses; no key
 *    material is read or logged, only presence.
 */
private fun shellStatusLine(context: Context): ShellStatusLineParts {
    val sync = SyncCapability.syncAvailable(context)
    val obd = ObdBluetoothManager.isConnected
    val key = GeminiKeyProvider.hasKey()
    return formatShellStatusLine(sync, obd, key)
}

/**
 * The two segments [StatusLine] needs (mission-control ticket 04 build, section 3 of the brief):
 * `left` carries `SYNC ... OBD ...`, `keySegment` carries `KEY ...` on its own - the split ticket
 * 04 answer §6 requires so an ALARM segment can replace [left] while [keySegment] survives next to
 * it ("while an alarm is present the segment replaces SYNC and OBD, and KEY survives").
 */
data class ShellStatusLineParts(val left: String, val keySegment: String)

/**
 * The pure half of [shellStatusLine] - three already-resolved booleans in, two formatted strings
 * out, no [Context] read, so this is the piece that is actually unit-testable without an Android
 * runtime (see `ShellStatusLineTest.kt`). [shellStatusLine] itself stays impure on purpose: it is
 * the one place that reads [SyncCapability]/[ObdBluetoothManager]/[GeminiKeyProvider], and this
 * function's whole job is to not need to know how those three booleans were produced.
 */
internal fun formatShellStatusLine(syncOn: Boolean, obdConnected: Boolean, keyArmed: Boolean): ShellStatusLineParts {
    val sync = if (syncOn) "ON" else "OFF"
    val obd = if (obdConnected) "LINK" else "NO LINK"
    val key = if (keyArmed) "ARMED" else "NOT SET"
    return ShellStatusLineParts(left = "SYNC $sync   OBD $obd", keySegment = "KEY $key")
}

/**
 * [LegionShell]'s combined status-line/alarm poll state (mission-control ticket 04 build, section
 * 4: "fold the quarantine count into the existing STATUS_POLL_MS poll... do not add a second
 * timer"). [alarmCount] is
 * [com.kevin.legion.ledger.LedgerController.quarantinedCount] - see that function's own doc, and
 * [com.kevin.legion.data.local.IngestedFileDao.countQuarantined]'s, for why this is the ALARM
 * tier's only source and why an active DTC is deliberately not a second one.
 */
private data class ShellStatus(val parts: ShellStatusLineParts, val alarmCount: Int)

/** [LegionShell]'s StatusLine left-segment poll interval - see [shellStatusLine]'s doc for why this is a poll, not a push. */
private const val STATUS_POLL_MS = 4_000L

/** [LegionShell]'s clock poll interval - once a minute, per this ticket's build brief, not once a second. */
private const val CLOCK_POLL_MS = 60_000L

/**
 * The five deck hard-keys (cyberdeck-ui ticket 05's Answer: "Bottom bar
 * reskinned as five physical hard-keys: HOME / BIO / LOG / FLEET / CRED").
 * [LegionRoute]'s constants and [LegionRoute.label] are UNCHANGED - this is
 * presentation-only relabeling of five of the six routes in
 * [LegionRoute.TOP_LEVEL] (all but SETTINGS), so nothing about navigation,
 * deep links, or the back stack moves. SETTINGS deliberately has no key here:
 * ticket 05's Answer -
 * "Utility screens stay reachable through the existing settings route, no
 * bespoke key" - it stays reachable from [AssistantStrip]'s settings hop and
 * anywhere else that already navigates there.
 *
 * Order is the hard-key sequence from the Answer, not [LegionRoute.TOP_LEVEL]'s
 * bottom-nav order (which keeps Settings in its list for [LegionRoute.topLevelOf]'s
 * prefix matching elsewhere).
 */
private val HARD_KEYS = listOf(
    LegionRoute.TODAY to "HOME",
    LegionRoute.BODY to "BIO",
    LegionRoute.NOTES to "LOG",
    LegionRoute.FLEET to "FLEET",
    LegionRoute.MONEY to "CRED",
)

/**
 * The deck hard-key row (cyberdeck-ui ticket 05's Answer), replacing the M3
 * `NavigationBar`/`NavigationBarItem` presentation this function (formerly
 * `LegionBottomBar`) used to be. Full-width equal flex, five keys, stencil
 * caps (Type.kt's `labelLarge`), 1px [LegionSemantics.ruleFaint] separators
 * between keys, a 2px [LegionSemantics.rule] edge rule across the top of the
 * whole row, and the active key INVERTED - amber fill, ground-colour text
 * (ticket 05: "active key inverts to amber"). Inactive keys read in
 * [LegionSemantics.faint].
 *
 * All navigation wiring is UNCHANGED from the old `NavigationBarItem` version:
 * same tap-to-navigate-with-popUpTo-to-start-destination behaviour, same
 * [LegionRoute.topLevelOf] selection derivation (so a sub-route like
 * `fleet/places` still lights the FLEET key), same back-stack shape. Only the
 * presentation changed.
 */
@Composable
private fun LegionHardKeyRow(navController: NavHostController) {
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    // The TAB the current route sits under, not the route itself - a
    // sub-route like `settings/key` keeps Settings lit. See
    // LegionRoute.topLevelOf. (Settings has no hard key of its own, but
    // topLevelOf is still used here for FLEET's own sub-routes - fleet/places,
    // fleet/cars, fleet/telemetry - to keep the FLEET key lit under them.)
    val selectedTab = LegionRoute.topLevelOf(currentRoute)
    val sem = LocalLegionSemantics.current
    val density = LocalDensity.current
    val edgeStroke = with(density) { 2.dp.toPx() }
    val sepStroke = with(density) { 1.dp.toPx() }

    Row(
        Modifier
            .fillMaxWidth()
            .height(HARD_KEY_ROW_HEIGHT)
            .background(MaterialTheme.colorScheme.surface)
            // The 2px edge rule across the TOP of the whole row (ticket 05) -
            // drawn on the Row itself, not per-key, so it reads as one panel
            // seam rather than five separate top borders.
            .drawBehind {
                drawLine(sem.rule, Offset(0f, 0f), Offset(size.width, 0f), edgeStroke)
            },
    ) {
        HARD_KEYS.forEachIndexed { index, (route, keyLabel) ->
            val active = selectedTab == route
            val bg = if (active) MaterialTheme.colorScheme.primary else Color.Transparent
            val fg = if (active) MaterialTheme.colorScheme.onPrimary else sem.faint
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .background(bg)
                    // 1px separators BETWEEN keys only - no separator after
                    // the last key, which would just double the row's own
                    // edge/border and isn't what "between keys" asked for.
                    .let { base ->
                        if (index < HARD_KEYS.lastIndex) {
                            base.drawBehind {
                                drawLine(sem.ruleFaint, Offset(size.width, 0f), Offset(size.width, size.height), sepStroke)
                            }
                        } else base
                    }
                    .clickable {
                        if (currentRoute != route) {
                            navController.navigate(route) {
                                popUpTo(navController.graph.findStartDestination().id) {
                                    saveState = false
                                }
                                launchSingleTop = true
                            }
                        }
                    },
                contentAlignment = Alignment.Center,
            ) {
                // Stencil caps per Type.kt's label style (labelLarge is the
                // tracked, bold-medium caps role every other header in the
                // deck already reads through) - .uppercase() explicit per the
                // repo's "callers format case, styles don't" convention (see
                // DeckPanels.kt), even though every HARD_KEYS label is
                // already upper.
                Text(keyLabel.uppercase(), style = MaterialTheme.typography.labelLarge, color = fg)
            }
        }
    }
}

/** The hard-key row's fixed height - matches M3 `NavigationBar`'s default so this ticket's swap doesn't change the Scaffold's measured bottomBar footprint. */
private val HARD_KEY_ROW_HEIGHT = 56.dp
