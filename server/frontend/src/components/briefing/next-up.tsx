import { Link } from '@tanstack/react-router'
import { Clock, Plus } from 'lucide-react'

import { useEventSheet } from '@/components/event-sheet-context'
import { timeLabel } from '@/components/event-row'
import { Button } from '@/components/ui/button'
import { BriefingSection, Quiet } from '@/components/briefing/parts'
import { pickNextUp, untilWords } from '@/lib/briefing'
import { splitCourse } from '@/lib/horizon'
import type { Occurrence } from '@/lib/recurrence'
import { visibilityOf } from '@/lib/visibility'

/** The words under a row in "the rest of today": where, or who sees it, or that it repeats. */
function rowDetail(occurrence: Occurrence): string {
  const parts: string[] = []
  if (occurrence.event.kind === 'task') parts.push('To do')
  if (occurrence.event.location) parts.push(occurrence.event.location)
  parts.push(visibilityOf(occurrence.event) === 'private' ? 'Only you' : 'Shared with the household')
  if (occurrence.recurring) parts.push('Repeats')
  return parts.join(', ')
}

function clock(iso: string): string {
  return new Date(iso).toLocaleTimeString(undefined, { hour: 'numeric', minute: '2-digit' })
}

export function NextUpCard({ occurrence, now }: { occurrence: Occurrence; now: Date }) {
  const sheet = useEventSheet()
  const { label } = splitCourse(occurrence.event.title)
  const span = occurrence.endsAt
    ? `${clock(occurrence.startsAt)} to ${clock(occurrence.endsAt)}`
    : clock(occurrence.startsAt)
  return (
    <article
      aria-label={`Next: ${label}`}
      className="flex flex-col gap-1.5 rounded-sheet bg-primary-container px-5 py-5 text-primary-container-foreground md:px-6"
    >
      <p className="flex items-center gap-2 text-[0.875rem] font-semibold">
        <Clock aria-hidden="true" className="size-4" />
        {span}, {untilWords(occurrence.startsAt, now)}
      </p>
      <h3 className="text-[1.625rem] leading-tight font-medium tracking-tight">{label}</h3>
      <p className="text-[0.875rem] opacity-90">{rowDetail(occurrence)}</p>
      <div className="mt-2.5 flex flex-wrap gap-2">
        <Button asChild variant="outline" className="rounded-full border-0 bg-background text-foreground hover:bg-surface-2">
          <Link to="/calendar">Open in Calendar</Link>
        </Button>
        {sheet && (
          <Button
            variant="outline"
            className="rounded-full border-0 bg-background text-foreground hover:bg-surface-2"
            onClick={() => sheet.open({ kind: 'edit', occurrence })}
          >
            Details
          </Button>
        )}
      </div>
    </article>
  )
}

function LaterRow({ occurrence, when }: { occurrence: Occurrence; when: string }) {
  const { label } = splitCourse(occurrence.event.title)
  return (
    <li className="flex min-h-11 items-baseline gap-3 rounded-xl px-2.5 py-2">
      <span className="w-[4.5rem] shrink-0 text-[0.8125rem] text-muted-foreground tabular-nums">{when}</span>
      <span className="min-w-0">
        <span className="block text-[0.9375rem] font-medium">{label}</span>
        <span className="block text-[0.75rem] text-muted-foreground">{rowDetail(occurrence)}</span>
      </span>
    </li>
  )
}

/** The desk Home's way to add an event: the same sheet Calendar opens. */
function NewEvent() {
  const sheet = useEventSheet()
  if (sheet === null) return null
  return (
    <Button size="sm" variant="secondary" className="rounded-full" onClick={() => sheet.open({ kind: 'create' })}>
      <Plus aria-hidden="true" />
      New event
    </Button>
  )
}

/**
 * Section 1: the next thing on the calendar as a big card, and the rest of
 * today beside it, then tomorrow's first thing. Three states in words: could
 * not read the calendar, nothing left today, or the rows.
 */
export function NextUpSection({
  today,
  tomorrow,
  now,
}: {
  /** Today's occurrences, or null when the calendar could not be read. */
  today: Occurrence[] | null
  tomorrow: Occurrence[]
  now: Date
}) {
  if (today === null) {
    return (
      <BriefingSection n={1} id="briefing-next" title="Next up">
        <div className="rounded-sheet bg-card px-5 py-4">
          <Quiet>Could not reach the engine, so what is on today is not known.</Quiet>
        </div>
      </BriefingSection>
    )
  }
  const { next, rest } = pickNextUp(today, now)
  const first = tomorrow[0]
  return (
    <BriefingSection n={1} id="briefing-next" title="Next up" aside={<NewEvent />}>
      <div className="grid gap-3 md:grid-cols-[1.25fr_1fr]">
        {next ? (
          <NextUpCard occurrence={next} now={now} />
        ) : (
          <div className="rounded-sheet bg-card px-5 py-5">
            <Quiet>{today.length === 0 ? 'Nothing on the calendar today.' : 'Nothing else on the calendar today.'}</Quiet>
          </div>
        )}
        <div className="rounded-sheet bg-card px-2.5 py-3">
          {rest.length === 0 && !first ? (
            <Quiet className="px-2.5 py-2">Nothing else today or tomorrow.</Quiet>
          ) : (
            <ul aria-label="Later today and tomorrow">
              {rest.map((occurrence) => (
                <LaterRow
                  key={`${occurrence.event.id}:${occurrence.date}`}
                  occurrence={occurrence}
                  when={timeLabel(occurrence)}
                />
              ))}
              {first && <LaterRow occurrence={first} when="Tomorrow" />}
            </ul>
          )}
        </div>
      </div>
    </BriefingSection>
  )
}
