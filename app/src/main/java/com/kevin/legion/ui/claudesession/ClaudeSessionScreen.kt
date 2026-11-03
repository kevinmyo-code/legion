package com.kevin.legion.ui.claudesession

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.kevin.legion.ui.theme.soft.SoftTheme

/**
 * The Claude session screen (claude-session ticket 05, ADR 0035's hands path for the
 * `claude_session` voice tool), soft Material (ADR 0051). Reached from HOME's More sheet, route
 * `LegionRoute.CLAUDE_SESSION`. Everything goes through [ClaudeSessionViewModel] and so through
 * [com.kevin.legion.claude.ClaudeSessionController], the controller the voice tool calls.
 */
@Composable
fun ClaudeSessionScreen(onBack: () -> Unit) {
    val viewModel: ClaudeSessionViewModel = viewModel()
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    SoftTheme {
        ClaudeSessionContent(
            state = state,
            callbacks = ClaudeSessionCallbacks(
                onBack = onBack,
                onRefresh = viewModel::refresh,
                onDraft = viewModel::setDraft,
                onPickMachine = viewModel::pickMachine,
                onEmailMe = viewModel::setEmailMe,
                onPageMe = viewModel::setPageMe,
                onSend = viewModel::send,
                onToggleTask = viewModel::toggleTask,
                onAnswerText = viewModel::setAnswer,
                onAnswer = viewModel::answer,
                onAssign = viewModel::assign,
                onSendFile = viewModel::sendFile,
                // The tap path the result notification takes (ticket 09): fresh link, LEGION's browser.
                onOpenPage = { id -> context.startActivity(ClaudePageActivity.intent(context, id)) },
                onAnnouncements = viewModel::setAnnouncements,
            ),
        )
    }
}

/** Everything the screen can ask for, one bag so the stateless content renders with fakes. */
data class ClaudeSessionCallbacks(
    val onBack: () -> Unit = {},
    val onRefresh: () -> Unit = {},
    val onDraft: (String) -> Unit = {},
    val onPickMachine: (String?) -> Unit = {},
    val onEmailMe: (Boolean) -> Unit = {},
    val onPageMe: (Boolean) -> Unit = {},
    val onSend: () -> Unit = {},
    val onToggleTask: (Int) -> Unit = {},
    val onAnswerText: (String) -> Unit = {},
    /** (task id, yes): yes is the "Yes, go ahead" button. */
    val onAnswer: (Int, Boolean) -> Unit = { _, _ -> },
    val onAssign: (Int, String) -> Unit = { _, _ -> },
    val onSendFile: (Int, Int) -> Unit = { _, _ -> },
    /** (task id): open its delivered page in the browser. */
    val onOpenPage: (Int) -> Unit = {},
    val onAnnouncements: (Boolean) -> Unit = {},
)
