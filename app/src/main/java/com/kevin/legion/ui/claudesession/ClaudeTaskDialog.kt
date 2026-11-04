package com.kevin.legion.ui.claudesession

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.kevin.legion.claude.ClaudeModalHolder
import com.kevin.legion.claude.ClaudeOutcome
import com.kevin.legion.claude.ClaudeSessionController
import com.kevin.legion.claude.ClaudeWording
import com.kevin.legion.ui.theme.soft.SoftColors
import com.kevin.legion.ui.theme.soft.SoftTheme
import java.time.Duration

/**
 * The dialog over the app that follows one Claude task (Kevin 2026-11-04), mounted once in
 * MainActivity's shell so it opens over whichever screen is up. It shows the task
 * [ClaudeModalHolder.pending] names, polls that task only while drawn and the app is started
 * ([ClaudeQuickRows.follow]), and stays until Kevin closes it: a finished task never dismisses it.
 * [suppressed] (driving mode) keeps it pending without drawing it.
 */
@Composable
fun ClaudeTaskModalHost(
    onOpenAll: () -> Unit,
    suppressed: Boolean = false,
    /** Test seams: production reads the process holder and the engine-backed controller. */
    holderOverride: ClaudeModalHolder? = null,
    controllerOverride: ClaudeSessionController? = null,
    pollEvery: Duration = POLL_EVERY,
) {
    val context = LocalContext.current
    val holder = holderOverride ?: remember(context) { ClaudeModalHolder.get(context) }
    val pending by holder.pending.collectAsStateWithLifecycle()
    val p = pending ?: return
    if (suppressed || !holder.mayShow(p)) {
        // Started while the app was away and too old now: dropped unseen (see the holder's doc).
        LaunchedEffect(p, suppressed) { if (!suppressed) holder.admit(p) }
        return
    }
    LaunchedEffect(p) { holder.admit(p) }
    val controller = controllerOverride ?: remember(context) { ClaudeSessionController.forContext(context) }
    val ui = rememberFollowedTask(controller, p.taskId, pollEvery)
    val close = { holder.close(p.taskId) }
    SoftTheme {
        Dialog(onDismissRequest = close) {
            ClaudeTaskDialogContent(
                ui = ui,
                taskId = p.taskId,
                onClose = close,
                onOpenAll = {
                    close()
                    onOpenAll()
                },
            )
        }
    }
}

/** Task [id] as the dialog draws it, read every [every] while the app is started. */
@Composable
private fun rememberFollowedTask(controller: ClaudeSessionController, id: Int, every: Duration): ClaudeTaskModalUi? {
    var ui by remember(id) { mutableStateOf<ClaudeTaskModalUi?>(null) }
    var failure by remember(id) { mutableStateOf<String?>(null) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(id, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            ClaudeQuickRows.follow(controller, id, every).collect { out ->
                when (out) {
                    is ClaudeOutcome.Ok -> {
                        ui = ClaudeQuickRows.modal(out.value, controller.wording)
                        failure = null
                    }
                    else -> failure = ClaudeWording.failed(out, "read task $id")
                }
            }
        }
    }
    return ui?.copy(unavailable = failure) ?: failure?.let { unreadable(id, it) }
}

private fun unreadable(id: Int, sentence: String) = ClaudeTaskModalUi(
    taskId = id, request = "", machineText = "", statusText = "Not known", currentStep = null,
    steps = emptyList(), outcome = null, nextStep = null, finished = false, unavailable = sentence,
)

/**
 * The dialog's stateless body. [ui] null is the first read still on its way. Status is always a
 * word; colour only repeats it.
 */
@Composable
fun ClaudeTaskDialogContent(ui: ClaudeTaskModalUi?, taskId: Int, onClose: () -> Unit, onOpenAll: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(SoftColors.cardHigh)
            .padding(20.dp)
            .testTag(DIALOG_TAG),
    ) {
        Text("Claude, task $taskId", style = MaterialTheme.typography.labelLarge, color = SoftColors.text2)
        if (ui == null) {
            Text("Reading...", color = SoftColors.text, modifier = Modifier.padding(vertical = 12.dp))
        } else {
            DialogBody(ui)
        }
        Row(
            Modifier.fillMaxWidth().padding(top = 12.dp),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onOpenAll, modifier = Modifier.heightIn(min = 48.dp)) { Text("See all") }
            Button(onClick = onClose, modifier = Modifier.heightIn(min = 48.dp)) { Text("Close") }
        }
    }
}

@Composable
private fun DialogBody(ui: ClaudeTaskModalUi) {
    if (ui.request.isNotEmpty()) {
        Text(
            ui.request,
            style = MaterialTheme.typography.titleMedium,
            color = SoftColors.text,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
    if (ui.machineText.isNotEmpty()) Text(ui.machineText, color = SoftColors.text2)
    Text(
        ui.statusText,
        style = MaterialTheme.typography.titleSmall,
        color = if (ui.finished) SoftColors.text else SoftColors.caution,
        modifier = Modifier.padding(top = 8.dp).testTag(STATUS_TAG),
    )
    ui.currentStep?.let { Text("Now: $it", color = SoftColors.text, modifier = Modifier.padding(top = 2.dp)) }
    ui.outcome?.let { Text(it, color = SoftColors.text, modifier = Modifier.padding(top = 8.dp)) }
    ui.nextStep?.let { Text(it, color = SoftColors.text2, modifier = Modifier.padding(top = 4.dp)) }
    ui.unavailable?.let { Text(it, color = SoftColors.onAlert, modifier = Modifier.padding(top = 8.dp)) }
    if (ui.steps.isNotEmpty()) {
        Text(
            "Steps",
            style = MaterialTheme.typography.labelLarge,
            color = SoftColors.text,
            modifier = Modifier.padding(top = 12.dp, bottom = 2.dp),
        )
        Column(Modifier.heightIn(max = STEPS_MAX_HEIGHT).verticalScroll(rememberScrollState())) {
            ui.steps.forEach { step ->
                Row(Modifier.fillMaxWidth().padding(vertical = 1.dp).testTag(DIALOG_STEP_TAG)) {
                    Text(
                        step.time,
                        style = MaterialTheme.typography.bodySmall,
                        color = SoftColors.text3,
                        modifier = Modifier.width(STEP_TIME_WIDTH),
                    )
                    Text(
                        step.text,
                        style = MaterialTheme.typography.bodySmall,
                        color = SoftColors.text,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

private const val POLL_SECONDS = 5L
private val POLL_EVERY: Duration = Duration.ofSeconds(POLL_SECONDS)
private val STEPS_MAX_HEIGHT = 220.dp
private val STEP_TIME_WIDTH = 96.dp

const val DIALOG_TAG = "claude-task-dialog"
const val DIALOG_STEP_TAG = "claude-task-dialog-step"
const val STATUS_TAG = "claude-task-dialog-status"
