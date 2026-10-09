import type { MouseEvent } from 'react'

import { isCanvasRow, timeLabel } from '@/components/event-row'
import { useEventSheet } from '@/components/event-sheet-context'
import { TaskCheck } from '@/components/task-check'
import { SUGGESTION_TONE, SuggestionMark } from '@/components/suggestion-mark'
import { SuggestionPinnedLine } from '@/components/suggestion-pin'
import { VisibilityMark } from '@/components/visibility-mark'
import { canvasLine, canvasMetaOf } from '@/lib/canvas'
import { dateForEpochDay } from '@/lib/day'
import { splitCourse } from '@/lib/horizon'
import type { Occurrence } from '@/lib/recurrence'
import { isSuggestion } from '@/lib/suggestion'
import { cn } from '@/lib/utils'
import { visibilityOf } from '@/lib/visibility'
import {
  GRID_END_MINUTES,
  GRID_START_MINUTES,
  hourLabel,
  isTimed,
  layoutTimed,
  type Placed,
} from '@/lib/week-layout'

/**
 * The week, Kevin's planning surface (spec D10, workbench).
 *
 * Seven day columns on an hour grid from 6:00 to midnight, with a lane above it
 * for what has no place on the clock: all-day events and tasks. A task is a
 * deadline, and a deadline has no duration; a day of nine "11:59 PM" tasks as
 * nine blocks on the last minute of the grid would be unreadable, so tasks stay
 * in the lane with their time written on them and their Canvas status line
 * under the title, and a checkbox that is the one way to mark them done.
 *
 * Events that overlap sit side by side (`lib/week-layout.ts`). Pressing an empty
 * part of a column opens the event sheet on that day at that time, to the half
 * hour; pressing an event opens it for editing. Every block, chip and task says
 * who sees it in words - pink and "Shared", or a lock and "Only you" - because
 * colour alone is not a disclosure.
 */

export const HOUR_PX = 56
const HOURS = Array.from({ length: (GRID_END_MINUTES - GRID_START_MINUTES) / 60 }, (_, i) => GRID_START_MINUTES / 60 + i)
const GRID_HEIGHT = HOURS.length * HOUR_PX

function minutesToY(minutes: number): number {
  return ((minutes - GRID_START_MINUTES) / 60) * HOUR_PX
}

function clockLabel(minutes: number): string {
  return new Date(2000, 0, 1, Math.floor(minutes / 60), minutes % 60).toLocaleTimeString(undefined, {
    hour: 'numeric',
    minute: '2-digit',
  })
}

function dayHeading(day: number): string {
  return dateForEpochDay(day).toLocaleDateString(undefined, { weekday: 'long', month: 'short', day: 'numeric' })
}

export function WeekView({
  days,
  today,
  byDay,
}: {
  days: number[]
  today: number
  byDay: Map<number, Occurrence[]>
}) {
  const sheet = useEventSheet()

  return (
    <div className="overflow-hidden rounded-sheet bg-card">
      <div className="grid grid-cols-[3.5rem_repeat(7,minmax(0,1fr))]">
        {/* Day headings. */}
        <div />
        {days.map((day) => {
          const date = dateForEpochDay(day)
          const isToday = day === today
          return (
            <div key={day} className="flex flex-col items-center gap-0.5 px-1 pt-3 pb-2">
              <span className={cn('text-[0.75rem] uppercase', isToday ? 'font-semibold text-primary' : 'text-muted-foreground')}>
                {date.toLocaleDateString(undefined, { weekday: 'short' })}
              </span>
              <span
                aria-label={dayHeading(day)}
                aria-current={isToday ? 'date' : undefined}
                className={cn(
                  'grid size-9 place-items-center rounded-full text-[1.125rem] tabular-nums',
                  isToday ? 'bg-primary font-medium text-primary-foreground' : '',
                )}
              >
                {date.getDate()}
              </span>
            </div>
          )
        })}

        {/* The lane: all-day events, and tasks, which have a deadline and no duration. */}
        <div className="px-2 pt-2 text-right text-[0.6875rem] text-muted-foreground">All day</div>
        {days.map((day) => (
          <Lane
            key={day}
            day={day}
            isToday={day === today}
            items={(byDay.get(day) ?? []).filter((o) => !isTimed(o))}
          />
        ))}
      </div>

      <div className="grid grid-cols-[3.5rem_repeat(7,minmax(0,1fr))] border-t border-outline-variant">
        <div className="relative" style={{ height: GRID_HEIGHT }}>
          {HOURS.map((hour, index) => (
            <span
              key={hour}
              className="absolute right-2 -translate-y-1/2 text-[0.6875rem] text-muted-foreground tabular-nums"
              style={{ top: index * HOUR_PX, display: index === 0 ? 'none' : undefined }}
            >
              {hourLabel(hour)}
            </span>
          ))}
        </div>
        {days.map((day) => (
          <DayColumn key={day} day={day} isToday={day === today} items={byDay.get(day) ?? []} sheet={sheet} />
        ))}
      </div>
    </div>
  )
}

function Lane({ day, isToday, items }: { day: number; isToday: boolean; items: Occurrence[] }) {
  const sheet = useEventSheet()
  return (
    <div
      className={cn('flex min-h-12 flex-col gap-1 px-1 pb-2', isToday && 'bg-primary-container/30')}
      data-lane-day={day}
    >
      {items.map((occurrence) =>
        occurrence.event.kind === 'task' ? (
          <TaskChip key={`${occurrence.event.id}:${occurrence.date}`} occurrence={occurrence} />
        ) : (
          <AllDayChip
            key={`${occurrence.event.id}:${occurrence.date}`}
            occurrence={occurrence}
            onOpen={sheet ? () => sheet.open({ kind: 'edit', occurrence }) : undefined}
          />
        ),
      )}
    </div>
  )
}

function AllDayChip({ occurrence, onOpen }: { occurrence: Occurrence; onOpen?: () => void }) {
  const { event } = occurrence
  const visibility = visibilityOf(event)
  const suggestion = isSuggestion(event)
  const shared = visibility === 'shared' && !suggestion
  const body = (
    <>
      <span className="block truncate text-[0.8125rem] font-medium">{event.title}</span>
      {suggestion && <SuggestionPinnedLine event={event} compact className="max-w-full" />}
      {suggestion ? <SuggestionMark compact /> : <VisibilityMark visibility={visibility} compact onTint={shared} />}
    </>
  )
  const className = cn(
    'flex min-w-0 flex-col items-start gap-0.5 rounded-lg px-2 py-1 text-left',
    suggestion ? SUGGESTION_TONE : shared ? 'bg-shared text-shared-foreground' : 'bg-surface-3',
  )
  return onOpen ? (
    <button
      type="button"
      className={cn(className, 'outline-none focus-visible:outline-2 focus-visible:outline-primary')}
      aria-label={`Edit ${event.title}`}
      onClick={onOpen}
    >
      {body}
    </button>
  ) : (
    <div className={className}>{body}</div>
  )
}

function TaskChip({ occurrence }: { occurrence: Occurrence }) {
  const { event } = occurrence
  const sheet = useEventSheet()
  const { course, label } = splitCourse(event.title)
  const canvasMeta = canvasMetaOf(event.structured_meta)
  const canvas = canvasMeta ? canvasLine(event.done ?? false, canvasMeta) : ''
  const editable = sheet !== null && !isCanvasRow(occurrence)
  const text = (
    <>
      <span className={cn('block text-[0.8125rem] font-medium', event.done && 'text-muted-foreground line-through')}>
        {label}
      </span>
      {course && <span className="block truncate text-[0.6875rem] text-muted-foreground">{course}</span>}
    </>
  )
  return (
    <div className="flex min-w-0 flex-col gap-0.5 rounded-lg bg-surface-2 px-2 py-1.5">
      <div className="flex items-start gap-2">
        <TaskCheck event={event} className="mt-0.5" />
        {editable ? (
          <button
            type="button"
            className="min-w-0 flex-1 rounded-sm text-left outline-none focus-visible:outline-2 focus-visible:outline-primary"
            aria-label={`Edit ${label}`}
            onClick={() => sheet.open({ kind: 'edit', occurrence })}
          >
            {text}
          </button>
        ) : (
          <div className="min-w-0 flex-1">{text}</div>
        )}
      </div>
      <div className="flex flex-wrap items-center gap-x-2 pl-8 text-[0.6875rem] text-muted-foreground">
        <span className="tabular-nums">{timeLabel(occurrence)}</span>
        <VisibilityMark visibility={visibilityOf(event)} compact />
      </div>
      {canvas && <p className="pl-8 text-[0.6875rem] text-muted-foreground">{canvas}</p>}
    </div>
  )
}

function DayColumn({
  day,
  isToday,
  items,
  sheet,
}: {
  day: number
  isToday: boolean
  items: Occurrence[]
  sheet: ReturnType<typeof useEventSheet>
}) {
  const placed = layoutTimed(items)

  function create(event: MouseEvent<HTMLDivElement>) {
    // A press on a block opens that block; only the bare column adds.
    if (sheet === null || event.target !== event.currentTarget) return
    const rect = event.currentTarget.getBoundingClientRect()
    const y = Math.min(Math.max(event.clientY - rect.top, 0), GRID_HEIGHT - 1)
    const half = Math.floor(((y / HOUR_PX) * 60) / 30) * 30
    sheet.open({ kind: 'create', day, startMinutes: GRID_START_MINUTES + half })
  }

  return (
    <div
      data-day={day}
      aria-label={`${dayHeading(day)}. Press an empty time to add an event.`}
      role="group"
      onClick={create}
      className={cn(
        'relative cursor-cell border-l border-outline-variant bg-[linear-gradient(to_bottom,var(--outline-variant)_1px,transparent_1px)]',
        isToday && 'bg-primary-container/20',
      )}
      style={{ height: GRID_HEIGHT, backgroundSize: `100% ${HOUR_PX}px` }}
    >
      {placed.map((block) => (
        <Block key={`${block.occurrence.event.id}:${block.occurrence.date}`} block={block} sheet={sheet} />
      ))}
    </div>
  )
}

function Block({ block, sheet }: { block: Placed; sheet: ReturnType<typeof useEventSheet> }) {
  const { occurrence } = block
  const { event } = occurrence
  const visibility = visibilityOf(event)
  const suggestion = isSuggestion(event)
  const shared = visibility === 'shared' && !suggestion
  const top = minutesToY(block.startMinutes)
  const height = ((block.endMinutes - block.startMinutes) / 60) * HOUR_PX
  // What fits. A half-hour block is one row (title, then the words); an hour has
  // room for the title and the words stacked; the time line only appears from an
  // hour and a quarter, and is always in the label a screen reader gets.
  const short = height < 45
  const roomy = height >= 72
  const className = cn(
    'absolute flex min-w-0 overflow-hidden rounded-lg px-2 text-left',
    short ? 'flex-row items-center gap-1.5 py-0' : 'flex-col items-start gap-0.5 py-1',
    suggestion ? SUGGESTION_TONE : shared ? 'bg-shared text-shared-foreground' : 'bg-surface-3 text-foreground',
  )
  const style = {
    top: top + 1,
    height: height - 2,
    left: `calc(${(block.column / block.columns) * 100}% + 2px)`,
    width: `calc(${100 / block.columns}% - 4px)`,
  }
  const content = (
    <>
      <span className="block min-w-0 shrink truncate text-[0.8125rem] leading-tight font-medium">{event.title}</span>
      {roomy && (
        <span className="block w-full shrink-0 truncate text-[0.6875rem] leading-4 tabular-nums opacity-80">
          {clockLabel(block.startMinutes)} {'–'} {clockLabel(block.endMinutes)}
          {event.location ? ` · ${event.location}` : ''}
        </span>
      )}
      {suggestion && !short && <SuggestionPinnedLine event={event} compact className="max-w-full" />}
      {suggestion ? (
        <SuggestionMark compact className="shrink-0" />
      ) : (
        <VisibilityMark visibility={visibility} compact onTint={shared} className="shrink-0" />
      )}
    </>
  )
  return sheet ? (
    <button
      type="button"
      style={style}
      className={cn(className, 'outline-none focus-visible:outline-2 focus-visible:outline-primary')}
      aria-label={`Edit ${event.title}, ${clockLabel(block.startMinutes)}`}
      onClick={() => sheet.open({ kind: 'edit', occurrence })}
    >
      {content}
    </button>
  ) : (
    <div style={style} className={className}>
      {content}
    </div>
  )
}
