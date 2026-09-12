import { useMutation, useQueryClient } from '@tanstack/react-query'
import { createFileRoute } from '@tanstack/react-router'
import { Trash2 } from 'lucide-react'
import { useState } from 'react'

import { api } from '@/api/client'
import { CHANGES_KEY, useChanges } from '@/api/queries'
import { newChecklist, newChecklistItem, type Checklist, type ChecklistItem, type ChecklistTick } from '@/api/types'
import { Button } from '@/components/ui/button'
import { Checkbox } from '@/components/ui/checkbox'
import { Input } from '@/components/ui/input'
import { Skeleton } from '@/components/ui/skeleton'
import { tickState } from '@/lib/checklist'
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

  const setTick = useMutation({
    mutationFn: async (nextTicked: boolean) => {
      if (nextTicked) {
        const { error, response } = await api.POST(
          '/api/checklists/{checklist_id}/items/{item_id}/tick',
          {
            params: { path: { checklist_id: checklist.id, item_id: item.id } },
            body: { day: today, source: 'USER_REPORTED' },
          },
        )
        if (error) throw new Error(`POST tick answered ${response.status}`)
      } else {
        const { error, response } = await api.DELETE(
          '/api/checklists/{checklist_id}/items/{item_id}/tick/{day}',
          { params: { path: { checklist_id: checklist.id, item_id: item.id, day: dayToClear } } },
        )
        if (error) throw new Error(`DELETE tick answered ${response.status}`)
      }
    },
    onSuccess: () => queryClient.invalidateQueries({ queryKey: CHANGES_KEY }),
  })

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
    <li className="flex items-center gap-3 py-1.5">
      <Checkbox
        checked={ticked}
        disabled={setTick.isPending}
        onCheckedChange={(checked) => setTick.mutate(checked === true)}
        aria-label={`Mark "${item.text}" ${ticked ? 'not done' : 'done'}`}
      />
      <span className={`flex-1 text-sm ${ticked ? 'text-muted-foreground line-through' : ''}`}>
        {item.text}
      </span>
      <Button
        variant="ghost"
        size="icon-sm"
        aria-label={`Remove "${item.text}"`}
        disabled={remove.isPending}
        onClick={() => remove.mutate()}
      >
        <Trash2 className="size-3.5" />
      </Button>
      {(setTick.isError || remove.isError) && (
        <span className="text-xs text-destructive">
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
      className="mt-2 flex gap-2"
      onSubmit={(event) => {
        event.preventDefault()
        const trimmed = text.trim()
        if (trimmed) add.mutate(trimmed)
      }}
    >
      <Input
        value={text}
        onChange={(event) => setText(event.target.value)}
        placeholder="Add an item"
        aria-label="Add an item"
      />
      <Button type="submit" disabled={add.isPending || text.trim() === ''}>
        Add
      </Button>
      {add.isError && <span className="text-xs text-destructive">{add.error.message}</span>}
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

  return (
    <div className="rounded-md border p-4">
      <h2 className="mb-2 font-semibold">{checklist.name}</h2>
      {ownItems.length === 0 ? (
        <p className="text-sm text-muted-foreground">Nothing on this list yet.</p>
      ) : (
        <ul className="divide-y">
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
      className="flex gap-2"
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
      />
      <Button type="submit" disabled={create.isPending || name.trim() === ''}>
        New list
      </Button>
      {create.isError && <span className="text-xs text-destructive">{create.error.message}</span>}
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

  if (changes.isError) {
    return (
      <div className="mx-auto max-w-lg rounded-md border border-destructive/30 bg-destructive/5 p-4 text-sm">
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
      {checklists.length === 0 ? (
        <p className="text-sm text-muted-foreground">No lists yet. Start one below.</p>
      ) : (
        checklists.map((checklist) => (
          <ChecklistCard key={checklist.id} checklist={checklist} items={items} ticks={ticks} />
        ))
      )}
      <NewChecklistForm />
    </div>
  )
}
