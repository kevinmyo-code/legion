package com.kevin.legion.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.kevin.legion.service.cascade.CascadeSession
import com.kevin.legion.service.cascade.CascadeTimings
import com.kevin.legion.service.cascade.CloudSpeechWire
import com.kevin.legion.service.cascade.LaptopProbe
import com.kevin.legion.service.cascade.LaptopVoiceConfig
import com.kevin.legion.service.cascade.SpokenLanguages
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
 * the two to compare"). Cloud (default since 2026-11-15, when it replaced Gemini Live) or the laptop
 * pipeline, the laptop's Tailscale address and token, a connection test, and what the last
 * conversation's voice did and why.
 *
 * The token is never shown back: the field starts empty and a blank save keeps the stored one.
 * Applies from the next conversation.
 */
@Suppress("LongMethod", "CyclomaticComplexMethod") // one settings row: engine, voice, speed, languages, laptop
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
    var speed by remember { mutableStateOf(VoiceEngineSettings.speechSpeed(context)) }
    var voice by remember { mutableStateOf(VoiceEngineSettings.cloudVoice(context)) }
    var voiceField by remember { mutableStateOf("") }
    var voiceNote by remember { mutableStateOf<String?>(null) }
    var spoken by remember { mutableStateOf(SpokenLanguages.load(context).map { it.name }.toSet()) }

    Surface(Modifier.fillMaxWidth(), tonalElevation = 1.dp) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
            Text(
                "Voice engine",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                "Cloud: Gemini hears you, Gemini with tools answers on the phone, and Gemini speaks " +
                    "in the voice below. Laptop pipeline: the same, with speech on your laptop; " +
                    "when the laptop cannot be used it runs on Cloud. Applies from the next conversation.",
                style = LegionType.stamp,
                color = sem.faint,
            )
            Spacer(Modifier.height(4.dp))
            for (option in VoiceEngineSettings.OFFERED) {
                DeckRadio(
                    selected = engine == option,
                    onClick = {
                        VoiceEngineSettings.setEngine(context, option)
                        engine = option
                    },
                    label = engineLabel(option),
                )
            }
            // 2026-11-17: speaking speed, pitch kept, for every voice and scripted line.
            Spacer(Modifier.height(4.dp))
            Text(
                speedLabel(speed),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Slider(
                value = speed,
                onValueChange = { speed = VoiceEngineSettings.clampSpeed(it) },
                onValueChangeFinished = { VoiceEngineSettings.setSpeechSpeed(context, speed) },
                valueRange = VoiceEngineSettings.SPEED_MIN..VoiceEngineSettings.SPEED_MAX,
                steps = SPEED_STEPS,
                modifier = Modifier.fillMaxWidth(),
            )
            // 2026-11-17: the cloud voice, Puck or a Gemini voice id, tried once before it is kept.
            Spacer(Modifier.height(4.dp))
            Text(
                "Cloud voice: $voice",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            OutlinedTextField(
                value = voiceField,
                onValueChange = { voiceField = it },
                label = { Text("Voice id (e.g. Puck, or a library id)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Row {
                DeckButton(
                    text = "Use this voice",
                    enabled = voiceField.isNotBlank(),
                    onClick = {
                        val wanted = voiceField.trim()
                        voiceNote = "Trying $wanted..."
                        scope.launch {
                            if (VoiceEngineSettings.testVoice(wanted)) {
                                VoiceEngineSettings.setCloudVoice(context, wanted)
                                voice = wanted
                                voiceField = ""
                                voiceNote = "Now using $wanted from the next conversation."
                            } else {
                                voiceNote = "$wanted did not work, so the voice stays $voice."
                            }
                        }
                    },
                )
                Spacer(Modifier.width(8.dp))
                DeckButton(
                    text = "Puck",
                    enabled = voice != CloudSpeechWire.FALLBACK_VOICE,
                    onClick = {
                        VoiceEngineSettings.setCloudVoice(context, CloudSpeechWire.FALLBACK_VOICE)
                        voice = CloudSpeechWire.FALLBACK_VOICE
                        voiceNote = null
                    },
                )
                Spacer(Modifier.width(8.dp))
                DeckButton(
                    text = "Default",
                    enabled = voice != CloudSpeechWire.VOICE,
                    onClick = {
                        VoiceEngineSettings.setCloudVoice(context, CloudSpeechWire.VOICE)
                        voice = CloudSpeechWire.VOICE
                        voiceNote = null
                    },
                )
            }
            voiceNote?.let { Text(it, style = LegionType.stamp, color = sem.faint) }
            // 2026-11-17: the languages he speaks. English always; each conversation starts in English
            // and switches when he asks ("speak Burmese"). Cloud voice only.
            Spacer(Modifier.height(4.dp))
            Text(
                "Languages I speak (English always; say \"speak Burmese\" to switch, cloud voice only)",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            for (lang in SpokenLanguages.OFFERED) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = lang.name in spoken,
                        onCheckedChange = { on ->
                            spoken = if (on) spoken + lang.name else spoken - lang.name
                            SpokenLanguages.save(context, spoken)
                        },
                    )
                    Text(
                        lang.name,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
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
                                    else -> VoiceEngineSettings.laptopWords(r.token)
                                }
                                VoiceEngineSettings.noteStatus(context, result.orEmpty())
                                last = VoiceEngineSettings.lastStatus(context)
                                testing = false
                            }
                        },
                    )
                }
                result?.let { Text(it, style = LegionType.stamp, color = sem.faint) }
            }
            // Shown for either engine: a cloud voice that failed says so here too.
            last?.let { (words, at) ->
                Text(
                    "Last voice status, ${DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(at))}: $words",
                    style = LegionType.stamp,
                    color = sem.faint,
                )
            }
        }
    }
}

/** 1.0 to 1.4 in 0.05 steps: seven stops between the ends. */
private const val SPEED_STEPS = 7

/** "Speaking speed: 1.15x (applies from the next conversation)". */
internal fun speedLabel(speed: Float): String =
    "Speaking speed: ${"%.2f".format(java.util.Locale.US, speed).trimEnd('0').trimEnd('.')}x " +
        "(applies from the next conversation)"

/** What Settings calls each engine. */
internal fun engineLabel(engine: VoiceEngine): String = when (engine) {
    VoiceEngine.CLOUD -> "Cloud"
    VoiceEngine.LAPTOP -> "Laptop pipeline"
    VoiceEngine.LIVE -> "Gemini Live"
}
