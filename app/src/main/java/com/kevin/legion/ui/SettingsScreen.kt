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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
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
 */
@Composable
fun SettingsScreen(onOpenKeyScreen: () -> Unit) {
    val context = LocalContext.current
    var enabled by remember { mutableStateOf(AssistantIgnition.isEnabled(context)) }
    var refusalReason by remember { mutableStateOf<String?>(null) }

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

            Button(onClick = onOpenKeyScreen) {
                Text("Gemini key")
            }
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
