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
import com.kevin.legion.advisor.AdvisorProposalExecutor
import com.kevin.legion.checklists.ChecklistController
import com.kevin.legion.ui.common.DeckPane
import com.kevin.legion.ui.common.DeckRow
import com.kevin.legion.ui.common.DeckTag
import com.kevin.legion.ui.common.DeckTagStyle
import com.kevin.legion.ui.common.GapEmptyRow
import com.kevin.legion.ui.theme.LegionTheme
import com.kevin.legion.ui.theme.LegionType
import com.kevin.legion.ui.theme.LocalLegionSemantics
import java.time.LocalDate
import kotlinx.coroutines.launch

/**
 * The BIO daily checklist panel, on `ui/BodyScreen.kt`.
 *
 * **REPOINTED 2026-09-10 (one-home ticket 05) and the source of the lines is the whole change.**
 * It used to read `advisor/GoalChecklistSync.kt`, which materialised today's lines into `list_items`
 * and found them again by scanning display text for `ITEM_PREFIX = "Plan: "`. That mechanism is
 * retired (one-home ticket 04): a prefix cannot survive a user typing a line that starts the same
 * way, records no tick history, and has no identity a key could point at. The advisor now writes a
 * real recurring checklist through [AdvisorProposalExecutor]'s allowlisted `create_checklist` op,
 * stamped with [AdvisorProposalExecutor.BIO_CHECKLIST_SOURCE_KEY], and this panel finds it by that
 * key - never by name, never by matching an item's text.
 *
 * **Ticket 04's resolution said this panel would be DELETED, and that was wrong.** It was written
 * believing the calendar day view rendered it, in which case the checklist section there already
 * covered it. By the time ticket 05 was built, ticket 02's HOME restructure had removed that call
 * site, and `ui/BodyScreen.kt` was the ONLY caller left - where the panel also hosts the relocated
 * TRAINING affordances ([onLogSet], [onOpenTrainingDrilldown]) that `goal-plans` ticket 08 moved
 * into it. Deleting it would have taken `log_workout_set`'s hands path with it, which is the exact
 * ADR 0035 failure one-home ticket 02 exists to prevent, one screen over. So the panel stays and its
 * DATA moved, which is what ticket 04 actually decided.
 *
 * **Unreadable and empty are different sentences** (CLAUDE.md §1). Three distinct states, never
 * collapsed: no checklist exists yet (the advisor has not written one), the checklist exists and has
 * no lines, and the checklist could not be READ - [ChecklistController.ChecklistItemsResult.Failed]
 * carries a reason and it is shown rather than rendered as an empty day.
 *
 * **No score, no streak, no percentage** (ticket 04's binding rule, CLAUDE.md §7). The completion
 * record below is [ChecklistController.checklistHistory] read back as plain dates - never "X of Y
 * days", never a percentage. An empty history gets a worded caption rather than silently reading as
 * "done every day".
 *
 * [compact] switches between the full rendering and an at-a-glance one; both call the same loader,
 * rather than two composables that could drift on what "today's items" means.
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
    var state by remember { mutableStateOf<PanelState>(PanelState.Loading) }
    var refusal by remember { mutableStateOf<String?>(null) }

    // Shared by the initial load and every tap - a tick/untick writes through ChecklistController
    // and then re-reads exactly the way a fresh compose would, so this panel never guesses the new
    // state from the tap alone. A second write landing between the tap and the reload (a spoken
    // tick on the SAME line) would leave a guessed state wrong; a re-read cannot be.
    suspend fun reload() {
        val checklist = ChecklistController.getChecklistBySourceKey(
            context,
            AdvisorProposalExecutor.BIO_CHECKLIST_SOURCE_KEY,
        )
        if (checklist == null) {
            state = PanelState.NoChecklist
            return
        }
        state = when (val res = ChecklistController.itemsWithTickState(context, checklist.id)) {
            is ChecklistController.ChecklistItemsResult.Failed -> PanelState.Unreadable(res.reason)
            is ChecklistController.ChecklistItemsResult.Loaded -> {
                val today = ChecklistController.today()
                // Full mode only: the completion record costs a second read over a window, and the
                // glance card does not render it.
                val history = if (compact) emptyList() else ChecklistController.checklistHistory(
                    context,
                    checklist.id,
                    fromDay = today - RECENT_COMPLETION_WINDOW_DAYS,
                    toDay = today - 1,
                )
                PanelState.Loaded(
                    items = res.items,
                    completionsByItemId = history
                        .filter { it.ticked }
                        .groupBy({ it.item.id }, { it.day }),
                )
            }
        }
    }

    LaunchedEffect(compact) { reload() }

    val sem = LocalLegionSemantics.current
    val loadedItems = (state as? PanelState.Loaded)?.items.orEmpty()
    DeckPane(
        header = if (compact) "Today's plan" else "Checklist",
        headerAccent = if (loadedItems.isNotEmpty()) "${loadedItems.size} TODAY" else null,
        modifier = modifier,
    ) {
        when (val s = state) {
            // No flicker of an empty state before the one load this panel does.
            PanelState.Loading -> {}

            PanelState.NoChecklist -> GapEmptyRow(
                label = "No plan yet",
                message = "Say \"I want to lose fat and gain muscle\" (or however you'd put your " +
                    "BIO goal) to get one.",
            )

            // NOT the same sentence as "no plan yet". The checklist is there and something went
            // wrong reading it; saying "no plan" would tell you that you have nothing to do when in
            // fact the app cannot see.
            is PanelState.Unreadable -> GapEmptyRow(
                label = "Could not read today's plan",
                message = s.reason,
            )

            is PanelState.Loaded -> {
                if (s.items.isEmpty()) {
                    // A third distinct case: the checklist exists and is empty. Nothing is wrong and
                    // there is nothing to do.
                    GapEmptyRow(
                        label = "Nothing on today's plan",
                        message = "The list is there, it just has no lines on it right now.",
                    )
                } else {
                    val shown = if (compact) s.items.take(HOME_ITEM_CAP) else s.items
                    shown.forEach { itemState ->
                        ChecklistLineRow(
                            itemState = itemState,
                            showCompletionHistory = !compact,
                            recentCompletionDays = s.completionsByItemId[itemState.item.id].orEmpty(),
                            onToggle = {
                                scope.launch {
                                    if (itemState.ticked) {
                                        ChecklistController.untick(context, itemState.item.id)
                                        refusal = null
                                    } else {
                                        // A measured line with no number is a SKIP, never a silent
                                        // done (ChecklistController's own ruling, "a number is the
                                        // point"). The refusal is SHOWN - a refusal that stops being
                                        // rendered is a silent failure.
                                        when (val out = ChecklistController.tick(context, itemState.item.id)) {
                                            is ChecklistController.TickOutcome.Refused ->
                                                refusal = out.message
                                            ChecklistController.TickOutcome.Ticked ->
                                                refusal = null
                                        }
                                    }
                                    reload()
                                }
                            },
                        )
                    }
                    refusal?.let {
                        Text(
                            it,
                            style = LegionType.stamp,
                            color = sem.faint,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                        )
                    }
                    if (compact && s.items.size > HOME_ITEM_CAP) {
                        Text(
                            "+${s.items.size - HOME_ITEM_CAP} more on Body",
                            style = LegionType.stamp,
                            color = sem.faint,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                        )
                    }
                }

                // The relocated TRAINING affordances (`goal-plans` ticket 08). FULL mode only; the
                // glance card gets neither. This panel still owns no controller call or DAO of its
                // own for either - both are callbacks into BodyScreen's EXISTING dialog state and
                // its EXISTING top-level drilldown swap.
                if (!compact) {
                    onLogSet?.let {
                        DeckRow(label = "+ LOG SET", value = "", modifier = Modifier.clickable(onClick = it))
                    }
                    onOpenTrainingDrilldown?.let {
                        DeckRow(label = "Training history", value = "", modifier = Modifier.clickable(onClick = it))
                    }
                }
            }
        }
    }
}

/** What the panel is showing. A sealed type rather than a nullable list plus a boolean, because the
 * three not-loaded cases say genuinely different things and a boolean pair would let two of them be
 * true at once. */
private sealed interface PanelState {
    object Loading : PanelState
    /** The advisor has never written one. */
    object NoChecklist : PanelState
    /** It exists and the read failed. NOT the same as having nothing to do. */
    data class Unreadable(val reason: String) : PanelState
    data class Loaded(
        val items: List<ChecklistController.ItemState>,
        val completionsByItemId: Map<Long, List<Int>>,
    ) : PanelState
}

/** How far back the completion record looks. Carried over unchanged from the retired
 * `GoalChecklistSync.RECENT_COMPLETION_WINDOW_DAYS`. */
private const val RECENT_COMPLETION_WINDOW_DAYS = 7

/** The glance card's line cap - the HOME pane caps at five with a worded overflow line, sized down
 * here because a checklist line is usually longer text than an alert row. */
private const val HOME_ITEM_CAP = 3

@Composable
private fun ChecklistLineRow(
    itemState: ChecklistController.ItemState,
    showCompletionHistory: Boolean,
    recentCompletionDays: List<Int>,
    onToggle: () -> Unit = {},
) {
    val sem = LocalLegionSemantics.current
    Column(Modifier.padding(bottom = 2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // Tapping ticks; tapping again unticks - both through ChecklistController.tick/untick,
            // the exact functions `service/LiveToolbox.kt` already calls for a spoken tick. Same
            // mechanism, a finger on it instead of a voice (ADR 0035). No optimistic local flip: the
            // checkbox always shows what the last read back said, never a guess.
            Checkbox(checked = itemState.ticked, onCheckedChange = { onToggle() })
            DeckRow(
                label = itemState.item.text,
                value = if (itemState.ticked) "DONE" else "",
                tag = if (itemState.ticked) { { DeckTag("DONE", DeckTagStyle.INVERTED_GREEN) } } else null,
                modifier = Modifier.weight(1f),
            )
        }
        if (showCompletionHistory) {
            // A plain fact, never a grade: no "X of Y days", no percentage - CLAUDE.md §7's
            // compulsion ban applies to a screen as much as to a spoken raise.
            val caption = if (recentCompletionDays.isEmpty()) {
                "No completions recorded in the last $RECENT_COMPLETION_WINDOW_DAYS days"
            } else {
                "Done " + recentCompletionDays.sortedDescending()
                    .joinToString(", ") { LocalDate.ofEpochDay(it.toLong()).toString() }
            }
            Text(
                caption,
                style = LegionType.stamp,
                color = sem.faint,
                modifier = Modifier.padding(start = 48.dp, bottom = 4.dp),
            )
        }
    }
}

@Preview
@Composable
private fun GoalChecklistPanelPreview() {
    LegionTheme {
        Surface(color = MaterialTheme.colorScheme.background) {
            GoalChecklistPanel()
        }
    }
}
