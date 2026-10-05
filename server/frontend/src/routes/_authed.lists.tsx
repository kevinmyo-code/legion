import { useMutation, useQueryClient } from '@tanstack/react-query'
import { createFileRoute } from '@tanstack/react-router'
import { ChevronDown, Trash2 } from 'lucide-react'
import { useState } from 'react'

import { api } from '@/api/client'
import { useSetChecklistTick } from '@/api/mutations'
import { PURCHASES_LIMIT, usePurchases } from '@/api/purchases'
import { CHANGES_KEY, useChanges } from '@/api/queries'
import {
  newChecklist,
  newChecklistItem,
  type Checklist,
  type ChecklistItem,
  type ChecklistTick,
  type Purchase,
} from '@/api/types'
import { DeleteChecklistControl } from '@/components/checklist-delete'
import { Freshness } from '@/components/freshness'
import { ListVisibilityToggle } from '@/components/list-visibility-toggle'
import { PinButton } from '@/components/pin-button'
import { Button } from '@/components/ui/button'
import { Checkbox } from '@/components/ui/checkbox'
import { Input } from '@/components/ui/input'
import { Skeleton } from '@/components/ui/skeleton'
import { isChecklistComplete, tickState } from '@/lib/checklist'
import { todayEpochDay } from '@/lib/day'
import { isGroceriesList, lastBoughtLine, lastExactFor } from '@/lib/purchases'
import { visibilityOf } from '@/lib/visibility'

export const Route = createFileRoute('/_authed/lists')({
  component: Lists,
})

function isLive<T extends { deleted_at: string | null }>(row: T): boolean {
  return row.deleted_at === null
}

/** What the bought log knows about the Groceries list: its entries, or that it
 * could not be read. `null` for every other list, which says nothing new. */
type BoughtLog = { entries: Purchase[] } | { unreadable: true } | null

/** "last bought Sep 20 · Mia" under a Groceries line (ADR 0055: on this one list
 * a tick is a purchase, and the log is where "bought" comes from). No record
 * says nothing at all, never "never bought"; an unreadable log says so. */
function LastBought({ item, log }: { item: ChecklistItem; log: BoughtLog }) {
  if (log === null) return null
  if ('unreadable' in log) {
    return <span className="text-[0.8125rem] text-muted-foreground">last bought: can&apos;t check right now</span>
  }
  const entry = lastExactFor(item.text, log.entries)
  if (entry === null) return null
  return <span className="text-[0.8125rem] text-muted-foreground">{lastBoughtLine(entry, todayEpochDay())}</span>
}

function ItemRow({
  checklist,
  item,
  ticks,
  log,
}: {
  checklist: Checklist
  item: ChecklistItem
  ticks: ChecklistTick[]
  log: BoughtLog
}) {
  const queryClient = useQueryClient()
  const today = todayEpochDay()
  const { ticked, dayToClear } = tickState(checklist, item, ticks, today)

  const setTick = useSetChecklistTick()

  const remove = useMutation({
    mutationFn: async () => {
      const { error, response } = await api.DELETE('/api/checklists/{checklist_id}/items/{item_id}', {
        params: { path: { checklist_id: checklist.id, item_id: item.id } },
      })
      if (error) throw new Error(`DELETE item answered ${response.status}`)
    },
    onSuccess: () => queryClient.invalidateQueries({ queryKey: CHANGES_KEY }),
  })

  return (
    <li className="flex min-h-11 items-center gap-3 py-0.5 pl-1">
      <Checkbox
        checked={ticked}
        disabled={setTick.isPending}
        onCheckedChange={(checked) =>
          setTick.mutate({
            checklistId: checklist.id,
            itemId: item.id,
            ticked: checked === true,
            today,
            dayToClear,
          })
        }
        aria-label={`Mark "${item.text}" ${ticked ? 'not done' : 'done'}`}
      />
      <span className="flex min-w-0 flex-1 flex-col">
        <span className={`text-base ${ticked ? 'text-muted-foreground line-through' : ''}`}>
          {item.text}
        </span>
        <LastBought item={item} log={log} />
      </span>
      <Button
        variant="ghost"
        size="icon-sm"
        className="text-muted-foreground"
        aria-label={`Remove "${item.text}"`}
        disabled={remove.isPending}
        onClick={() => remove.mutate()}
      >
        <Trash2 />
      </Button>
      {(setTick.isError || remove.isError) && (
        <span className="text-[0.8125rem] text-destructive">
          Could not save. {(setTick.error ?? remove.error)?.message}
        </span>
      )}
    </li>
  )
}

function AddItemForm({ checklistId }: { checklistId: string }) {
  const [text, setText] = useState('')
  const queryClient = useQueryClient()

  const add = useMutation({
    mutationFn: async (itemText: string) => {
      const { error, response } = await api.POST('/api/checklists/{checklist_id}/items', {
        params: { path: { checklist_id: checklistId } },
        body: newChecklistItem(checklistId, itemText),
      })
      if (error) throw new Error(`POST item answered ${response.status}`)
    },
    onSuccess: () => {
      setText('')
      void queryClient.invalidateQueries({ queryKey: CHANGES_KEY })
    },
  })

  return (
    <form
      className="mt-2 flex flex-wrap items-center gap-x-3 gap-y-1"
      onSubmit={(event) => {
        event.preventDefault()
        const trimmed = text.trim()
        if (trimmed) add.mutate(trimmed)
      }}
    >
      {/* The add bar of the prototype: one pill, the field and its button inside
          it, the focus ring on the pill rather than on the bare field. */}
      <div className="flex h-13 min-w-0 flex-1 items-center gap-2 rounded-full bg-surface-3 pr-1.5 pl-5 focus-within:ring-2 focus-within:ring-primary">
        <Input
          value={text}
          onChange={(event) => setText(event.target.value)}
          placeholder="Add an item"
          aria-label="Add an item"
          className="h-full flex-1 rounded-none border-0 bg-transparent px-0 focus-visible:bg-transparent"
        />
        <Button type="submit" size="lg" disabled={add.isPending || text.trim() === ''}>
          Add
        </Button>
      </div>
      {add.isError && <span className="text-[0.8125rem] text-destructive">{add.error.message}</span>}
    </form>
  )
}

function ChecklistCard({
  checklist,
  items,
  ticks,
  log,
}: {
  checklist: Checklist
  items: ChecklistItem[]
  ticks: ChecklistTick[]
  log: BoughtLog
}) {
  const [showTicked, setShowTicked] = useState(false)
  const ownItems = items.filter((item) => item.checklist === checklist.id)
  const today = todayEpochDay()
  const complete = isChecklistComplete(checklist, items, ticks, today)
  // A ticked item drops out of the list into "Ticked" rather than vanishing: a
  // mis-tap is undone by opening that section and unticking it (spec D11, user
  // story 13). `tickState` is the one rule for "ticked", so a scheduled list's
  // items come back tomorrow.
  const open = ownItems.filter((item) => !tickState(checklist, item, ticks, today).ticked)
  const ticked = ownItems.filter((item) => tickState(checklist, item, ticks, today).ticked)
  const tickedId = `ticked-${checklist.id}`

  return (
    <div className="rounded-sheet bg-card p-4 md:p-5">
      <div className="mb-3 flex items-start justify-between gap-3">
        <div className="flex min-w-0 flex-col items-start gap-1.5">
          <h2 className="text-[1.375rem] leading-tight font-medium">{checklist.name}</h2>
          <div className="flex flex-wrap items-center gap-x-3 gap-y-1">
            <ListVisibilityToggle checklist={checklist} />
            {ownItems.length > 0 && (
              <span className="text-[0.8125rem] text-muted-foreground">{open.length} left</span>
            )}
          </div>
        </div>
        <div className="flex shrink-0 flex-col items-end gap-1">
          <DeleteChecklistControl checklistId={checklist.id} checklistName={checklist.name} />
          <PinButton checklist={checklist} />
        </div>
      </div>
      {complete && (
        <p className="mb-2 rounded-control bg-surface-3 px-3.5 py-2.5 text-[0.8125rem] text-muted-foreground">
          Everything on this list is ticked off. Delete it with the icon above when you are done
          with it - what you ticked is kept either way.
        </p>
      )}
      {ownItems.length === 0 ? (
        <p className="text-[0.9375rem] text-muted-foreground">Nothing on this list yet.</p>
      ) : open.length === 0 ? (
        <p className="text-[0.9375rem] text-muted-foreground">Nothing left on this list.</p>
      ) : (
        <ul className="flex flex-col divide-y divide-outline-variant">
          {open.map((item) => (
            <ItemRow key={item.id} checklist={checklist} item={item} ticks={ticks} log={log} />
          ))}
        </ul>
      )}
      {ticked.length > 0 && (
        <div className="mt-2">
          <button
            type="button"
            aria-expanded={showTicked}
            aria-controls={tickedId}
            className="flex min-h-11 w-full items-center gap-1.5 rounded-control px-1 text-left text-[0.9375rem] font-medium text-muted-foreground outline-none focus-visible:outline-2 focus-visible:outline-primary"
            onClick={() => setShowTicked((value) => !value)}
          >
            Ticked {ticked.length}
            <ChevronDown
              className={`size-4 transition-transform ${showTicked ? 'rotate-180' : ''}`}
              aria-hidden="true"
            />
          </button>
          {showTicked && (
            <ul id={tickedId} className="flex flex-col divide-y divide-outline-variant">
              {ticked.map((item) => (
                <ItemRow key={item.id} checklist={checklist} item={item} ticks={ticks} log={log} />
              ))}
            </ul>
          )}
        </div>
      )}
      <AddItemForm checklistId={checklist.id} />
    </div>
  )
}

function NewChecklistForm() {
  const [name, setName] = useState('')
  const queryClient = useQueryClient()

  const create = useMutation({
    mutationFn: async (listName: string) => {
      const { error, response } = await api.POST('/api/checklists/', {
        body: newChecklist(listName),
      })
      if (error) throw new Error(`POST checklist answered ${response.status}`)
    },
    onSuccess: () => {
      setName('')
      void queryClient.invalidateQueries({ queryKey: CHANGES_KEY })
    },
  })

  return (
    <form
      className="flex flex-wrap items-center gap-2"
      onSubmit={(event) => {
        event.preventDefault()
        const trimmed = name.trim()
        if (trimmed) create.mutate(trimmed)
      }}
    >
      <Input
        value={name}
        onChange={(event) => setName(event.target.value)}
        placeholder="New list name (e.g. Groceries)"
        aria-label="New list name"
        className="min-w-0 flex-1"
      />
      <Button type="submit" size="lg" variant="secondary" disabled={create.isPending || name.trim() === ''}>
        New list
      </Button>
      {create.isError && <span className="text-[0.8125rem] text-destructive">{create.error.message}</span>}
    </form>
  )
}

function Lists() {
  const changes = useChanges(true)
  const hasGroceries = (changes.data?.checklists ?? []).some(
    (list) =>
      list.deleted_at === null && isGroceriesList(list.name) && visibilityOf(list) === 'shared',
  )
  // One read of the log for the whole page: every Groceries line compares itself
  // against it, instead of asking the engine once per line.
  const purchases = usePurchases({ limit: PURCHASES_LIMIT, enabled: hasGroceries })

  if (changes.isPending) {
    return (
      <div className="mx-auto flex max-w-lg flex-col gap-3">
        <Skeleton className="h-24 w-full" />
        <Skeleton className="h-24 w-full" />
      </div>
    )
  }

  // `isError` is also true after a failed background refetch with the last good
  // data attached (the page refetches every 30 s), so the unreachable banner is for
  // "nothing to show", and stale data is the `Freshness` line below.
  if (changes.isError && changes.data === undefined) {
    return (
      <div className="mx-auto max-w-lg rounded-card bg-destructive-container p-4 text-sm text-destructive-container-foreground">
        Could not reach the engine, so this is not the real state of your lists.{' '}
        {changes.error.message}
      </div>
    )
  }

  const checklists = (changes.data.checklists ?? [])
    .filter(isLive)
    .filter((checklist) => !checklist.archived)
    .sort((a, b) => (a.sort_order ?? 0) - (b.sort_order ?? 0))
  const items = (changes.data.checklist_items ?? []).filter(isLive)
  const ticks = (changes.data.checklist_ticks ?? []).filter(isLive)

  /** The household's Groceries list is a shared list by that name; a private one
   * called Groceries is somebody's own and the engine does not log its ticks. */
  const logFor = (checklist: Checklist): BoughtLog => {
    if (!isGroceriesList(checklist.name) || visibilityOf(checklist) !== 'shared') return null
    if (purchases.data === undefined) return purchases.isError ? { unreadable: true } : null
    return { entries: purchases.data.results }
  }

  return (
    <div className="mx-auto flex max-w-lg flex-col gap-4">
      <Freshness
        updatedAt={changes.dataUpdatedAt}
        isFetching={changes.isFetching}
        failureCount={changes.failureCount}
        error={changes.error}
      />
      {checklists.length === 0 ? (
        <p className="text-[0.9375rem] text-muted-foreground">No lists yet. Start one below.</p>
      ) : (
        checklists.map((checklist) => (
          <ChecklistCard
            key={checklist.id}
            checklist={checklist}
            items={items}
            ticks={ticks}
            log={logFor(checklist)}
          />
        ))
      )}
      <NewChecklistForm />
    </div>
  )
}
