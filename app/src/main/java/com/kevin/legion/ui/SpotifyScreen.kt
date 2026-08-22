package com.kevin.legion.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.kevin.legion.BuildConfig
import com.kevin.legion.ai.CompanionProfile
import com.kevin.legion.media.SpotifyController
import com.kevin.legion.media.SpotifyWebApi
import com.kevin.legion.ui.common.DeckButton
import com.kevin.legion.ui.common.DeckScreenHeader
import com.kevin.legion.ui.spotify.SpotifyAuthorizeRow
import com.kevin.legion.ui.spotify.SpotifyClientIdRow
import com.kevin.legion.ui.spotify.SpotifyConnectResolver
import com.kevin.legion.ui.spotify.SpotifyRegistrationRow
import com.kevin.legion.ui.spotify.SpotifySearchTestRow
import com.kevin.legion.ui.spotify.SpotifySetupStatusRow
import com.kevin.legion.util.AppSigning
import com.kevin.legion.ui.theme.LegionType
import com.kevin.legion.ui.theme.LocalLegionSemantics
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * `settings/spotify` - the "Connect Spotify" screen. Before this screen
 * existed, the entire Spotify tier was structurally unreachable (traced
 * 2026-08-12, the same shape as [DriveSyncScreen]'s own 2026-08-03 finding):
 * [CompanionProfile.saveSpotifyClientId], [SpotifyController.connect] and
 * [SpotifyWebApi.beginAuthorization] all had zero callers, so
 * [CompanionProfile.spotifyClientId] could never be non-blank,
 * `SpotifyController.startConnect` always bailed at its blank-ID guard, and
 * [com.kevin.legion.service.LiveToolbox]'s `play_music` answered "Spotify
 * isn't connected - connect your Spotify account in Setup" pointing at a Setup
 * screen that did not exist. This screen is that missing entry point; nothing
 * in `media/` changed except the addition of [SpotifyController.isInstalled].
 *
 * **Two grants, sequenced.** See [SpotifyConnectResolver]'s doc for why they
 * are separate. In order: save a client ID, approve Web API access in a
 * browser (PKCE), link App Remote to the installed Spotify app. The screen
 * only ever offers the one step the current [SpotifyConnectResolver.Stage]
 * says is next.
 *
 * **The browser round trip lands in [MainActivity], not here.** The redirect
 * comes back as an `ACTION_VIEW` intent on the app-fixed
 * [SpotifyController.REDIRECT_URI] (manifest intent-filter, `singleTask`), so
 * it arrives at `MainActivity.onNewIntent` - which cannot assume this
 * composable is still in the back stack, since the browser hop can outlive the
 * process. [LegionShell] performs [SpotifyWebApi.handleRedirect] above the
 * `NavHost`, navigates here, and hands the outcome down as [authOk]/[authNonce].
 * That is the same shape the notes deep link already uses, for the same reason.
 *
 * **Every flag is re-read on `ON_RESUME`**, not just at composition: this
 * screen is left and returned to by construction (the browser hop, and
 * Spotify's own consent sheet for the player link), which makes resume the
 * exact moment its state is stale.
 */
@Composable
fun SpotifyScreen(
    onBack: () -> Unit,
    authOk: Boolean? = null,
    authNonce: Int = 0,
    // Command-center ticket 04's entry point: the media control panel is a drill-down from
    // wherever a driver already goes to manage Spotify, per that ticket's own brief. Default
    // no-op so this stays source-compatible with any preview/test caller that doesn't wire it.
    onOpenMedia: () -> Unit = {},
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current

    var clientIdText by remember { mutableStateOf(CompanionProfile.spotifyClientId(context)) }
    var hasClientId by remember { mutableStateOf(CompanionProfile.hasSpotifyClientId(context)) }
    var isAuthorized by remember { mutableStateOf(SpotifyWebApi.isAuthorized(context)) }
    // See SpotifyConnectResolver.Stage.NEEDS_REAUTHORIZATION's own doc: distinguishes "never
    // connected" from "connected once, but 2026-08-18's SCOPES widening invalidated the grant" -
    // this is the flag that makes the Setup screen say "needs re-approving" instead of showing
    // the same "Not set up" a driver who never connected at all would see.
    var hasStaleGrant by remember { mutableStateOf(SpotifyWebApi.hasStaleGrant(context)) }
    var appInstalled by remember { mutableStateOf(SpotifyController.isInstalled(context)) }
    var playerLinked by remember { mutableStateOf(SpotifyController.isConnected) }
    var working by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var messageIsError by remember { mutableStateOf(false) }
    // Read once, not on every resume: the signing certificate of the running APK cannot
    // change without the process being replaced.
    val signingSha1 = remember { AppSigning.sha1(context) }
    // (isError, line) from the last search test - see SpotifySearchTestRow for why this
    // diagnostic lives in the UI rather than in logcat.
    var testResult by remember { mutableStateOf<Pair<Boolean, String>?>(null) }

    fun refresh() {
        hasClientId = CompanionProfile.hasSpotifyClientId(context)
        isAuthorized = SpotifyWebApi.isAuthorized(context)
        hasStaleGrant = SpotifyWebApi.hasStaleGrant(context)
        appInstalled = SpotifyController.isInstalled(context)
        playerLinked = SpotifyController.isConnected
    }

    // Cheap, synchronous, on-device reads - the same "cheap, not a poll" shape
    // DriveSyncScreen and SettingsScreen use. `working` is cleared here too:
    // AUTHORIZE hands off to a browser and never returns through its own
    // callback, so without this a cancelled approval would leave the button
    // disabled with no way back short of leaving the screen.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        refresh()
        working = false
    }

    // The browser round trip's outcome, handed down from LegionShell (see the
    // file doc). Keyed on the nonce, not the Boolean, so two consecutive
    // failures still both report. authOk null = nothing happened, which is the
    // ordinary case every time this screen is opened by hand.
    LaunchedEffect(authNonce) {
        when (authOk) {
            null -> Unit
            true -> {
                refresh()
                message = SpotifyConnectResolver.AUTHORIZED_MESSAGE
                messageIsError = false
                // Approval just happened, the driver is deliberately in setup,
                // and App Remote's grant is a SEPARATE consent - so this is the
                // one moment it is appropriate to pop Spotify's own sheet
                // unprompted. Anywhere else this would be connectSilently.
                SpotifyController.connect(context)
            }
            false -> {
                message = SpotifyConnectResolver.AUTH_DENIED_MESSAGE
                messageIsError = true
            }
        }
    }

    fun save() {
        when (SpotifyConnectResolver.checkClientId(clientIdText)) {
            SpotifyConnectResolver.ClientIdCheck.BLANK -> {
                message = SpotifyConnectResolver.BLANK_MESSAGE
                messageIsError = true
            }
            SpotifyConnectResolver.ClientIdCheck.UNEXPECTED_FORMAT -> {
                CompanionProfile.saveSpotifyClientId(context, clientIdText)
                refresh()
                message = SpotifyConnectResolver.SAVED_UNEXPECTED_FORMAT_MESSAGE
                messageIsError = false
            }
            SpotifyConnectResolver.ClientIdCheck.OK -> {
                CompanionProfile.saveSpotifyClientId(context, clientIdText)
                refresh()
                message = SpotifyConnectResolver.SAVED_MESSAGE
                messageIsError = false
            }
        }
    }

    fun authorize() {
        message = null
        // beginAuthorization returns false only for "no client ID" (impossible
        // in this stage) or "no browser could handle the intent" - a real
        // outcome on a stripped device, which is why it is reported rather
        // than assumed. On true, the app leaves for the browser and the result
        // arrives via MainActivity; `working` is cleared by ON_RESUME above.
        if (SpotifyWebApi.beginAuthorization(context)) {
            working = true
        } else {
            message = SpotifyConnectResolver.NO_BROWSER_MESSAGE
            messageIsError = true
        }
    }

    fun linkPlayer() {
        message = null
        working = true
        // showAuthView = true: the driver just tapped LINK PLAYER, which is
        // exactly the explicit-request context SpotifyController.connect's doc
        // reserves the consent sheet for.
        SpotifyController.connect(context)
        // connect is fire-and-forget by design, so poll the real state once the
        // SDK has had its ~1-2s round trip rather than claiming success now.
        scope.launch {
            delay(LINK_SETTLE_MS)
            refresh()
            working = false
            if (!SpotifyController.isConnected) {
                message = SpotifyConnectResolver.PLAYER_LINK_FAILED_MESSAGE
                messageIsError = true
            }
        }
    }

    fun runSearchTest() {
        testResult = null
        working = true
        scope.launch {
            // A query with an unambiguous, always-present answer, so a NoMatch here
            // genuinely means the search is not working rather than that the phrase
            // was obscure.
            testResult = when (val outcome = SpotifyWebApi.searchTrack(context, SEARCH_TEST_QUERY)) {
                is SpotifyWebApi.SearchOutcome.Found -> false to "OK - ${outcome.uri}"
                SpotifyWebApi.SearchOutcome.NeedsAuthorization ->
                    true to "NOT AUTHORIZED - no usable token on file. Tap AUTHORIZE."
                is SpotifyWebApi.SearchOutcome.Unauthorized ->
                    true to "REJECTED (401/403) - ${outcome.detail ?: "no detail returned"}"
                SpotifyWebApi.SearchOutcome.Unreachable ->
                    true to "UNREACHABLE - could not reach Spotify."
                SpotifyWebApi.SearchOutcome.NoMatch ->
                    true to "NO MATCH - Spotify answered, with nothing for \"$SEARCH_TEST_QUERY\"."
                is SpotifyWebApi.SearchOutcome.Failed ->
                    true to "HTTP ${outcome.code} - ${outcome.detail ?: "no detail returned"}\n" +
                        "raw: ${outcome.raw ?: "(empty)"}"
            }
            working = false
        }
    }

    fun removeClientId() {
        // saveSpotifyClientId("") removes both the encrypted and plaintext slots AND clears the
        // tokens (blank differs from the stored value, so its change-detection branch fires), so
        // this cannot leave a refresh token behind for a client that no longer exists.
        CompanionProfile.saveSpotifyClientId(context, "")
        SpotifyController.disconnect()
        clientIdText = ""
        refresh()
        message = SpotifyConnectResolver.REMOVED_MESSAGE
        messageIsError = false
    }

    fun disconnect() {
        CompanionProfile.clearSpotifyTokens(context)
        SpotifyController.disconnect()
        refresh()
        message = SpotifyConnectResolver.DISCONNECTED_MESSAGE
        messageIsError = false
    }

    val stage = SpotifyConnectResolver.stage(
        hasClientId = hasClientId,
        isAuthorized = isAuthorized,
        hasStaleGrant = hasStaleGrant,
    )
    val sem = LocalLegionSemantics.current

    Surface(modifier = Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            DeckScreenHeader(title = "Spotify", onBack = onBack)

            Column(
                modifier = Modifier
                    .padding(horizontal = 4.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                Spacer(Modifier.height(8.dp))
                Text(
                    "You register your own Spotify app and paste its client ID here. Nothing ships a " +
                        "shared one and nothing goes through a server I run - this is your Spotify " +
                        "developer account, not mine.",
                    style = LegionType.stamp,
                    color = sem.faint,
                    modifier = Modifier.padding(horizontal = 12.dp),
                )

                Spacer(Modifier.height(8.dp))
                // Now-playing/transport/volume need no Spotify connection at all - see
                // MediaScreen's own doc - so this row is offered regardless of setup [stage].
                DeckButton(
                    text = "Media panel",
                    onClick = onOpenMedia,
                    modifier = Modifier.padding(horizontal = 12.dp),
                )

                Spacer(Modifier.height(8.dp))
                SpotifySetupStatusRow(stage = stage, spotifyAppInstalled = appInstalled)

                Spacer(Modifier.height(8.dp))
                SpotifyClientIdRow(
                    value = clientIdText,
                    onValueChange = { clientIdText = it },
                    onSave = ::save,
                    onRemove = ::removeClientId,
                    hasStoredId = hasClientId,
                    working = working,
                )

                Spacer(Modifier.height(8.dp))
                SpotifyRegistrationRow(
                    packageName = AppSigning.packageName(context),
                    sha1 = signingSha1,
                    redirectUri = SpotifyController.REDIRECT_URI,
                    onCopy = { label, value ->
                        clipboard.setText(AnnotatedString(value))
                        message = "$label copied."
                        messageIsError = false
                    },
                )

                Spacer(Modifier.height(8.dp))
                SpotifyAuthorizeRow(
                    stage = stage,
                    playerLinked = playerLinked,
                    working = working,
                    onAuthorize = ::authorize,
                    onLinkPlayer = ::linkPlayer,
                    onDisconnect = ::disconnect,
                )

                // Debug builds only. The row reports raw HTTP statuses and Spotify's
                // unedited error bodies - the right trade while chasing a bug on a phone
                // whose logcat swallows app output, and the wrong thing to hand a user in
                // a release build, where it reads as a developer console bolted to a
                // settings screen. BuildConfig.DEBUG is resolved at compile time, so the
                // whole branch is dropped from the release APK rather than merely hidden.
                if (BuildConfig.DEBUG) {
                    Spacer(Modifier.height(8.dp))
                    SpotifySearchTestRow(
                        enabled = stage == SpotifyConnectResolver.Stage.READY,
                        working = working,
                        resultLine = testResult?.second,
                        resultIsError = testResult?.first == true,
                        onRunTest = ::runSearchTest,
                    )
                }

                message?.let {
                    Spacer(Modifier.height(8.dp))
                    // ADVISORY (ticket 13 re-home): a connect/action result, not a failed gate.
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (messageIsError) sem.estimated else sem.faint,
                        modifier = Modifier.padding(horizontal = 12.dp),
                    )
            }

            // The status message is the last thing in the scroll container and the longest
            // strings here run to three lines, so without trailing room the final line sits
            // under the AssistantStrip and clips (observed on the A17K 2026-08-12).
            Spacer(Modifier.height(24.dp))
            }
        }
    }
}

/**
 * How long to wait before reading App Remote's real connection state after
 * [SpotifyController.connect]. That call is fire-and-forget (it hands a
 * listener to the SDK and returns), so reading `isConnected` immediately after
 * it always reports false - the same "returned true the instant the call was
 * DISPATCHED" mistake [SpotifyController.playUri]'s doc records. Long enough
 * to cover the SDK's ~1-2s handshake plus the consent sheet being dismissed.
 */
private const val LINK_SETTLE_MS = 2_500L

/** A track that unambiguously exists, so a NO MATCH result indicts the search, not the query. */
private const val SEARCH_TEST_QUERY = "mask off future"
