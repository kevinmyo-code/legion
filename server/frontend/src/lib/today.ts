import type { Checklist, ChecklistItem, ChecklistTick, Event } from '@/api/types'
import { appliesOnDay, localDayOf } from '@/lib/day'

/** A checklist item due today, or already ticked/skipped today - the
 * "to do today" zone `docs/design/today.md` recommends leading with, one
 * item per row regardless of which checklist it belongs to. */
export interface DueItem {
  checklist: Checklist
  item: ChecklistItem
  tickedToday: boolean
}

/** Live (not soft-deleted) rows only - every screen in this ticket treats a
 * tombstone as absent, never as a row to render. */
function isLive<T extends { deleted_at: string | null }>(row: T): boolean {
  return row.deleted_at === null
}

/**
 * Every checklist item due on `day`, with whether it was already ticked -
 * a simplified `ChecklistController.checklistsForDay` +
 * `itemsWithTickState`, ported to the web because there is no report
 * endpoint yet (web-and-households ticket 11) to compute this server-side.
 *
 * A plain, unscheduled checklist (`schedule_kind` null - a grocery-style
 * one-time list) never appears here: `appliesOnDay` still returns true for
 * it, but the phone's own rule is that such a list has no day axis at all
 * ("done" is a fact about the item, not the day), so putting it on Today
 * would show every never-finished grocery item as "due" forever. Only
 * checklists with a real `DAILY`/`WEEKLY` schedule are day-gated items;
 * everything else lives on `/lists` only.
 */
export function itemsDueOn(
  day: number,
  checklists: Checklist[],
  items: ChecklistItem[],
  ticks: ChecklistTick[],
): DueItem[] {
  const tickedItemIds = new Set(
    ticks.filter(isLive).filter((tick) => tick.day === day).map((tick) => tick.item),
  )
  const dueChecklists = checklists
    .filter(isLive)
    .filter((checklist) => !checklist.archived)
    // Loose comparison: `schedule_kind` is `null` on a row round-tripped
    // through the API and `undefined` on one only ever built client-side, and
    // both mean the same thing here - no schedule.
    .filter((checklist) => checklist.schedule_kind != null)
    .filter((checklist) => appliesOnDay(checklist, day))

  const due: DueItem[] = []
  for (const checklist of dueChecklists) {
    for (const item of items.filter(isLive).filter((item) => item.checklist === checklist.id)) {
      due.push({ checklist, item, tickedToday: tickedItemIds.has(item.id) })
    }
  }
  return due.sort((a, b) => (a.item.sort_order ?? 0) - (b.item.sort_order ?? 0))
}

/** Live events/tasks whose `starts_at` falls on the viewer's local `day`.
 * `starts_at` is nullable (an event with no time at all); such rows never
 * land in a day bucket, matching the phone's own `activeByKindInLocalWindow`
 * (a row with no anchor cannot be placed in a window). */
export function eventsOnDay(day: number, events: Event[]): Event[] {
  return events
    .filter(isLive)
    .filter((event) => event.starts_at !== null && event.starts_at !== undefined)
    .filter((event) => localDayOf(event.starts_at as string) === day)
    .sort((a, b) => (a.starts_at ?? '').localeCompare(b.starts_at ?? ''))
}
