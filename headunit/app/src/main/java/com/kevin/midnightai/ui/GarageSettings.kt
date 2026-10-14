package com.kevin.midnightai.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.kevin.midnightai.ai.CompanionProfile
import com.kevin.midnightai.ui.theme.AriaColors
import com.kevin.midnightai.vehicle.GarageController
import com.kevin.midnightai.vehicle.GarageDoorConfig
import com.kevin.midnightai.vehicle.GarageException
import com.kevin.midnightai.vehicle.GaragePreferences
import kotlinx.coroutines.launch

/**
 * Setup section for BYO-hardware garage/gate control over the Shelly Cloud
 * Control API (CLAUDE.md sec 3/9): paste the account's `auth_key` + server
 * host once, then add each door as deviceId + channel + friendly name.
 * Optional, BYO credential, no regression
 * if skipped). The relay itself lives AT THE GARAGE on home WiFi - the head
 * unit reaches it over the phone hotspot through Shelly's cloud, not a
 * car-local radio (see [com.kevin.midnightai.vehicle.ShellyCloudOpener]).
 */
@Composable
fun GarageSettings(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var doors by remember { mutableStateOf(GaragePreferences.doors(context)) }
    var defaultId by remember { mutableStateOf(GaragePreferences.defaultDoorId(context)) }

    fun refresh() {
        doors = GaragePreferences.doors(context)
        defaultId = GaragePreferences.defaultDoorId(context)
    }

    var authKeyText by remember { mutableStateOf(CompanionProfile.shellyAuthKey(context)) }
    var serverHostText by remember { mutableStateOf(GaragePreferences.serverHost(context).orEmpty()) }
    var accountSaved by remember {
        mutableStateOf(CompanionProfile.hasShellyAuthKey(context) && !GaragePreferences.serverHost(context).isNullOrBlank())
    }

    var deviceIdText by remember { mutableStateOf("") }
    var channelText by remember { mutableStateOf("0") }
    var friendlyName by remember { mutableStateOf("") }
    var addError by remember { mutableStateOf<String?>(null) }

    var pendingTest by remember { mutableStateOf<GarageDoorConfig?>(null) }
    var testingId by remember { mutableStateOf<String?>(null) }
    var testStatus by remember { mutableStateOf<Pair<String, String>?>(null) } // doorId to message

    Column(modifier) {
        Text(
            "In the Shelly app: User Settings -> Authorization cloud key -> Get key. Copy the key " +
                "and the server address shown on that same screen. Wire the relay across your garage " +
                "opener's wall-button terminals and power it there - it stays on your home WiFi, and " +
                "the head unit reaches it over your phone's hotspot. One button press per trigger - " +
                "there's no way to tell if it'll open or close.",
            style = MaterialTheme.typography.bodySmall,
            color = AriaColors.TextDim,
        )
        Spacer(Modifier.height(12.dp))

        OutlinedTextField(
            value = authKeyText,
            onValueChange = { authKeyText = it; accountSaved = false },
            label = { Text("Authorization cloud key") },
            singleLine = true,
            modifier = Modifier,
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = serverHostText,
            onValueChange = { serverHostText = it; accountSaved = false },
            label = { Text("Server host, e.g. shelly-12-eu.shelly.cloud") },
            singleLine = true,
            modifier = Modifier,
        )
        Spacer(Modifier.height(8.dp))
        Button(
            onClick = {
                CompanionProfile.saveShellyAuthKey(context, authKeyText)
                GaragePreferences.setServerHost(context, serverHostText)
                accountSaved = true
            },
        ) { Text(if (accountSaved) "SAVED" else "SAVE ACCOUNT") }

        Spacer(Modifier.height(20.dp))

        if (doors.isEmpty()) {
            Text("No doors set up yet.", style = MaterialTheme.typography.bodySmall, color = AriaColors.TextDim)
        } else {
            for (door in doors) {
                Spacer(Modifier.height(8.dp))
                GarageDoorRow(
                    door = door,
                    isDefault = door.id == defaultId,
                    testing = testingId == door.id,
                    statusLine = testStatus?.takeIf { it.first == door.id }?.second,
                    onSetDefault = { GaragePreferences.setDefaultDoorId(context, door.id); refresh() },
                    onToggleEnabled = {
                        GaragePreferences.updateDoor(context, door.copy(enabled = !door.enabled))
                        refresh()
                    },
                    onRemove = { GaragePreferences.removeDoor(context, door.id); refresh() },
                    onTest = { pendingTest = door },
                )
            }
        }

        Spacer(Modifier.height(20.dp))
        Text("Add a door", style = MaterialTheme.typography.titleMedium, color = AriaColors.TextPrimary)
        Spacer(Modifier.height(4.dp))
        Text(
            "Device ID is in the Shelly app under Device -> Settings -> Device Info. Channel is 0 " +
                "on single-relay devices like the Shelly Plus 1 / 1 Gen4.",
            style = MaterialTheme.typography.bodySmall,
            color = AriaColors.TextDim,
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = deviceIdText,
            onValueChange = { deviceIdText = it },
            label = { Text("Device ID") },
            singleLine = true,
            modifier = Modifier,
        )
        Spacer(Modifier.height(8.dp))
        Row {
            OutlinedTextField(
                value = channelText,
                onValueChange = { channelText = it.filter { c -> c.isDigit() } },
                label = { Text("Channel") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.width(110.dp),
            )
            Spacer(Modifier.width(8.dp))
            OutlinedTextField(
                value = friendlyName,
                onValueChange = { friendlyName = it },
                label = { Text("Name, e.g. Garage") },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
        }
        addError?.let {
            Spacer(Modifier.height(6.dp))
            Text(it, style = MaterialTheme.typography.bodySmall, color = AriaColors.ScanRed)
        }
        Spacer(Modifier.height(10.dp))
        Button(
            onClick = {
                val deviceId = deviceIdText.trim()
                val channel = channelText.toIntOrNull() ?: 0
                val name = friendlyName.trim()
                when {
                    !accountSaved -> addError = "Save your Shelly account (key + server) first."
                    deviceId.isBlank() -> addError = "Enter the relay's device ID from the Shelly app."
                    name.isBlank() -> addError = "Give this door a name."
                    else -> {
                        addError = null
                        GaragePreferences.addDoor(context, deviceId, channel, name)
                        deviceIdText = ""
                        friendlyName = ""
                        channelText = "0"
                        refresh()
                    }
                }
            },
        ) { Text("ADD DOOR") }
    }

    pendingTest?.let { door ->
        AlertDialog(
            onDismissRequest = { pendingTest = null },
            title = { Text("Test the ${door.friendlyName} door?") },
            text = { Text("This sends one real pulse to the relay, exactly like the voice command.") },
            confirmButton = {
                TextButton(onClick = {
                    val target = door
                    pendingTest = null
                    testingId = target.id
                    testStatus = null
                    scope.launch {
                        testStatus = try {
                            GarageController.activate(context, target)
                            target.id to "Triggered."
                        } catch (e: GarageException) {
                            target.id to (e.message ?: "Couldn't reach the relay.")
                        }
                        testingId = null
                    }
                }) { Text("TRIGGER") }
            },
            dismissButton = { TextButton(onClick = { pendingTest = null }) { Text("CANCEL") } },
        )
    }
}

@Composable
private fun GarageDoorRow(
    door: GarageDoorConfig,
    isDefault: Boolean,
    testing: Boolean,
    statusLine: String?,
    onSetDefault: () -> Unit,
    onToggleEnabled: () -> Unit,
    onRemove: () -> Unit,
    onTest: () -> Unit,
) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            RadioButton(selected = isDefault, onClick = onSetDefault)
            Column(Modifier.weight(1f)) {
                Text(door.friendlyName, style = MaterialTheme.typography.titleSmall, color = AriaColors.TextPrimary)
                Text(
                    "${door.deviceId} · channel ${door.relayId}" + if (!door.enabled) " · disabled" else "",
                    style = MaterialTheme.typography.bodySmall,
                    color = AriaColors.TextDim,
                )
            }
            Switch(checked = door.enabled, onCheckedChange = { onToggleEnabled() })
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onTest, enabled = !testing) { Text(if (testing) "TESTING..." else "TEST") }
            Spacer(Modifier.width(4.dp))
            TextButton(onClick = onRemove) { Text("REMOVE", color = AriaColors.ScanRed) }
        }
        statusLine?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = AriaColors.TextDim)
        }
    }
}
