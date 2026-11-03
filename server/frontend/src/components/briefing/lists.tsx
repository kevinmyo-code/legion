import { Link } from '@tanstack/react-router'
import { ListChecks, ShoppingBag } from 'lucide-react'

import { useSetChecklistTick } from '@/api/mutations'
import type { Checklist, ChecklistItem, ChecklistTick } from '@/api/types'
import { BriefingSection, Card, CardHead, Quiet, RowIcon } from '@/components/briefing/parts'
import { Checkbox } from '@/components/ui/checkbox'
import { VisibilityMark } from '@/components/visibility-mark'
import { tickState } from '@/lib/checklist'
import { todayEpochDay } from '@/lib/day'
import { isGroceriesList } from '@/lib/purchases'
import { visibilityOf } from '@/lib/visibility'

/** Items shown on the lead list before "and N more". */
export const LIST_ITEMS_SHOWN = 6

/** One tickable item: the same optimistic write Lists and Home's due rows use. */
function ItemRow({ checklist, item, ticks }: { checklist: Checklist; item: ChecklistItem; ticks: ChecklistTick[] }) {
  const today = todayEpochDay()
  const { ticked, dayToClear } = tickState(checklist, item, ticks, today)
  const setTick = useSetChecklistTick()
  return (
    <li className="flex min-h-11 items-center gap-3 pl-1">
      <Checkbox
        checked={ticked}
        disabled={setTick.isPending}
        onCheckedChange={(checked) =>
          setTick.mutate({ checklistId: checklist.id, itemId: item.id, ticked: checked === true, today, dayToClear })
        }
        aria-label={`Mark "${item.text}" ${ticked ? 'not done' : 'done'}`}
      />
      <span className={`text-[0.9375rem] ${ticked ? 'text-muted-foreground line-through' : ''}`}>{item.text}</span>
      {setTick.isError && (
        <span role="alert" className="text-[0.8125rem] text-destructive">
          Could not save. {setTick.error.message}
        </span>
      )}
    </li>
  )
}

function counts(checklist: Checklist, items: ChecklistItem[], ticks: ChecklistTick[]) {
  const today = todayEpochDay()
  const own = items.filter((item) => item.checklist === checklist.id)
  const ticked = own.filter((item) => tickState(checklist, item, ticks, today).ticked)
  return { own, ticked }
}

function tickedWords(own: number, ticked: number): string {
  return own === 0 ? 'Nothing on this list yet' : `${ticked} of ${own} ticked`
}

/**
 * Section 4: the lead list, tickable in place (Groceries when there is one, else
 * the plain list with the most left on it), and the other lists as one line
 * each. Plain lists only: a scheduled checklist's items are already in "Worth a
 * look" as "left on ... today".
 */
export function ListsSection({
  checklists,
  items,
  ticks,
}: {
  /** Null when the lists could not be read. */
  checklists: Checklist[] | null
  items: ChecklistItem[]
  ticks: ChecklistTick[]
}) {
  if (checklists === null) {
    return (
      <BriefingSection n={4} id="briefing-lists" title="Lists">
        <Card>
          <Quiet>Could not reach the engine, so your lists are not shown.</Quiet>
        </Card>
      </BriefingSection>
    )
  }
  const plain = checklists
    .filter((list) => list.deleted_at === null && !list.archived && list.schedule_kind == null)
    .sort((a, b) => (a.sort_order ?? 0) - (b.sort_order ?? 0))
  if (plain.length === 0) {
    return (
      <BriefingSection n={4} id="briefing-lists" title="Lists">
        <Card>
          <Quiet>No lists yet. Start one on Lists.</Quiet>
        </Card>
      </BriefingSection>
    )
  }
  const today = todayEpochDay()
  const open = (list: Checklist) =>
    items.filter((item) => item.checklist === list.id && !tickState(list, item, ticks, today).ticked).length
  const lead = plain.find(isGroceriesList) ?? [...plain].sort((a, b) => open(b) - open(a))[0]
  const others = plain.filter((list) => list !== lead)
  const lead_ = counts(lead, items, ticks)
  const ordered = [
    ...lead_.own.filter((item) => !lead_.ticked.includes(item)),
    ...lead_.ticked,
  ]
  const shown = ordered.slice(0, LIST_ITEMS_SHOWN)
  const more = ordered.length - shown.length

  return (
    <BriefingSection n={4} id="briefing-lists" title="Lists">
      <div className="grid gap-3 @2xl:grid-cols-2">
        <Card>
          <CardHead
            title={
              <span className="flex items-center gap-2.5">
                <RowIcon icon={ListChecks} tone="done" />
                {lead.name}
              </span>
            }
            aside={<VisibilityMark visibility={visibilityOf(lead)} />}
          />
          <p className="mb-1 text-[0.8125rem] text-muted-foreground">{tickedWords(lead_.own.length, lead_.ticked.length)}</p>
          {shown.length > 0 && (
            <ul aria-label={`${lead.name} items`}>
              {shown.map((item) => (
                <ItemRow key={item.id} checklist={lead} item={item} ticks={ticks} />
              ))}
            </ul>
          )}
          {more > 0 && (
            <Link to="/lists" className="mt-1 flex min-h-11 items-center text-[0.875rem] font-medium text-primary underline-offset-4 hover:underline">
              and {more} more on Lists
            </Link>
          )}
        </Card>
        <Card>
          <CardHead
            title="Other lists"
            aside={
              <Link to="/lists" className="text-[0.8125rem] text-muted-foreground underline-offset-4 hover:underline">
                All lists
              </Link>
            }
          />
          <ul>
            {others.length === 0 && <li className="py-2"><Quiet>No other lists.</Quiet></li>}
            {others.map((list) => {
              const { own, ticked } = counts(list, items, ticks)
              return (
                <li key={list.id} className="flex min-h-[3.25rem] items-center gap-3 py-1 not-first:border-t not-first:border-outline-variant">
                  <RowIcon icon={ListChecks} tone="done" />
                  <span className="min-w-0 flex-1">
                    <span className="block text-[0.9375rem]">{list.name}</span>
                    <span className="block text-[0.78rem] text-muted-foreground">{tickedWords(own.length, ticked.length)}</span>
                  </span>
                  <VisibilityMark visibility={visibilityOf(list)} />
                </li>
              )
            })}
            {(
              <li className="flex min-h-[3.25rem] items-center gap-3 py-1 not-first:border-t not-first:border-outline-variant">
                <RowIcon icon={ShoppingBag} tone="unverified" />
                <Link to="/bought" className="min-w-0 flex-1 underline-offset-4 hover:underline">
                  <span className="block text-[0.9375rem]">Bought lately</span>
                  <span className="block text-[0.78rem] text-muted-foreground">What the household ticked off as bought</span>
                </Link>
              </li>
            )}
          </ul>
        </Card>
      </div>
    </BriefingSection>
  )
}
