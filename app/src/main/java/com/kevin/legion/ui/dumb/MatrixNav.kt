package com.kevin.legion.ui.dumb

import androidx.compose.runtime.Composable

/** Draws the Matrix screen for [dest]. [onOpen] pushes another screen; [onBack] pops this one. */
@Composable
internal fun MatrixDestinationHost(dest: MatrixDest, onOpen: (MatrixDest) -> Unit, onBack: () -> Unit) {
    when (dest) {
        MatrixDest.INDEX -> MatrixIndexScreen(onOpen, onBack)
        MatrixDest.LISTS -> MatrixListsScreen(onBack)
        MatrixDest.CALENDAR -> MatrixCalendarScreen(onBack)
        MatrixDest.BRIEF -> MatrixBriefScreen(onBack)
    }
}

/** The LEGION row's text index: one tappable line per Matrix screen, so HOME needs only one row for them all. */
@Composable
internal fun MatrixIndexScreen(onOpen: (MatrixDest) -> Unit, onBack: () -> Unit) {
    MatrixScreen(MatrixDest.INDEX.label, onBack) {
        MatrixDest.indexed.forEach { dest -> MatrixRow(dest.label, { onOpen(dest) }) }
    }
}
