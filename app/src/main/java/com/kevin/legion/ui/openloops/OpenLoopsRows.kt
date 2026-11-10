@file:Suppress("FunctionNaming") // @Composable convention is PascalCase; detekt's rule does not know it.

package com.kevin.legion.ui.openloops

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.kevin.legion.openloops.LoopsView
import com.kevin.legion.openloops.OpenLoopsBrief
import com.kevin.legion.openloops.OpenLoopsController
import com.kevin.legion.openloops.Proposal
import com.kevin.legion.ui.common.DeckSwitch
import com.kevin.legion.ui.theme.LegionType
import com.kevin.legion.ui.theme.LocalLegionSemantics
import kotlinx.coroutines.launch

/** What the rows draw. [note] is the last tap's words, or why the list could not be read. */
data class OpenLoopsUi(
    val loaded: Boolean = false,
    val proposing: Boolean = true,
    val on: Boolean = true,
    val proposals: List<Proposal> = emptyList(),
    val note: String? = null,
)

/**
 * The hands path for proposed to-dos (ADR 0035, ADR 0085): the "stop proposing tasks" switch and
 * the pending list with Track and Dismiss, beside the spoken brief rows under Digest on Settings,
 * Proactive speech. Every tap calls [OpenLoopsController], the controller the voice answers call.
 * Self-loading, so the screen that hosts it adds one line. New UI in its own file: a redesign of
 * the settings screens is in flight elsewhere.
 */
@Composable
fun OpenLoopsRows(controllerFor: (android.content.Context) -> OpenLoopsController = OpenLoopsController::forContext) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val controller = remember { controllerFor(context) }
    var ui by remember { mutableStateOf(OpenLoopsUi()) }

    suspend fun reload(note: String? = null) {
        ui = when (val v = controller.view()) {
            is LoopsView.Showing -> OpenLoopsUi(
                loaded = true,
                proposing = v.listing.proposing,
                on = v.listing.on,
                proposals = v.listing.proposals,
                note = note,
            )
            is LoopsView.Unavailable -> ui.copy(loaded = true, note = v.sentence)
        }
    }

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { scope.launch { reload() } }

    OpenLoopsRowsContent(
        ui = ui,
        onToggle = { on ->
            ui = ui.copy(proposing = on)
            scope.launch { reload(controller.setProposing(on).words) }
        },
        onTrack = { p -> scope.launch { reload(controller.track(p.id).words) } },
        onDismiss = { p -> scope.launch { reload(controller.dismiss(p.id).words) } },
    )
}

/** Stateless, so a test can render it with fakes. */
@Composable
fun OpenLoopsRowsContent(
    ui: OpenLoopsUi,
    onToggle: (Boolean) -> Unit,
    onTrack: (Proposal) -> Unit,
    onDismiss: (Proposal) -> Unit,
) {
    val sem = LocalLegionSemantics.current
    Spacer(Modifier.height(4.dp))
    Surface(Modifier.fillMaxWidth(), tonalElevation = 1.dp) {
        Column(Modifier.fillMaxWidth().padding(start = 28.dp, end = 12.dp, top = 8.dp, bottom = 8.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        "Propose to-dos",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        "When you say you'll do something and don't set it, the brief asks whether to " +
                            "track it. Nothing is tracked without your yes. \"${OpenLoopsBrief.SILENCE_INSTRUCTION}\" " +
                            "turns this off." + if (ui.on) "" else " Off on the engine for now.",
                        style = LegionType.stamp,
                        color = sem.faint,
                    )
                }
                DeckSwitch(checked = ui.proposing, onCheckedChange = onToggle)
            }
            if (ui.proposing && ui.on) {
                if (ui.loaded && ui.proposals.isEmpty()) {
                    Text("No proposed to-dos.", style = LegionType.stamp, color = sem.faint)
                }
                ui.proposals.forEach { p -> ProposalRow(p, onTrack, onDismiss) }
            }
            ui.note?.let { Text(it, style = LegionType.stamp, color = MaterialTheme.colorScheme.onSurface) }
        }
    }
}

@Composable
private fun ProposalRow(p: Proposal, onTrack: (Proposal) -> Unit, onDismiss: (Proposal) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            p.title + (p.dueDate?.let { " (${OpenLoopsBrief.dayWords(it)})" } ?: ", no date"),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = { onTrack(p) }) { Text("Track") }
        TextButton(onClick = { onDismiss(p) }) { Text("Dismiss") }
    }
}
