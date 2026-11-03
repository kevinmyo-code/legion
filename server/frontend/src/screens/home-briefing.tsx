import { useChanges, useHousehold, useMe } from '@/api/queries'
import { AroundHouseSection } from '@/components/briefing/around-house'
import { ListsSection } from '@/components/briefing/lists'
import { MoneySection } from '@/components/briefing/money-month'
import { NextUpSection } from '@/components/briefing/next-up'
import { WorthALookSection, useWorthALook } from '@/components/briefing/worth-a-look'
import { Freshness } from '@/components/freshness'
import { timeLabel } from '@/components/event-row'
import { closingLine, daySentence } from '@/lib/briefing'
import { todayEpochDay } from '@/lib/day'
import { greeting } from '@/lib/home-copy'
import { buildHorizon, overdueOccurrences } from '@/lib/horizon'
import { useIsRecruiter } from '@/lib/recruiter'
import { occurrencesOnDay } from '@/lib/recurrence'
import { plansOnly } from '@/lib/suggestion'
import { itemsDueOn } from '@/lib/today'

/** The legend for the three trust words, said once at the top in words. */
export const TRUST_LEGEND =
  "Figures say where they came from: Verified is from a statement, the bank's own feed or a sensor, Unverified is not checked yet, Estimate is worked out."

/**
 * Home on the desk: the Morning briefing (recruiter-demo ticket 14, Kevin chose
 * prototype A). One reading column, top to bottom in the order a morning asks:
 * ask, the day in one sentence, what is next, what wants a look, money, lists,
 * and the rest of the house, then a closing line that says it is finished.
 *
 * Each section answers for itself from its own read, so one failing read never
 * blanks the page, and each says "not known" in its own words rather than
 * rendering as empty (CLAUDE.md section 1). Nothing on it pulls someone back:
 * no streaks, no "you haven't", no counts of absence (section 7).
 *
 * A recruiter's demo visit gets the same page less the body and the bank link,
 * and is not greeted by a member's name (ticket 13).
 */
export function BriefingHome() {
  const changes = useChanges(true)
  const me = useMe()
  const household = useHousehold(true)
  const recruiter = useIsRecruiter()
  const now = new Date()
  const today = todayEpochDay()

  const data = changes.data
  const readable = data !== undefined
  const events = plansOnly(data?.events ?? [])
  const skips = data?.event_skips ?? []
  const checklists = data?.checklists ?? []
  const items = (data?.checklist_items ?? []).filter((row) => row.deleted_at === null)
  const ticks = (data?.checklist_ticks ?? []).filter((row) => row.deleted_at === null)

  const todays = readable ? occurrencesOnDay(today, events, skips) : null
  const plans = todays?.filter((occurrence) => occurrence.event.kind !== 'task') ?? null
  const dueTasks = todays?.filter((occurrence) => occurrence.event.kind === 'task') ?? null
  const tomorrow = readable
    ? occurrencesOnDay(today + 1, events, skips).filter((occurrence) => occurrence.event.kind !== 'task')
    : []
  const overdue = readable ? overdueOccurrences(today, events, skips) : null
  const dueItems = readable ? itemsDueOn(today, checklists, items, ticks) : null

  const worth = useWorthALook({ recruiter, overdue, dueTasks, dueItems })

  const myId = me.data?.signedIn ? me.data.me.user_id : undefined
  const myName = recruiter ? undefined : household.data?.members.find((member) => member.user_id === myId)?.name.trim()
  const firstTimed = plans?.find((occurrence) => !occurrence.event.all_day)
  const sentence = daySentence({
    now,
    calendar: changes.isPending ? { count: 0, firstTime: null } : plans === null ? null : { count: plans.length, firstTime: firstTimed ? timeLabel(firstTimed) : null },
    worth: worth.pending ? null : { count: worth.items.length, complete: worth.complete },
  })
  const nextDue = readable
    ? (buildHorizon(today, events, undefined, skips).find((cell) => cell.offset >= 1 && cell.tasks > cell.tasksDone)?.date ?? null)
    : null

  return (
    <div className="@container mx-auto flex w-full max-w-[54rem] flex-col gap-7 pb-16">
      <header>
        <h1 className="text-[1.875rem] leading-tight font-medium tracking-tight @2xl:text-[2.5rem]">
          {greeting(now)}
          {myName ? `, ${myName}` : ''}.
        </h1>
        {changes.isPending ? (
          <p className="mt-2.5 text-base text-muted-foreground @2xl:text-lg">Reading the day…</p>
        ) : (
          <p className="mt-2.5 max-w-[40rem] text-base leading-relaxed text-muted-foreground @2xl:text-lg">{sentence}</p>
        )}
        <div className="mt-2">
          {readable ? (
            <Freshness
              updatedAt={changes.dataUpdatedAt}
              isFetching={changes.isFetching}
              failureCount={changes.failureCount}
              error={changes.error}
            />
          ) : null}
        </div>
        <p className="mt-2 text-[0.8125rem] text-muted-foreground">{TRUST_LEGEND}</p>
      </header>

      {!changes.isPending && <NextUpSection today={plans} tomorrow={tomorrow} now={now} />}
      <WorthALookSection worth={worth} />
      <MoneySection />
      {!changes.isPending && <ListsSection checklists={readable ? checklists : null} items={items} ticks={ticks} />}
      <AroundHouseSection recruiter={recruiter} />

      <p className="pt-2 text-center text-[0.875rem] text-muted-foreground">{closingLine(now, nextDue, readable)}</p>
    </div>
  )
}
