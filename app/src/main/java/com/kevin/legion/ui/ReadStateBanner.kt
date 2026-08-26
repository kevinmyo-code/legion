package com.kevin.legion.ui

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.kevin.legion.ui.theme.LegionType
import com.kevin.legion.ui.theme.LocalLegionSemantics

/**
 * The one Compose rendering of [readStateLine] - shared by [LedgerScreen], [PantryScreen] and
 * [FleetScreen] (backend-erp phase 3, `.scratch/backend-erp/issues/05-migration-path.md`) rather
 * than three copies of the same colour-picking logic. Renders nothing when [readStateLine] has
 * nothing honest to say (the normal, fresh-data case).
 *
 * [nowMs] defaults to the real clock but is a parameter so a screenshot test can pin it, matching
 * how [readStateLine] itself takes `nowMs` rather than reading a clock internally.
 */
@Composable
fun ReadStateBanner(read: ReadState, modifier: Modifier = Modifier, nowMs: Long = System.currentTimeMillis()) {
    val line = readStateLine(read, nowMs = nowMs) ?: return
    val sem = LocalLegionSemantics.current
    Text(
        line.text,
        style = LegionType.stamp,
        color = if (line.advisory) sem.quarantined else sem.faint,
        modifier = modifier,
    )
}
