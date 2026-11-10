package com.kevin.legion.ui.claudesession

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.kevin.legion.claude.ClaudeWorkflow
import com.kevin.legion.claude.ClaudeWorkflowWording
import com.kevin.legion.claude.ClaudeWording
import com.kevin.legion.ui.theme.soft.SoftColors

/**
 * One workflow on the Claude session screen (worker-workflows ticket 06, ADR 0035's hands path for
 * `action: "workflow"`): its name and one line, its last run, where it stops for Kevin's yes, its
 * standing mail and schedule in words, and Run with an optional params box.
 */
data class WorkflowRowUi(
    val machine: String,
    val name: String,
    val spoken: String,
    val says: String,
    val lastRun: String,
    /** "It stops for your yes first: ..." or null when it asks nothing. */
    val stops: String?,
    /** "Every run emails its result to you." (R3's standing yes), or null. */
    val mails: String?,
    /** The schedule in words, or why it does not run on its own on this machine; null for none. */
    val schedule: String?,
    /** The params it takes, as a label for the box ("which day"); null when it takes none. */
    val paramsHint: String?,
    /** A run is queued, running or waiting on his yes: Run is off and says why. */
    val waiting: Boolean,
) {
    /** The key the screen keeps this row's typed params under. */
    val key: String get() = "$machine/$name"
}

/** Pure: the engine's rows to the screen's, every sentence from [ClaudeWorkflowWording]. */
object ClaudeWorkflowRows {
    fun rows(list: List<ClaudeWorkflow>, wording: ClaudeWording = ClaudeWording()): List<WorkflowRowUi> =
        list.map { w ->
            WorkflowRowUi(
                machine = w.machine,
                name = w.name,
                spoken = w.spoken.replaceFirstChar(Char::uppercase),
                says = w.says,
                lastRun = ClaudeWorkflowWording.lastRun(w, wording),
                stops = ClaudeWorkflowWording.stops(w.asks).trim().ifEmpty { null },
                mails = ClaudeWorkflowWording.deliverLine(w),
                schedule = ClaudeWorkflowWording.schedule(w),
                paramsHint = w.params.takeIf { it.isNotEmpty() }?.joinToString { p ->
                    p.says.ifBlank { p.name } + if (p.required) "" else " (optional)"
                },
                waiting = w.waiting,
            )
        }
}

/** The Workflows section, between the send box and the tasks. Nothing at all when no machine has one. */
fun LazyListScope.workflowsSection(state: ClaudeSessionUiState, callbacks: ClaudeSessionCallbacks) {
    state.workflowsNote?.let { note -> item { WorkflowLine(note, SoftColors.onAlert) } }
    if (state.workflows.isEmpty()) return
    item {
        Text(
            "Workflows",
            style = MaterialTheme.typography.titleMedium,
            color = SoftColors.text,
            modifier = Modifier.padding(top = 16.dp, bottom = 6.dp),
        )
    }
    items(state.workflows, key = { "w-" + it.key }) { row ->
        WorkflowCard(row, state.workflowParams[row.key].orEmpty(), state.sending, callbacks)
    }
}

@Composable
private fun WorkflowCard(row: WorkflowRowUi, params: String, sending: Boolean, callbacks: ClaudeSessionCallbacks) {
    Column(
        Modifier.fillMaxWidth().padding(vertical = 4.dp).clip(RoundedCornerShape(16.dp))
            .background(SoftColors.card).padding(12.dp).testTag(WORKFLOW_TAG),
    ) {
        Text("${row.spoken} on the ${row.machine}", style = MaterialTheme.typography.bodyLarge, color = SoftColors.text)
        WorkflowLine(row.says)
        WorkflowLine(row.lastRun, SoftColors.text.copy(alpha = 0.75f))
        row.stops?.let { WorkflowLine(it, SoftColors.caution) }
        row.mails?.let { WorkflowLine(it) }
        row.schedule?.let { WorkflowLine(it, SoftColors.text.copy(alpha = 0.75f)) }
        row.paramsHint?.let { hint ->
            OutlinedTextField(
                value = params,
                onValueChange = { callbacks.onWorkflowParams(row.key, it) },
                label = { Text(hint) },
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (row.waiting) WorkflowLine("A run is already going or waiting on your yes.", SoftColors.caution)
        Button(
            onClick = { callbacks.onRunWorkflow(row.machine, row.name) },
            enabled = !row.waiting && !sending,
            modifier = Modifier.heightIn(min = 48.dp).padding(top = 4.dp),
        ) { Text("Run") }
    }
}

@Composable
private fun WorkflowLine(text: String, color: androidx.compose.ui.graphics.Color = SoftColors.text) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = color, modifier = Modifier.padding(vertical = 2.dp))
}

/** Each workflow card's test tag. */
const val WORKFLOW_TAG = "claude-session-workflow"
