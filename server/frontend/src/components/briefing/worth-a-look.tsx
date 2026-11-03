import { Link } from '@tanstack/react-router'
import { Car, CreditCard, FileWarning, ListChecks, Link2, SquareCheckBig } from 'lucide-react'
import type { ReactNode } from 'react'

import { maintenanceSchedules, serviceHistory, vehicles } from '@/api/aspects'
import { useBankStatus } from '@/api/bank'
import { ingestedFiles, useLedgerTransactions } from '@/api/ledger'
import { useRows } from '@/api/synced'
import { BriefingSection, Quiet, RowIcon, StatusLabel, TrustChip, type Status } from '@/components/briefing/parts'
import { Button } from '@/components/ui/button'
import { money, needCategory, servicesDue, type ServiceDue } from '@/lib/briefing'
import { dateForEpochDay } from '@/lib/day'
import { formatNumber, formatShortDay, todayDay } from '@/lib/figures'
import { splitCourse } from '@/lib/horizon'
import type { Occurrence } from '@/lib/recurrence'
import type { DueItem } from '@/lib/today'

/** One row of "Worth a look": what kind of thing, what it is, where it came from, what to do. */
export interface WorthItem {
  key: string
  status: Status
  icon: typeof Car
  title: ReactNode
  detail: ReactNode
  action: { label: string; to: string; search?: Record<string, unknown> }
}

export interface WorthALook {
  items: WorthItem[]
  /** Still reading at least one source. */
  pending: boolean
  /** Every source was read, so the count is the whole count. */
  complete: boolean
  /** A sentence per source that could not be read: unreadable is not empty. */
  unreadable: string[]
}

/** Overdue rows shown before "and N more". */
const OVERDUE_SHOWN = 3

function serviceTitle(due: ServiceDue): string {
  if (due.dueOn !== null) {
    return due.overdue
      ? `${due.service} for the ${due.vehicle}, overdue since ${formatShortDay(due.dueOn)}`
      : `${due.service} for the ${due.vehicle}, due ${formatShortDay(due.dueOn)}`
  }
  const left = due.dueAtMiles! - due.odometer!.miles
  return due.overdue
    ? `${due.service} for the ${due.vehicle}, ${formatNumber(-left, 0)} miles past due`
    : `${due.service} for the ${due.vehicle} in about ${formatNumber(left, 0)} miles`
}

function serviceDetail(due: ServiceDue): ReactNode {
  return (
    <>
      {due.estimate && <TrustChip word="Estimate" />}
      {due.odometer ? (
        <span>
          {formatNumber(due.odometer.miles, 0)} mi on the clock, from the {due.odometer.source} of{' '}
          {formatShortDay(due.odometer.readOn)}
        </span>
      ) : (
        <span>From Fleet's service records</span>
      )}
      {due.estimate && <span>The interval is a guess nobody has confirmed.</span>}
    </>
  )
}

/**
 * Everything "Worth a look" reads, as one hook, because the day sentence under
 * the greeting counts the same rows the section lists (CLAUDE.md section 1: a
 * sentence states its facts). Sources a demo visit may not use (the bank link)
 * are not even asked for.
 */
export function useWorthALook({
  recruiter,
  overdue,
  dueTasks,
  dueItems,
}: {
  recruiter: boolean
  /** Null when the calendar could not be read. */
  overdue: Occurrence[] | null
  dueTasks: Occurrence[] | null
  dueItems: DueItem[] | null
}): WorthALook {
  const cars = useRows(vehicles)
  const schedules = useRows(maintenanceSchedules)
  const history = useRows(serviceHistory)
  const { query: txns } = useLedgerTransactions()
  const bank = useBankStatus(!recruiter)
  // A demo visit has no ingestion ledger (ticket 13): not asked for.
  const files = useRows(ingestedFiles, !recruiter)

  const items: WorthItem[] = []
  const unreadable: string[] = []
  let pending = false

  // The car.
  if (cars.isPending || schedules.isPending || history.isPending) pending = true
  else if (!cars.data || !schedules.data || !history.data) {
    unreadable.push("Could not read the car's records, so whether a service is coming due is not known.")
  } else {
    const check = servicesDue(cars.data, schedules.data, history.data, todayDay())
    for (const due of check.due) {
      items.push({
        key: `service:${due.vehicle}:${due.service}`,
        status: due.overdue ? 'Overdue' : 'Due soon',
        icon: Car,
        title: serviceTitle(due),
        detail: serviceDetail(due),
        action: { label: 'Open Fleet', to: '/fleet' },
      })
    }
    if (check.uncheckable.length > 0) {
      unreadable.push(
        `No odometer reading on file for the ${check.uncheckable.join(' or the ')}, so its mileage services cannot be checked.`,
      )
    }
  }

  // The calendar: things that slipped, then today's.
  if (overdue === null || dueTasks === null || dueItems === null) {
    unreadable.push('Could not reach the engine, so what is overdue or due today is not known.')
  } else {
    for (const occurrence of overdue.slice(0, OVERDUE_SHOWN)) {
      const day = dateForEpochDay(occurrence.day).toLocaleDateString(undefined, { weekday: 'long', month: 'short', day: 'numeric' })
      items.push({
        key: `overdue:${occurrence.event.id}:${occurrence.date}`,
        status: 'Overdue',
        icon: SquareCheckBig,
        title: splitCourse(occurrence.event.title).label,
        detail: <span>Was due {day}. From the calendar.</span>,
        action: { label: 'Open Calendar', to: '/calendar' },
      })
    }
    if (overdue.length > OVERDUE_SHOWN) {
      const more = overdue.length - OVERDUE_SHOWN
      items.push({
        key: 'overdue:more',
        status: 'Overdue',
        icon: SquareCheckBig,
        title: `${more} more ${more === 1 ? 'task is' : 'tasks are'} past ${more === 1 ? 'its' : 'their'} date`,
        detail: <span>From the calendar, the last two weeks.</span>,
        action: { label: 'Open Calendar', to: '/calendar' },
      })
    }
    for (const occurrence of dueTasks.filter((row) => !row.event.done)) {
      items.push({
        key: `today:${occurrence.event.id}`,
        status: 'Due today',
        icon: SquareCheckBig,
        title: splitCourse(occurrence.event.title).label,
        detail: <span>{occurrence.event.all_day ? 'Due today' : `Due ${new Date(occurrence.startsAt).toLocaleTimeString(undefined, { hour: 'numeric', minute: '2-digit' })}`}. From the calendar.</span>,
        action: { label: 'Open Calendar', to: '/calendar' },
      })
    }
    const open = dueItems.filter((due) => !due.tickedToday)
    const byList = new Map<string, DueItem[]>()
    for (const due of open) byList.set(due.checklist.name, [...(byList.get(due.checklist.name) ?? []), due])
    for (const [list, rows] of byList) {
      items.push({
        key: `list:${list}`,
        status: 'Due today',
        icon: ListChecks,
        title: `${rows.length} ${rows.length === 1 ? 'thing' : 'things'} left on ${list} today`,
        detail: <span>{rows.slice(0, 3).map((due) => due.item.text).join(', ')}{rows.length > 3 ? ', and more' : ''}.</span>,
        action: { label: 'Open Lists', to: '/lists' },
      })
    }
  }

  // Money: charges nobody has categorised.
  if (txns.isPending) pending = true
  else if (txns.data === undefined) {
    unreadable.push('Could not reach the engine, so how many charges need a category is not known.')
  } else {
    const need = needCategory(txns.data)
    if (need.count > 0) {
      items.push({
        key: 'money:category',
        status: 'To confirm',
        icon: CreditCard,
        title: `${need.count} ${need.count === 1 ? 'charge' : 'charges'}, ${money(need.cents, need.currency)}, ${need.count === 1 ? 'needs' : 'need'} a category`,
        detail: (
          <>
            {need.unverified && <TrustChip word="Unverified" />}
            <span>
              {need.names.slice(0, 3).join(', ')}
              {need.names.length > 3 ? `, and ${need.names.length - 3} more` : ''}
            </span>
          </>
        ),
        action: { label: 'Review', to: '/money', search: { need: true } },
      })
    }
  }

  // Bank files the gate held back: members only.
  if (!recruiter) {
    if (files.isPending) pending = true
    else if (files.data === undefined) {
      unreadable.push('Could not reach the engine, so whether any bank file is held for review is not known.')
    } else {
      const held = files.data.filter((file) => file.state === 'QUARANTINED')
      if (held.length > 0) {
        items.push({
          key: 'files:held',
          status: 'To confirm',
          icon: FileWarning,
          title: `${held.length} bank ${held.length === 1 ? 'file' : 'files'} held for review`,
          detail: (
            <span>
              {held.map((file) => file.display_name ?? 'Unnamed file').join(', ')}. Nothing from{' '}
              {held.length === 1 ? 'it was' : 'them was'} added.
            </span>
          ),
          action: { label: 'See why', to: '/money', search: { tab: 'files' } },
        })
      }
    }
  }

  // The bank link: members only (ticket 13, ruling 1).
  if (!recruiter) {
    if (bank.isPending) pending = true
    else if (bank.data === undefined) {
      unreadable.push('Could not reach the engine, so whether the bank link is working is not known.')
    } else if (bank.data.connected && bank.data.needs_sign_in) {
      items.push({
        key: 'bank',
        status: 'Paused',
        icon: Link2,
        title: `The bank link${bank.data.institution_name ? ` to ${bank.data.institution_name}` : ''} needs signing in again`,
        detail: <span>{bank.data.sentence}</span>,
        action: { label: 'Reconnect', to: '/settings/bank' },
      })
    }
  }

  return { items, pending, complete: !pending && unreadable.length === 0, unreadable }
}

function WorthRow({ item }: { item: WorthItem }) {
  const Icon = item.icon
  return (
    <li className="flex flex-wrap items-center gap-x-3.5 gap-y-2 px-3.5 py-3 not-first:border-t not-first:border-outline-variant md:flex-nowrap">
      <span className="basis-full md:w-[7.5rem] md:shrink-0 md:basis-auto">
        <StatusLabel status={item.status} />
      </span>
      <RowIcon icon={Icon} />
      <div className="min-w-0 flex-1">
        <p className="text-[0.9375rem] font-medium">{item.title}</p>
        <div className="mt-0.5 flex flex-wrap items-center gap-x-2.5 gap-y-1 text-[0.8125rem] text-muted-foreground">
          {item.detail}
        </div>
      </div>
      <Button asChild variant="secondary" size="sm" className="shrink-0 rounded-full">
        <Link to={item.action.to as '/'} search={item.action.search as never}>
          {item.action.label}
        </Link>
      </Button>
    </li>
  )
}

/** Section 2: things that want a decision, each saying where it came from. */
export function WorthALookSection({ worth }: { worth: WorthALook }) {
  return (
    <BriefingSection n={2} id="briefing-worth" title="Worth a look">
      <div className="rounded-sheet bg-card py-1">
        {worth.items.length > 0 && (
          <ul aria-label="Worth a look">
            {worth.items.map((item) => (
              <WorthRow key={item.key} item={item} />
            ))}
          </ul>
        )}
        {worth.items.length === 0 && worth.complete && (
          <Quiet className="px-4 py-3">
            Nothing wants a look: no car service due in the next 30 days, nothing overdue or due today, and every
            charge has a category.
          </Quiet>
        )}
        {worth.pending && <Quiet className="px-4 py-3">Still reading the rest.</Quiet>}
        <Quiet className="px-4 py-2 text-[0.8125rem]">
          Bills are not listed: LEGION has no source of recurring bills yet. This is not a sign that none are due.
        </Quiet>
        {worth.unreadable.map((sentence) => (
          <Quiet key={sentence} className="px-4 py-2 text-[0.8125rem]">
            {sentence}
          </Quiet>
        ))}
      </div>
    </BriefingSection>
  )
}
