package com.kevin.legion.ui.settings

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
import androidx.compose.material3.Surface
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
import com.kevin.legion.ai.CompanionProfileStore
import com.kevin.legion.ai.personaFor
import com.kevin.legion.service.AssistantIgnition
import com.kevin.legion.service.WakeWordEngine
import com.kevin.legion.service.WakeWordPreferences
import com.kevin.legion.service.turn.VoiceTurnSettings
import com.kevin.legion.ui.ActiveCompanionRow
import com.kevin.legion.ui.IgnitionRow
import com.kevin.legion.ui.SettingsNavRow
import com.kevin.legion.ui.TemperatureUnitRow
import com.kevin.legion.ui.WakePipelineRow
import com.kevin.legion.ui.VoiceTurnRow
import com.kevin.legion.ui.WakeWordRow
import com.kevin.legion.ui.common.DeckScreenHeader
import com.kevin.legion.ui.help.VoiceGuideData
import com.kevin.legion.util.Temp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * "Assistant" - the first of the five subscreens `settings/` split into (command-center ticket 02,
 * `.scratch/command-center/issues/02-settings-submenus.md`), replacing a slice of the old 718-line
 * `SettingsScreen.kt` wall. Owns the companion settings link, the ignition switch, the
 * playbook editor link, the wake word toggle, and conversation-adjacent behaviour (temperature
 * unit - spoken by the assistant, not just displayed).
 *
 * **Every row here is moved, not rewritten** - [IgnitionRow], [ActiveCompanionRow], [WakeWordRow]
 * and [TemperatureUnitRow] are the exact same composables the old screen called, with their exact
 * single-writer write paths ([AssistantIgnition], [WakeWordPreferences], [Temp]) unchanged. Only the
 * host screen and the state that feeds them moved.
 *
 * **The "What can I do" row (command-center ticket 09) lives here, not on
 * [com.kevin.legion.ui.settings.DataPrivacyScreen].** That screen answers "what does it store";
 * this one is the conversation-behaviour screen, and "what can I ask it" is a conversation
 * question, same family as the wake word phrase and the persona above it - not a privacy concern.
 */
@Composable
fun AssistantSettingsScreen(
    onBack: () -> Unit,
    onOpenCompanions: () -> Unit,
    onOpenPlaybooks: () -> Unit,
    onOpenVoiceGuide: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var enabled by remember { mutableStateOf(AssistantIgnition.isEnabled(context)) }
    var refusalReason by remember { mutableStateOf<String?>(null) }
    var activeName by remember { mutableStateOf<String?>(null) }
    var activeBlurb by remember { mutableStateOf<String?>(null) }
    // Bumped on ON_RESUME to key the reload below - a plain Unit key would only ever fire once,
    // and this needs to re-fire every time the user comes back from the Companion screen having
    // edited it. Same shape the old monolith used for this same reload.
    var reloadNonce by remember { mutableStateOf(0) }
    var wakeWordOn by remember { mutableStateOf(WakeWordPreferences.isEnabled(context)) }
    var wakeTwoStage by remember { mutableStateOf(WakeWordPreferences.useTwoStage(context)) }
    val wakeTripped = WakeWordPreferences.twoStageTripped(context)
    var temperatureUnit by remember { mutableStateOf(Temp.unit(context)) }
    var smartEndOfTurn by remember { mutableStateOf(VoiceTurnSettings.smartEndOfTurn(context)) }
    var bargeIn by remember { mutableStateOf(VoiceTurnSettings.bargeIn(context)) }
    var asyncLookups by remember { mutableStateOf(VoiceTurnSettings.asyncLookups(context)) }
    var editedPlaybookCount by remember { mutableStateOf(0) }

    suspend fun reloadActiveProfile() {
        val profile = CompanionProfileStore.activeProfile(context)
        activeName = profile?.assistantName
        activeBlurb = profile?.let { personaFor(it.persona).blurb }
    }

    // [PlaybookStore] is raw blocking file IO (its own doc comment), so the read is wrapped in
    // Dispatchers.IO here same as every other caller must.
    suspend fun reloadPlaybookStatus() {
        editedPlaybookCount = withContext(Dispatchers.IO) {
            PrimingTopic.entries.count { PlaybookStore.isCustomised(context, it) }
        }
    }

    LaunchedEffect(reloadNonce) {
        reloadActiveProfile()
        reloadPlaybookStatus()
    }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        reloadNonce++
        enabled = AssistantIgnition.isEnabled(context)
        wakeWordOn = WakeWordPreferences.isEnabled(context)
        temperatureUnit = Temp.unit(context)
    }

    // Step 2 of the ignition chain: RECORD_AUDIO. Only reached once POST_NOTIFICATIONS is
    // settled (granted, or not applicable pre-Tiramisu). Unchanged from the old monolith.
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

    // Step 1 of the ignition chain: POST_NOTIFICATIONS (API 33+ only - see startIgnition).
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

    Surface(modifier = Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            DeckScreenHeader(title = "Assistant", onBack = onBack)
            Column(
                Modifier
                    .padding(16.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                IgnitionRow(
                    enabled = enabled,
                    refusalReason = refusalReason,
                    onToggle = { turnOn -> if (turnOn) startIgnition() else stopIgnition() },
                )

                Spacer(Modifier.height(8.dp))
                ActiveCompanionRow(name = activeName, blurb = activeBlurb, onOpenCompanions = onOpenCompanions)

                Spacer(Modifier.height(8.dp))
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
                WakeWordRow(
                    enabled = wakeWordOn,
                    companionName = activeName,
                    onToggle = { on ->
                        WakeWordPreferences.setEnabled(context, on)
                        wakeWordOn = on
                        if (on) WakeWordEngine.start(context) else WakeWordEngine.stop()
                    },
                )

                Spacer(Modifier.height(8.dp))
                VoiceTurnRow(
                    title = "Smart end of turn",
                    explanation = "the phone decides when you have finished, and waits through a " +
                        "pause that sounds mid-thought. Applies from the next conversation.",
                    enabled = smartEndOfTurn,
                    onToggle = { on ->
                        VoiceTurnSettings.setSmartEndOfTurn(context, on)
                        smartEndOfTurn = on
                    },
                )

                Spacer(Modifier.height(8.dp))
                VoiceTurnRow(
                    // The persona is renameable (CLAUDE.md sec 1): never a hardcoded name in copy.
                    title = "Interrupt ${activeName ?: "the assistant"} (call-style audio)",
                    explanation = "talk over a reply to stop it; the phone uses call audio while you " +
                        "talk. Never over car Bluetooth. Applies from the next conversation.",
                    enabled = bargeIn,
                    onToggle = { on ->
                        VoiceTurnSettings.setBargeIn(context, on)
                        bargeIn = on
                    },
                )

                // ADR 0065: read-only lookups run while the conversation carries on. Read when the
                // socket opens, so a warm connection keeps its old setting until it reconnects.
                Spacer(Modifier.height(8.dp))
                VoiceTurnRow(
                    title = "Answer lookups while talking",
                    explanation = "a slow lookup runs in the background after a short holding line, " +
                        "and the answer is spoken when it lands. Anything that changes your records " +
                        "still waits. Applies from the next connection.",
                    enabled = asyncLookups,
                    onToggle = { on ->
                        VoiceTurnSettings.setAsyncLookups(context, on)
                        asyncLookups = on
                    },
                )

                // Ticket 18: dev switch back to the Vosk-only wake path. Restarting the engine is what
                // makes the choice take effect, since the pipeline is chosen when capture opens.
                Spacer(Modifier.height(8.dp))
                WakePipelineRow(
                    twoStage = wakeTwoStage,
                    tripped = wakeTripped && !wakeTwoStage,
                    onToggle = { on ->
                        WakeWordPreferences.setUseTwoStage(context, on)
                        wakeTwoStage = on
                        if (wakeWordOn) {
                            WakeWordEngine.stop()
                            WakeWordEngine.start(context)
                        }
                    },
                )

                // Ticket 07, amended 2026-08-18: the unit is a setting, not a fixed Celsius. Kept
                // here rather than under Connections/Data - it is spoken by the assistant, not just
                // displayed, so it is conversation behaviour same as the wake word phrase above it.
                Spacer(Modifier.height(8.dp))
                TemperatureUnitRow(
                    unit = temperatureUnit,
                    onSelect = { unit ->
                        Temp.setUnit(context, unit)
                        temperatureUnit = unit
                    },
                )

                // Command-center ticket 09: the app can now say what it does, and where the
                // button for it is - see ui/help/VoiceGuideScreen.kt.
                Spacer(Modifier.height(8.dp))
                SettingsNavRow(
                    label = "What can I do",
                    status = "${VoiceGuideData.TOOL_COUNT} things it can do by voice, and where the hands path is",
                    onClick = onOpenVoiceGuide,
                )

                Spacer(Modifier.height(24.dp))
            }
        }
    }
}
