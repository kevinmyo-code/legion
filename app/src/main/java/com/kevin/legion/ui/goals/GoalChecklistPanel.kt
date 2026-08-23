package com.kevin.legion.ui.goals

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.kevin.legion.advisor.GoalChecklistSync
import com.kevin.legion.advisor.GoalChecklistSync.GoalChecklistItemView
import com.kevin.legion.data.local.ListItem
import com.kevin.legion.ui.common.DeckPane
import com.kevin.legion.ui.common.DeckRow
import com.kevin.legion.ui.common.DeckTag
import com.kevin.legion.ui.common.DeckTagStyle
import com.kevin.legion.ui.common.GapEmptyRow
import com.kevin.legion.ui.theme.LegionTheme
import com.kevin.legion.ui.theme.LegionType
import com.kevin.legion.ui.theme.LocalLegionSemantics
import com.kevin.legion.util.shortDate
import kotlinx.coroutines.launch

/**
 * The BIO daily checklist panel (ticket 04, `goal-plans`, adherence reworked by ticket 06) -
 * "today's items", derived from whatever `generate_goal_plan`/`accept_goal_plan` last wrote,
 * self-contained the same way [GoalsPanel] is (its own doc comment explains why: not part of
 * [com.kevin.legion.ui.BodyScreen]'s batched load, and a screen this reads from can change out
 * from under it between visits with no event this panel is otherwise told about).
 *
 * [compact] switches between the full [com.kevin.legion.ui.BodyScreen] rendering (every line, plus
 * this ticket's recent-completion record) and the HOME section's "at a glance" rendering (item
 * text only, capped, no completion detail) - one panel, two call sites, rather than two composables
 * that could quietly drift on what "today's items" means.
 *
 * **Has a real tick box now (ticket 07), correcting an earlier reading of ticket 04's "do not build
 * a second ticking path" rule.** An earlier version of this doc comment read that rule as "no
 * on-screen tick affordance at all" - that reading was wrong. The rule forbids a second ticking
 * MECHANISM (a parallel store, a different notion of "done"), not a second CALLER of the one
 * mechanism that already exists. The [Checkbox] below calls [GoalChecklistSync.toggle], which calls
 * [com.kevin.legion.notes.NotesController.tick]/`untick` directly - the exact functions
 * `service/LiveToolbox.kt`'s `manage_item` dispatch already calls for a spoken tick. Same path, a
 * finger on it instead of a voice. **ADR 0035 now makes this mandatory, not merely permitted:**
 * every voice capability needs a non-voice path, and a checklist tickable only by voice fails in
 * exactly the moment it gets used - at the gym, in a kitchen, next to someone asleep, none of which
 * are good places to expect the wake word to land. Ticket 06 is what made ticking a plan line
 * actually WORK at all (each line is now an ordinary one-off [com.kevin.legion.data.local.ListItem],
 * so [com.kevin.legion.notes.NotesController.tick] no longer refuses it) - see [GoalChecklistSync]'s
 * own class doc for the full account of why ticket 04's original recurring-item design could never
 * be ticked at all.
 *
 * **No score, no streak, no percentage** (ticket 04's own binding rule, CLAUDE.md §7, restated by
 * ticket 06: "adherence becomes truthful... still shown, never scored"). What this panel shows is
 * EXACTLY what [GoalChecklistSync.currentItems] returns and nothing derived from it: today's lines,
 * whether each is done, and - full mode only - the [ListItem.doneAt] timestamps of the same line's
 * completions on OTHER days within [GoalChecklistSync.RECENT_COMPLETION_WINDOW_DAYS]. Unlike ticket
 * 04's shipped version, this is a genuine completion record now, not an explicit-skip proxy for
 * one - a real `doneAt` exists because ticket 06 made every plan line a tickable one-off item. An
 * empty completion list still gets a worded caption rather than silently reading as "done every
 * day", matching CLAUDE.md §4's "unreadable and empty are different sentences" posture carried over
 * from ledger/pantry to this domain.
 *
 * **Hosts the relocated TRAINING affordances now (ticket 08, `goal-plans`, Kevin: "bio page >
 * training and checklist > retire training page. delete it.").** `ui/BodyScreen.kt` deleted its
 * standalone TRAINING `DeckPane`; ADR 0035's hands path for `log_workout_set` (the `+ LOG SET`
 * dialog) and the exercise-progression drilldown did not go with it - they render from here now,
 * in FULL mode only ([compact] `== false`; HOME's glance card gets neither). **This panel still
 * owns no controller call or DAO of its own for either one** - [onLogSet] and
 * [onOpenTrainingDrilldown] are callbacks into `BodyScreen`'s EXISTING `showLogSet` dialog state
 * and its EXISTING top-level [com.kevin.legion.ui.BodyDrilldown] swap, both null by default so
 * every other caller (and every preview) is unaffected. See `ui/BodyScreen.kt`'s own file doc
 * comment for why the swap mechanism itself stays there rather than a second one nesting inside
 * this panel's own `LazyColumn` item.
 */
@Composable
fun GoalChecklistPanel(
    compact: Boolean = false,
    modifier: Modifier = Modifier,
    onLogSet: (() -> Unit)? = null,
    onOpenTrainingDrilldown: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var items by remember { mutableStateOf<List<GoalChecklistItemView>>(emptyList()) }
    var loaded by remember { mutableStateOf(false) }

    // Shared by the initial load and every tap - a tick/untick writes through
    // GoalChecklistSync.toggle and then re-reads exactly the same way a fresh compose would, so
    // this panel never guesses what the new state is from the tap alone (a second write landing
    // between the tap and the reload - e.g. a spoken tick from the SAME plan line - would leave a
    // guessed state wrong; a re-read cannot be).
    suspend fun reload() {
        items = GoalChecklistSync.currentItems(context)
        loaded = true
    }

    LaunchedEffect(Unit) { reload() }

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
                shown.forEach { item ->
                    GoalChecklistItemRow(
                        item,
                        showCompletionHistory = !compact,
                        onToggle = { scope.launch { GoalChecklistSync.toggle(context, item.id); reload() } },
                    )
                }
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
        // Ticket 08's relocated TRAINING affordances - full mode only, and only when the caller
        // (BodyScreen) actually wired them, so HOME's compact card and every preview stay exactly
        // as they were before this ticket.
        if (!compact) {
            Row(Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
                onLogSet?.let { onClick ->
                    Text(
                        "+ LOG SET",
                        style = LegionType.stamp,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.clickable { onClick() },
                    )
                }
                onOpenTrainingDrilldown?.let { onClick ->
                    Text(
                        "TRAINING HISTORY",
                        style = LegionType.stamp,
                        color = sem.faint,
                        modifier = Modifier.padding(start = 16.dp).clickable { onClick() },
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
private fun GoalChecklistItemRow(
    item: GoalChecklistItemView,
    showCompletionHistory: Boolean,
    onToggle: () -> Unit = {},
) {
    val sem = LocalLegionSemantics.current
    Column(Modifier.padding(bottom = 2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // Tapping ticks; tapping again unticks - both through GoalChecklistSync.toggle, which
            // is NotesController.tick/untick called directly (see the class doc above). No new
            // write path, no local "optimistic" flip of `done` here - the checkbox always shows
            // whatever GoalChecklistSync.currentItems last read back, never a guess.
            Checkbox(checked = item.done, onCheckedChange = { onToggle() })
            DeckRow(
                label = item.text,
                value = if (item.done) "DONE" else "",
                tag = if (item.done) { { DeckTag("DONE", DeckTagStyle.INVERTED_GREEN) } } else null,
                modifier = Modifier.weight(1f),
            )
        }
        if (showCompletionHistory) {
            // A genuine record now (ticket 06) - [item.recentCompletionDates] is real `doneAt`
            // history, not the explicit-skip proxy ticket 04 shipped. Still worded as a plain
            // fact, never a grade: no "X of Y days", no percentage - CLAUDE.md §7's compulsion
            // ban applies to a screen just as much as it applies to a spoken raise.
            val caption = if (item.recentCompletionDates.isEmpty()) {
                "Nothing marked done in the last week for this line - an empty history here " +
                    "just means it hasn't been ticked yet, not that it was missed."
            } else {
                "Done: " + item.recentCompletionDates.joinToString(", ") { shortDate(it) }
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
                    GoalChecklistItemView(
                        "Hit 2,300 kcal / 180g protein", done = false, doneAt = null, recentCompletionDates = emptyList(),
                    ),
                    showCompletionHistory = true,
                )
                GoalChecklistItemRow(
                    GoalChecklistItemView(
                        "Sleep 8h", done = true, doneAt = System.currentTimeMillis(),
                        recentCompletionDates = listOf(System.currentTimeMillis() - 86_400_000L),
                    ),
                    showCompletionHistory = true,
                )
                GoalChecklistItemRow(
                    GoalChecklistItemView(
                        "Squat: 9 sets this week", done = false, doneAt = null, recentCompletionDates = emptyList(),
                    ),
                    showCompletionHistory = true,
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
