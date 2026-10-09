import type { Event, EventSkip } from '@/api/types'
import { EventRow } from '@/components/event-row'
import { GroupedTasks } from '@/components/grouped-tasks'
import { groupByDay } from '@/lib/calendar-range'
import { plansOnly } from '@/lib/suggestion'
import { dateForEpochDay, todayEpochDay } from '@/lib/day'
import { cn } from '@/lib/utils'

/** Today and this many days after it. */
export const AGENDA_DAYS_AFTER = 7

/**
 * "Today and the next 7 days": the workbench Home's agenda, one block per day in
 * the order they come (prototype C, artboard 4).
 *
 * Each day lists its events first, all-day ones before timed ones, then its
 * tasks grouped by course, so nine deadlines at 11:59 PM read as a course
 * heading and a short list rather than nine identical rows. Every row says who
 * sees it, in words, and opens the event sheet. A day with nothing on it says
 * "Nothing planned" - a sentence, not a gap - and the panel as a whole says so
 * when the whole stretch is empty, because a column of "Nothing planned" could
 * as easily mean "nothing was read".
 */
export function AgendaPanel({
  events,
  skips,
}: {
  events: Event[]
  skips?: readonly EventSkip[]
}) {
  const today = todayEpochDay()
  const days = Array.from({ length: AGENDA_DAYS_AFTER + 1 }, (_, offset) => today + offset)
  // Home is the household's plans: a suggestion is not one (lib/suggestion.ts).
  const byDay = groupByDay(days, plansOnly(events), skips)
  const total = [...byDay.values()].reduce((sum, items) => sum + items.length, 0)

  return (
    <section className="rounded-sheet bg-card px-4 pt-3.5 pb-5 md:px-5">
      <h2 className="mb-3 text-base font-medium">Today and the next 7 days</h2>
      {total === 0 && (
        <p className="mb-3 rounded-control bg-surface-2 px-4 py-3 text-[0.9375rem] text-muted-foreground">
          Nothing on the calendar for the next 7 days.
        </p>
      )}
      <ol className="flex flex-col gap-4">
        {days.map((day) => {
          const date = dateForEpochDay(day)
          const isToday = day === today
          const items = byDay.get(day) ?? []
          const dated = items.filter((o) => o.event.kind !== 'task')
          const tasks = items.filter((o) => o.event.kind === 'task')
          return (
            <li key={day} className="grid grid-cols-[3.25rem_minmax(0,1fr)] gap-3">
              <div
                className="flex flex-col items-center pt-1"
                aria-label={date.toLocaleDateString(undefined, { weekday: 'long', month: 'long', day: 'numeric' })}
              >
                <span className={cn('text-[0.75rem]', isToday ? 'font-semibold text-primary' : 'text-muted-foreground')}>
                  {isToday ? 'Today' : date.toLocaleDateString(undefined, { weekday: 'short' })}
                </span>
                <span
                  className={cn(
                    'grid size-8 place-items-center rounded-full text-base tabular-nums',
                    isToday && 'bg-primary font-medium text-primary-foreground',
                  )}
                >
                  {date.getDate()}
                </span>
              </div>
              <div className="flex min-w-0 flex-col gap-1.5">
                {items.length === 0 ? (
                  <p className="rounded-[1.125rem] border border-outline-variant px-4 py-2.5 text-[0.9375rem] text-muted-foreground">
                    Nothing planned
                  </p>
                ) : (
                  <>
                    {dated.length > 0 && (
                      <ul className="flex flex-col gap-1.5">
                        {dated.map((occurrence) => (
                          <EventRow key={`${occurrence.event.id}:${occurrence.date}`} occurrence={occurrence} />
                        ))}
                      </ul>
                    )}
                    {tasks.length > 0 && <GroupedTasks tasks={tasks} />}
                  </>
                )}
              </div>
            </li>
          )
        })}
      </ol>
    </section>
  )
}
