package com.kevin.legion.ui.settings

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
import androidx.compose.ui.text.input.PasswordVisualTransformation
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
    CarLinkContent(
        state = state,
        onBack = onBack,
        actions = CarLinkActions(
            scan = viewModel::scan,
            paste = viewModel::pasteCode,
            cancel = viewModel::cancel,
            dismiss = viewModel::dismiss,
            forget = viewModel::forget,
            sendLocation = viewModel::setSendLocation,
            carKey = viewModel::setCarGeminiKey,
        ),
    )
}

/** The screen's buttons, gathered so [CarLinkContent] stays stateless. */
class CarLinkActions(
    val scan: () -> Unit,
    val paste: (String) -> Unit,
    val cancel: () -> Unit,
    val dismiss: () -> Unit,
    val forget: () -> Unit,
    val sendLocation: (Boolean) -> Unit,
    val carKey: (String?) -> Unit,
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
                Spacer(Modifier.height(12.dp))
                PairButtons(state, actions)
                if (state.debugPaste && state.canPair) DebugPaste(actions.paste)

                Spacer(Modifier.height(16.dp))
                VoiceTurnRow(
                    title = "Send location to the head unit",
                    explanation = "only while paired and on the car's hotspot. Off by default.",
                    enabled = state.sendLocation,
                    onToggle = actions.sendLocation,
                )
                Spacer(Modifier.height(8.dp))
                CarKeyField(state.carKeySet, actions.carKey)
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

/** Six digits shown as two groups of three, easier to read across a car. */
private const val DIGIT_GROUP = 3

@Composable
private fun PairButtons(state: CarLinkUiState, actions: CarLinkActions) {
    Row(Modifier.fillMaxWidth()) {
        if (state.canPair) {
            val label = if (state.phase == CarLinkPhase.PAIRED) "Pair again" else "Scan the code"
            DeckButton(text = label, onClick = actions.scan, enabled = state.scannerAvailable)
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
    if (state.canPair && !state.scannerAvailable) {
        Spacer(Modifier.height(4.dp))
        Text(
            "No code scanner is built in yet, so pairing waits on that choice.",
            style = LegionType.stamp,
            color = LocalLegionSemantics.current.faint,
        )
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

/**
 * The car's OWN Gemini key (5.3, rev 5): a separate, API-restricted key made for the car. Never
 * prefilled from this phone's key, never shown back; empty means the car gets no Gemini key.
 */
@Composable
private fun CarKeyField(isSet: Boolean, onSave: (String?) -> Unit) {
    var text by remember { mutableStateOf("") }
    Spacer(Modifier.height(16.dp))
    Text("Car's own Gemini key", style = MaterialTheme.typography.labelLarge)
    Text(
        if (isSet) {
            "Set. It goes to the head unit at the next pairing."
        } else {
            "Not set: the head unit gets no Gemini key. Use a separate, restricted key made for the car, " +
                "never this phone's own."
        },
        style = MaterialTheme.typography.bodyMedium,
    )
    OutlinedTextField(
        value = text,
        onValueChange = { text = it },
        label = { Text("Paste the car's key") },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
    )
    Row(Modifier.fillMaxWidth()) {
        DeckButton(text = "Save", onClick = { onSave(text); text = "" }, enabled = text.isNotBlank())
        if (isSet) {
            Spacer(Modifier.width(8.dp))
            DeckButton(text = "Clear", onClick = { onSave(null) })
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
