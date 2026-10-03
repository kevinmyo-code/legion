import { Link } from '@tanstack/react-router'
import { ListChecks } from 'lucide-react'

import type { Checklist, ChecklistItem, ChecklistTick } from '@/api/types'
import { VisibilityMark } from '@/components/visibility-mark'
import { todayEpochDay } from '@/lib/day'
import { LIST_ALL_TICKED, LIST_NO_ITEMS } from '@/lib/home-copy'
import { openItems } from '@/lib/pins'
import { visibilityOf } from '@/lib/visibility'

/** How many open items a pinned card shows before "and N more". */
const PREVIEW_ITEMS = 3

/**
 * A pinned list on Home: its name, who sees it, the first three things still to
 * do and a count of the rest. A preview, not an editor: the whole card opens
 * Lists, where an item is ticked or added. The little squares are drawn, not
 * controls, so nothing here looks tickable that is not.
 */
export function PinnedListCard({
  checklist,
  items,
  ticks,
}: {
  checklist: Checklist
  items: ChecklistItem[]
  ticks: ChecklistTick[]
}) {
  const today = todayEpochDay()
  const open = openItems(checklist, items, ticks, today)
  const total = items.filter((item) => item.checklist === checklist.id && item.deleted_at === null).length
  const shown = open.slice(0, PREVIEW_ITEMS)
  const rest = open.length - shown.length

  return (
    <li className="rounded-sheet bg-card">
      <Link
        to="/lists"
        className="block rounded-sheet px-4 pt-3.5 pb-4 outline-none focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-primary"
      >
        <span className="mb-2 flex items-center gap-2.5">
          <span
            aria-hidden="true"
            className="grid size-8 shrink-0 place-items-center rounded-full bg-done-container text-done"
          >
            <ListChecks className="size-4" />
          </span>
          <span className="min-w-0 flex-1 truncate text-base font-medium">{checklist.name}</span>
          <VisibilityMark visibility={visibilityOf(checklist)} />
        </span>
        {shown.length === 0 ? (
          <span className="block text-[0.9375rem] text-muted-foreground">
            {total === 0 ? LIST_NO_ITEMS : LIST_ALL_TICKED}
          </span>
        ) : (
          <span className="flex flex-col gap-1.5">
            {shown.map((item) => (
              <span key={item.id} className="flex items-center gap-3 text-[0.9375rem]">
                <span aria-hidden="true" className="size-4 shrink-0 rounded-[0.3rem] border-2 border-outline" />
                {item.text}
              </span>
            ))}
            {rest > 0 && (
              <span className="pl-7 text-[0.8125rem] text-muted-foreground">and {rest} more</span>
            )}
          </span>
        )}
      </Link>
    </li>
  )
}
