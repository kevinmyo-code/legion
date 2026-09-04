package com.kevin.legion.checklists

import com.kevin.legion.data.local.Checklist

/**
 * Pure helpers `ui/CalendarScreen.kt`'s day view and `ui/checklists/ChecklistsScreen.kt`'s history
 * screen call into, split out of both composables the same way `ui/phone/PhoneDialLogic.kt` keeps
 * its own pure logic testable without Compose or Robolectric.
 */

/**
 * The day-view section header for one checklist - name plus that day's own progress, e.g.
 * "BIO (2/5)" (`DeckSectionRule` uppercases whatever it is handed, so the case here does not
 * matter). **Never a percentage, never a streak** - CLAUDE.md §7's compulsion ban; a plain
 * done/total count is the same kind of fact `GoalChecklistPanel`'s own "N TODAY" accent states,
 * not a score. A checklist with zero items renders "(0/0)" here - the caller still shows its own
 * "No items yet." row underneath, this label alone does not need to special-case that.
 */
fun checklistSectionLabel(checklist: Checklist, items: List<ChecklistController.ItemState>): String {
    val done = items.count { it.ticked }
    return "${checklist.name} (${done}/${items.size})"
}

/**
 * Groups [ChecklistController.checklistHistory]'s flat `(item, day)` lines by [ChecklistController.ChecklistHistoryLine.day],
 * most recent day first - the "look back and see what i did" read (`ui/checklists/ChecklistsScreen.kt`'s
 * history view) wants one heading per day, not one row per line. **Shown, never scored** (the
 * ticket's own instruction, CLAUDE.md §7): this only groups and orders, it computes no streak, no
 * percentage, no "N of M days" figure - a caller wanting a count of lines on a day gets it from
 * `.size` on the list it is handed, this function never precomputes one.
 *
 * Within a day, lines stay in [ChecklistController.checklistHistory]'s own order (sorted by
 * `item.sortOrder`, per that function's own doc comment) - not re-sorted here, so a caller reading
 * across days sees each day's lines in the same order the checklist itself lists them in.
 */
fun historyGroupedByDayDescending(
    lines: List<ChecklistController.ChecklistHistoryLine>,
): List<Pair<Int, List<ChecklistController.ChecklistHistoryLine>>> =
    lines.groupBy { it.day }.toSortedMap(compareByDescending { it }).map { (day, dayLines) -> day to dayLines }
