package com.kevin.legion.ui.checklists

import com.kevin.legion.checklists.ChecklistController

/**
 * An icon card's ring and short label (home-launcher ticket 04) - pure, so
 * [ListsScreenshotTest][com.kevin.legion.screenshot.ListsScreenshotTest] and a plain unit test can
 * both exercise every case without a `Context`.
 *
 * [fraction] is `null` only for a failed read ("no ring" - the ticket's own wording), `0f` for
 * "Not today"/"Empty" (an empty track, no arc drawn), and the real ticked/total ratio otherwise.
 * A caller renders the ring from [fraction] and the [label] underneath, never inferring one from
 * the other - "Not today" and "Empty" share the same empty ring but say different things in words.
 */
data class ListProgress(val fraction: Float?, val label: String)

/**
 * [isRoutine]: `checklist.scheduleKind != null` - a schedule tracks per day, a plain list is done
 * once ever. [appliesToday]: whether [com.kevin.legion.checklists.ChecklistController.checklistsForDay]
 * would include this checklist for the day being viewed - always `true` for a non-routine, since
 * "does not apply today" has no meaning for a plain list. [items] is that day's
 * [ChecklistController.ItemState] rows (empty and never fetched when a routine does not apply
 * today - there is nothing to count). [loadFailed]: the day's own
 * [ChecklistController.ChecklistItemsResult.Failed] - checked before every other case, since a
 * failed read must never render as "0/0" (CLAUDE.md's empty-vs-unreadable rule).
 */
fun listProgress(
    isRoutine: Boolean,
    appliesToday: Boolean,
    items: List<ChecklistController.ItemState>,
    loadFailed: Boolean,
): ListProgress = when {
    loadFailed -> ListProgress(fraction = null, label = "Couldn't load")
    isRoutine && !appliesToday -> ListProgress(fraction = 0f, label = "Not today")
    items.isEmpty() -> ListProgress(fraction = 0f, label = "Empty")
    else -> {
        val done = items.count { it.ticked }
        val fraction = done.toFloat() / items.size
        val label = if (isRoutine) "$done/${items.size} today" else "$done/${items.size}"
        ListProgress(fraction = fraction, label = label)
    }
}
