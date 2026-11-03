package com.kevin.legion.ui.dumb

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.kevin.legion.dumb.DumbFormat
import com.kevin.legion.dumb.FeedOffGate
import com.kevin.legion.dumb.FeedOffRule
import com.kevin.legion.dumb.GateResult
import com.kevin.legion.ui.theme.LegionType
import com.kevin.legion.ui.theme.LocalLegionSemantics
import kotlinx.coroutines.delay

/**
 * The wait and sentence in front of turning the feed block down (ADR 0066, 2026-11-02): the same 60
 * second wall-clock wait and typed sentence as a 15 minute pass, shown under the row that asked.
 * The countdown is re-read from the persisted instant each tick, so leaving the screen, locking the
 * phone or losing the process does not reset it. [FeedOffGate] re-checks every rule; this is the view.
 *
 * [onConfirmed] applies the change; [onDropped] runs when he cancels or the request lapses.
 */
@Composable
internal fun FeedOffGatePanel(
    gate: FeedOffGate,
    target: String,
    sentence: String,
    confirmLabel: String,
    onConfirmed: () -> Unit,
    onDropped: () -> Unit,
) {
    var waitLeftMs by remember(target) { mutableLongStateOf(gate.waitRemainingMs(target) ?: 0L) }
    var typed by remember(target) { mutableStateOf("") }
    var note by remember(target) { mutableStateOf<String?>(null) }

    LaunchedEffect(target) {
        while (true) {
            val left = gate.waitRemainingMs(target)
            if (left == null) {
                onDropped()
                break
            }
            waitLeftMs = left
            delay(GATE_TICK_MS)
        }
    }

    val matches = FeedOffRule.matches(typed, sentence)
    Surface(Modifier.fillMaxWidth(), tonalElevation = 3.dp) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (waitLeftMs > 0L) {
                GateCountdown(waitLeftMs)
            } else {
                GateSentence(sentence, typed, note ?: if (matches) "matches" else "not yet") {
                    typed = it
                    note = null
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = {
                    gate.cancel(target)
                    onDropped()
                }) { Text("Keep it") }
                if (waitLeftMs == 0L) {
                    Button(
                        enabled = matches,
                        onClick = {
                            when (val result = gate.confirm(target, sentence, typed)) {
                                GateResult.Confirmed -> onConfirmed()
                                GateResult.NotRequested -> onDropped()
                                is GateResult.TooEarly ->
                                    note = "Still waiting: ${DumbFormat.secondsCeil(result.remainingMs)} seconds left."
                                GateResult.WrongSentence -> note = "That is not the sentence yet."
                                is GateResult.Waiting -> Unit
                            }
                        },
                    ) { Text(confirmLabel) }
                }
            }
        }
    }
}

@Composable
private fun GateCountdown(waitLeftMs: Long) {
    Text(
        "${DumbFormat.secondsCeil(waitLeftMs)} seconds, then a sentence. The wait keeps running if " +
            "you leave this screen; it lapses 10 minutes after you asked.",
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
    )
}

@Composable
private fun GateSentence(sentence: String, typed: String, note: String, onTyped: (String) -> Unit) {
    val sem = LocalLegionSemantics.current
    Text("Type: $sentence", style = LegionType.stamp, color = sem.faint)
    OutlinedTextField(value = typed, onValueChange = onTyped, singleLine = true, modifier = Modifier.fillMaxWidth())
    Text(note, style = LegionType.stamp, color = sem.faint)
}

private const val GATE_TICK_MS = 250L
