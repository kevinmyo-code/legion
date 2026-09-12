import { useMutation, useQueryClient } from '@tanstack/react-query'
import { createFileRoute } from '@tanstack/react-router'

import { api } from '@/api/client'
import { CHANGES_KEY, useChanges } from '@/api/queries'
import type { Event } from '@/api/types'
import { Checkbox } from '@/components/ui/checkbox'
import { Skeleton } from '@/components/ui/skeleton'
import { todayEpochDay } from '@/lib/day'
import { eventsOnDay, itemsDueOn, type DueItem } from '@/lib/today'

export const Route = createFileRoute('/_authed/')({
  component: Today,
})

function EventRow({ event }: { event: Event }) {
  const queryClient = useQueryClient()
  const toggleDone = useMutation({
    mutationFn: async (done: boolean) => {
      const { error, response } = await api.PATCH('/api/events/{id}', {
        params: { path: { id: event.id } },
        body: { done },
      })
      if (error) {
        throw new Error(`PATCH /api/events/${event.id} answered ${response.status}`)
      }
    },
    onSuccess: () => queryClient.invalidateQueries({ queryKey: CHANGES_KEY }),
  })

  const time = event.all_day
    ? 'All day'
    : event.starts_at
      ? new Date(event.starts_at).toLocaleTimeString(undefined, {
          hour: 'numeric',
          minute: '2-digit',
        })
      : ''

  return (
    <li className="flex items-center gap-3 py-1.5">
      {event.kind === 'task' && (
        <Checkbox
          checked={event.done}
          disabled={toggleDone.isPending}
          onCheckedChange={(checked) => toggleDone.mutate(checked === true)}
          aria-label={`Mark "${event.title}" ${event.done ? 'not done' : 'done'}`}
        />
      )}
      <span className="w-16 shrink-0 text-sm text-muted-foreground">{time}</span>
      <span
        className={
          event.kind === 'task' && event.done
            ? 'text-sm text-muted-foreground line-through'
            : 'text-sm'
        }
      >
        {event.title}
      </span>
      {event.kind === 'task' && (
        <span className="rounded bg-muted px-1.5 py-0.5 text-[0.65rem] uppercase text-muted-foreground">
          due
        </span>
      )}
      {toggleDone.isError && (
        <span className="text-xs text-destructive">Could not save. {toggleDone.error.message}</span>
      )}
    </li>
  )
}

function DueItemRow({ due }: { due: DueItem }) {
  const queryClient = useQueryClient()
  const today = todayEpochDay()
  const setTick = useMutation({
    mutationFn: async (ticked: boolean) => {
      if (ticked) {
        const { error, response } = await api.POST(
          '/api/checklists/{checklist_id}/items/{item_id}/tick',
          {
            params: { path: { checklist_id: due.checklist.id, item_id: due.item.id } },
            body: { day: today, source: 'USER_REPORTED' },
          },
        )
        if (error) {
          throw new Error(`POST tick answered ${response.status}`)
        }
      } else {
        const { error, response } = await api.DELETE(
          '/api/checklists/{checklist_id}/items/{item_id}/tick/{day}',
          {
            params: {
              path: { checklist_id: due.checklist.id, item_id: due.item.id, day: today },
            },
          },
        )
        if (error) {
          throw new Error(`DELETE tick answered ${response.status}`)
        }
      }
    },
    onSuccess: () => queryClient.invalidateQueries({ queryKey: CHANGES_KEY }),
  })

  return (
    <li className="flex items-center gap-3 py-1.5">
      <Checkbox
        checked={due.tickedToday}
        disabled={setTick.isPending}
        onCheckedChange={(checked) => setTick.mutate(checked === true)}
        aria-label={`Mark "${due.item.text}" ${due.tickedToday ? 'not done' : 'done'} for today`}
      />
      <span className={due.tickedToday ? 'text-sm text-muted-foreground line-through' : 'text-sm'}>
        {due.item.text}
      </span>
      <span className="text-xs text-muted-foreground">{due.checklist.name}</span>
      {setTick.isError && (
        <span className="text-xs text-destructive">Could not save. {setTick.error.message}</span>
      )}
    </li>
  )
}

function Today() {
  const changes = useChanges(true)

  if (changes.isPending) {
    return (
      <div className="mx-auto flex max-w-lg flex-col gap-3">
        <Skeleton className="h-6 w-40" />
        <Skeleton className="h-24 w-full" />
        <Skeleton className="h-40 w-full" />
      </div>
    )
  }

  // CLAUDE.md section 1: unreadable and empty are different sentences. A
  // failed pull says so in words rather than rendering as an empty, quiet
  // day - a parent must be able to tell "nothing due" from "LEGION could
  // not check".
  if (changes.isError) {
    return (
      <div className="mx-auto max-w-lg rounded-md border border-destructive/30 bg-destructive/5 p-4 text-sm">
        Could not reach the engine, so this is not today's real list.{' '}
        {changes.error.message}
      </div>
    )
  }

  const today = todayEpochDay()
  const due = itemsDueOn(
    today,
    changes.data.checklists ?? [],
    changes.data.checklist_items ?? [],
    changes.data.checklist_ticks ?? [],
  )
  const todaysEvents = eventsOnDay(today, changes.data.events ?? [])
  const tomorrowsEvents = eventsOnDay(today + 1, changes.data.events ?? [])

  return (
    <div className="mx-auto flex max-w-lg flex-col gap-6">
      <section>
        <h2 className="mb-2 text-sm font-semibold text-muted-foreground">To do today</h2>
        {due.length === 0 ? (
          <p className="text-sm text-muted-foreground">Nothing due today.</p>
        ) : (
          <ul className="divide-y">
            {due.map((item) => (
              <DueItemRow key={item.item.id} due={item} />
            ))}
          </ul>
        )}
      </section>

      <section>
        <h2 className="mb-2 text-sm font-semibold text-muted-foreground">
          Today,{' '}
          {new Date().toLocaleDateString(undefined, { month: 'short', day: 'numeric' })}
        </h2>
        {todaysEvents.length === 0 ? (
          <p className="text-sm text-muted-foreground">Nothing on the calendar today.</p>
        ) : (
          <ul className="divide-y">
            {todaysEvents.map((event) => (
              <EventRow key={event.id} event={event} />
            ))}
          </ul>
        )}
      </section>

      <section>
        <h2 className="mb-2 text-sm font-semibold text-muted-foreground">Tomorrow</h2>
        {tomorrowsEvents.length === 0 ? (
          <p className="text-sm text-muted-foreground">Nothing on the calendar tomorrow.</p>
        ) : (
          <ul className="divide-y">
            {tomorrowsEvents.map((event) => (
              <EventRow key={event.id} event={event} />
            ))}
          </ul>
        )}
      </section>
    </div>
  )
}
