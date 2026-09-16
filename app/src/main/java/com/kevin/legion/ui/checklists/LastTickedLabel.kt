package com.kevin.legion.ui.checklists

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.kevin.legion.checklists.TickHistoryController
import com.kevin.legion.util.shortDate

/**
 * Web-calendar-and-lists ticket 04's hands path (ADR 0035: a voice capability needs a hands path
 * calling the same controller, not a second implementation) - split into its own file, out of
 * `ChecklistsScreen.kt`, purely to stay under detekt's per-file [TooManyFunctions] ceiling; that
 * screen file was already at the limit before this ticket touched it.
 *
 * Reads straight through [TickHistoryController.lastTicked] - the exact same read the
 * `get_last_ticked` voice tool calls (`service/LiveToolbox.kt`'s `getLastTicked`). Tapping an item
 * is literally ticket 04's brief: "tapping an item shows when it was last ticked". Same wording
 * rule as the voice tool: says "ticked", never "bought" - a tick carries no price and nothing
 * reconciled it (CLAUDE.md §4 rule 5).
 *
 * Looked up once, against [itemText] as it stood when the dialog was OPENED - not a live-edited
 * text field - so retyping mid-edit does not re-query on every keystroke. The match is on whatever
 * line is actually recorded right now; ticket 03's exact-modulo-case-and-whitespace rule applies
 * here identically to the voice tool.
 */
@Composable
fun rememberLastTickedLabel(itemText: String): String? {
    val context = LocalContext.current
    var label by remember(itemText) { mutableStateOf<String?>(null) }
    LaunchedEffect(itemText) {
        val match = TickHistoryController.lastTicked(context, itemText).firstOrNull()
        label = if (match != null) {
            "Last ticked ${shortDate(match.tickedAt)}, on \"${match.checklistName}\""
        } else {
            "No record of this being ticked"
        }
    }
    return label
}
