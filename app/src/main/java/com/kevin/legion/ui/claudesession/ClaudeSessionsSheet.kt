package com.kevin.legion.ui.claudesession

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.kevin.legion.claude.ClaudeLiveWatch
import com.kevin.legion.claude.ClaudeModalHolder
import com.kevin.legion.claude.ClaudeOutcome
import com.kevin.legion.claude.ClaudeSessionController
import com.kevin.legion.claude.ClaudeWording
import com.kevin.legion.ui.theme.soft.SoftColors
import com.kevin.legion.ui.theme.soft.SoftTheme
import java.time.Duration
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/**
 * The header button's sheet (Kevin 2026-11-04: "a new button to open the existing claude sessions,
 * minimal view on what each is doing"). Reads through [ClaudeSessionController] like the full
 * screen, every [POLL_EVERY] while open, and hands each read to [ClaudeLiveWatch] so the badge and
 * the ongoing notification agree with it. A task row opens the task dialog
 * ([ClaudeModalHolder.open]); the link at the bottom opens the full Claude session screen.
 */
@Composable
fun ClaudeSessionsSheetHost(onDismiss: () -> Unit, onOpenAll: () -> Unit) {
    val context = LocalContext.current
    val controller = remember(context) { ClaudeSessionController.forContext(context) }
    val live = remember(context) { ClaudeLiveWatch.get(context) }
    val holder = remember(context) { ClaudeModalHolder.get(context) }
    var ui by remember { mutableStateOf(ClaudeSheetUi()) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (isActive) {
                ui = when (val out = controller.overview()) {
                    is ClaudeOutcome.Ok -> {
                        live.seen(out.value)
                        ClaudeSheetUi(loading = false, rows = ClaudeQuickRows.sheet(out.value, controller.wording))
                    }
                    else -> {
                        live.unreadable()
                        ui.copy(loading = false, unavailable = ClaudeWording.failed(out, "read the Claude sessions"))
                    }
                }
                delay(POLL_EVERY.toMillis())
            }
        }
    }
    SoftTheme {
        ClaudeSessionsSheet(
            ui = ui,
            onDismiss = onDismiss,
            onOpenTask = { id ->
                holder.open(id)
                onDismiss()
            },
            onOpenAll = {
                onDismiss()
                onOpenAll()
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ClaudeSessionsSheet(
    ui: ClaudeSheetUi,
    onDismiss: () -> Unit,
    onOpenTask: (Int) -> Unit,
    onOpenAll: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState, containerColor = SoftColors.cardHigh) {
        ClaudeSessionsSheetBody(ui, onOpenTask, onOpenAll)
    }
}

/** The sheet's body, apart from the sheet itself so a test draws it without sheet animation. */
@Composable
fun ClaudeSessionsSheetBody(ui: ClaudeSheetUi, onOpenTask: (Int) -> Unit, onOpenAll: () -> Unit) {
    Column(Modifier.fillMaxWidth().navigationBarsPadding().testTag(SHEET_TAG)) {
        Text(
            "Claude sessions",
            style = MaterialTheme.typography.titleMedium,
            color = SoftColors.text,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
        )
        when {
            ui.unavailable != null && ui.rows.isEmpty() -> SheetLine(ui.unavailable, SoftColors.onAlert)
            ui.loading && ui.rows.isEmpty() -> SheetLine("Reading...")
            ui.rows.isEmpty() -> SheetLine(EMPTY_SHEET)
            else -> {
                ui.unavailable?.let { SheetLine(it, SoftColors.onAlert) }
                LazyColumn(Modifier.heightIn(max = ROWS_MAX_HEIGHT)) {
                    items(ui.rows, key = { (it.taskId?.let { id -> "t$id" } ?: "s") + it.machine + it.doing }) { row ->
                        SheetRow(row, onOpenTask)
                        HorizontalDivider(color = SoftColors.outline.copy(alpha = 0.4f))
                    }
                }
            }
        }
        TextButton(
            onClick = onOpenAll,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp).heightIn(min = 48.dp),
        ) { Text("Open Claude sessions") }
    }
}

@Composable
private fun SheetLine(text: String, color: androidx.compose.ui.graphics.Color = SoftColors.text) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = color,
        modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
    )
}

@Composable
private fun SheetRow(row: ClaudeSheetRowUi, onOpenTask: (Int) -> Unit) {
    val tap = row.taskId?.let { id -> Modifier.clickable { onOpenTask(id) } } ?: Modifier
    Column(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .then(tap)
            .padding(horizontal = 20.dp, vertical = 8.dp)
            .testTag(SHEET_ROW_TAG),
    ) {
        Text(
            row.doing,
            style = MaterialTheme.typography.bodyLarge,
            color = SoftColors.text,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            "${row.statusText}, ${row.machine}, ${row.age}",
            style = MaterialTheme.typography.bodySmall,
            color = SoftColors.text2,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private const val POLL_SECONDS = 15L
private val POLL_EVERY: Duration = Duration.ofSeconds(POLL_SECONDS)
private val ROWS_MAX_HEIGHT = 420.dp

const val EMPTY_SHEET = "No Claude tasks or sessions yet. Ask by voice, or send one from the Claude sessions screen."
const val SHEET_TAG = "claude-sessions-sheet"
const val SHEET_ROW_TAG = "claude-sessions-sheet-row"
