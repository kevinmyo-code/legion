package com.kevin.legion.ui.settings

import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.kevin.legion.service.WakeSampleRecorder
import com.kevin.legion.service.WakeWordPreferences
import com.kevin.legion.service.speaker.SpeakerPolicy
import com.kevin.legion.service.speaker.SpeakerVerifier
import com.kevin.legion.ui.common.DeckButton
import com.kevin.legion.ui.common.DeckSwitch
import com.kevin.legion.ui.theme.LegionType
import com.kevin.legion.ui.theme.LocalLegionSemantics
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

/**
 * "Only my voice" (Kevin 2026-11-16): build a voiceprint from the wake takes above, then let the
 * wake word open only for him. The switch is off by default and cannot be turned on without a
 * voiceprint; while off, every wake is scored and logged and nothing is held back.
 */
@Composable
fun OnlyMyVoiceSection(context: Context) {
    val sem = LocalLegionSemantics.current
    val info by SpeakerVerifier.info.collectAsState()
    var on by remember { mutableStateOf(WakeWordPreferences.onlyMyVoice(context)) }

    LaunchedEffect(Unit) { SpeakerVerifier.refresh(context) }
    // A deleted voiceprint turns the switch off with it.
    LaunchedEffect(info) { if (info == null) on = WakeWordPreferences.onlyMyVoice(context) }

    Text("Only my voice", style = MaterialTheme.typography.titleMedium)
    Spacer(Modifier.height(4.dp))
    Text(
        "Builds a voiceprint from your wake takes, kept only on this phone and never backed up. " +
            "With it on, a wake that does not sound like you is ignored, and one it is unsure of " +
            "asks for a tap. Tapping to talk always works.",
        style = LegionType.stamp,
        color = sem.faint,
    )
    Spacer(Modifier.height(8.dp))
    VoiceSwitch(
        on = on,
        hasVoiceprint = info != null,
        onToggle = { v ->
            WakeWordPreferences.setOnlyMyVoice(context, v)
            on = v
        },
    )
    Spacer(Modifier.height(8.dp))
    VoiceprintControls(context = context, info = info, onDeleted = { on = false })
}

/** The stored voiceprint, the session choice, build and delete. */
@Composable
private fun VoiceprintControls(context: Context, info: SpeakerVerifier.Info?, onDeleted: () -> Unit) {
    val sem = LocalLegionSemantics.current
    val note by SpeakerVerifier.note.collectAsState()
    val lastTake by WakeSampleRecorder.lastTake.collectAsState()
    var sessions by remember { mutableStateOf<List<SpeakerVerifier.Session>>(emptyList()) }
    var chosen by remember { mutableStateOf<Set<String>>(emptySet()) }
    var deleteArmed by remember { mutableStateOf(false) }
    // Re-read after every take, so a session recorded on this screen shows up without leaving it.
    LaunchedEffect(lastTake) { sessions = withContext(Dispatchers.IO) { SpeakerVerifier.sessions(context) } }

    Text(
        info?.let {
            "Voiceprint from ${it.takes} takes, built " +
                DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(it.builtAtMs)) + "."
        } ?: "No voiceprint yet.",
        style = LegionType.stamp,
        color = sem.faint,
    )
    Spacer(Modifier.height(8.dp))
    SessionPicker(sessions = sessions, chosen = chosen, onChange = { chosen = it })
    Spacer(Modifier.height(8.dp))
    DeckButton(
        text = "Build voiceprint from chosen sessions",
        enabled = chosen.isNotEmpty(),
        onClick = { SpeakerVerifier.build(context, chosen) },
    )
    note?.let {
        Spacer(Modifier.height(4.dp))
        Text(it, style = LegionType.stamp, color = sem.faint)
    }
    Spacer(Modifier.height(8.dp))
    DeckButton(
        text = if (deleteArmed) "Confirm: delete voiceprint" else "Delete voiceprint",
        destructive = true,
        confirming = deleteArmed,
        enabled = info != null,
        onClick = {
            if (deleteArmed) {
                SpeakerVerifier.delete(context)
                onDeleted()
                deleteArmed = false
            } else {
                deleteArmed = true
            }
        },
    )
}

@Composable
private fun VoiceSwitch(on: Boolean, hasVoiceprint: Boolean, onToggle: (Boolean) -> Unit) {
    val sem = LocalLegionSemantics.current
    Surface(Modifier.fillMaxWidth(), tonalElevation = 1.dp) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("Only my voice", style = MaterialTheme.typography.bodyMedium)
                Text(
                    if (hasVoiceprint) {
                        "Off: every wake is scored and logged, nothing is held back."
                    } else {
                        "Needs a voiceprint first: record the wake takes above, then build one below."
                    },
                    style = LegionType.stamp,
                    color = sem.faint,
                )
            }
            // Turning off is always allowed; turning on needs a voiceprint.
            DeckSwitch(checked = on, onCheckedChange = onToggle, enabled = on || hasVoiceprint)
        }
    }
}

/** One switch per enrolment session; the voiceprint is built from the chosen ones together. */
@Composable
private fun SessionPicker(
    sessions: List<SpeakerVerifier.Session>,
    chosen: Set<String>,
    onChange: (Set<String>) -> Unit,
) {
    val sem = LocalLegionSemantics.current
    if (sessions.isEmpty()) {
        Text("No wake take sessions on this phone yet.", style = LegionType.stamp, color = sem.faint)
        return
    }
    Text(
        "Choose sessions recorded with only you talking (a session with a TV or a call in the " +
            "background can teach it the wrong voice). A voiceprint needs at least " +
            "${SpeakerPolicy.MIN_ENROL_TAKES} takes the wake word heard, across the chosen sessions.",
        style = LegionType.stamp,
        color = sem.faint,
    )
    sessions.forEach { s ->
        Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                "${s.name.removePrefix("enroll-")}: ${s.usableTakes} usable takes",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            DeckSwitch(
                checked = s.name in chosen,
                onCheckedChange = { v -> onChange(if (v) chosen + s.name else chosen - s.name) },
                enabled = s.usableTakes > 0,
            )
        }
    }
}
