import type { Changes } from '@/api/types'
import { Freshness } from '@/components/freshness'
import { MonthCalendar } from '@/components/month-calendar'
import { NewEventButton } from '@/components/new-event-button'

/**
 * Calendar on the phone (spec D10, family): the month as a grid with a count on
 * each busy day, the tapped day's agenda under it, and a "+" to add. The grid
 * and the agenda are `MonthCalendar`, the vocabulary Home already uses, so a day
 * reads the same on both screens. Its rows say who sees them in words, and a
 * row opens the event sheet.
 */
export function CalendarFamily({
  changes,
  freshness,
}: {
  changes: Changes
  freshness: Parameters<typeof Freshness>[0]
}) {
  return (
    <div className="flex flex-col gap-4">
      <div className="flex flex-col gap-1">
        <h1 className="text-[1.75rem] leading-tight tracking-tight">Calendar</h1>
        <Freshness {...freshness} />
      </div>
      <section className="rounded-sheet bg-card px-4 pt-3.5 pb-4">
        <MonthCalendar events={changes.events ?? []} skips={changes.event_skips} />
      </section>
      <NewEventButton />
    </div>
  )
}
