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
