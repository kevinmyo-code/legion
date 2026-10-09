import { ChevronLeft, ChevronRight, Pin, Plus } from 'lucide-react'
import { useState } from 'react'

import { EventRow } from '@/components/event-row'
import { useEventSheet } from '@/components/event-sheet-context'
import { Button } from '@/components/ui/button'
import type { Event, EventSkip } from '@/api/types'
import { dateForEpochDay, todayEpochDay } from '@/lib/day'
import { buildMonth, type MonthCell } from '@/lib/horizon'
import { isSuggestion, pinnedSuggestionsOnly } from '@/lib/suggestion'
import { occurrencesOnDay } from '@/lib/recurrence'

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
export function MonthCalendar({ events, skips }: { events: Event[]; skips?: readonly EventSkip[] }) {
  const today = todayEpochDay()
  const sheet = useEventSheet()
  const [monthAnchor, setMonthAnchor] = useState(() => dateForEpochDay(today))
  const [selectedDay, setSelectedDay] = useState(today)
  // "Pinned only" (2026-10-09): hides the suggestions nobody has pinned, on the
  // grid and in the day. Plans are never hidden by it.
  const [pinnedOnly, setPinnedOnly] = useState(false)
  const shown = pinnedOnly ? pinnedSuggestionsOnly(events) : events

  const cells = buildMonth(monthAnchor, shown, skips)
  const monthLabel = monthAnchor.toLocaleDateString(undefined, { month: 'long', year: 'numeric' })

  const goToMonth = (offset: number) => {
    setMonthAnchor((prev) => new Date(prev.getFullYear(), prev.getMonth() + offset, 1))
  }
  const goToToday = () => {
    setMonthAnchor(dateForEpochDay(today))
    setSelectedDay(today)
  }

  const selectedEvents = occurrencesOnDay(selectedDay, shown, skips)
  const daySuggestionCount = pinnedOnly
    ? occurrencesOnDay(selectedDay, events, skips).filter((o) => isSuggestion(o.event)).length
    : selectedEvents.filter((o) => isSuggestion(o.event)).length
  const selectedTasks = selectedEvents.filter((o) => o.event.kind === 'task')
  const selectedCalendar = selectedEvents.filter((o) => o.event.kind !== 'task' && !isSuggestion(o.event))
  const selectedSuggestions = selectedEvents.filter((o) => isSuggestion(o.event))
  const planCount = selectedTasks.length + selectedCalendar.length
  const selectedLabel = dateForEpochDay(selectedDay).toLocaleDateString(undefined, {
    weekday: 'long',
    month: 'short',
    day: 'numeric',
  })

  return (
    <div className="flex flex-col gap-3">
      <div className="flex items-center justify-between gap-2">
        <h3 className="text-[0.9375rem] font-medium">{monthLabel}</h3>
        <div className="flex items-center gap-1">
          <Button variant="secondary" size="icon-sm" aria-label="Previous month" onClick={() => goToMonth(-1)}>
            <ChevronLeft />
          </Button>
          <Button variant="secondary" size="sm" onClick={goToToday}>
            Today
          </Button>
          <Button variant="secondary" size="icon-sm" aria-label="Next month" onClick={() => goToMonth(1)}>
            <ChevronRight />
          </Button>
        </div>
      </div>

      <div className="grid grid-cols-7 gap-1">
        {WEEKDAY_LABELS.map((label, index) => (
          <div
            key={index}
            className="text-center text-[0.6875rem] uppercase text-muted-foreground"
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

      <div className="rounded-card bg-surface-1 p-3">
        <div className="mb-2 flex items-center justify-between gap-2">
          <h3 className="px-1 text-[0.9375rem] font-medium">{selectedLabel}</h3>
          {sheet && (
            <Button
              variant="ghost"
              size="sm"
              aria-label={`Add an event on ${selectedLabel}`}
              onClick={() => sheet.open({ kind: 'create', day: selectedDay })}
            >
              <Plus />
              Add
            </Button>
          )}
        </div>
        {planCount === 0 ? (
          <p className="px-1 text-[0.9375rem] text-muted-foreground">Nothing on the calendar this day.</p>
        ) : (
          <ul className="flex flex-col gap-1.5">
            {selectedTasks.map((occurrence) => (
              <EventRow key={`${occurrence.event.id}:${occurrence.date}`} occurrence={occurrence} />
            ))}
            {selectedCalendar.map((occurrence) => (
              <EventRow key={`${occurrence.event.id}:${occurrence.date}`} occurrence={occurrence} />
            ))}
          </ul>
        )}
        {daySuggestionCount > 0 && (
          <div className="mt-3 flex flex-col gap-1.5">
            <div className="flex items-center justify-between gap-2">
              <h4 className="px-1 text-[0.8125rem] text-muted-foreground">Suggestions, not in your plans</h4>
              <Button
                type="button"
                variant={pinnedOnly ? 'secondary' : 'outline'}
                size="xs"
                aria-pressed={pinnedOnly}
                onClick={() => setPinnedOnly((on) => !on)}
              >
                <Pin aria-hidden="true" />
                Pinned only
              </Button>
            </div>
            {selectedSuggestions.length === 0 ? (
              <p className="px-1 text-[0.9375rem] text-muted-foreground">
                No pinned suggestions on this day. Nobody has said they want to go to one yet.
              </p>
            ) : (
              <ul className="flex flex-col gap-1.5">
                {selectedSuggestions.map((occurrence) => (
                  <EventRow key={`${occurrence.event.id}:${occurrence.date}`} occurrence={occurrence} />
                ))}
              </ul>
            )}
          </div>
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
      }${cell.events > 0 ? `, ${cell.events} on the calendar` : ''}${
        cell.suggestions > 0 ? `, ${cell.suggestions} ${cell.suggestions === 1 ? 'suggestion' : 'suggestions'}, not planned` : ''
      }`}
      aria-current={isSelected ? 'date' : undefined}
      className={[
        'relative flex aspect-square min-w-0 flex-col items-center justify-center gap-0.5 rounded-xl bg-surface-1 text-xs lg:aspect-auto lg:h-14',
        cell.inMonth ? '' : 'opacity-40',
        isSelected ? 'ring-2 ring-primary' : '',
        isToday ? 'font-bold text-primary' : '',
      ].join(' ')}
      style={
        outstanding > 0
          ? { backgroundColor: `color-mix(in oklab, var(--primary) ${10 + Math.min(outstanding, 6) * 6}%, var(--surface-1))` }
          : undefined
      }
    >
      <span className="tabular-nums">{cell.date.getDate()}</span>
      <span
        className={[
          'h-3 text-[0.6875rem] tabular-nums',
          outstanding > 0 ? 'font-semibold text-foreground' : 'text-muted-foreground',
        ].join(' ')}
      >
        {outstanding > 0 ? outstanding : cell.events > 0 ? '·' : ''}
      </span>
      {cell.suggestions > 0 && (
        <span
          aria-hidden="true"
          title="Suggestion, not planned"
          className="absolute top-1 right-1 size-1.5 rounded-full border border-dashed border-primary bg-primary-container"
        />
      )}
    </button>
  )
}
