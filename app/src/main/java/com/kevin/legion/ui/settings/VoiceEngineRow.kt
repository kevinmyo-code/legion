package com.kevin.legion.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.kevin.legion.service.cascade.CascadeSession
import com.kevin.legion.service.cascade.CascadeTimings
import com.kevin.legion.service.cascade.LaptopProbe
import com.kevin.legion.service.cascade.LaptopVoiceConfig
import com.kevin.legion.service.cascade.VoiceEngine
import com.kevin.legion.service.cascade.VoiceEngineSettings
import com.kevin.legion.ui.common.DeckButton
import com.kevin.legion.ui.common.DeckRadio
import com.kevin.legion.ui.theme.LegionType
import com.kevin.legion.ui.theme.LocalLegionSemantics
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

/**
 * Settings > Assistant > "Voice engine" (Kevin, 2026-11-10: "i want to be able to switch between
 * the two to compare"). Gemini Live (default) or the laptop pipeline, the laptop's Tailscale
 * address and token, a connection test, and why the last laptop attempt fell back to Live.
 *
 * The token is never shown back: the field starts empty and a blank save keeps the stored one.
 * Applies from the next conversation.
 */
@Suppress("LongMethod") // one settings row: the choice, two fields, two buttons, two status lines
@Composable
fun VoiceEngineRow() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val sem = LocalLegionSemantics.current
    var engine by remember { mutableStateOf(VoiceEngineSettings.engine(context)) }
    val saved = remember { VoiceEngineSettings.laptop(context) }
    var address by remember { mutableStateOf(saved.baseUrl) }
    var token by remember { mutableStateOf("") }
    var tokenSaved by remember { mutableStateOf(saved.token.isNotBlank()) }
    var testing by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<String?>(null) }
    var last by remember { mutableStateOf(VoiceEngineSettings.lastStatus(context)) }

    Surface(Modifier.fillMaxWidth(), tonalElevation = 1.dp) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
            Text(
                "Voice engine",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                "Gemini Live, or the laptop pipeline: speech on your laptop, Gemini text with tools on " +
                    "the phone. If the laptop cannot be reached the conversation uses Live. Applies from " +
                    "the next conversation.",
                style = LegionType.stamp,
                color = sem.faint,
            )
            Spacer(Modifier.height(4.dp))
            DeckRadio(
                selected = engine == VoiceEngine.LIVE,
                onClick = {
                    VoiceEngineSettings.setEngine(context, VoiceEngine.LIVE)
                    engine = VoiceEngine.LIVE
                },
                label = "Gemini Live",
            )
            DeckRadio(
                selected = engine == VoiceEngine.LAPTOP,
                onClick = {
                    VoiceEngineSettings.setEngine(context, VoiceEngine.LAPTOP)
                    engine = VoiceEngine.LAPTOP
                },
                label = "Laptop pipeline",
            )
            if (engine == VoiceEngine.LAPTOP) {
                Spacer(Modifier.height(4.dp))
                OutlinedTextField(
                    value = address,
                    onValueChange = { address = it },
                    label = { Text("Laptop address (Tailscale name), e.g. http://laptop.tailnet.ts.net:8765") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = token,
                    onValueChange = { token = it },
                    label = { Text(if (tokenSaved) "Token (saved; type to replace)" else "Token") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(4.dp))
                Row {
                    DeckButton(
                        text = "Save",
                        enabled = LaptopVoiceConfig.isValidBaseUrl(address),
                        onClick = {
                            VoiceEngineSettings.setLaptop(context, address, token, emptyList())
                            if (token.isNotBlank()) tokenSaved = true
                            token = ""
                            result = "Saved"
                        },
                    )
                    Spacer(Modifier.width(8.dp))
                    DeckButton(
                        text = if (testing) "Testing..." else "Test connection",
                        enabled = !testing,
                        onClick = {
                            testing = true
                            scope.launch {
                                val config = VoiceEngineSettings.laptop(context)
                                val r = LaptopProbe.probe(config, CascadeTimings.SETTINGS_TEST_TIMEOUT_MS)
                                if (r is LaptopProbe.Result.Ready) CascadeSession.clearRecentFailure()
                                result = when (r) {
                                    is LaptopProbe.Result.Ready -> "Laptop ready (${r.ms} ms)"
                                    else -> VoiceEngineSettings.statusWords(r.token).replace(", used Live", "")
                                }
                                VoiceEngineSettings.noteStatus(context, result.orEmpty())
                                last = VoiceEngineSettings.lastStatus(context)
                                testing = false
                            }
                        },
                    )
                }
                result?.let { Text(it, style = LegionType.stamp, color = sem.faint) }
                last?.let { (words, at) ->
                    Text(
                        "Last laptop attempt, ${DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(at))}: $words",
                        style = LegionType.stamp,
                        color = sem.faint,
                    )
                }
            }
        }
    }
}

