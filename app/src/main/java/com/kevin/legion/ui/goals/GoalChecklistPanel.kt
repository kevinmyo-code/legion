package com.kevin.legion.ui.goals

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.kevin.legion.advisor.GoalChecklistSync
import com.kevin.legion.advisor.GoalChecklistSync.GoalChecklistItemView
import com.kevin.legion.ui.common.DeckPane
import com.kevin.legion.ui.common.DeckRow
import com.kevin.legion.ui.common.DeckTag
import com.kevin.legion.ui.common.DeckTagStyle
import com.kevin.legion.ui.common.GapEmptyRow
import com.kevin.legion.ui.theme.LegionTheme
import com.kevin.legion.ui.theme.LegionType
import com.kevin.legion.ui.theme.LocalLegionSemantics
import com.kevin.legion.util.shortDate

/**
 * The BIO daily checklist panel (ticket 04, `goal-plans`) - "today's items", derived from
 * whatever `generate_goal_plan`/`accept_goal_plan` last wrote, self-contained the same way
 * [GoalsPanel] is (its own doc comment explains why: not part of [com.kevin.legion.ui.BodyScreen]'s
 * batched load, and a screen this reads from can change out from under it between visits with no
 * event this panel is otherwise told about).
 *
 * [compact] switches between the full [com.kevin.legion.ui.BodyScreen] rendering (every line, plus
 * this ticket's recent-skip record) and the HOME section's "at a glance" rendering (item text
 * only, capped, no skip detail) - one panel, two call sites, rather than two composables that could
 * quietly drift on what "today's items" means.
 *
 * **Read-only, matching every other panel on these screens except [GoalsPanel] itself.** There is
 * deliberately no on-screen tick or skip affordance here - see [GoalChecklistSync.sync]'s own doc
 * comment for why a recurring item cannot be ticked at all, and CLAUDE.md's own "voice is how X
 * gets written" posture for why this screen does not grow a tap-to-skip control just to route
 * around that; `manage_item`'s `skip` action is the one path, unchanged.
 *
 * **No score, no streak, no percentage** (ticket 04's own binding rule, CLAUDE.md §7). What this
 * panel shows is EXACTLY what [GoalChecklistSync.currentItems] returns and nothing derived from
 * it: today's lines, and - full mode only - which explicit skip dates exist in the last
 * [GoalChecklistSync.RECENT_SKIP_WINDOW_DAYS] days. **The gap this panel does NOT paper over**: a
 * line with no skip recorded could mean it was done, or it could mean nobody looked at it - this
 * schema has no per-occurrence completion state for a recurring item (only skip, an opt-OUT), so a
 * genuine "days completed" adherence view is not buildable from what is actually stored. The caption
 * under each recent-skip line says so in words rather than a caller having to infer it from an
 * empty list, matching CLAUDE.md §4's "unreadable and empty are different sentences" posture
 * carried over from ledger/pantry to this domain.
 */
@Composable
fun GoalChecklistPanel(compact: Boolean = false, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var items by remember { mutableStateOf<List<GoalChecklistItemView>>(emptyList()) }
    var loaded by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        items = GoalChecklistSync.currentItems(context)
        loaded = true
    }

    val sem = LocalLegionSemantics.current
    DeckPane(
        header = if (compact) "Today's plan" else "Checklist",
        headerAccent = if (items.isNotEmpty()) "${items.size} TODAY" else null,
        modifier = modifier,
    ) {
        when {
            !loaded -> {} // no flicker of an empty state before the one load this panel ever does
            items.isEmpty() -> GapEmptyRow(
                label = "No plan yet",
                message = "Say \"I want to lose fat and gain muscle\" (or however you'd put your BIO goal) to get one.",
            )
            else -> {
                val shown = if (compact) items.take(HOME_ITEM_CAP) else items
                shown.forEach { item -> GoalChecklistItemRow(item, showSkipHistory = !compact) }
                if (compact && items.size > HOME_ITEM_CAP) {
                    Text(
                        "+${items.size - HOME_ITEM_CAP} more on Body",
                        style = LegionType.stamp,
                        color = sem.faint,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                    )
                }
            }
        }
    }
}

/** HOME's "at a glance" cap - matches the same instinct [com.kevin.legion.ui.TodayScreen]'s ALERTS
 * pane caps at five with a worded overflow line, sized down here because a checklist line is
 * usually longer text than an alert row. */
private const val HOME_ITEM_CAP = 3

@Composable
private fun GoalChecklistItemRow(item: GoalChecklistItemView, showSkipHistory: Boolean) {
    val sem = LocalLegionSemantics.current
    Column(Modifier.padding(bottom = 2.dp)) {
        DeckRow(
            label = item.text,
            value = if (item.skippedToday) "SKIPPED TODAY" else "",
            tag = if (item.skippedToday) { { DeckTag("SKIPPED", DeckTagStyle.OUTLINE_MUTED) } } else null,
        )
        if (showSkipHistory) {
            val caption = if (item.recentSkipDates.isEmpty()) {
                "No skip recorded in the last week - this only tracks explicit skips, not " +
                    "completion, so that is not the same as having done it every day."
            } else {
                "Skipped: " + item.recentSkipDates.joinToString(", ") { shortDate(it) }
            }
            Text(
                caption,
                style = LegionType.stamp,
                color = sem.faint,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 0.dp),
            )
        }
    }
}

// ------------------------------------------------------------------------- previews
//
// [GoalChecklistPanel] itself owns LocalContext/CarDatabase reads the moment it composes (same
// reasoning [GoalsPanel]'s own preview section gives), so only the plain `@Composable` row below
// is previewed directly - rendering was not performed in this execution environment (no Compose
// preview renderer available here), carried forward explicitly per L11 rather than claimed done.

@Composable
private fun GoalChecklistPreviewContent() {
    Surface(color = MaterialTheme.colorScheme.background) {
        Column {
            DeckPane(header = "Checklist", headerAccent = "3 TODAY") {
                GoalChecklistItemRow(
                    GoalChecklistItemView("Hit 2,300 kcal / 180g protein", skippedToday = false, recentSkipDates = emptyList()),
                    showSkipHistory = true,
                )
                GoalChecklistItemRow(
                    GoalChecklistItemView("Sleep 8h", skippedToday = true, recentSkipDates = listOf(System.currentTimeMillis())),
                    showSkipHistory = true,
                )
                GoalChecklistItemRow(
                    GoalChecklistItemView("Squat: 9 sets this week", skippedToday = false, recentSkipDates = emptyList()),
                    showSkipHistory = true,
                )
            }
        }
    }
}

@Composable
private fun GoalChecklistEmptyPreviewContent() {
    Surface(color = MaterialTheme.colorScheme.background) {
        DeckPane(header = "Checklist") {
            GapEmptyRow(
                label = "No plan yet",
                message = "Say \"I want to lose fat and gain muscle\" (or however you'd put your BIO goal) to get one.",
            )
        }
    }
}

@Preview(name = "Checklist panel - populated, full", showBackground = true)
@Composable
private fun PreviewGoalChecklistPopulated() {
    LegionTheme { GoalChecklistPreviewContent() }
}

@Preview(name = "Checklist panel - no plan yet", showBackground = true)
@Composable
private fun PreviewGoalChecklistEmpty() {
    LegionTheme { GoalChecklistEmptyPreviewContent() }
}

/** The Oppo A17K's narrow-width case, same one [GoalsPanel]'s own previews check. */
@Preview(name = "Checklist panel - 320dp narrow", widthDp = 320, showBackground = true)
@Composable
private fun PreviewGoalChecklistNarrow() {
    LegionTheme { GoalChecklistPreviewContent() }
}
