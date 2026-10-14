package com.kevin.midnightai.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.kevin.midnightai.ai.CompanionProfile
import com.kevin.midnightai.sync.DriveAuth
import com.kevin.midnightai.sync.SyncCapability
import com.kevin.midnightai.sync.SyncEngine
import com.kevin.midnightai.ui.theme.AriaColors
import kotlinx.coroutines.launch

/**
 * Setup control for cross-device BYO-cloud sync (S1): connects the driver's OWN
 * Google Drive so their car data (OBD history, logbook, mixtapes, recaps) rides
 * between the head unit and their phone. Opt-in, off by default; optional,
 * on-device, no regression if skipped.
 *
 * Connecting runs the Google Identity Authorization API for the narrow
 * `drive.appdata` scope. The first grant needs an interactive consent, launched
 * here via an ActivityResult; after that the token refreshes silently and the
 * [com.kevin.midnightai.sync.SyncEngine] (later build step) does the actual
 * transfer. Data lands only in the user's own Drive - never a Kevin-hosted
 * server (CLAUDE.md sec 9).
 */
@Composable
fun DriveSyncEntry(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val playServices = remember { SyncCapability.playServicesAvailable(context) }
    var connected by remember { mutableStateOf(CompanionProfile.isSyncEnabled(context)) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }

    // Launches the interactive Drive consent when the account hasn't granted the
    // scope yet; on return, a non-null token means the grant succeeded.
    val consentLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        val token = DriveAuth.tokenFromConsent(context, result.data)
        if (token != null) {
            CompanionProfile.setSyncEnabled(context, true)
            connected = true
            message = "Connected. Your car data now syncs to your Google Drive."
        } else {
            message = "Sign-in was cancelled - Drive is not connected."
        }
        busy = false
    }

    Column(modifier) {
        val status = when {
            !playServices -> "This device has no Google Play Services, so Drive sync is unavailable here. " +
                "Everything still works, just on this device only."
            connected -> "Connected. Your car data syncs to your own Google Drive - reach it from your phone " +
                "and the dash. Only the data (OBD history, logbook, mixtapes, recaps) syncs; song files and " +
                "photos stay on each device."
            else -> "Not connected - all your data stays on this device only."
        }
        Text(status, style = MaterialTheme.typography.bodySmall, color = AriaColors.TextDim)
        Spacer(Modifier.height(10.dp))

        Row {
            if (!connected) {
                Button(
                    enabled = playServices && !busy,
                    onClick = {
                        scope.launch {
                            busy = true
                            message = null
                            when (val outcome = DriveAuth.authorize(context)) {
                                is DriveAuth.Outcome.Authorized -> {
                                    CompanionProfile.setSyncEnabled(context, true)
                                    connected = true
                                    message = "Connected. Your car data now syncs to your Google Drive."
                                    busy = false
                                }
                                is DriveAuth.Outcome.NeedsConsent -> {
                                    // Launcher callback flips busy off when it returns.
                                    consentLauncher.launch(
                                        IntentSenderRequest.Builder(outcome.pendingIntent).build()
                                    )
                                }
                                is DriveAuth.Outcome.Failed -> {
                                    message = "Couldn't connect to Drive - check your connection and try again."
                                    busy = false
                                }
                            }
                        }
                    },
                ) { Text(if (busy) "CONNECTING..." else "CONNECT GOOGLE DRIVE") }
            } else {
                Button(
                    enabled = !busy,
                    onClick = {
                        scope.launch {
                            busy = true
                            message = "Syncing..."
                            val result = SyncEngine.syncNow(context)
                            message = result.message
                            busy = false
                        }
                    },
                ) { Text(if (busy) "SYNCING..." else "SYNC NOW") }
                Spacer(Modifier.width(8.dp))
                OutlinedButton(
                    enabled = !busy,
                    onClick = {
                        CompanionProfile.setSyncEnabled(context, false)
                        connected = false
                        message = "Disconnected. Nothing new will sync; data already in your Drive stays there."
                    },
                ) { Text("DISCONNECT", color = AriaColors.TextDim) }
            }
        }

        message?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodySmall,
                color = if (connected) AriaColors.NeonCyan else AriaColors.TextDim,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}
