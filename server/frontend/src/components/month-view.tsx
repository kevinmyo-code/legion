import { Plus } from 'lucide-react'

import { isCanvasRow, timeLabel } from '@/components/event-row'
import { useEventSheet } from '@/components/event-sheet-context'
import { TaskCheck } from '@/components/task-check'
import { VisibilityMark } from '@/components/visibility-mark'
import { dateForEpochDay } from '@/lib/day'
import { splitCourse } from '@/lib/horizon'
import type { Occurrence } from '@/lib/recurrence'
import { cn } from '@/lib/utils'
import { visibilityOf } from '@/lib/visibility'

/**
 * The month, on the desk (spec D10, workbench): a full-width grid of weeks, each
 * day a cell listing what is on it, so the term can be planned at a glance.
 *
 * Each cell shows up to `SHOWN` rows - events first, tasks after - and a
 * "+ N more" that opens that day in the week view, where everything fits. A day
 * with more than it shows says how many it hides rather than quietly cutting.
 * Every row says who sees it in words, small but present: a pink chip with
 * "Shared", or a lock and "Only you". Pressing a row edits it; pressing a day's
 * "+" adds to that day.
 */

const SHOWN = 3
const WEEKDAYS = [0, 1, 2, 3, 4, 5, 6].map((index) =>
  new Date(2026, 0, 4 + index).toLocaleDateString(undefined, { weekday: 'short' }),
)

function dayName(day: number): string {
  return dateForEpochDay(day).toLocaleDateString(undefined, { weekday: 'long', month: 'short', day: 'numeric' })
}

export function MonthView({
  days,
  anchor,
  today,
  byDay,
  onOpenDay,
}: {
  days: number[]
  /** Any day of the month being shown; cells outside it are dimmed. */
  anchor: number
  today: number
  byDay: Map<number, Occurrence[]>
  onOpenDay: (day: number) => void
}) {
  const sheet = useEventSheet()
  const month = dateForEpochDay(anchor).getMonth()

  return (
    <div className="overflow-hidden rounded-sheet bg-card">
      <div className="grid grid-cols-7 border-b border-outline-variant">
        {WEEKDAYS.map((name) => (
          <div key={name} className="px-3 py-2 text-[0.75rem] uppercase text-muted-foreground">
            {name}
          </div>
        ))}
      </div>
      <div className="grid grid-cols-7">
        {days.map((day) => {
          const date = dateForEpochDay(day)
          const items = byDay.get(day) ?? []
          const ordered = [
            ...items.filter((o) => o.event.kind !== 'task'),
            ...items.filter((o) => o.event.kind === 'task'),
          ]
          const hidden = ordered.length - SHOWN
          const inMonth = date.getMonth() === month
          return (
            <div
              key={day}
              data-day={day}
              className={cn(
                'flex min-h-32 min-w-0 flex-col gap-1 border-r border-b border-outline-variant p-1.5 [&:nth-child(7n)]:border-r-0',
                !inMonth && 'bg-surface-1/60 text-muted-foreground',
                day === today && 'bg-primary-container/30',
              )}
            >
              <div className="flex items-center justify-between">
                <button
                  type="button"
                  aria-label={`Open ${dayName(day)} in the week view`}
                  onClick={() => onOpenDay(day)}
                  className={cn(
                    'grid size-7 place-items-center rounded-full text-[0.8125rem] tabular-nums outline-none focus-visible:outline-2 focus-visible:outline-primary',
                    day === today ? 'bg-primary font-medium text-primary-foreground' : 'hover:bg-surface-3',
                  )}
                >
                  {date.getDate()}
                </button>
                {sheet && (
                  <button
                    type="button"
                    aria-label={`Add an event on ${dayName(day)}`}
                    onClick={() => sheet.open({ kind: 'create', day })}
                    className="grid size-7 place-items-center rounded-full text-muted-foreground outline-none hover:bg-surface-3 focus-visible:outline-2 focus-visible:outline-primary"
                  >
                    <Plus className="size-4" aria-hidden="true" />
                  </button>
                )}
              </div>
              {ordered.slice(0, SHOWN).map((occurrence) => (
                <Chip key={`${occurrence.event.id}:${occurrence.date}`} occurrence={occurrence} />
              ))}
              {hidden > 0 && (
                <button
                  type="button"
                  onClick={() => onOpenDay(day)}
                  aria-label={`Show all ${ordered.length} on ${dayName(day)}`}
                  className="rounded-md px-1.5 py-0.5 text-left text-[0.75rem] font-medium text-primary outline-none hover:bg-surface-3 focus-visible:outline-2 focus-visible:outline-primary"
                >
                  + {hidden} more
                </button>
              )}
            </div>
          )
        })}
      </div>
    </div>
  )
}

function Chip({ occurrence }: { occurrence: Occurrence }) {
  const { event } = occurrence
  const sheet = useEventSheet()
  const visibility = visibilityOf(event)
  const shared = visibility === 'shared'
  const isTask = event.kind === 'task'
  const { label } = splitCourse(event.title)
  const editable = sheet !== null && !isCanvasRow(occurrence)
  const time = event.all_day || isTask ? '' : timeLabel(occurrence)

  const text = (
    <>
      {time && <span className="shrink-0 tabular-nums opacity-80">{time}</span>}
      <span className={cn('min-w-0 truncate font-medium', isTask && event.done && 'text-muted-foreground line-through')}>
        {label}
      </span>
    </>
  )
  const tone = isTask ? 'bg-surface-2' : shared ? 'bg-shared text-shared-foreground' : 'bg-surface-3'

  return (
    <div className={cn('flex min-w-0 flex-col gap-0.5 rounded-md px-1.5 py-1 text-[0.75rem]', tone)}>
      <div className="flex min-w-0 items-center gap-1.5">
        {isTask && <TaskCheck event={event} className="size-4 rounded-[0.3rem]" />}
        {editable ? (
          <button
            type="button"
            aria-label={`Edit ${label}`}
            onClick={() => sheet.open({ kind: 'edit', occurrence })}
            className="flex min-w-0 flex-1 items-center gap-1.5 rounded-sm text-left outline-none focus-visible:outline-2 focus-visible:outline-primary"
          >
            {text}
          </button>
        ) : (
          <div className="flex min-w-0 flex-1 items-center gap-1.5">{text}</div>
        )}
      </div>
      <VisibilityMark visibility={visibility} compact onTint={shared && !isTask} className="self-start" />
    </div>
  )
}
