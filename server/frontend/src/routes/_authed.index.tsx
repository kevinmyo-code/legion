import { useMutation, useQueryClient } from '@tanstack/react-query'
import { createFileRoute } from '@tanstack/react-router'

import { api } from '@/api/client'
import { CHANGES_KEY, useChanges } from '@/api/queries'
import type { Event } from '@/api/types'
import { Freshness } from '@/components/freshness'
import { HorizonStrip } from '@/components/horizon-strip'
import { Checkbox } from '@/components/ui/checkbox'
import { Skeleton } from '@/components/ui/skeleton'
import { todayEpochDay } from '@/lib/day'
import {
  buildHorizon,
  groupByCourse,
  loadSentence,
  nextUp,
  overdueTasks,
  splitCourse,
} from '@/lib/horizon'
import { eventsOnDay, itemsDueOn, type DueItem } from '@/lib/today'

export const Route = createFileRoute('/_authed/')({
  component: Today,
})

function EventRow({ event, showCourse = true }: { event: Event; showCourse?: boolean }) {
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

  const { course, label } = splitCourse(event.title)
  const time = event.all_day
    ? 'All day'
    : event.starts_at
      ? new Date(event.starts_at).toLocaleTimeString(undefined, {
          hour: 'numeric',
          minute: '2-digit',
        })
      : ''

  return (
    <li className="flex items-start gap-3 py-2">
      {event.kind === 'task' ? (
        <Checkbox
          className="mt-0.5"
          checked={event.done}
          disabled={toggleDone.isPending}
          onCheckedChange={(checked) => toggleDone.mutate(checked === true)}
          aria-label={`Mark "${label}" ${event.done ? 'not done' : 'done'}`}
        />
      ) : (
        // An event passes whether or not you engage with it (one-today ticket
        // 08). No checkbox, and the gap where one would be is deliberate - it is
        // what makes the two kinds distinguishable without reading the row.
        <span className="mt-0.5 w-4 shrink-0" aria-hidden="true" />
      )}
      <div className="min-w-0 flex-1">
        <span
          className={
            event.kind === 'task' && event.done
              ? 'text-sm text-muted-foreground line-through'
              : 'text-sm'
          }
        >
          {label}
        </span>
        {showCourse && course && (
          <span className="ml-2 text-xs text-muted-foreground">{course}</span>
        )}
      </div>
      <span className="shrink-0 text-xs tabular-nums text-muted-foreground">{time}</span>
      {toggleDone.isError && (
        <span className="text-xs text-destructive">Could not save. {toggleDone.error.message}</span>
      )}
    </li>
  )
}

/** A day's tasks, grouped by course. Nine rows that all read `11:59 PM` are nine
 * rows whose times say nothing; the course is the only thing that separates them
 * at a glance, so it becomes a heading instead of a prefix repeated nine times. */
function GroupedTasks({ tasks }: { tasks: Event[] }) {
  const groups = groupByCourse(tasks)
  if (groups.length <= 1) {
    return (
      <ul className="divide-y">
        {tasks.map((event) => (
          <EventRow key={event.id} event={event} />
        ))}
      </ul>
    )
  }
  return (
    <div className="flex flex-col gap-3">
      {groups.map((group) => (
        <div key={group.course ?? 'none'}>
          {group.course && (
            <h4 className="mb-0.5 text-xs font-medium text-muted-foreground">{group.course}</h4>
          )}
          <ul className="divide-y">
            {group.items.map((event) => (
              <EventRow key={event.id} event={event} showCourse={false} />
            ))}
          </ul>
        </div>
      ))}
    </div>
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
    <li className="flex items-center gap-3 py-2">
      <Checkbox
        checked={due.tickedToday}
        disabled={setTick.isPending}
        onCheckedChange={(checked) => setTick.mutate(checked === true)}
        aria-label={`Mark "${due.item.text}" ${due.tickedToday ? 'not done' : 'done'} for today`}
      />
      <span
        className={
          due.tickedToday ? 'flex-1 text-sm text-muted-foreground line-through' : 'flex-1 text-sm'
        }
      >
        {due.item.text}
      </span>
      <span className="shrink-0 text-xs text-muted-foreground">{due.checklist.name}</span>
      {setTick.isError && (
        <span className="text-xs text-destructive">Could not save. {setTick.error.message}</span>
      )}
    </li>
  )
}

function Section({
  title,
  aside,
  children,
}: {
  title: string
  aside?: string | null
  children: React.ReactNode
}) {
  return (
    <section>
      <div className="mb-1 flex items-baseline justify-between gap-3">
        <h2 className="text-sm font-semibold text-muted-foreground">{title}</h2>
        {aside && <span className="text-xs text-muted-foreground">{aside}</span>}
      </div>
      {children}
    </section>
  )
}

function Today() {
  const changes = useChanges(true)

  if (changes.isPending) {
    return (
      <div className="flex flex-col gap-3">
        <Skeleton className="h-6 w-40" />
        <Skeleton className="h-24 w-full" />
        <Skeleton className="h-40 w-full" />
      </div>
    )
  }

  // CLAUDE.md section 1: unreadable and empty are different sentences. A failed
  // pull with NOTHING cached says so in words rather than rendering as an empty,
  // quiet day. The third case - a failed refetch with data still on screen - is
  // the `Freshness` line below, not this branch.
  if (changes.isError) {
    return (
      <div className="mx-auto max-w-lg rounded-md border border-destructive/30 bg-destructive/5 p-4 text-sm">
        Could not reach the engine, so this is not today's real list.{' '}
        {changes.error.message}
      </div>
    )
  }

  const today = todayEpochDay()
  const events = changes.data.events ?? []
  const due = itemsDueOn(
    today,
    changes.data.checklists ?? [],
    changes.data.checklist_items ?? [],
    changes.data.checklist_ticks ?? [],
  )
  const horizon = buildHorizon(today, events)
  const overdue = overdueTasks(today, events)
  const todaysEvents = eventsOnDay(today, events)
  const tomorrowsEvents = eventsOnDay(today + 1, events)
  const tomorrowsTasks = tomorrowsEvents.filter((event) => event.kind === 'task')
  const tomorrowsCalendar = tomorrowsEvents.filter((event) => event.kind !== 'task')
  const upcoming = nextUp(horizon)

  const dayLabel = (offset: number) =>
    new Date(Date.now() + offset * 86_400_000).toLocaleDateString(undefined, {
      weekday: 'long',
      month: 'short',
      day: 'numeric',
    })

  return (
    // Two columns from `lg` up. The desktop is Kevin's workbench and was
    // rendering this whole screen into a ~512px column inside a 1707px window;
    // the phone keeps the single column it needs.
    <div className="flex flex-col gap-8 lg:flex-row lg:items-start lg:gap-12">
      <div className="flex min-w-0 flex-1 flex-col gap-7">
        <div className="flex flex-col gap-1">
          <h1 className="text-xl font-semibold">{dayLabel(0)}</h1>
          <Freshness
            updatedAt={changes.dataUpdatedAt}
            isFetching={changes.isFetching}
            failureCount={changes.failureCount}
            error={changes.error}
          />
        </div>

        {/* Kept, not hidden. A deadline that slid is still work, and dropping it
            quietly is the same class of lie as rendering a failed read as an
            empty day. */}
        {overdue.length > 0 && (
          <Section title="Still not done" aside={`${overdue.length} past their date`}>
            <GroupedTasks tasks={overdue} />
          </Section>
        )}

        <Section
          title="To do today"
          aside={due.length > 0 ? `${due.filter((d) => d.tickedToday).length} of ${due.length} ticked` : null}
        >
          {due.length === 0 ? (
            <p className="text-sm text-muted-foreground">Nothing due today.</p>
          ) : (
            <ul className="divide-y">
              {due.map((item) => (
                <DueItemRow key={item.item.id} due={item} />
              ))}
            </ul>
          )}
        </Section>

        <Section title="On today">
          {todaysEvents.length === 0 ? (
            <p className="text-sm text-muted-foreground">Nothing on the calendar today.</p>
          ) : (
            <ul className="divide-y">
              {todaysEvents.map((event) => (
                <EventRow key={event.id} event={event} />
              ))}
            </ul>
          )}
        </Section>

        <Section title={dayLabel(1)} aside={loadSentence(horizon[1])}>
          {tomorrowsEvents.length === 0 ? (
            <p className="text-sm text-muted-foreground">Nothing on the calendar tomorrow.</p>
          ) : (
            <div className="flex flex-col gap-3">
              {tomorrowsTasks.length > 0 && <GroupedTasks tasks={tomorrowsTasks} />}
              {tomorrowsCalendar.length > 0 && (
                <ul className="divide-y">
                  {tomorrowsCalendar.map((event) => (
                    <EventRow key={event.id} event={event} />
                  ))}
                </ul>
              )}
            </div>
          )}
        </Section>
      </div>

      <aside className="flex w-full shrink-0 flex-col gap-7 lg:w-80">
        <HorizonStrip cells={horizon} />

        {/* Today and tomorrow are already rendered in full on the left; repeating
            them here would be the wall of rows the strip exists to avoid. */}
        <section>
          <h2 className="mb-2 text-sm font-semibold text-muted-foreground">After tomorrow</h2>
          {upcoming.length === 0 ? (
            <p className="text-sm text-muted-foreground">
              Nothing else due in the next two weeks.
            </p>
          ) : (
            <ul className="flex flex-col gap-2">
              {upcoming.map((cell) => (
                <li key={cell.day} className="flex items-baseline justify-between gap-3 text-sm">
                  <span>
                    {cell.date.toLocaleDateString(undefined, {
                      weekday: 'long',
                      month: 'short',
                      day: 'numeric',
                    })}
                  </span>
                  <span className="shrink-0 tabular-nums text-muted-foreground">
                    {cell.tasks - cell.tasksDone} due
                  </span>
                </li>
              ))}
            </ul>
          )}
        </section>
      </aside>
    </div>
  )
}
