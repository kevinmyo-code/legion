package com.kevin.legion.ui.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.kevin.legion.backend.engine.EngineConfig
import com.kevin.legion.backend.engine.EngineHttp
import com.kevin.legion.board.BoardOutcome
import com.kevin.legion.board.BoardQueue
import com.kevin.legion.board.EngineControlRoomBackend
import com.kevin.legion.claude.ClaudeLiveWatch
import com.kevin.legion.claude.ClaudeSessionController
import com.kevin.legion.ui.theme.soft.SoftColors
import java.time.Duration
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/**
 * HOME's one quiet Claude line, "Claude: 3 running, 1 needs you" (Kevin 2026-11-03), or null when
 * there is nothing to report or the engine could not be read (HOME never shows an error for this).
 *
 * The state is [ClaudeLiveWatch.summary], the same summary the ongoing notification follows, read
 * through [ClaudeSessionController] like the Claude session screen. It polls every
 * [POLL_EVERY] **only while HOME is started** (`repeatOnLifecycle`; Navigation keeps a covered HOME
 * below STARTED), and reuses any read the result watcher made in the last [REUSE_WITHIN].
 * When anything waits on him, the control room's [needsYouLine] takes its place (one queue read
 * per tick, same lifecycle).
 */
@Composable
fun rememberClaudeWatchLine(): String? {
    val context = LocalContext.current
    val live = remember(context) { ClaudeLiveWatch.get(context) }
    val controller = remember(context) { ClaudeSessionController.forContext(context) }
    val queue = remember(context) {
        EngineControlRoomBackend(EngineHttp(EngineConfig(context.applicationContext)))
    }
    var needsYou by remember { mutableStateOf<String?>(null) }
    var offTicks = 0
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(lifecycle, live) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (isActive) {
                live.refreshIfStale(controller, REUSE_WITHIN)
                // The board dark on the engine: one queue read per OFF_EVERY ticks, not every tick.
                if (offTicks > 0) {
                    offTicks--
                } else {
                    val read = queue.queue()
                    val off = (read as? BoardOutcome.Failed)?.sentence == EngineControlRoomBackend.OFF
                    offTicks = if (off) OFF_EVERY else 0
                    needsYou = needsYouLine(read)
                }
                delay(POLL_EVERY.toMillis())
            }
        }
    }
    val summary by live.summary.collectAsStateWithLifecycle()
    return needsYou ?: summary?.homeLine
}

/**
 * The control room's "Needs you: N sessions, N approvals, N decisions" (control room C4), the
 * engine's code-built line, when anything waits on him; null otherwise (nothing waits, or the
 * board is switched off or unreadable), so the Claude line shows as before.
 */
internal fun needsYouLine(out: BoardOutcome<BoardQueue>): String? =
    (out as? BoardOutcome.Ok)?.value?.takeIf { it.total > 0 }?.line?.takeIf { it.isNotBlank() }

/** The line itself; nothing at all when [line] is null. Tapping opens the control room. */
@Composable
fun ClaudeWatchLine(line: String?, onOpen: () -> Unit) {
    line ?: return
    Text(
        line,
        style = MaterialTheme.typography.labelMedium,
        color = SoftColors.text2,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 4.dp)
            .heightIn(min = MIN_TOUCH)
            .clickable(onClick = onOpen)
            .wrapContentHeight()
            .padding(horizontal = 4.dp)
            .testTag(CLAUDE_LINE_TAG),
    )
}

private const val POLL_SECONDS = 30L
private const val OFF_EVERY = 30
private const val REUSE_SECONDS = 25L
private val POLL_EVERY: Duration = Duration.ofSeconds(POLL_SECONDS)
private val REUSE_WITHIN: Duration = Duration.ofSeconds(REUSE_SECONDS)
private val MIN_TOUCH = 44.dp

const val CLAUDE_LINE_TAG = "home-claude-line"
