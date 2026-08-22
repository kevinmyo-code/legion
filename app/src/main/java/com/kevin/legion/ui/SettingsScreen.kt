package com.kevin.legion.ui

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
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
import com.kevin.legion.location.BackgroundLocationAccess
import com.kevin.legion.location.LocationAccessState
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
import com.kevin.legion.service.ProactiveCategory
import com.kevin.legion.service.ProactiveSettings
import com.kevin.legion.service.CallActions
import com.kevin.legion.service.CallerId
import com.kevin.legion.service.PlaceCallAction
import com.kevin.legion.sitrep.SitrepModule
import com.kevin.legion.sitrep.SitrepScheduler
import com.kevin.legion.sitrep.SitrepSettings

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
    // Seeded false and corrected by ProactiveSettings.load below, rather than read from the old
    // ProactivePreferences: that key is now a MIGRATION SOURCE only (see ProactiveSettings), and
    // reading it here would show a stale answer the moment the two disagree.
    var proactiveOn by remember { mutableStateOf(true) }
    var proactiveCategories by remember {
        mutableStateOf<Map<ProactiveCategory, Boolean>>(emptyMap())
    }
    // `.scratch/wake-word/issues/02-the-settings-toggle.md`: WakeWordPreferences.setEnabled had
    // zero callers, so the engine could never start. This row's handler is now its only writer.
    var wakeWordOn by remember { mutableStateOf(WakeWordPreferences.isEnabled(context)) }
    // Ticket 22's module registry - same seeded-false-corrected-on-load shape as
    // `proactiveCategories` above, since [SitrepSettings.load] is itself a suspend Room read.
    var sitrepModules by remember { mutableStateOf<Map<SitrepModule, Boolean>>(emptyMap()) }
    var sitrepHourText by remember { mutableStateOf("") }
    var sitrepMinuteText by remember { mutableStateOf("") }
    var sitrepSendersText by remember { mutableStateOf("") }
    var sitrepScheduleStatus by remember { mutableStateOf("No schedule set - the sitrep is askable any time, but never fires on its own.") }
    // goal-plans ticket 05 - same seeded-blank-until-loaded shape as the sitrep schedule fields
    // above, and the same reason: an empty field is what tells Kevin nothing has been saved yet,
    // rather than a fake default time.
    var wellbeingHourText by remember { mutableStateOf("") }
    var wellbeingMinuteText by remember { mutableStateOf("") }
    var wellbeingScheduleStatus by remember { mutableStateOf("No schedule set - the wellbeing digest never fires on its own.") }
    var canSeeCaller by remember { mutableStateOf(false) }
    var canAnswerCalls by remember { mutableStateOf(false) }
    var canPlaceCalls by remember { mutableStateOf(false) }
    // Background location (`.scratch/location-intelligence/issues/01-background-location.md`,
    // settled decision 11) - three-state resolution, re-read on every resume same as the call
    // permissions above, because the fix for a permanently-denied background grant is a trip to
    // system Settings and back, not a re-request from inside the app.
    var locationAccess by remember { mutableStateOf(BackgroundLocationAccess.current(context)) }
    // Set only inside requestBackgroundLocation's own callback below, right after a real denial -
    // NOT recomputed on resume, because shouldShowRequestPermissionRationale reads false both
    // "never asked" and "asked and permanently denied", and recomputing it blind on every resume
    // would offer the Settings shortcut to a driver who has never even seen the background dialog
    // once. See LocationAccessRow's onGrant wiring below for where this is consumed.
    var offerLocationAppSettings by remember { mutableStateOf(false) }
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

    // Ticket 22 part F: loads the module switches plus whatever schedule is stored, formatting
    // hour/minute as plain zero-padded digits for the two [DeckTextField]s. A never-set schedule
    // (`SitrepSettings.schedule` returns null) leaves the three fields blank rather than seeding a
    // fake default time - an empty field is what tells Kevin nothing has been saved yet.
    suspend fun reloadSitrepSettings() {
        SitrepSettings.load(context)
        sitrepModules = SitrepSettings.modules.value
        val schedule = SitrepSettings.schedule(context)
        if (schedule != null) {
            sitrepHourText = schedule.hour.toString()
            sitrepMinuteText = schedule.minute.toString()
            sitrepSendersText = schedule.senders
            sitrepScheduleStatus = "Fires daily at %02d:%02d".format(schedule.hour, schedule.minute)
        } else {
            sitrepScheduleStatus = "No schedule set - the sitrep is askable any time, but never fires on its own."
        }
    }

    // goal-plans ticket 05 - same shape as [reloadSitrepSettings] above, minus the module switches
    // that domain has and this one does not.
    suspend fun reloadWellbeingDigestSettings() {
        val schedule = com.kevin.legion.wellbeing.WellbeingDigestSettings.schedule(context)
        if (schedule != null) {
            wellbeingHourText = schedule.hour.toString()
            wellbeingMinuteText = schedule.minute.toString()
            wellbeingScheduleStatus = "Fires daily at %02d:%02d".format(schedule.hour, schedule.minute)
        } else {
            wellbeingScheduleStatus = "No schedule set - the wellbeing digest never fires on its own."
        }
    }

    LaunchedEffect(reloadNonce) {
        reloadActiveProfile()
        reloadPlaybookAndMemoryStatus()
        reloadSitrepSettings()
        reloadWellbeingDigestSettings()
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
        scope.launch {
            ProactiveSettings.load(context)
            proactiveOn = ProactiveSettings.master.value
            proactiveCategories = ProactiveSettings.categories.value
        }
        wakeWordOn = WakeWordPreferences.isEnabled(context)
        // Both halves of call handling are re-read on every resume rather than cached: the
        // user may have changed either one in system Settings while away.
        canSeeCaller = CallerId.hasCallLogPermission(context) &&
            CallerId.hasContactsPermission(context)
        canAnswerCalls = CallActions.hasPermission(context)
        canPlaceCalls = PlaceCallAction.hasCallPermission(context)
        val newLocationAccess = BackgroundLocationAccess.current(context)
        // Granted wipes the "offer Settings" flag - a driver who fixed it in system Settings and
        // came back should see a clean GRANT-less row, not a stale settings shortcut.
        if (newLocationAccess == LocationAccessState.Granted) offerLocationAppSettings = false
        locationAccess = newLocationAccess
    }

    // Caller ID + voice answer/decline + place_call. Asked for as ONE dialog rather than four:
    // they are one feature to a human, and Android groups READ_PHONE_STATE/READ_CALL_LOG/
    // ANSWER_PHONE_CALLS/CALL_PHONE under PHONE anyway. CALL_PHONE joined this group 2026-08-22
    // (ticket 26) for place_call - it is what lets ACTION_CALL dial directly rather than only
    // opening the dialer. The callback ignores its own granted flags and re-reads the real state -
    // a single source of truth, same shape as TodayScreen's calendar request.
    val requestCallPermissions = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ ->
        canSeeCaller = CallerId.hasCallLogPermission(context) && CallerId.hasContactsPermission(context)
        canAnswerCalls = CallActions.hasPermission(context)
        canPlaceCalls = PlaceCallAction.hasCallPermission(context)
    }

    // Background location, second half of the two-step chain (ticket 01's rule 1: foreground must
    // be granted FIRST, in its own prompt - Android refuses to grant background otherwise). Fires
    // ONLY out of requestForegroundLocation below, never launched directly by a tap on the row.
    val requestBackgroundLocation = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        locationAccess = BackgroundLocationAccess.current(context)
        offerLocationAppSettings = if (granted) {
            false
        } else {
            // The exact test the ticket specifies: a real denial (not just "haven't asked yet")
            // where the system itself says it will not show a rationale-eligible dialog again -
            // that is Android's own signal that the ONLY way forward is system Settings.
            // findActivity() rather than `context as? Activity`: the cast is correct TODAY, because
            // LocalContext.current under MainActivity's setContent is the Activity itself and
            // LegionTheme/LegionShell wrap composition rather than the Context. But it is an
            // assumption about a chain someone could lengthen later, and its failure is SILENT -
            // a wrapped context makes this expression `null == false`, so the Settings shortcut
            // simply never appears and nothing reports why. Walking the wrapper chain removes the
            // assumption instead of documenting it.
            context.findActivity()?.shouldShowRequestPermissionRationale(
                Manifest.permission.ACCESS_BACKGROUND_LOCATION,
            ) == false
        }
    }

    // Background location, first half: fine/coarse together, same "one feature, one dialog" shape
    // as requestCallPermissions above. Chains straight into the background request the moment
    // foreground lands - a driver who just said yes to "while using the app" is mid-thought about
    // location, not somewhere else in the settings screen, so asking the follow-up immediately
    // (rather than waiting for a second tap on the row) keeps the two-step flow feeling like one
    // decision instead of two. Below API 30 ACCESS_BACKGROUND_LOCATION does not need a runtime ask
    // at all (foreground access already implies background), so the chain is skipped there and
    // BackgroundLocationAccess.current will read Granted directly off the foreground grant.
    val requestForegroundLocation = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { granted ->
        locationAccess = BackgroundLocationAccess.current(context)
        val foregroundGranted = granted[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            granted[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        if (foregroundGranted && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            requestBackgroundLocation.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        }
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
                companionName = activeName,
                onToggle = { on ->
                    proactiveOn = on
                    scope.launch { ProactiveSettings.setMaster(context, on) }
                },
            )

            // The five category switches (ticket 04). They render whether or not the master is on -
            // a user turning proactive back on should find the choices they already made, not a
            // blank slate - and each row says in words when its category has no content yet.
            ProactiveCategory.entries.forEach { category ->
                Spacer(Modifier.height(4.dp))
                ProactiveCategoryRow(
                    category = category,
                    enabled = proactiveCategories[category] == true,
                    masterOn = proactiveOn,
                    onToggle = { on ->
                        proactiveCategories = proactiveCategories + (category to on)
                        scope.launch { ProactiveSettings.setCategory(context, category, on) }
                    },
                )
            }

            // Ticket 22: the sitrep module registry, plus its schedule. Sits right below Digest's
            // own switch/category row above, since a scheduled sitrep is delivered THROUGH that
            // category (ticket 08 §1) - these rows configure WHAT it says, not WHETHER it may.
            Spacer(Modifier.height(8.dp))
            SitrepModule.entries.forEach { module ->
                Spacer(Modifier.height(4.dp))
                SitrepModuleRow(
                    module = module,
                    enabled = sitrepModules[module] == true,
                    onToggle = { on ->
                        sitrepModules = sitrepModules + (module to on)
                        scope.launch { SitrepSettings.setModule(context, module, on) }
                    },
                )
            }
            Spacer(Modifier.height(4.dp))
            SitrepScheduleRow(
                hourText = sitrepHourText,
                minuteText = sitrepMinuteText,
                sendersText = sitrepSendersText,
                onHourChange = { sitrepHourText = it.filter(Char::isDigit).take(2) },
                onMinuteChange = { sitrepMinuteText = it.filter(Char::isDigit).take(2) },
                onSendersChange = { sitrepSendersText = it },
                statusLine = sitrepScheduleStatus,
                onSave = {
                    val hour = sitrepHourText.toIntOrNull()?.coerceIn(0, 23)
                    val minute = sitrepMinuteText.toIntOrNull()?.coerceIn(0, 59)
                    if (hour == null || minute == null) {
                        sitrepScheduleStatus = "Enter a valid hour (0-23) and minute (0-59) first."
                        return@SitrepScheduleRow
                    }
                    val senders = sitrepSendersText.split(",").map { it.trim() }.filter { it.isNotBlank() }
                    scope.launch {
                        SitrepSettings.setSchedule(context, hour, minute, senders)
                        // Re-arms immediately (ticket 22 part D) - a saved schedule with no armed
                        // alarm until the next reboot would be a setting that lies about being live.
                        SitrepScheduler.schedule(context, hour, minute)
                        sitrepScheduleStatus = "Fires daily at %02d:%02d".format(hour, minute)
                    }
                },
            )

            // goal-plans ticket 05: the wellbeing digest's own schedule. Sits right below the
            // WELLBEING category row above, same "configures WHEN, not WHETHER" split
            // SitrepScheduleRow's own doc states for DIGEST.
            Spacer(Modifier.height(4.dp))
            com.kevin.legion.ui.WellbeingDigestScheduleRow(
                hourText = wellbeingHourText,
                minuteText = wellbeingMinuteText,
                onHourChange = { wellbeingHourText = it.filter(Char::isDigit).take(2) },
                onMinuteChange = { wellbeingMinuteText = it.filter(Char::isDigit).take(2) },
                statusLine = wellbeingScheduleStatus,
                onSave = {
                    val hour = wellbeingHourText.toIntOrNull()?.coerceIn(0, 23)
                    val minute = wellbeingMinuteText.toIntOrNull()?.coerceIn(0, 59)
                    if (hour == null || minute == null) {
                        wellbeingScheduleStatus = "Enter a valid hour (0-23) and minute (0-59) first."
                        return@WellbeingDigestScheduleRow
                    }
                    scope.launch {
                        com.kevin.legion.wellbeing.WellbeingDigestSettings.setSchedule(context, hour, minute)
                        // Re-arms immediately, same reasoning as the sitrep schedule save above -
                        // a saved schedule with no armed alarm until the next reboot would be a
                        // setting that lies about being live.
                        com.kevin.legion.wellbeing.WellbeingDigestScheduler.schedule(context, hour, minute)
                        wellbeingScheduleStatus = "Fires daily at %02d:%02d".format(hour, minute)
                    }
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
            Spacer(Modifier.height(8.dp))
            CallHandlingRow(
                canSeeCaller = canSeeCaller,
                canAnswer = canAnswerCalls,
                canPlace = canPlaceCalls,
                onGrant = {
                    requestCallPermissions.launch(
                        arrayOf(
                            Manifest.permission.READ_CALL_LOG,
                            Manifest.permission.READ_CONTACTS,
                            Manifest.permission.ANSWER_PHONE_CALLS,
                            Manifest.permission.CALL_PHONE,
                        )
                    )
                },
            )

            Spacer(Modifier.height(8.dp))
            LocationAccessRow(
                state = locationAccess,
                onGrant = {
                    when (locationAccess) {
                        // No location at all yet - start the chain at the beginning. The
                        // background follow-up fires on its own out of requestForegroundLocation's
                        // callback once this lands, so there is nothing else to do here.
                        LocationAccessState.None -> requestForegroundLocation.launch(
                            arrayOf(
                                Manifest.permission.ACCESS_FINE_LOCATION,
                                Manifest.permission.ACCESS_COARSE_LOCATION,
                            )
                        )
                        // Foreground already granted - either retry the background dialog, or, if
                        // the system has already told us (via shouldShowRequestPermissionRationale
                        // returning false after a real denial) that it will not show one again,
                        // send the driver straight to the app's own Settings page where "Allow all
                        // the time" actually lives.
                        LocationAccessState.ForegroundOnly -> {
                            if (offerLocationAppSettings) {
                                context.startActivity(
                                    Intent(
                                        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                        Uri.fromParts("package", context.packageName, null),
                                    )
                                )
                            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                                requestBackgroundLocation.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                            }
                        }
                        // Already granted - the row hides its own button in this state, so this
                        // branch should be unreachable, but it's a no-op rather than a crash if it
                        // ever is (a stray tap racing a resume re-read, say).
                        LocationAccessState.Granted -> {}
                    }
                },
            )

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

/**
 * The [Activity] hosting this composition, unwrapping any [android.content.ContextWrapper] chain -
 * or null if there genuinely is not one.
 *
 * Exists because `LocalContext.current as? Activity` is an assumption about how deep the context is
 * wrapped, and **its failure mode is silence**: the cast yields null, the caller reads a `false`
 * that means "no rationale needed" rather than "could not check", and a permission shortcut quietly
 * stops appearing. This is the standard AndroidX pattern and it cannot be wrong.
 */
internal tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is android.content.ContextWrapper -> baseContext.findActivity()
    else -> null
}
