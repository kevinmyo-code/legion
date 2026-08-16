package com.kevin.legion.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.kevin.legion.ai.CompanionProfile
import com.kevin.legion.ai.CompanionProfileStore
import com.kevin.legion.ai.GeminiKeyProvider
import com.kevin.legion.ai.personaFor
import com.kevin.legion.ledger.LedgerController
import com.kevin.legion.media.NowPlayingController
import com.kevin.legion.media.SpotifyWebApi
import com.kevin.legion.service.AssistantIgnition
import com.kevin.legion.service.DebugSettings
import com.kevin.legion.sync.SyncCapability
import com.kevin.legion.ui.media.MediaTransportAccessBanner
import com.kevin.legion.ui.spotify.SpotifyConnectResolver
import com.kevin.legion.ui.theme.LegionType
import com.kevin.legion.ui.theme.LocalLegionSemantics
import kotlinx.coroutines.launch

/**
 * `settings` tab. Owns the assistant ignition toggle (ticket 07 resolution
 * §1) plus the way into every sub-screen under `settings/`. This is the
 * state-holder half of the state-holder/UI split
 * (`.claude/skills/compose-state-holder-ui-split`): it owns the two permission
 * launchers and talks to [AssistantIgnition]; `ui/SettingsRows.kt` is the
 * plain UI half.
 *
 * **How you get here (2026-08-12).** Via the SETUP stamp on the global
 * [com.kevin.legion.ui.common.StatusLine]. Before that stamp existed this
 * screen was unreachable on any ordinary device - see StatusLine's own doc for
 * the closed loop, and note the consequence: the Gemini key, Drive sync,
 * companions and Spotify screens all hang off this one, so none of them could
 * be opened either. cyberdeck-ui ticket 05's Answer still holds - Settings has
 * no hard key of its own and this is not one.
 *
 * **Permission order is exactly the resolution's**: POST_NOTIFICATIONS, then
 * RECORD_AUDIO, then `startForegroundService`. A refusal at either step
 * leaves the toggle off and states why - it never partially starts the
 * service. Nothing here touches ledger/pantry/fleet; they have no
 * permission gate.
 *
 * **Every row states its live status**, re-read on each `ON_RESUME` - the same
 * "cheap, not a poll" shape [LedgerScreen] and [DriveSyncScreen] use. Coming
 * BACK from a sub-screen having just set a key or connected Drive is exactly
 * the moment those need to be fresh, and all five reads are synchronous and
 * on-device (the sole suspend one, the companion profile, already had its own
 * nonce-keyed reload).
 */
@Composable
fun SettingsScreen(
    onOpenKeyScreen: () -> Unit,
    onOpenCompanions: () -> Unit,
    onOpenGoogleAccess: () -> Unit,
    onOpenSpotify: () -> Unit,
    onOpenCarProbe: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var enabled by remember { mutableStateOf(AssistantIgnition.isEnabled(context)) }
    var refusalReason by remember { mutableStateOf<String?>(null) }
    var activeName by remember { mutableStateOf<String?>(null) }
    var activeBlurb by remember { mutableStateOf<String?>(null) }
    // Bumped on ON_RESUME to key the reload below - a plain Unit key would
    // only ever fire once, and this needs to re-fire every time the user
    // comes back from settings/companions having edited or switched.
    var reloadNonce by remember { mutableStateOf(0) }

    var recallAlertsOn by remember { mutableStateOf(DebugSettings.recallAlertsEnabled(context)) }
    var hasKey by remember { mutableStateOf(GeminiKeyProvider.hasKey()) }
    var driveConnected by remember { mutableStateOf(SyncCapability.syncAvailable(context)) }
    var playServices by remember { mutableStateOf(SyncCapability.playServicesAvailable(context)) }
    var spotifyStage by remember {
        mutableStateOf(
            SpotifyConnectResolver.stage(
                hasClientId = CompanionProfile.hasSpotifyClientId(context),
                isAuthorized = SpotifyWebApi.isAuthorized(context),
            ),
        )
    }
    // Notification-listener access - the system gate media transport (pause/skip/previous)
    // needs and Gemini key/Drive/Spotify have no equivalent of. Re-read on ON_RESUME below for
    // the same reason those are: the fix is a trip to Settings and back, never a re-request
    // from inside the app.
    var hasMediaAccess by remember { mutableStateOf(NowPlayingController.hasAccess(context)) }

    suspend fun reloadActiveProfile() {
        val profile = CompanionProfileStore.activeProfile(context)
        activeName = profile?.assistantName
        activeBlurb = profile?.let { personaFor(it.persona).blurb }
    }

    LaunchedEffect(reloadNonce) { reloadActiveProfile() }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        reloadNonce++
        enabled = AssistantIgnition.isEnabled(context)
        hasKey = GeminiKeyProvider.hasKey()
        driveConnected = SyncCapability.syncAvailable(context)
        playServices = SyncCapability.playServicesAvailable(context)
        spotifyStage = SpotifyConnectResolver.stage(
            hasClientId = CompanionProfile.hasSpotifyClientId(context),
            isAuthorized = SpotifyWebApi.isAuthorized(context),
        )
        hasMediaAccess = NowPlayingController.hasAccess(context)
        recallAlertsOn = DebugSettings.recallAlertsEnabled(context)
    }

    // Step 2 of the chain: RECORD_AUDIO. Only reached once POST_NOTIFICATIONS
    // is settled (granted, or not applicable pre-Tiramisu).
    val requestMic = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            AssistantIgnition.start(context)
            enabled = true
            refusalReason = null
        } else {
            refusalReason = "Microphone permission was refused. The assistant needs it to " +
                "hear you - ledger, pantry, and fleet are unaffected."
        }
    }

    // Step 1 of the chain: POST_NOTIFICATIONS (API 33+ only - see startIgnition).
    val requestNotifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            requestMic.launch(Manifest.permission.RECORD_AUDIO)
        } else {
            refusalReason = "Notification permission was refused. Android requires it to keep " +
                "the assistant's persistent service visible while it runs - ledger, pantry, " +
                "and fleet are unaffected."
        }
    }

    fun startIgnition() {
        refusalReason = null
        val notificationsGranted = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        val micGranted = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

        when {
            !notificationsGranted -> requestNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
            !micGranted -> requestMic.launch(Manifest.permission.RECORD_AUDIO)
            else -> {
                AssistantIgnition.start(context)
                enabled = true
            }
        }
    }

    fun stopIgnition() {
        AssistantIgnition.stop(context)
        enabled = false
        refusalReason = null
    }

    val sem = LocalLegionSemantics.current

    Surface(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            Text("SETUP", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurface)
            Text(
                "Credentials live on this device and in your own accounts. Nothing goes through " +
                    "a server I run.",
                style = LegionType.stamp,
                color = sem.faint,
            )

            Spacer(Modifier.height(12.dp))
            IgnitionRow(
                enabled = enabled,
                refusalReason = refusalReason,
                onToggle = { turnOn -> if (turnOn) startIgnition() else stopIgnition() },
            )

            Spacer(Modifier.height(8.dp))
            ActiveCompanionRow(name = activeName, blurb = activeBlurb, onOpenCompanions = onOpenCompanions)

            Spacer(Modifier.height(8.dp))
            // attention on "Not set": no key means the assistant and every LLM fallback path
            // are dead, which is the one genuinely blocking state on this screen.
            SettingsNavRow(
                label = "Gemini key",
                status = if (hasKey) "Set" else "Not set - the assistant can't run without one",
                onClick = onOpenKeyScreen,
                attention = !hasKey,
            )

            Spacer(Modifier.height(8.dp))
            // One row for all three Google grants (Drive/Calendar/Gmail) - ticket 06's Answer §2:
            // "the question 'what does this app have access to in my Google account' deserves
            // exactly one place to read the answer". This headline is a cheap summary for the
            // list view only; GoogleAccessScreen behind it does the real, per-grant live probe.
            SettingsNavRow(
                label = "Google",
                status = when {
                    !playServices -> "Drive unavailable - no Play Services on this device"
                    driveConnected -> "Drive connected"
                    else -> "Drive not connected - data stays on this device"
                },
                onClick = onOpenGoogleAccess,
            )

            Spacer(Modifier.height(8.dp))
            SettingsNavRow(
                label = "Spotify",
                status = SpotifyConnectResolver.headline(spotifyStage),
                onClick = onOpenSpotify,
            )

            // Renders nothing at all once the grant is present (MediaTransportAccessBanner's own
            // doc comment) - kept right under the Spotify row because that is the other place
            // media transport shows up on this screen, and the message is explicitly about the
            // gap between the two: Spotify playback works without this grant, pause/skip do not.
            MediaTransportAccessBanner(hasAccess = hasMediaAccess)

            // Mission-control ticket 12: DebugSettings.setRecallAlerts had zero callers before
            // this row - the toggle governed AriaForegroundService.checkRecallsOnce but nothing
            // could ever turn it on. Now that the proactive push shares the on-request check's
            // identity-present gate, it has a coherent answer to give.
            Spacer(Modifier.height(8.dp))
            RecallAlertsRow(
                enabled = recallAlertsOn,
                onToggle = { on ->
                    DebugSettings.setRecallAlerts(context, on)
                    recallAlertsOn = on
                },
            )

            // Android Auto probe harness (`.scratch/android-auto/map.md` wave 1) - a debug
            // surface, not a driver feature, kept last in the nav-row list for that reason.
            Spacer(Modifier.height(8.dp))
            SettingsNavRow(
                label = "Car probe",
                status = "On-screen diagnostic log for Android Auto probes",
                onClick = onOpenCarProbe,
            )

            // Mission-control ticket 16: re-homed from CRED's own root (ticket 12's ruling - "a
            // destructive purge does not belong on a surface you open daily"). Last on the screen,
            // same reasoning the old CRED-root placement already carried: putting a not-undoable
            // action anywhere above the content it destroys invites the mis-tap it exists to make
            // deliberate.
            Spacer(Modifier.height(16.dp))
            PurgeLedgerRow(
                onPurge = {
                    scope.launch { LedgerController.purgeAll(context) }
                },
            )

            Spacer(Modifier.height(24.dp))
        }
    }
}
