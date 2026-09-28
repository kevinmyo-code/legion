package com.kevin.legion.ui.checklists

import com.kevin.legion.data.local.Checklist
import com.kevin.legion.data.local.ChecklistItem
import com.kevin.legion.checklists.ChecklistController

/**
 * [ListsViewModel]'s three internal states, home-launcher ticket 04 - "Three internal states, not
 * three nav-graph destinations", the same convention `ChecklistsScreen.kt`'s own pre-04 doc
 * comment already established (list -> single-checklist editor -> that checklist's history).
 */
sealed interface ListsMode {
    data object Page : ListsMode
    data class Detail(val checklistId: Long) : ListsMode
    data class History(val checklistId: Long) : ListsMode
}

/** One icon card - `Checklist` plus its resolved [ListVisual] and [ListProgress], both pure reads
 * of the checklist/day the page loaded. */
data class ListCardUi(val checklist: Checklist, val visual: ListVisual, val progress: ListProgress)

/** The Lists page's own slice of [ListsUiState]. */
data class ListsPageState(
    val loading: Boolean = true,
    val routines: List<ListCardUi> = emptyList(),
    val plainLists: List<ListCardUi> = emptyList(),
    val archivedLists: List<ListCardUi> = emptyList(),
    val showArchived: Boolean = false,
    val showCreateDialog: Boolean = false,
    /** A thrown [ChecklistController.createChecklist] call (audit finding 6) - the dialog stays
     * open (unlike a successful create, which closes it and navigates to the new list) and states
     * what did not happen, rendered inside [CreateChecklistDialog] itself. Cleared the next time
     * the dialog is opened, so a stale error never survives to a fresh attempt. */
    val createError: String? = null,
)

/** One item row inside an open list - the item plus the day's own tick state
 * ([ChecklistController.ItemState], carried through rather than re-derived) and whether its
 * tick/untick is still sitting in the outbox (CLAUDE.md §7: a write not yet on the engine must not
 * look like one that is). */
data class ListItemUi(
    val item: ChecklistItem,
    val ticked: Boolean,
    val value: Double?,
    val tickDay: Int?,
    val queued: Boolean,
)

/** An open list's own slice of [ListsUiState] - Keep-style detail, ticket 04's own layout. Always
 * scoped to TODAY ([com.kevin.legion.checklists.ChecklistController.today]); a routine's schedule
 * line already says so in words ("Ticks count for today, <day>."), matching the ticket's own
 * "opened" semantics rather than the calendar day view's arbitrary-day one. */
data class ListDetailState(
    val checklist: Checklist? = null,
    val loading: Boolean = true,
    val loadFailed: Boolean = false,
    val unticked: List<ListItemUi> = emptyList(),
    val ticked: List<ListItemUi> = emptyList(),
    val showTicked: Boolean = true,
    val appliesToday: Boolean = true,
    val scheduleLabel: String = "",
    val draft: String = "",
    val inputValues: Map<Long, String> = emptyMap(),
    val refusals: Map<Long, String> = emptyMap(),
    val editingItem: ChecklistItem? = null,
    val longPressItem: ChecklistItem? = null,
    val showRenameDialog: Boolean = false,
    val showSchedulePicker: Boolean = false,
    val showOverflowMenu: Boolean = false,
    val showDeleteConfirm: Boolean = false,
    /** A thrown [ChecklistController] write - audit finding 6 - says in words what did not
     * happen, rendered as a one-line banner on this screen, never a toast. Cleared on the next
     * successful write and left in place across an unrelated `refresh()` (loadDetail's own
     * `.copy(...)` never mentions this field). */
    val writeError: String? = null,
)

/** History mode's own slice - "shown, never scored" (unchanged from before this ticket; only its
 * presentation moves onto the soft theme). */
data class ListHistoryState(
    val checklistName: String = "",
    val loading: Boolean = true,
    val lines: List<ChecklistController.ChecklistHistoryLine> = emptyList(),
)

/** [ListsViewModel]'s one [kotlinx.coroutines.flow.StateFlow] - page state, the open list's state
 * and session-only UI flags all live here, per the ticket's own brief. Only the slice matching
 * [mode] is ever rendered; the others are left at their last value rather than cleared, so
 * flipping back from History to Detail does not need a re-fetch to redraw the same screen. */
data class ListsUiState(
    val mode: ListsMode = ListsMode.Page,
    val page: ListsPageState = ListsPageState(),
    val detail: ListDetailState? = null,
    val history: ListHistoryState? = null,
)
