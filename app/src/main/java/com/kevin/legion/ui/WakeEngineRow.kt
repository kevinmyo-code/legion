package com.kevin.legion.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.kevin.legion.service.WakeEngineKind
import com.kevin.legion.ui.common.DeckRadio
import com.kevin.legion.ui.theme.LegionType
import com.kevin.legion.ui.theme.LocalLegionSemantics
import java.util.Locale
import kotlin.math.roundToInt

/**
 * "Wake word engine" (Kevin 2026-11-10): the current keyword spotter + confirm, or openWakeWord, with
 * openWakeWord's threshold. Both choices stay, so the two can be compared on the phone and he can
 * switch back. [note] is the engine's own word on what is actually running ("model missing - using
 * the current engine"), shown so a fallback is never silent. The threshold is committed when the
 * slider is let go, not on every step, because each commit restarts the capture.
 */
@Suppress("FunctionNaming", "LongParameterList") // @Composable naming; one row, its own state in
@Composable
fun WakeEngineRow(
    engine: WakeEngineKind,
    threshold: Float,
    note: String?,
    onSelect: (WakeEngineKind) -> Unit,
    onThreshold: (Float) -> Unit,
) {
    val sem = LocalLegionSemantics.current
    var dragging by remember(threshold) { mutableFloatStateOf(threshold) }
    Surface(Modifier.fillMaxWidth(), tonalElevation = 1.dp) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
            Text("Wake word engine", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
            Text(
                "Which detector listens for the wake phrase. openWakeWord is new and not yet tested on this phone.",
                style = LegionType.stamp,
                color = sem.faint,
            )
            Spacer(Modifier.height(4.dp))
            Column(Modifier.fillMaxWidth().selectableGroup(), verticalArrangement = Arrangement.spacedBy(0.dp)) {
                for (kind in WakeEngineKind.entries) {
                    DeckRadio(selected = engine == kind, onClick = { onSelect(kind) }, label = kind.label)
                }
            }
            if (engine == WakeEngineKind.OPEN_WAKE_WORD) {
                Spacer(Modifier.height(4.dp))
                Text(
                    "Threshold " + String.format(Locale.ROOT, "%.2f", dragging) +
                        " - lower wakes more easily and false-wakes more often.",
                    style = LegionType.stamp,
                    color = sem.faint,
                )
                Slider(
                    value = dragging,
                    onValueChange = { dragging = (it * STEPS).roundToInt() / STEPS },
                    onValueChangeFinished = { onThreshold(dragging) },
                    valueRange = WakeEngineKind.THRESHOLD_RANGE,
                    modifier = Modifier.semantics { contentDescription = "openWakeWord threshold" },
                )
            }
            if (note != null) {
                Text(note, style = LegionType.stamp, color = sem.faint)
            }
        }
    }
}

private const val STEPS = 20f
