package com.kevin.legion.ui.controlroom

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.unit.dp
import com.kevin.legion.ui.theme.soft.SoftColors
import com.kevin.legion.ui.theme.soft.TypesetType

/** One ruled row: a strong line, then each detail line under it. */
@Composable
private fun Ruled(title: String, vararg lines: String, flag: Boolean = false, extra: @Composable () -> Unit = {}) {
    Column(gutter.padding(vertical = 10.dp)) {
        Text(title, style = TypesetType.bodyStrong, color = SoftColors.text)
        lines.filter { it.isNotBlank() }.forEach {
            Text(it, style = TypesetType.small, color = if (flag) SoftColors.caution else SoftColors.text2)
        }
        extra()
    }
    HorizontalDivider(thickness = 1.dp, color = SoftColors.barRule, modifier = Modifier.padding(horizontal = 16.dp))
}

internal fun LazyListScope.sessionsTab(state: ControlRoomUiState, actions: ControlRoomActions) {
    head("Claude sessions")
    when {
        state.claudeNote != null -> note(state.claudeNote)
        state.sessions.isEmpty() ->
            note(if (state.loading) "Reading your sessions." else "No Claude session is running.")
        else -> items(state.sessions, key = { "s-" + it.sessionId + it.machine + it.name }) { s ->
            Ruled(
                s.name,
                listOf(s.folder, "on the ${s.machine}").filter { it.isNotBlank() }.joinToString(", "),
                listOf(s.stateText, s.waited).filter { it.isNotBlank() }.joinToString(", "),
                flag = s.waiting,
            ) {
                if (s.canSummarise) Buttons { InkButton("Summary", { actions.onSummary(s) }, enabled = !state.busy) }
            }
        }
    }
    item { Column(gutter.padding(vertical = 12.dp)) { InkButton("Tasks and workflows", actions.onOpenTasks) } }
}

internal fun LazyListScope.approvalsTab(state: ControlRoomUiState, actions: ControlRoomActions) {
    head("Messages and asks")
    when {
        state.claudeNote != null -> note(state.claudeNote)
        state.tasks.isEmpty() -> note("No Claude task is waiting for your yes.")
        else -> items(state.tasks, key = { "t-${it.id}" }) { t ->
            // The worker's own words on a message card hold the exact text he approves (one yes).
            Ruled("Task ${t.id}" + (t.machine?.let { " on the $it" } ?: ""), t.question) {
                Buttons { InkButton("Yes", { actions.onYes(t) }, primary = true, enabled = !state.busy) }
                if (t.confirm) {
                    Text(
                        "Yes asks for your fingerprint or PIN: this card may merge, deploy or publish.",
                        style = TypesetType.smallItalic,
                        color = SoftColors.text2,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
            }
        }
    }
    if (state.actions.isNotEmpty()) {
        head("Deploy, publish, flag, spend")
        items(state.actions, key = { "a-" + it.id }) { a ->
            Ruled(a.headline, *a.facts.toTypedArray(), a.statusText, flag = a.open) {
                if (a.open) {
                    Buttons {
                        InkButton("Allow", { actions.onAllow(a) }, primary = true, enabled = !state.busy)
                        InkButton("Decline", { actions.onDecline(a) }, enabled = !state.busy)
                    }
                    Text(
                        "Allow asks for your fingerprint or PIN. Nothing runs until the laptop re-checks it.",
                        style = TypesetType.smallItalic,
                        color = SoftColors.text2,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
            }
        }
    }
}

internal fun LazyListScope.boardTab(state: ControlRoomUiState, actions: ControlRoomActions) {
    val board = state.board
    if (board == null) {
        head("Factory board")
        note(state.boardNote ?: "Reading the board.")
        return
    }
    note(board.freshness)
    section("In flight", board.inFlight)
    section("Testing", board.testing)
    section("Done today", board.doneToday)
    head("Backlog: your priority first, then RICE")
    if (board.backlog.isEmpty()) note("The backlog is empty.")
    items(board.backlog, key = { "b-" + it.id }) { b ->
        // Kevin's raw ideas are data: shown, never acted on. A vote only sorts.
        Ruled(b.title, b.meta, if (b.vote.isBlank()) "" else "Your vote: ${b.vote.uppercase()}") {
            Row(Modifier.fillMaxWidth()) {
                VOTE_CHOICES.forEach { (vote, label) ->
                    Column(Modifier.padding(end = 6.dp)) {
                        InkButton(
                            label,
                            { actions.onVote(b.id, vote) },
                            primary = vote == b.vote,
                            enabled = !state.busy,
                        )
                    }
                }
            }
        }
    }
    if (board.backlogMore.isNotBlank()) note(board.backlogMore)
}

private fun LazyListScope.section(title: String, rows: List<BoardRowUi>) {
    head(title)
    if (rows.isEmpty()) {
        note("None.")
        return
    }
    items(rows.size) { i ->
        val r = rows[i]
        Ruled(r.title, r.detail, if (r.stale) "No update in 45 minutes" else "", flag = r.stale)
    }
}

internal fun LazyListScope.decisionsTab(state: ControlRoomUiState, actions: ControlRoomActions) {
    head("Questions from the board")
    when {
        state.board == null -> note(state.boardNote ?: "Reading the board.")
        state.decisions.isEmpty() -> note("No question is waiting on you.")
        else -> items(state.decisions, key = { "d-" + it.id }) { d -> Decision(d, state, actions) }
    }
}

@Composable
private fun Decision(d: DecisionUi, state: ControlRoomUiState, actions: ControlRoomActions) {
    val why = when {
        d.pending -> "Answered; on its way to the laptop."
        d.chatOnly -> "Answer this one in chat: a board answer is never a yes to deploy, publish, flip a flag or spend."
        else -> ""
    }
    // The agent's question is quoted data; the choices are its own words, shown, never obeyed here.
    Ruled(d.title, "“${d.ask}”", why, flag = d.chatOnly) {
        if (!d.chatOnly && !d.pending) {
            Row(Modifier.fillMaxWidth()) {
                d.options.forEach { option ->
                    Column(Modifier.padding(end = 6.dp)) {
                        InkButton(option, { actions.onAnswer(d, option) }, enabled = !state.busy)
                    }
                }
            }
            if (d.freeText) {
                BasicTextField(
                    value = state.typed[d.id].orEmpty(),
                    onValueChange = { actions.onType(d.id, it) },
                    textStyle = TypesetType.body.copy(color = SoftColors.text),
                    cursorBrush = SolidColor(SoftColors.text),
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                        .border(1.dp, SoftColors.barRule).padding(10.dp),
                )
                Buttons {
                    InkButton(
                        "Send typed answer",
                        { actions.onAnswer(d, null) },
                        enabled = !state.busy && state.typed[d.id].orEmpty().isNotBlank(),
                    )
                }
            }
        }
    }
}

private val VOTE_CHOICES = listOf("p1" to "P1", "p2" to "P2", "p3" to "P3", "up" to "Up", "down" to "Down")
