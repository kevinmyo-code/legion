package com.kevin.legion.ui.settings

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.RadioButton
import androidx.compose.ui.Alignment
import androidx.core.content.ContextCompat
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.kevin.legion.ui.VoiceTurnRow
import com.kevin.legion.ui.common.DeckButton
import com.kevin.legion.ui.common.DeckScreenHeader
import com.kevin.legion.ui.theme.LegionType
import com.kevin.legion.ui.theme.LocalLegionSemantics

/**
 * Settings, Car link: pair this phone with the car's head unit, send it the phone's location, and
 * forget it. The hands path for the car link (ADR 0035); it calls the one
 * [com.kevin.legion.carlink.CarLinkController].
 */
@Composable
fun CarLinkScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val viewModel: CarLinkViewModel = viewModel(factory = CarLinkViewModel.Factory(context))
    val state by viewModel.state.collectAsStateWithLifecycle()
    // The camera is asked for only when Scan is tapped, never before (and never in the background).
    val cameraAsk = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) viewModel.scan() else viewModel.onCameraDenied()
    }
    val hasCamera = {
        ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
    }
    CarLinkContent(
        state = state,
        onBack = onBack,
        actions = CarLinkActions(
            scan = { if (hasCamera()) viewModel.scan() else cameraAsk.launch(Manifest.permission.CAMERA) },
            scanned = viewModel::onScanned,
            scannerFailed = viewModel::onScannerFailed,
            paste = viewModel::pasteCode,
            cancel = viewModel::cancel,
            dismiss = viewModel::dismiss,
            forget = viewModel::forget,
            sendLocation = viewModel::setSendLocation,
            vehicle = viewModel::chooseVehicle,
            openAppSettings = {
                runCatching {
                    context.startActivity(
                        Intent(
                            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.parse("package:${context.packageName}"),
                        )
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                }
            },
        ),
    )
}

/** The screen's buttons, gathered so [CarLinkContent] stays stateless. */
class CarLinkActions(
    val scan: () -> Unit,
    /** The camera pane read a QR: its raw text, which only the pairing parser ever sees. */
    val scanned: (String) -> Unit,
    /** The scanner itself could not start on this phone. */
    val scannerFailed: () -> Unit,
    val paste: (String) -> Unit,
    val cancel: () -> Unit,
    val dismiss: () -> Unit,
    val forget: () -> Unit,
    val sendLocation: (Boolean) -> Unit,
    val vehicle: (String?) -> Unit,
    val openAppSettings: () -> Unit,
)

@Composable
fun CarLinkContent(state: CarLinkUiState, onBack: () -> Unit, actions: CarLinkActions) {
    val sem = LocalLegionSemantics.current
    Surface(modifier = Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            DeckScreenHeader(title = "Car link", onBack = onBack)
            Column(Modifier.padding(16.dp).verticalScroll(rememberScrollState())) {
                Text(state.headline, style = MaterialTheme.typography.titleMedium)
                state.digits?.let {
                    Spacer(Modifier.height(12.dp))
                    Text(it.chunked(DIGIT_GROUP).joinToString(" "), style = MaterialTheme.typography.displayMedium)
                }
                state.detail?.let {
                    Spacer(Modifier.height(6.dp))
                    Text(it, style = MaterialTheme.typography.bodyMedium)
                }
                state.note?.let {
                    Spacer(Modifier.height(6.dp))
                    Text(it, style = MaterialTheme.typography.bodyMedium)
                }
                ScanSection(state, actions)
                if (state.canPair) VehiclePicker(state, actions.vehicle)
                Spacer(Modifier.height(12.dp))
                PairButtons(state, actions)
                if (state.canPair && state.handsOver.isNotEmpty()) {
                    Spacer(Modifier.height(4.dp))
                    Text(state.handsOver, style = LegionType.stamp, color = sem.faint)
                }
                if (state.debugPaste && state.canPair) DebugPaste(actions.paste)

                Spacer(Modifier.height(16.dp))
                VoiceTurnRow(
                    title = "Send location to the head unit",
                    explanation = "only while paired, and only while this phone is on the network it paired on " +
                        "(the same hotspot or Wi-Fi, same address range). Off by default. When this phone is " +
                        "the car's hotspot, the \"Sending location to the car\" notification stays while the " +
                        "hotspot is on, even with the car off.",
                    enabled = state.sendLocation,
                    onToggle = actions.sendLocation,
                )
                if (state.canForget) ForgetButton(actions.forget)
                Spacer(Modifier.height(16.dp))
                Text(
                    "Pairing uses a code the head unit shows and six digits you type on it. " +
                        "Keys stay on this phone, sealed by Android, and never go in a backup.",
                    style = LegionType.stamp,
                    color = sem.faint,
                )
            }
        }
    }
}

/** The camera pane while scanning, and what to say when the scanner or the camera could not start. */
@Composable
private fun ScanSection(state: CarLinkUiState, actions: CarLinkActions) {
    if (state.phase == CarLinkPhase.SCANNING) {
        Spacer(Modifier.height(12.dp))
        CarLinkQrScanPane(onQr = actions.scanned, onFailed = actions.scannerFailed)
        state.scanReject?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
    }
    state.scannerFailure?.let {
        Spacer(Modifier.height(6.dp))
        Text(it, style = MaterialTheme.typography.bodyMedium)
    }
    state.cameraNote?.let {
        Spacer(Modifier.height(6.dp))
        Text(it, style = MaterialTheme.typography.bodyMedium)
        DeckButton(text = "Open app settings", onClick = actions.openAppSettings)
    }
}

/** Six digits shown as two groups of three, easier to read across a car. */
private const val DIGIT_GROUP = 3

@Composable
private fun PairButtons(state: CarLinkUiState, actions: CarLinkActions) {
    Row(Modifier.fillMaxWidth()) {
        if (state.canPair && state.showScan) {
            val label = if (state.phase == CarLinkPhase.PAIRED) "Pair again" else "Scan the code"
            DeckButton(text = label, onClick = actions.scan)
        }
        if (state.canCancel) {
            Spacer(Modifier.width(8.dp))
            DeckButton(text = "Cancel", onClick = actions.cancel)
        }
        if (state.phase == CarLinkPhase.FAILED) {
            Spacer(Modifier.width(8.dp))
            DeckButton(text = "OK", onClick = actions.dismiss)
        }
    }
    if (state.canPair) {
        state.scanNote?.let {
            Spacer(Modifier.height(4.dp))
            Text(it, style = LegionType.stamp, color = LocalLegionSemantics.current.faint)
        }
    }
}

@Composable
private fun DebugPaste(onPaste: (String) -> Unit) {
    var text by remember { mutableStateOf("") }
    Spacer(Modifier.height(8.dp))
    OutlinedTextField(
        value = text,
        onValueChange = { text = it },
        label = { Text("Pairing code text (debug builds only)") },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
    )
    Spacer(Modifier.height(4.dp))
    DeckButton(text = "Pair from pasted code", onClick = { onPaste(text); text = "" }, enabled = text.isNotBlank())
}

/** Which of the household's cars this head unit is in: the engine binds the unit's sign-in to one. */
@Composable
private fun VehiclePicker(state: CarLinkUiState, onPick: (String?) -> Unit) {
    Spacer(Modifier.height(12.dp))
    Text("Which car is this head unit in?", style = MaterialTheme.typography.labelLarge)
    state.vehicleNote?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
    for (v in state.vehicles) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            RadioButton(selected = state.vehicleId == v.id, onClick = { onPick(v.id) })
            Text(v.label, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun ForgetButton(onForget: () -> Unit) {
    var confirming by remember { mutableStateOf(false) }
    Spacer(Modifier.height(16.dp))
    DeckButton(
        text = if (confirming) "Tap again to forget" else "Forget head unit",
        onClick = {
            if (confirming) onForget()
            confirming = !confirming
        },
        destructive = true,
        confirming = confirming,
    )
}
