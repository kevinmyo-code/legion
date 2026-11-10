package com.kevin.legion.ui.checklists

import com.kevin.legion.data.local.Checklist

/**
 * The open list's "Clear ticked items" overflow entry (Kevin, 2026-11-10: 74 ticked grocery lines
 * and no way to drop them but one at a time). Pure, so a JVM test pins when it shows and what the
 * Undo snackbar says. The removal itself is [com.kevin.legion.checklists.ChecklistController.clearTicked],
 * the same call the voice tool's `clear_ticked` makes (ADR 0035).
 */
enum class ClearTickedMenu { HIDDEN, DISABLED, ENABLED }

/** Hidden on a routine (its ticks reset by day and its lines are the routine); disabled when nothing is ticked. */
fun clearTickedMenu(checklist: Checklist?, tickedCount: Int): ClearTickedMenu = when {
    checklist == null || checklist.scheduleKind != null -> ClearTickedMenu.HIDDEN
    tickedCount == 0 -> ClearTickedMenu.DISABLED
    else -> ClearTickedMenu.ENABLED
}

/** The snackbar line while a clear waits for its Undo window to pass. */
fun clearedTickedMessage(count: Int): String =
    if (count == 1) "Cleared 1 ticked item" else "Cleared $count ticked items"

/** The rows still shown while [pending] lines wait to be removed. */
fun withoutPendingClear(rows: List<ListItemUi>, pending: List<Long>): List<ListItemUi> =
    if (pending.isEmpty()) rows else rows.filterNot { it.item.id in pending }
