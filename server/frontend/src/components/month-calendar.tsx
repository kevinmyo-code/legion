import { useState } from 'react'

import { EventRow } from '@/components/event-row'
import { Button } from '@/components/ui/button'
import type { Event } from '@/api/types'
import { dateForEpochDay, todayEpochDay } from '@/lib/day'
import { buildMonth, type MonthCell } from '@/lib/horizon'
import { eventsOnDay } from '@/lib/today'

const WEEKDAY_LABELS = ['S', 'M', 'T', 'W', 'T', 'F', 'S']

/**
 * Month grid primary, day view below - the same order `ui/CalendarScreen.kt`
 * settled on 2026-09-01 ("month grid primary. tapping a day on the month
 * opens up view B"), read for its SHAPE only (CLAUDE.md: the phone's chart
 * vocabulary is frozen, but this is a Compose screen, not a chart, and the
 * ordering decision is a UI ruling worth carrying over, not a pixel to copy).
 *
 * Each cell shows density, not content - a count of unfinished tasks or a dot
 * for an event, exactly `HorizonStrip`'s vocabulary, so a reader does not
 * have to learn a second visual language three inches away. Titles live only
 * in the day view underneath the selected cell.
 */
export function MonthCalendar({ events }: { events: Event[] }) {
  const today = todayEpochDay()
  const [monthAnchor, setMonthAnchor] = useState(() => dateForEpochDay(today))
  const [selectedDay, setSelectedDay] = useState(today)

  const cells = buildMonth(monthAnchor, events)
  const monthLabel = monthAnchor.toLocaleDateString(undefined, { month: 'long', year: 'numeric' })

  const goToMonth = (offset: number) => {
    setMonthAnchor((prev) => new Date(prev.getFullYear(), prev.getMonth() + offset, 1))
  }
  const goToToday = () => {
    setMonthAnchor(dateForEpochDay(today))
    setSelectedDay(today)
  }

  const selectedEvents = eventsOnDay(selectedDay, events)
  const selectedTasks = selectedEvents.filter((event) => event.kind === 'task')
  const selectedCalendar = selectedEvents.filter((event) => event.kind !== 'task')
  const selectedLabel = dateForEpochDay(selectedDay).toLocaleDateString(undefined, {
    weekday: 'long',
    month: 'short',
    day: 'numeric',
  })

  return (
    <div className="flex flex-col gap-3">
      <div className="flex items-center justify-between gap-2">
        <h2 className="text-sm font-semibold text-muted-foreground">{monthLabel}</h2>
        <div className="flex items-center gap-1">
          <Button variant="outline" size="icon-sm" aria-label="Previous month" onClick={() => goToMonth(-1)}>
            ‹
          </Button>
          <Button variant="outline" size="sm" onClick={goToToday}>
            Today
          </Button>
          <Button variant="outline" size="icon-sm" aria-label="Next month" onClick={() => goToMonth(1)}>
            ›
          </Button>
        </div>
      </div>

      <div className="grid grid-cols-7 gap-1">
        {WEEKDAY_LABELS.map((label, index) => (
          <div
            key={index}
            className="text-center text-[0.6rem] uppercase text-muted-foreground"
            aria-hidden="true"
          >
            {label}
          </div>
        ))}
        {cells.map((cell) => (
          <MonthDayCell
            key={cell.day}
            cell={cell}
            isToday={cell.day === today}
            isSelected={cell.day === selectedDay}
            onSelect={() => setSelectedDay(cell.day)}
          />
        ))}
      </div>

      <div className="rounded-md border p-3">
        <h3 className="mb-1 text-sm font-semibold">{selectedLabel}</h3>
        {selectedEvents.length === 0 ? (
          <p className="text-sm text-muted-foreground">Nothing on the calendar this day.</p>
        ) : (
          <ul className="divide-y">
            {selectedTasks.map((event) => (
              <EventRow key={event.id} event={event} />
            ))}
            {selectedCalendar.map((event) => (
              <EventRow key={event.id} event={event} />
            ))}
          </ul>
        )}
      </div>
    </div>
  )
}

function MonthDayCell({
  cell,
  isToday,
  isSelected,
  onSelect,
}: {
  cell: MonthCell
  isToday: boolean
  isSelected: boolean
  onSelect: () => void
}) {
  const outstanding = cell.tasks - cell.tasksDone
  return (
    <button
      type="button"
      onClick={onSelect}
      aria-label={`${cell.date.toLocaleDateString(undefined, { weekday: 'long', month: 'short', day: 'numeric' })}${
        outstanding > 0 ? `, ${outstanding} unfinished` : ''
      }${cell.events > 0 ? `, ${cell.events} on the calendar` : ''}`}
      aria-current={isSelected ? 'date' : undefined}
      className={[
        'flex aspect-square min-w-0 flex-col items-center justify-center gap-0.5 rounded-md border text-xs',
        cell.inMonth ? '' : 'opacity-40',
        isSelected ? 'border-foreground/60 ring-1 ring-foreground/30' : 'border-border',
        isToday ? 'font-semibold' : '',
      ].join(' ')}
      style={
        outstanding > 0
          ? { backgroundColor: `color-mix(in oklab, var(--primary) ${8 + Math.min(outstanding, 6) * 6}%, transparent)` }
          : undefined
      }
    >
      <span className="tabular-nums">{cell.date.getDate()}</span>
      <span
        className={[
          'h-3 text-[0.65rem] tabular-nums',
          outstanding > 0 ? 'font-semibold text-foreground' : 'text-muted-foreground',
        ].join(' ')}
      >
        {outstanding > 0 ? outstanding : cell.events > 0 ? '·' : ''}
      </span>
    </button>
  )
}
