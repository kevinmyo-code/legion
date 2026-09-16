import { createFileRoute } from '@tanstack/react-router'

import { useSetChecklistTick } from '@/api/mutations'
import { useChanges } from '@/api/queries'
import type { Checklist, ChecklistItem, ChecklistTick, Event } from '@/api/types'
import { DeleteChecklistControl } from '@/components/checklist-delete'
import { EventRow } from '@/components/event-row'
import { Freshness } from '@/components/freshness'
import { HorizonStrip } from '@/components/horizon-strip'
import { MonthCalendar } from '@/components/month-calendar'
import { Checkbox } from '@/components/ui/checkbox'
import { Skeleton } from '@/components/ui/skeleton'
import { isChecklistComplete, tickState } from '@/lib/checklist'
import { todayEpochDay } from '@/lib/day'
import {
  buildHorizon,
  groupByCourse,
  loadSentence,
  nextUp,
  overdueTasks,
} from '@/lib/horizon'
import { eventsOnDay, itemsDueOn, type DueItem } from '@/lib/today'

/** Live (not tombstoned), non-archived rows only, matching every other
 * reader on this page. */
function isLive<T extends { deleted_at: string | null }>(row: T): boolean {
  return row.deleted_at === null
}

/**
 * Home's own view of a checklist - read-mostly (no add-item form, no per-
 * item ticking), because `/lists` already owns editing and this page is a
 * summary someone can clear from without navigating (web-calendar-and-lists
 * ticket 02, Kevin verbatim: "also let me delete list from the home
 * screen"). Restricted to PLAIN (unscheduled) lists - a scheduled checklist
 * already renders as individual rows in "To do today"; repeating it here
 * would be the wall-of-rows `horizon.ts` exists to avoid.
 */
function HomeListCard({
  checklist,
  items,
  ticks,
}: {
  checklist: Checklist
  items: ChecklistItem[]
  ticks: ChecklistTick[]
}) {
  const today = todayEpochDay()
  const ownItems = items.filter((item) => item.checklist === checklist.id)
  const complete = isChecklistComplete(checklist, items, ticks, today)
  const tickedCount = ownItems.filter(
    (item) => tickState(checklist, item, ticks, today).ticked,
  ).length

  return (
    <li className="flex items-center justify-between gap-3 py-2">
      <div className="min-w-0">
        <p className="text-sm font-medium">{checklist.name}</p>
        <p className="text-xs text-muted-foreground">
          {ownItems.length === 0
            ? 'Nothing on this list yet.'
            : complete
              ? `All ${ownItems.length} ticked.`
              : `${tickedCount} of ${ownItems.length} ticked.`}
        </p>
      </div>
      <DeleteChecklistControl checklistId={checklist.id} checklistName={checklist.name} />
    </li>
  )
}

export const Route = createFileRoute('/_authed/')({
  component: Today,
})

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
  const today = todayEpochDay()
  // Every item here comes from `itemsDueOn`, which only ever includes a
  // checklist with a real schedule - `tickState`'s own rule for a scheduled
  // list is that `dayToClear` is always today, so this never needs to look
  // up a different day the way a plain list's item can.
  const setTick = useSetChecklistTick()

  return (
    <li className="flex items-center gap-3 py-2">
      <Checkbox
        checked={due.tickedToday}
        disabled={setTick.isPending}
        onCheckedChange={(checked) =>
          setTick.mutate({
            checklistId: due.checklist.id,
            itemId: due.item.id,
            ticked: checked === true,
            today,
            dayToClear: today,
          })
        }
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

  const checklists = (changes.data.checklists ?? []).filter(isLive).filter((c) => !c.archived)
  const checklistItems = (changes.data.checklist_items ?? []).filter(isLive)
  const checklistTicks = (changes.data.checklist_ticks ?? []).filter(isLive)
  // Only PLAIN (unscheduled) lists - a scheduled checklist already renders as
  // individual rows in "To do today" above; repeating it here would be the
  // wall of rows `horizon.ts` exists to avoid.
  const plainChecklists = checklists
    .filter((c) => c.schedule_kind == null)
    .sort((a, b) => (a.sort_order ?? 0) - (b.sort_order ?? 0))

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

        <Section title="Calendar">
          <MonthCalendar events={events} />
        </Section>

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

        <section>
          <h2 className="mb-2 text-sm font-semibold text-muted-foreground">Lists</h2>
          {plainChecklists.length === 0 ? (
            <p className="text-sm text-muted-foreground">No lists yet. Start one on Lists.</p>
          ) : (
            <ul className="divide-y">
              {plainChecklists.map((checklist) => (
                <HomeListCard
                  key={checklist.id}
                  checklist={checklist}
                  items={checklistItems}
                  ticks={checklistTicks}
                />
              ))}
            </ul>
          )}
        </section>

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
