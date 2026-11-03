@file:Suppress("TooManyFunctions") // one small private composable per part of the screen

package com.kevin.legion.ui.claudesession

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.kevin.legion.R
import com.kevin.legion.ui.theme.soft.MsIcon
import com.kevin.legion.ui.theme.soft.SoftColors

/**
 * The stateless screen: machines with their state and "off since", a box to send a task (machine
 * picker, "Email it to me"), the newest tasks (tap one for its summary, its question with an answer
 * box, and its found files with Send to phone), the interactive sessions with "waiting on you"
 * first, and the switch for result announcements.
 */
@Composable
fun ClaudeSessionContent(state: ClaudeSessionUiState, callbacks: ClaudeSessionCallbacks) {
    Column(Modifier.fillMaxSize().background(SoftColors.ground)) {
        TopBar(callbacks)
        LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp).testTag(LIST_TAG)) {
            state.unavailable?.let { sentence ->
                item { Line(sentence, SoftColors.onAlert) }
                item { TextButton(onClick = callbacks.onRefresh) { Text("Try again") } }
            }
            if (state.loading && state.machines.isEmpty() && state.unavailable == null) item { Line("Reading...") }
            if (state.unavailable == null && !state.loading) {
                item { Header("Machines") }
                if (state.machines.isEmpty()) item { Line("No machine has ever connected.") }
                items(state.machines, key = { "m-" + it.name }) { MachineRow(it) }
            }
            item { SendBox(state, callbacks) }
            state.message?.let { item { Line(it, SoftColors.good) } }
            if (state.tasks.isNotEmpty()) item { Header("Tasks") }
            items(state.tasks, key = { "t-" + it.id }) { task ->
                TaskCard(task, expanded = state.expandedTask == task.id, state, callbacks)
            }
            if (state.sessions.isNotEmpty()) item { Header("Claude Code sessions") }
            items(state.sessions, key = { "s-" + it.machine + it.project + it.stateText }) { SessionRow(it) }
            item { AnnouncementsRow(state.announcementsOn, callbacks.onAnnouncements) }
        }
    }
}

@Composable
private fun TopBar(callbacks: ClaudeSessionCallbacks) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = callbacks.onBack, modifier = Modifier.size(48.dp)) {
            MsIcon(res = R.drawable.ms_arrow_back, contentDescription = "Back", tint = SoftColors.text)
        }
        Text(
            "Claude session",
            style = MaterialTheme.typography.titleLarge,
            color = SoftColors.text,
            modifier = Modifier.weight(1f).padding(start = 4.dp),
        )
        TextButton(onClick = callbacks.onRefresh) { Text("Refresh") }
    }
}

@Composable
private fun Header(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleMedium,
        color = SoftColors.text,
        modifier = Modifier.padding(top = 16.dp, bottom = 6.dp),
    )
}

@Composable
private fun Line(text: String, color: Color = SoftColors.text) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = color,
        modifier = Modifier.padding(vertical = 4.dp),
    )
}

@Composable
private fun Card(content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(vertical = 4.dp).clip(RoundedCornerShape(16.dp))
            .background(SoftColors.card).padding(12.dp),
    ) { content() }
}

@Composable
private fun MachineRow(m: MachineRowUi) = Card {
    Text(m.name, style = MaterialTheme.typography.bodyLarge, color = SoftColors.text)
    Text(
        m.stateText,
        style = MaterialTheme.typography.bodyMedium,
        color = if (m.online) SoftColors.good else SoftColors.caution,
    )
    if (m.summariesOnly) Line("Summaries only, never files.")
}

@Composable
private fun SendBox(state: ClaudeSessionUiState, callbacks: ClaudeSessionCallbacks) {
    Column(Modifier.padding(top = 12.dp)) {
        OutlinedTextField(
            value = state.draft,
            onValueChange = callbacks.onDraft,
            label = { Text("What should Claude do?") },
            modifier = Modifier.fillMaxWidth(),
        )
        Row(
            Modifier.fillMaxWidth().padding(top = 6.dp).horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Choice("Whichever is on", state.pickedMachine == null) { callbacks.onPickMachine(null) }
            state.machines.forEach { m ->
                Choice(m.name, state.pickedMachine == m.name) { callbacks.onPickMachine(m.name) }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = state.emailMe, onCheckedChange = callbacks.onEmailMe)
            Text("Email it to me", color = SoftColors.text)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = state.pageMe, onCheckedChange = callbacks.onPageMe)
            Text("As a page on my phone (Alienware)", color = SoftColors.text)
        }
        Button(
            onClick = callbacks.onSend,
            enabled = state.draft.isNotBlank() && !state.sending,
            modifier = Modifier.heightIn(min = 48.dp),
        ) { Text(if (state.sending) "Sending..." else "Send") }
    }
}

@Composable
private fun Choice(label: String, picked: Boolean, onClick: () -> Unit) {
    if (picked) {
        Button(onClick = onClick, modifier = Modifier.heightIn(min = 44.dp)) { Text(label) }
    } else {
        OutlinedButton(onClick = onClick, modifier = Modifier.heightIn(min = 44.dp)) { Text(label) }
    }
}

@Composable
private fun TaskCard(
    task: TaskRowUi,
    expanded: Boolean,
    state: ClaudeSessionUiState,
    callbacks: ClaudeSessionCallbacks,
) =
    Card {
        Column(Modifier.fillMaxWidth().clickable { callbacks.onToggleTask(task.id) }) {
            Text(
                task.request,
                style = MaterialTheme.typography.bodyLarge,
                color = SoftColors.text,
                maxLines = if (expanded) EXPANDED_LINES else 2,
            )
            val where = task.machine?.let { " on the $it" } ?: ""
            Text("Task ${task.id}$where: ${task.statusText}", color = SoftColors.text.copy(alpha = 0.75f))
            task.progress?.let { Text("Now: $it", color = SoftColors.caution) }
        }
        if (expanded) TaskDetail(task, state, callbacks)
    }

@Composable
private fun TaskDetail(task: TaskRowUi, state: ClaudeSessionUiState, callbacks: ClaudeSessionCallbacks) {
    task.summary?.let { Line(it) }
    task.question?.let { Line("Question: $it", SoftColors.caution) }
    task.pageTitle?.let { title ->
        Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(title, color = SoftColors.text, modifier = Modifier.weight(1f))
            Button(onClick = { callbacks.onOpenPage(task.id) }, modifier = Modifier.heightIn(min = 48.dp)) {
                Text("Open page")
            }
        }
    }
    if (task.pageTitle == null && task.pageWaiting) Line("The page has not arrived.", SoftColors.caution)
    if (task.needsMachine) {
        Line("Which machine?")
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            state.machines.forEach { m -> Choice(m.name, false) { callbacks.onAssign(task.id, m.name) } }
        }
    }
    if (task.needsConfirmation) {
        Button(onClick = { callbacks.onAnswer(task.id, true) }, enabled = !state.sending) { Text("Yes, go ahead") }
    }
    OutlinedTextField(
        value = state.answer,
        onValueChange = callbacks.onAnswerText,
        label = { Text(if (task.needsConfirmation) "Or answer" else "Follow up") },
        modifier = Modifier.fillMaxWidth(),
    )
    TextButton(
        onClick = { callbacks.onAnswer(task.id, false) },
        enabled = state.answer.isNotBlank() && !state.sending,
    ) { Text("Send answer") }
    task.files.forEach { f -> FileRow(task, f, callbacks) }
}

@Composable
private fun FileRow(task: TaskRowUi, f: FileRowUi, callbacks: ClaudeSessionCallbacks) {
    Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("${f.name} (${f.sizeText})", color = SoftColors.text, modifier = Modifier.weight(1f))
        when {
            !task.filesMayCome -> Text("Summary only", color = SoftColors.text.copy(alpha = 0.7f))
            f.uploaded -> Text("Uploaded", color = SoftColors.good)
            f.requested -> Text("Requested", color = SoftColors.caution)
            else -> OutlinedButton(onClick = { callbacks.onSendFile(task.id, f.id) }) { Text("Send to phone") }
        }
    }
}

@Composable
private fun SessionRow(s: SessionRowUi) = Card {
    Text("${s.project} on the ${s.machine}", style = MaterialTheme.typography.bodyLarge, color = SoftColors.text)
    Text(s.stateText, color = if (s.waiting) SoftColors.caution else SoftColors.text.copy(alpha = 0.75f))
    s.summary?.let { Text(it, color = SoftColors.text.copy(alpha = 0.75f), maxLines = 2) }
}

@Composable
private fun AnnouncementsRow(on: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("Tell me when a task finishes", color = SoftColors.text)
            Text("Off: results only wait here.", color = SoftColors.text.copy(alpha = 0.7f))
        }
        Switch(checked = on, onCheckedChange = onChange)
    }
}

private const val EXPANDED_LINES = 8

/** The list's test tag, so a test can scroll to a row below the fold. */
const val LIST_TAG = "claude-session-list"
