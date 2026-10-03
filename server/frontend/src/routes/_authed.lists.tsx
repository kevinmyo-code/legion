import { useMutation, useQueryClient } from '@tanstack/react-query'
import { createFileRoute } from '@tanstack/react-router'
import { Trash2 } from 'lucide-react'
import { useState } from 'react'

import { api } from '@/api/client'
import { useSetChecklistTick } from '@/api/mutations'
import { CHANGES_KEY, useChanges } from '@/api/queries'
import { newChecklist, newChecklistItem, type Checklist, type ChecklistItem, type ChecklistTick } from '@/api/types'
import { DeleteChecklistControl } from '@/components/checklist-delete'
import { Freshness } from '@/components/freshness'
import { ListVisibilityToggle } from '@/components/list-visibility-toggle'
import { Button } from '@/components/ui/button'
import { Checkbox } from '@/components/ui/checkbox'
import { Input } from '@/components/ui/input'
import { Skeleton } from '@/components/ui/skeleton'
import { isChecklistComplete, tickState } from '@/lib/checklist'
import { todayEpochDay } from '@/lib/day'

export const Route = createFileRoute('/_authed/lists')({
  component: Lists,
})

function isLive<T extends { deleted_at: string | null }>(row: T): boolean {
  return row.deleted_at === null
}

function ItemRow({
  checklist,
  item,
  ticks,
}: {
  checklist: Checklist
  item: ChecklistItem
  ticks: ChecklistTick[]
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
      <span className={`flex-1 text-base ${ticked ? 'text-muted-foreground line-through' : ''}`}>
        {item.text}
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
}: {
  checklist: Checklist
  items: ChecklistItem[]
  ticks: ChecklistTick[]
}) {
  const ownItems = items.filter((item) => item.checklist === checklist.id)
  const today = todayEpochDay()
  const complete = isChecklistComplete(checklist, items, ticks, today)

  return (
    <div className="rounded-sheet bg-card p-4 md:p-5">
      <div className="mb-2 flex items-start justify-between gap-3">
        <div className="flex min-w-0 flex-col items-start gap-1">
          <h2 className="text-lg font-medium">{checklist.name}</h2>
          <ListVisibilityToggle checklist={checklist} />
        </div>
        <DeleteChecklistControl checklistId={checklist.id} checklistName={checklist.name} />
      </div>
      {complete && (
        <p className="mb-2 rounded-control bg-surface-3 px-3.5 py-2.5 text-[0.8125rem] text-muted-foreground">
          Everything on this list is ticked off. Delete it with the icon above when you are done
          with it - what you ticked is kept either way.
        </p>
      )}
      {ownItems.length === 0 ? (
        <p className="text-[0.9375rem] text-muted-foreground">Nothing on this list yet.</p>
      ) : (
        <ul className="flex flex-col">
          {ownItems.map((item) => (
            <ItemRow key={item.id} checklist={checklist} item={item} ticks={ticks} />
          ))}
        </ul>
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
          <ChecklistCard key={checklist.id} checklist={checklist} items={items} ticks={ticks} />
        ))
      )}
      <NewChecklistForm />
    </div>
  )
}
