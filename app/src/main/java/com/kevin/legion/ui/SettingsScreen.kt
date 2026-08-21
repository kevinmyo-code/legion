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
import com.kevin.legion.advisor.PlaybookStore
import com.kevin.legion.advisor.PrimingTopic
import com.kevin.legion.ai.CompanionProfile
import com.kevin.legion.ai.CompanionProfileStore
import com.kevin.legion.ai.GeminiKeyProvider
import com.kevin.legion.ai.personaFor
import com.kevin.legion.data.local.CarDatabase
import com.kevin.legion.ledger.LedgerController
import com.kevin.legion.media.NowPlayingController
import com.kevin.legion.media.SpotifyWebApi
import com.kevin.legion.service.AssistantIgnition
import com.kevin.legion.service.DebugSettings
import com.kevin.legion.service.ProactivePreferences
import com.kevin.legion.service.WakeWordEngine
import com.kevin.legion.service.WakeWordPreferences
import com.kevin.legion.sync.SyncCapability
import com.kevin.legion.ui.media.MediaTransportAccessBanner
import com.kevin.legion.ui.spotify.SpotifyConnectResolver
import com.kevin.legion.ui.theme.LegionType
import com.kevin.legion.ui.theme.LocalLegionSemantics
import com.kevin.legion.util.Temp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
/** Shared with `settings/memory`'s own MEMORY_LIMIT - kept as a separate named constant here
 * rather than importing that private one, since this screen only needs it to keep its own
 * headline count from ever exceeding what the destination screen can actually show. */
private const val MEMORY_SETTINGS_SCAN = 200

@Composable
fun SettingsScreen(
    onOpenKeyScreen: () -> Unit,
    onOpenCompanions: () -> Unit,
    onOpenGoogleAccess: () -> Unit,
    onOpenSpotify: () -> Unit,
    onOpenCarProbe: () -> Unit,
    onOpenPlaybooks: () -> Unit,
    onOpenMemory: () -> Unit,
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
    // The proactive master switch (`.scratch/proactive-mode/issues/01-one-gate-not-three.md`) -
    // stored inverted (ProactivePreferences.muted) but shown as "Proactive speech" so the row reads
    // as what it does, not as a double negative.
    var proactiveOn by remember { mutableStateOf(!ProactivePreferences.isMuted(context)) }
    // `.scratch/wake-word/issues/02-the-settings-toggle.md`: WakeWordPreferences.setEnabled had
    // zero callers, so the engine could never start. This row's handler is now its only writer.
    var wakeWordOn by remember { mutableStateOf(WakeWordPreferences.isEnabled(context)) }
    var temperatureUnit by remember { mutableStateOf(Temp.unit(context)) }
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

    // Playbook/memory ticket (2026-08-18): counts for the two new rows below, both cheap enough
    // to re-read on every ON_RESUME the same way the other five status reads on this screen do.
    var editedPlaybookCount by remember { mutableStateOf(0) }
    var storedMemoryCount by remember { mutableStateOf(0) }

    suspend fun reloadActiveProfile() {
        val profile = CompanionProfileStore.activeProfile(context)
        activeName = profile?.assistantName
        activeBlurb = profile?.let { personaFor(it.persona).blurb }
    }

    // [PlaybookStore] is raw blocking file IO (its own doc comment), so the read is wrapped in
    // Dispatchers.IO here same as every other caller must. The memory counts below are Room
    // suspend DAO calls, which already dispatch off this thread on their own - no wrap needed,
    // same posture ai/AriaBrain.kt's own memoryDao/companionMemoryDao calls take.
    //
    // Both counts are capped at MEMORY_SETTINGS_SCAN - `settings/memory`'s own screen fetches with
    // the same limit, so this status line can never claim a bigger number than that screen can
    // actually show, rather than running a second, uncapped query just to headline a count.
    suspend fun reloadPlaybookAndMemoryStatus() {
        editedPlaybookCount = withContext(Dispatchers.IO) {
            PrimingTopic.entries.count { PlaybookStore.isCustomised(context, it) }
        }
        val db = CarDatabase.getDatabase(context)
        storedMemoryCount = db.memoryDao().getRecent(MEMORY_SETTINGS_SCAN).size +
            db.companionMemoryDao().allRecent(MEMORY_SETTINGS_SCAN).size
    }

    LaunchedEffect(reloadNonce) {
        reloadActiveProfile()
        reloadPlaybookAndMemoryStatus()
    }
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
        temperatureUnit = Temp.unit(context)
        proactiveOn = !ProactivePreferences.isMuted(context)
        wakeWordOn = WakeWordPreferences.isEnabled(context)
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
            // Mission-control playbook/memory build (2026-08-18): the doctrine the assistant
            // primes itself with before it answers, and what it has actually remembered - both
            // previously invisible and uneditable outside a rebuild.
            SettingsNavRow(
                label = "Playbooks",
                status = if (editedPlaybookCount > 0) {
                    "$editedPlaybookCount of ${PrimingTopic.entries.size} edited"
                } else {
                    "All ${PrimingTopic.entries.size} on the shipped default"
                },
                onClick = onOpenPlaybooks,
            )

            Spacer(Modifier.height(8.dp))
            SettingsNavRow(
                label = "Memory",
                status = if (storedMemoryCount > 0) {
                    "$storedMemoryCount memories stored"
                } else {
                    "Nothing remembered yet"
                },
                onClick = onOpenMemory,
            )

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

            // Ticket `.scratch/proactive-mode/issues/01-one-gate-not-three.md`: the master kill
            // switch this whole effort depends on, wired to a Settings row for the first time -
            // ProactivePreferences.setMuted had zero callers anywhere before this.
            Spacer(Modifier.height(8.dp))
            ProactiveSpeechRow(
                proactiveOn = proactiveOn,
                onToggle = { on ->
                    ProactivePreferences.setMuted(context, !on)
                    proactiveOn = on
                },
            )

            // `.scratch/wake-word/issues/02-the-settings-toggle.md`. The handler is the ONLY writer
            // of WakeWordPreferences, matching AssistantIgnition's single-writer discipline.
            //
            // It calls start/stop, NOT refresh. Caught on the phone, 2026-08-20: refresh() opens
            // with `val loadedModel = model ?: return`, so it is a no-op unless the engine is
            // ALREADY running - it rebuilds a live grammar, it does not ignite one. Wiring the
            // toggle to it wrote the preference and started nothing, and the row then read "On"
            // over a dead engine, which is the exact defect this ticket's verification step exists
            // to catch. start() is idempotent (`if (scope != null) return`) and reads the
            // preference itself, so it is safe from here.
            Spacer(Modifier.height(8.dp))
            WakeWordRow(
                enabled = wakeWordOn,
                companionName = activeName,
                onToggle = { on ->
                    WakeWordPreferences.setEnabled(context, on)
                    wakeWordOn = on
                    if (on) WakeWordEngine.start(context) else WakeWordEngine.stop()
                },
            )

            // Ticket 07, amended 2026-08-18: the unit is a setting, not a fixed Celsius. Every
            // temperature surface (UPLINK, DRIVE MODE, FAULTS, TELEMETRY, and the assistant's own
            // voice) reads this same value through com.kevin.legion.util.Temp.
            Spacer(Modifier.height(8.dp))
            TemperatureUnitRow(
                unit = temperatureUnit,
                onSelect = { unit ->
                    Temp.setUnit(context, unit)
                    temperatureUnit = unit
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
