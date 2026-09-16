import type { Checklist, ChecklistItem, ChecklistTick } from '@/api/types'

/** Whether an item is currently ticked, and which epoch day to send a
 * DELETE against to undo it - the two facts the Lists screen's checkbox
 * needs and nothing more.
 *
 * Mirrors `ChecklistController.itemsWithTickState`'s own split
 * (`Checklist.kt`'s doc comment, restated in `checklists/models.py`):
 * a scheduled checklist (`schedule_kind` set) tracks a tick PER DAY, so
 * only today's tick counts; a plain checklist has no day axis at all -
 * "done" is true the moment ANY live tick exists for the item, on
 * whichever day it happened to be ticked, and undoing it means deleting
 * THAT day, not today's (there may be no tick for today at all).
 */
export function tickState(
  checklist: Checklist,
  item: ChecklistItem,
  ticks: ChecklistTick[],
  today: number,
): { ticked: boolean; dayToClear: number } {
  const itemTicks = ticks.filter((tick) => tick.item === item.id && tick.deleted_at === null)

  if (checklist.schedule_kind !== null) {
    const todaysTick = itemTicks.find((tick) => tick.day === today)
    return { ticked: todaysTick !== undefined, dayToClear: today }
  }

  const latest = itemTicks.reduce<ChecklistTick | null>((best, tick) => {
    if (!best) return tick
    return tick.ticked_at > best.ticked_at ? tick : best
  }, null)
  return { ticked: latest !== null, dayToClear: latest?.day ?? today }
}

/**
 * Whether every live item on `checklist` is currently ticked, per
 * `tickState`'s own per-checklist rule (today's tick for a scheduled list,
 * any live tick for a plain one) - the "offer the delete" test web-calendar-
 * and-lists ticket 02 asks for.
 *
 * A list with no live items is NOT complete. "Nothing on this list yet" and
 * "everything on this list is done" are different sentences, and offering a
 * delete on an empty list the moment it is created would read as the app
 * calling nothing done - `appliesOnDay`'s own "trap 1" note makes the same
 * distinction for a day before a checklist existed.
 */
export function isChecklistComplete(
  checklist: Checklist,
  items: ChecklistItem[],
  ticks: ChecklistTick[],
  today: number,
): boolean {
  const ownItems = items.filter(
    (item) => item.checklist === checklist.id && item.deleted_at === null,
  )
  if (ownItems.length === 0) return false
  return ownItems.every((item) => tickState(checklist, item, ticks, today).ticked)
}
