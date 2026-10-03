package com.kevin.legion.ui.navigation

import com.kevin.legion.navigation.resolve.Candidate

/** Every button on the screen, one bag (the same shape as `HomeCallbacks`) so [NavContent] stays stateless. */
data class NavActions(
    val onBack: () -> Unit,
    val onOpenSetup: () -> Unit,
    val onInput: (String) -> Unit,
    val onSubmit: () -> Unit,
    val onNavigateTo: (String) -> Unit,
    val onPickCandidate: (Candidate) -> Unit,
    val onDismissChoice: () -> Unit,
    val onPickRoute: (Int) -> Unit,
    val onStart: () -> Unit,
    val onCancelPreview: () -> Unit,
    val onEnd: () -> Unit,
    val onDismiss: () -> Unit,
    val onToggleTolls: () -> Unit,
    val onToggleMute: () -> Unit,
    val onOverviewOrRecenter: () -> Unit,
    val onOpenPanel: (NavPanel) -> Unit,
    val onClosePanel: () -> Unit,
    val onStopInput: (String) -> Unit,
    val onAddStop: () -> Unit,
    val onDropStop: (String) -> Unit,
)
