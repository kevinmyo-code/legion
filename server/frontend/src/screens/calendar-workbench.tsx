import { ChevronLeft, ChevronRight, Pin } from 'lucide-react'
import { RadioGroup } from 'radix-ui'
import { useState } from 'react'

import type { Changes } from '@/api/types'
import { Freshness } from '@/components/freshness'
import { MonthView } from '@/components/month-view'
import { NewEventButton } from '@/components/new-event-button'
import { Button } from '@/components/ui/button'
import { WeekView } from '@/components/week-view'
import { EmptySentence, PageHeader } from '@/components/workbench/page'
import { groupByDay, monthGridDays, monthLabel, stepAnchor, weekDays, weekLabel } from '@/lib/calendar-range'
import { todayEpochDay } from '@/lib/day'
import { pinnedSuggestionsOnly } from '@/lib/suggestion'

type View = 'week' | 'month'

const VIEW_CHIP =
  'inline-flex h-10 items-center justify-center rounded-full border border-outline px-5 text-sm font-medium outline-none transition-colors focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-primary data-[state=checked]:border-transparent data-[state=checked]:bg-primary-container data-[state=checked]:text-primary-container-foreground'

/**
 * Calendar on the desk (spec D10): the week to plan in, the month to see the
 * term in. One header carries the view switch, Today and the arrows; the "New
 * event" button opens the same sheet a row or an empty slot does.
 *
 * It says in words when the range it is showing has nothing in it. A grid of bare
 * hour lines reads as "free" when it may only mean "nothing here yet", and those
 * are different sentences (CLAUDE.md section 1).
 */
export function CalendarWorkbench({
  changes,
  freshness,
}: {
  changes: Changes
  freshness: Parameters<typeof Freshness>[0]
}) {
  const today = todayEpochDay()
  const [view, setView] = useState<View>('week')
  const [anchor, setAnchor] = useState(today)
  // "Pinned only" (2026-10-09): hides the suggestions nobody has pinned. Plans
  // are never hidden by it.
  const [pinnedOnly, setPinnedOnly] = useState(false)

  const days = view === 'week' ? weekDays(anchor) : monthGridDays(anchor)
  const events = changes.events ?? []
  const byDay = groupByDay(days, pinnedOnly ? pinnedSuggestionsOnly(events) : events, changes.event_skips)
  const total = [...byDay.values()].reduce((sum, items) => sum + items.length, 0)
  const label = view === 'week' ? weekLabel(days) : monthLabel(anchor)

  return (
    <div className="flex flex-col gap-5">
      <PageHeader
        title="Calendar"
        subtitle={<Freshness {...freshness} />}
        action={<NewEventButton />}
      />

      <div className="flex flex-wrap items-center gap-3">
        <RadioGroup.Root
          aria-label="View"
          value={view}
          onValueChange={(next) => setView(next as View)}
          className="flex gap-2"
        >
          <RadioGroup.Item value="week" className={VIEW_CHIP}>
            Week
          </RadioGroup.Item>
          <RadioGroup.Item value="month" className={VIEW_CHIP}>
            Month
          </RadioGroup.Item>
        </RadioGroup.Root>

        <div className="flex items-center gap-1">
          <Button
            variant="secondary"
            size="icon-sm"
            aria-label={view === 'week' ? 'Previous week' : 'Previous month'}
            onClick={() => setAnchor((day) => stepAnchor(day, view, -1))}
          >
            <ChevronLeft />
          </Button>
          <Button variant="secondary" size="sm" onClick={() => setAnchor(today)}>
            Today
          </Button>
          <Button
            variant="secondary"
            size="icon-sm"
            aria-label={view === 'week' ? 'Next week' : 'Next month'}
            onClick={() => setAnchor((day) => stepAnchor(day, view, 1))}
          >
            <ChevronRight />
          </Button>
        </div>
        <Button
          type="button"
          variant={pinnedOnly ? 'secondary' : 'outline'}
          size="sm"
          aria-pressed={pinnedOnly}
          onClick={() => setPinnedOnly((on) => !on)}
        >
          <Pin aria-hidden="true" />
          Pinned only
        </Button>
        <h2 className="text-xl font-medium" aria-live="polite">
          {label}
        </h2>
      </div>

      {total === 0 && (
        <EmptySentence>
          {pinnedOnly
            ? `Nothing on the calendar this ${view}, and no pinned suggestions. Turn off Pinned only to see every suggestion.`
            : `Nothing on the calendar this ${view}. Press an empty time, or New event, to add something.`}
        </EmptySentence>
      )}

      {view === 'week' ? (
        <WeekView days={days} today={today} byDay={byDay} />
      ) : (
        <MonthView
          days={days}
          anchor={anchor}
          today={today}
          byDay={byDay}
          onOpenDay={(day) => {
            setAnchor(day)
            setView('week')
          }}
        />
      )}
    </div>
  )
}
