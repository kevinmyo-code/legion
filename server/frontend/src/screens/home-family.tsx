import { Link } from '@tanstack/react-router'
import type { ReactNode } from 'react'

import { useHousehold, useChanges, useMe } from '@/api/queries'
import { DueItemRow } from '@/components/due-item-row'
import { EventRow, timeLabel } from '@/components/event-row'
import { PinnedListCard } from '@/components/family/pinned-list-card'
import { SpendCard } from '@/components/family/spend-card'
import { relativeTime } from '@/components/freshness'
import { NewEventButton } from '@/components/new-event-button'
import { BoughtPill } from '@/components/purchases/pill'
import { Skeleton } from '@/components/ui/skeleton'
import { dateForEpochDay, todayEpochDay } from '@/lib/day'
import { HOME_COPY, NOTHING_TO_SHOW_PINNED, greeting, type HomeSection } from '@/lib/home-copy'
import { overdueOccurrences } from '@/lib/horizon'
import { homeLists, usePins } from '@/lib/pins'
import { occurrencesOnDay, type Occurrence } from '@/lib/recurrence'
import { plansOnly } from '@/lib/suggestion'
import { itemsDueOn } from '@/lib/today'

/** Overdue rows shown before "and N more" (spec D6). */
export const OVERDUE_SHOWN = 3

/**
 * Mia's Home, below 1024 px (spec D6, ticket 10): what is on today, what has
 * slipped, what needs doing, the lists she pinned, and what the two accounts
 * have spent. In that order, because that is the order she asks the questions.
 *
 * Every section answers for itself. The calendar and the lists come from one
 * read (`/api/changes`) and the spend from another, so one can fail while the
 * other works: each section says so in its own sentence (`HOME_COPY`) instead of
 * the whole page turning into one error. Three situations, three sentences:
 * nothing there, could not reach the engine, and showing older data. A failed
 * read is never drawn as an empty day, and never as `$0.00`.
 *
 * No assistant name anywhere on it (CLAUDE.md section 1): the greeting is to a
 * person and the date.
 */

/** "Due 6:00 PM", or "Due today" for a task with no time of day. */
function dueLine(occurrence: Occurrence): string {
  return occurrence.event.all_day ? 'Due today' : `Due ${timeLabel(occurrence)}`
}

/** "Was due Thu, Oct 1, 6:00 PM": the DAY first, because an overdue row that only
 * said a time would not say how overdue it is. */
function wasDueLine(occurrence: Occurrence): string {
  const day = dateForEpochDay(occurrence.day).toLocaleDateString(undefined, {
    weekday: 'short',
    month: 'short',
    day: 'numeric',
  })
  return occurrence.event.all_day ? `Was due ${day}` : `Was due ${day}, ${timeLabel(occurrence)}`
}

type Readiness = 'pending' | 'unreachable' | 'ready'

function Section({
  id,
  title,
  aside,
  bare = false,
  children,
}: {
  id: string
  title: string
  aside?: string | null
  /** No card behind it: the rows sit on the page itself, under a small label
   * (the prototype's "Today"), because the events are already tonal containers. */
  bare?: boolean
  children: ReactNode
}) {
  if (bare) {
    return (
      <section aria-labelledby={id} className="flex flex-col gap-2">
        <h2 id={id} className="px-1 pt-1 text-[0.8125rem] font-medium text-muted-foreground">
          {title}
        </h2>
        {children}
      </section>
    )
  }
  return (
    <section aria-labelledby={id} className="rounded-sheet bg-card px-4 pt-3.5 pb-4">
      <div className="mb-2 flex items-baseline justify-between gap-3">
        <h2 id={id} className="text-base font-medium">
          {title}
        </h2>
        {aside && <span className="text-[0.8125rem] text-muted-foreground">{aside}</span>}
      </div>
      {children}
    </section>
  )
}

function Sentence({ children, card = false }: { children: ReactNode; card?: boolean }) {
  return (
    <p
      className={
        card
          ? 'rounded-card bg-card px-4 py-3 text-[0.9375rem] text-muted-foreground'
          : 'text-[0.9375rem] text-muted-foreground'
      }
    >
      {children}
    </p>
  )
}

function StaleNote({ section, updatedAt }: { section: HomeSection; updatedAt: number }) {
  return (
    <p className="mt-2 px-1 text-[0.8125rem] text-muted-foreground">
      {HOME_COPY[section].stale(relativeTime(updatedAt))}
    </p>
  )
}

/** A section still being read: a block of the right size and no heading, so
 * nothing on screen names a section whose content is not here yet. */
function SectionSkeleton({ label }: { label: string }) {
  return (
    <section aria-busy="true" aria-label={label} className="rounded-sheet bg-card px-4 pt-3.5 pb-4">
      <Skeleton className="mb-2 h-5 w-28" />
      <Skeleton className="h-11 w-full" />
    </section>
  )
}

export function FamilyHome() {
  const changes = useChanges(true)
  const me = useMe()
  const household = useHousehold(true)
  const pins = usePins()

  const readiness: Readiness = changes.isPending
    ? 'pending'
    : changes.data === undefined
      ? 'unreachable'
      : 'ready'
  const stale = changes.isError && changes.data !== undefined

  const myId = me.data?.signedIn ? me.data.me.user_id : undefined
  const myName = household.data?.members.find((member) => member.user_id === myId)?.name.trim()

  const header = (
    <>
      <header className="flex items-start justify-between gap-3 pt-1">
        <div className="min-w-0">
          <h1 className="text-[1.75rem] leading-tight tracking-tight">
            {greeting()}
            {myName ? `, ${myName}` : ''}
          </h1>
          <p className="text-[0.9375rem] text-muted-foreground">
            {new Date().toLocaleDateString(undefined, { weekday: 'long', day: 'numeric', month: 'long' })}
          </p>
        </div>
      </header>
      <BoughtPill />
    </>
  )

  if (readiness === 'pending') {
    return (
      <div className="flex flex-col gap-3 pb-16">
        {header}
        <SectionSkeleton label="Loading what is on today" />
        <SectionSkeleton label="Loading what is due today" />
        <SectionSkeleton label="Loading your lists" />
        <SpendCard />
        <NewEventButton />
      </div>
    )
  }

  if (readiness === 'unreachable') {
    return (
      <div className="flex flex-col gap-3 pb-16">
        {header}
        <Section id="home-on-today" title="On today" bare>
          <Sentence card>{HOME_COPY['on-today'].unreachable}</Sentence>
        </Section>
        <Section id="home-overdue" title="Still not done">
          <Sentence>{HOME_COPY.overdue.unreachable}</Sentence>
        </Section>
        <Section id="home-to-do" title="To do today">
          <Sentence>{HOME_COPY['to-do'].unreachable}</Sentence>
        </Section>
        <Section id="home-pinned" title="Lists">
          <Sentence>{HOME_COPY.pinned.unreachable}</Sentence>
        </Section>
        <SpendCard />
        <NewEventButton />
      </div>
    )
  }

  const data = changes.data!
  const today = todayEpochDay()
  // Suggestions are not the household's plans; Home never sees them.
  const events = plansOnly(data.events ?? [])
  const skips = data.event_skips ?? []
  const checklists = data.checklists ?? []
  const items = (data.checklist_items ?? []).filter((row) => row.deleted_at === null)
  const ticks = (data.checklist_ticks ?? []).filter((row) => row.deleted_at === null)

  const todays = occurrencesOnDay(today, events, skips)
  const onToday = todays.filter((occurrence) => occurrence.event.kind !== 'task')
  const dueTasks = todays.filter((occurrence) => occurrence.event.kind === 'task')
  const dueItems = itemsDueOn(today, checklists, items, ticks)
  const overdue = overdueOccurrences(today, events, skips)
  const overdueMore = overdue.length - OVERDUE_SHOWN

  const left =
    dueTasks.filter((occurrence) => !occurrence.event.done).length +
    dueItems.filter((due) => !due.tickedToday).length
  const nothingToDo = dueTasks.length === 0 && dueItems.length === 0

  const shown = homeLists(checklists, items, ticks, pins, today)

  return (
    <div className="flex flex-col gap-3 pb-16">
      {header}

      <Section id="home-on-today" title="On today" bare>
        {onToday.length === 0 ? (
          <Sentence card>{HOME_COPY['on-today'].empty}</Sentence>
        ) : (
          <ul className="flex flex-col gap-1.5">
            {onToday.map((occurrence) => (
              <EventRow key={`${occurrence.event.id}:${occurrence.date}`} occurrence={occurrence} />
            ))}
          </ul>
        )}
        {stale && <StaleNote section="on-today" updatedAt={changes.dataUpdatedAt} />}
      </Section>

      <Section
        id="home-overdue"
        title="Still not done"
        aside={overdue.length > 0 ? `${overdue.length} past their date` : null}
      >
        {overdue.length === 0 ? (
          <Sentence>{HOME_COPY.overdue.empty}</Sentence>
        ) : (
          <>
            <ul className="flex flex-col gap-1.5">
              {overdue.slice(0, OVERDUE_SHOWN).map((occurrence) => (
                <EventRow
                  key={`${occurrence.event.id}:${occurrence.date}`}
                  occurrence={occurrence}
                  detail={wasDueLine(occurrence)}
                />
              ))}
            </ul>
            {overdueMore > 0 && (
              <Link
                to="/calendar"
                className="mt-1 flex min-h-11 items-center px-1 text-[0.9375rem] font-medium text-primary underline-offset-4 hover:underline"
              >
                and {overdueMore} more
              </Link>
            )}
          </>
        )}
        {stale && <StaleNote section="overdue" updatedAt={changes.dataUpdatedAt} />}
      </Section>

      <Section id="home-to-do" title="To do today" aside={nothingToDo ? null : `${left} left`}>
        {nothingToDo ? (
          <Sentence>{HOME_COPY['to-do'].empty}</Sentence>
        ) : (
          <ul className="flex flex-col gap-1.5">
            {dueTasks.map((occurrence) => (
              <EventRow
                key={`${occurrence.event.id}:${occurrence.date}`}
                occurrence={occurrence}
                detail={dueLine(occurrence)}
              />
            ))}
            {dueItems.map((due) => (
              <DueItemRow key={due.item.id} due={due} />
            ))}
          </ul>
        )}
        {stale && <StaleNote section="to-do" updatedAt={changes.dataUpdatedAt} />}
      </Section>

      {shown.lists.length > 0 ? (
        <section aria-labelledby="home-pinned" className="flex flex-col gap-2">
          <h2 id="home-pinned" className="px-1 pt-1 text-[0.8125rem] font-medium text-muted-foreground">
            {shown.pinned ? 'Pinned lists' : 'Lists'}
          </h2>
          <ul className="flex flex-col gap-3">
            {shown.lists.map((list) => (
              <PinnedListCard key={list.id} checklist={list} items={items} ticks={ticks} />
            ))}
          </ul>
          {stale && <StaleNote section="pinned" updatedAt={changes.dataUpdatedAt} />}
        </section>
      ) : (
        <Section id="home-pinned" title="Lists">
          <Sentence>
            {checklists.some((list) => list.deleted_at === null && !list.archived)
              ? NOTHING_TO_SHOW_PINNED
              : HOME_COPY.pinned.empty}
          </Sentence>
          {stale && <StaleNote section="pinned" updatedAt={changes.dataUpdatedAt} />}
        </Section>
      )}

      <SpendCard />
      <NewEventButton />
    </div>
  )
}
