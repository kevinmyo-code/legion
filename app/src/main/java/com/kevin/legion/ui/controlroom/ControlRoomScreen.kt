package com.kevin.legion.ui.controlroom

import android.app.Activity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.kevin.legion.ui.theme.soft.SoftTheme

/**
 * The control room route (`LegionRoute.CONTROL_ROOM`, control room C1-C4): Claude sessions, the
 * one approvals queue, the factory board with the whole backlog, and the board's questions. The
 * hands path for the voice `needs` action and for every message card (ADR 0035). Reached from
 * Controls ("Claude sessions") and the home line. Tasks and workflows stay on the Claude session
 * screen ([onOpenTasks]).
 */
@Composable
fun ControlRoomScreen(onBack: () -> Unit, onOpenTasks: () -> Unit) {
    val viewModel: ControlRoomViewModel = viewModel()
    val state by viewModel.state.collectAsStateWithLifecycle()
    val activity = LocalContext.current as? Activity
    val confirmer = remember(activity) {
        activity?.let(::BiometricActionConfirmer)
            ?: ActionConfirmer { _, _, refused -> refused(BiometricActionConfirmer.CANCELLED) }
    }
    SoftTheme {
        ControlRoomContent(
            state = state,
            actions = ControlRoomActions(
                onBack = onBack,
                onRefresh = viewModel::refresh,
                onTab = viewModel::pick,
                onSummary = viewModel::summarise,
                onOpenTasks = onOpenTasks,
                onYes = viewModel::yes,
                onAllow = { card -> viewModel.allow(card, confirmer) },
                onDecline = viewModel::decline,
                onAnswer = viewModel::answer,
                onType = viewModel::type,
                onVote = viewModel::vote,
            ),
        )
    }
}
