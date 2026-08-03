package com.kevin.legion.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.kevin.legion.ai.CompanionProfileStore
import com.kevin.legion.ai.personaFor
import com.kevin.legion.service.AssistantIgnition

/**
 * `settings` tab. Owns the assistant ignition toggle (ticket 07 resolution
 * §1) plus the way into `settings/key`. This is the state-holder half of the
 * state-holder/UI split (`.claude/skills/compose-state-holder-ui-split`):
 * it owns the two permission launchers and talks to [AssistantIgnition];
 * [IgnitionRow] below is the plain UI half.
 *
 * **Permission order is exactly the resolution's**: POST_NOTIFICATIONS, then
 * RECORD_AUDIO, then `startForegroundService`. A refusal at either step
 * leaves the toggle off and states why - it never partially starts the
 * service. Nothing here touches ledger/pantry/fleet; they have no
 * permission gate.
 *
 * **The "who is active" identity line (companion profiles Part 2, 2026-08-02).**
 * Reads [CompanionProfileStore.activeProfile] so the current name and persona
 * blurb are visible without navigating into `settings/companions` - a user
 * whose partner just switched (or renamed) the profile on this same device
 * shouldn't have to open a sub-screen to find out who they're talking to.
 * Reloaded on every `ON_RESUME`, the same "cheap, not a poll" shape
 * [LedgerScreen] uses for its folder/key status, since coming BACK from the
 * companions screen after an edit is exactly the moment this needs to be
 * fresh.
 */
@Composable
fun SettingsScreen(onOpenKeyScreen: () -> Unit, onOpenCompanions: () -> Unit) {
    val context = LocalContext.current
    var enabled by remember { mutableStateOf(AssistantIgnition.isEnabled(context)) }
    var refusalReason by remember { mutableStateOf<String?>(null) }
    var activeName by remember { mutableStateOf<String?>(null) }
    var activeBlurb by remember { mutableStateOf<String?>(null) }
    // Bumped on ON_RESUME to key the reload below - a plain Unit key would
    // only ever fire once, and this needs to re-fire every time the user
    // comes back from settings/companions having edited or switched.
    var reloadNonce by remember { mutableStateOf(0) }

    suspend fun reloadActiveProfile() {
        val profile = CompanionProfileStore.activeProfile(context)
        activeName = profile?.assistantName
        activeBlurb = profile?.let { personaFor(it.persona).blurb }
    }

    LaunchedEffect(reloadNonce) { reloadActiveProfile() }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { reloadNonce++ }

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

    Surface(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("Settings - not built yet. See ticket 08/09.")

            IgnitionRow(
                enabled = enabled,
                refusalReason = refusalReason,
                onToggle = { turnOn -> if (turnOn) startIgnition() else stopIgnition() },
            )

            ActiveCompanionRow(name = activeName, blurb = activeBlurb, onOpenCompanions = onOpenCompanions)

            Button(onClick = onOpenKeyScreen) {
                Text("Gemini key")
            }
        }
    }
}

/**
 * The "who is active" line - plain UI half, previewable without
 * [CompanionProfileStore]. [name]/[blurb] null means the roster hasn't loaded
 * yet or (a genuinely fresh install, pre-onboarding) no profile is active on
 * this device at all; both render as "No companion set up yet" rather than
 * blank space, matching CLAUDE.md's "say plainly what is not built/not set"
 * posture.
 */
@Composable
private fun ActiveCompanionRow(name: String?, blurb: String?, onOpenCompanions: () -> Unit) {
    Column {
        if (name != null) {
            Text("Active companion: $name")
            if (blurb != null) Text(blurb)
        } else {
            Text("No companion set up yet")
        }
        Button(onClick = onOpenCompanions) {
            Text("Companions")
        }
    }
}

/**
 * Plain UI half of [SettingsScreen]. Takes immutable state plus a single
 * callback - previewable without a `Context`, permission launchers, or
 * [AssistantIgnition].
 */
@Composable
private fun IgnitionRow(
    enabled: Boolean,
    refusalReason: String?,
    onToggle: (Boolean) -> Unit,
) {
    Column {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(if (enabled) "Assistant (on)" else "Assistant (off)")
            Switch(checked = enabled, onCheckedChange = onToggle)
        }
        if (refusalReason != null) {
            Text(refusalReason)
        }
    }
}
