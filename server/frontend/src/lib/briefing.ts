import type { LedgerTransaction, Statement } from '@/api/ledger'
import type { MaintenanceSchedule, ServiceHistory, Vehicle } from '@/api/aspects'
import type { components } from '@/api/schema'
import { isUnverified } from '@/lib/ledger'
import { formatMoney, localDay, parseDay, plural } from '@/lib/figures'

/**
 * The pure rules behind Home's Morning briefing (recruiter-demo ticket 14).
 * No fetching and no React: every sentence the briefing builds from data is
 * built here, so a test can hand it facts and read back exactly what it says.
 *
 * The rule every function obeys is CLAUDE.md section 1: **a sentence states
 * its facts or forbids its subject.** Nothing here makes up a judgement the
 * data does not carry ("none of them urgent"), and a read that failed is said
 * as "not known", never as an empty day.
 */

// ---- The day sentence -------------------------------------------------------

/** "Monday 2 November": the day, as the greeting says it. */
export function dayWords(now: Date): string {
  const weekday = now.toLocaleDateString('en-US', { weekday: 'long' })
  const month = now.toLocaleDateString('en-US', { month: 'long' })
  return `${weekday} ${now.getDate()} ${month}`
}

const SMALL = ['No', 'One', 'Two', 'Three', 'Four', 'Five', 'Six', 'Seven', 'Eight', 'Nine', 'Ten']

/** "Four things", "12 things": a count at the head of a sentence. */
export function countWords(count: number, one: string, many = `${one}s`): string {
  const word = count < SMALL.length ? SMALL[count] : String(count)
  return `${word} ${count === 1 ? one : many}`
}

export interface DayFacts {
  now: Date
  /** What is on the calendar today (plans, not tasks), or null when the calendar could not be read. */
  calendar: { count: number; firstTime: string | null } | null
  /**
   * How many things want a look, and whether every source behind that count
   * was read. A partial count is said as "at least"; an unknown one is not said.
   */
  worth: { count: number; complete: boolean } | null
}

/**
 * The one plain sentence under the greeting. Counts and times only, each taken
 * from `facts`; no titles, no adjectives about urgency, no talk of absence.
 */
export function daySentence({ now, calendar, worth }: DayFacts): string {
  const parts = [`It is ${dayWords(now)}.`]
  if (calendar === null) {
    parts.push('Could not reach the engine, so what is on the calendar today is not known.')
  } else if (calendar.count === 0) {
    parts.push('Nothing on the calendar today.')
  } else if (calendar.count === 1) {
    parts.push(
      calendar.firstTime === null
        ? 'One thing on the calendar today, all day.'
        : `One thing on the calendar today, at ${calendar.firstTime}.`,
    )
  } else {
    parts.push(
      calendar.firstTime === null
        ? `${countWords(calendar.count, 'thing')} on the calendar today, all day.`
        : `${countWords(calendar.count, 'thing')} on the calendar today, the first at ${calendar.firstTime}.`,
    )
  }
  if (worth !== null) {
    if (worth.count === 0 && worth.complete) parts.push('Nothing wants a look.')
    else if (worth.count > 0) {
      const head = worth.complete ? countWords(worth.count, 'thing') : `At least ${plural(worth.count, 'thing')}`
      parts.push(`${head} ${worth.count === 1 ? 'wants' : 'want'} a look.`)
    }
  }
  return parts.join(' ')
}

// ---- Car service due ----------------------------------------------------------

/** Within this many days, a date-based service is listed (`brief/compose.py`). */
export const SERVICE_DAYS = 30
/** Within this many miles of the last reading, a mileage-based service is listed. */
export const SERVICE_MILES = 500

export interface Odometer {
  miles: number
  /** `YYYY-MM-DD` the reading was taken. */
  readOn: string
  /** Where it came from, in words. */
  source: 'odometer reading' | 'service record'
}

export interface ServiceDue {
  vehicle: string
  service: string
  overdue: boolean
  /** True when the interval is one LEGION seeded and nobody confirmed: a guess. */
  estimate: boolean
  /** A date-based due day (`YYYY-MM-DD`), or null for a mileage one. */
  dueOn: string | null
  /** A mileage-based due point, or null for a date one. */
  dueAtMiles: number | null
  odometer: Odometer | null
  lastDone: string | null
}

export interface ServiceCheck {
  due: ServiceDue[]
  /** Vehicles with a mileage interval and no odometer reading to check it against. */
  uncheckable: string[]
  /** Whether there is any schedule at all to check. */
  hasSchedules: boolean
}

function addMonths(day: string, months: number): string {
  const date = parseDay(day)
  const target = new Date(date.getFullYear(), date.getMonth() + months, 1)
  const last = new Date(target.getFullYear(), target.getMonth() + 1, 0).getDate()
  target.setDate(Math.min(date.getDate(), last))
  return localDay(target)
}

function addDays(day: string, days: number): string {
  const date = parseDay(day)
  date.setDate(date.getDate() + days)
  return localDay(date)
}

export function vehicleName(vehicle: Pick<Vehicle, 'name' | 'year' | 'make' | 'model'>): string {
  return vehicle.name.trim() || `${vehicle.year} ${vehicle.make} ${vehicle.model}`
}

/** The highest mileage on file for a vehicle, with the day it was read. A fact on file, never projected. */
export function latestOdometer(vehicle: Vehicle, history: readonly ServiceHistory[]): Odometer | null {
  const readings: Odometer[] = []
  if (vehicle.odometer_baseline != null && vehicle.odometer_baseline_at) {
    readings.push({
      miles: vehicle.odometer_baseline,
      readOn: localDay(vehicle.odometer_baseline_at),
      source: 'odometer reading',
    })
  }
  for (const row of history) {
    if (row.vehicle_id !== vehicle.id || row.deleted_at !== null || row.mileage == null) continue
    readings.push({
      miles: row.mileage,
      readOn: row.service_date ?? localDay(row.created_at),
      source: 'service record',
    })
  }
  return readings.reduce<Odometer | null>(
    (best, reading) =>
      best === null || reading.miles > best.miles || (reading.miles === best.miles && reading.readOn > best.readOn)
        ? reading
        : best,
    null,
  )
}

/**
 * Services coming due, ported from `brief/compose.py`'s `_maintenance_lines`:
 * date-based within `SERVICE_DAYS`, mileage-based within `SERVICE_MILES` of the
 * last reading on file. A service never logged as done has no due point and is
 * not listed (compose.py names those separately; Fleet shows them).
 */
export function servicesDue(
  vehicles: readonly Vehicle[],
  schedules: readonly MaintenanceSchedule[],
  history: readonly ServiceHistory[],
  today: string,
): ServiceCheck {
  const due: ServiceDue[] = []
  const uncheckable = new Set<string>()
  const horizon = addDays(today, SERVICE_DAYS)
  const live = vehicles.filter((vehicle) => vehicle.deleted_at === null && !vehicle.archived)
  let hasSchedules = false

  for (const vehicle of live) {
    const name = vehicleName(vehicle)
    const own = schedules.filter((row) => row.vehicle_id === vehicle.id && row.deleted_at === null)
    if (own.length > 0) hasSchedules = true
    const odometer = latestOdometer(vehicle, history)
    for (const schedule of own) {
      const done = history.filter(
        (row) =>
          row.vehicle_id === vehicle.id &&
          row.deleted_at === null &&
          row.service_name.trim().toLowerCase() === schedule.service_name.trim().toLowerCase(),
      )
      if (done.length === 0) continue
      const estimate = schedule.interval_source === 'SEEDED'
      const dated = done.map((row) => row.service_date).filter((day): day is string => !!day)
      const lastDone = dated.length > 0 ? dated.sort().at(-1)! : null
      if (schedule.interval_months && lastDone) {
        const when = addMonths(lastDone, schedule.interval_months)
        if (when <= horizon) {
          due.push({
            vehicle: name,
            service: schedule.service_name,
            overdue: when < today,
            estimate,
            dueOn: when,
            dueAtMiles: null,
            odometer,
            lastDone,
          })
          continue
        }
      }
      if (schedule.interval_miles) {
        const miles = done.map((row) => row.mileage).filter((value): value is number => value != null)
        if (miles.length === 0) continue
        const at = Math.max(...miles) + schedule.interval_miles
        if (odometer === null) {
          uncheckable.add(name)
          continue
        }
        if (at - odometer.miles <= SERVICE_MILES) {
          due.push({
            vehicle: name,
            service: schedule.service_name,
            overdue: odometer.miles >= at,
            estimate,
            dueOn: null,
            dueAtMiles: at,
            odometer,
            lastDone,
          })
        }
      }
    }
  }
  due.sort((a, b) => Number(b.overdue) - Number(a.overdue) || a.service.localeCompare(b.service))
  return { due, uncheckable: [...uncheckable], hasSchedules }
}

// ---- Money --------------------------------------------------------------------

export interface DayTotal {
  /** Day of the month, 1-based. */
  day: number
  /** `YYYY-MM-DD`. */
  date: string
  /** Running total of categorised spending outflows through this day, positive cents. */
  cents: number
  /** True when any row counted so far is unverified. */
  unverified: boolean
}

/**
 * The month's running total by day, worked out on this page from the card rows:
 * outflows with a category, outside the categories the household marks as not
 * spending, in one currency. It is a picture of the month's shape, NOT the
 * engine's spend figure (which also pairs transfers and moves late Housing
 * charges); the page prints the engine's figure as the headline and says so.
 */
export function runningTotal(
  txns: readonly LedgerTransaction[],
  month: string,
  currency: string,
  notSpending: readonly string[],
  today: string,
): DayTotal[] {
  const skip = new Set(notSpending)
  const daily = new Map<number, { cents: number; unverified: boolean }>()
  for (const row of txns) {
    if (row.currency !== currency || row.txn_date.slice(0, 7) !== month) continue
    if (row.amount_cents >= 0 || row.category === null || skip.has(row.category)) continue
    const day = Number(row.txn_date.slice(8, 10))
    const entry = daily.get(day) ?? { cents: 0, unverified: false }
    entry.cents += -row.amount_cents
    entry.unverified ||= isUnverified(row)
    daily.set(day, entry)
  }
  const [year, monthNumber] = month.split('-').map(Number)
  const daysInMonth = new Date(year, monthNumber, 0).getDate()
  const last = today.slice(0, 7) === month ? Number(today.slice(8, 10)) : today.slice(0, 7) > month ? daysInMonth : 0
  const points: DayTotal[] = []
  let cents = 0
  let unverified = false
  for (let day = 1; day <= last; day += 1) {
    const entry = daily.get(day)
    if (entry) {
      cents += entry.cents
      unverified ||= entry.unverified
    }
    points.push({ day, date: `${month}-${String(day).padStart(2, '0')}`, cents, unverified })
  }
  return points
}

/** Days left in the month after today, today excluded. */
export function daysLeftInMonth(today: string): number {
  const date = parseDay(today)
  const last = new Date(date.getFullYear(), date.getMonth() + 1, 0).getDate()
  return last - date.getDate()
}

export type TrustWord = 'Verified' | 'Unverified' | 'Estimate'

export interface Balance {
  account: string
  label: string
  cents: number
  currency: string
  word: TrustWord
  /** Where the figure came from and how old it is, in words. */
  detail: string
}

const SHORT = new Intl.DateTimeFormat('en-US', { month: 'short', day: 'numeric' })
const CLOCK = new Intl.DateTimeFormat('en-US', { hour: 'numeric', minute: '2-digit' })

/** One linked account as `GET /api/ingest/plaid` reports it, balance included. */
export type BankAccount = components['schemas']['BankAccount']

/** "as of 9:14 AM, Nov 3" in the browser's zone: the bank's figure is never "now". */
export function bankAsOf(iso: string): string {
  const at = new Date(iso)
  return `as of ${CLOCK.format(at)}, ${SHORT.format(at)}`
}

/**
 * The newest balance on file per account. A gated statement's closing balance is
 * Verified ("Statement to 12 Nov"); a newer ledger row that printed a balance is
 * used instead, Verified when a gate or the bank's own feed stands behind it and
 * Unverified when nothing checked it. The bank's own balance (Kevin, 2026-11-03:
 * "store them"; ADR 0057) wins when it is as new or newer: fact, with its as-of
 * time. A credit account's balance is what is owed, shown as money out. An
 * account with none of these has no balance and is not listed: no balance is
 * never $0.
 */
export function latestBalances(
  statements: readonly Statement[],
  txns: readonly LedgerTransaction[],
  bank: readonly BankAccount[] = [],
): Balance[] {
  const accounts = new Map<string, Balance & { asOf: string }>()
  for (const statement of statements) {
    const seen = accounts.get(statement.account_last4)
    if (seen && seen.asOf >= statement.period_end) continue
    accounts.set(statement.account_last4, {
      account: statement.account_last4,
      label: statement.account_nickname.trim() || `Account ending ${statement.account_last4}`,
      cents: statement.closing_balance_cents,
      currency: statement.currency,
      word: 'Verified',
      detail: `Statement to ${SHORT.format(parseDay(statement.period_end))}`,
      asOf: statement.period_end,
    })
  }
  const newest = new Map<string, LedgerTransaction>()
  for (const row of txns) {
    if (row.balance_cents === null) continue
    const seen = newest.get(row.account_last4)
    if (!seen || row.txn_date > seen.txn_date || (row.txn_date === seen.txn_date && row.created_at > seen.created_at)) {
      newest.set(row.account_last4, row)
    }
  }
  for (const [account, row] of newest) {
    const seen = accounts.get(account)
    if (seen && seen.asOf >= row.txn_date) continue
    const unverified = isUnverified(row)
    const from =
      row.provenance === 'BANK_API' ? 'From the bank link' : unverified ? 'From a bank export no statement has checked' : 'From a statement'
    accounts.set(account, {
      account,
      label: seen?.label ?? (row.account_nickname.trim() || `Account ending ${account}`),
      cents: row.balance_cents!,
      currency: row.currency,
      word: unverified ? 'Unverified' : 'Verified',
      detail: `${from}, ${SHORT.format(parseDay(row.txn_date))}`,
      asOf: row.txn_date,
    })
  }
  for (const account of bank) {
    if (account.balance_current_cents === null || !account.balance_currency) continue
    if (!account.mask || !/^\d{4}$/.test(account.mask)) continue
    const asOf = account.balance_as_of ? localDay(new Date(account.balance_as_of)) : null
    const seen = accounts.get(account.mask)
    if (seen && asOf !== null && seen.asOf > asOf) continue
    const credit = account.type === 'credit'
    const when = account.balance_as_of ? bankAsOf(account.balance_as_of) : 'with no time given'
    accounts.set(account.mask, {
      account: account.mask,
      label: seen?.label ?? (account.name?.trim() || `Account ending ${account.mask}`),
      cents: credit ? -account.balance_current_cents : account.balance_current_cents,
      currency: account.balance_currency,
      word: 'Verified',
      detail: `${credit ? 'Owed, from the bank' : 'From the bank'} ${when}`,
      asOf: asOf ?? '',
    })
  }
  return [...accounts.values()]
    .sort((a, b) => a.label.localeCompare(b.label))
    .map(({ asOf: _asOf, ...balance }) => balance)
}

/** Transactions that need a category, with their total and whether any is unverified. */
export function needCategory(txns: readonly LedgerTransaction[]): {
  count: number
  cents: number
  currency: string
  unverified: boolean
  names: string[]
} {
  const rows = txns.filter((row) => row.category === null)
  const names = [...new Set(rows.map((row) => (row.display_description ?? row.description).trim()))]
  return {
    count: rows.length,
    cents: rows.reduce((sum, row) => sum + Math.abs(row.amount_cents), 0),
    currency: rows[0]?.currency ?? 'USD',
    unverified: rows.some(isUnverified),
    names,
  }
}

// ---- Closing line --------------------------------------------------------------

/**
 * The last line of the briefing: "That is everything for this morning", then the
 * one fact that bounds it, the next day anything is due. Never a nudge to come back.
 */
export function closingLine(
  now: Date,
  nextDueDay: Date | null,
  calendarKnown: boolean,
): string {
  const hour = now.getHours()
  const part = hour < 12 ? 'this morning' : hour < 18 ? 'this afternoon' : 'this evening'
  const head = `That is everything for ${part}.`
  if (!calendarKnown) return head
  if (nextDueDay === null) return `${head} Nothing else is due in the next two weeks.`
  const weekday = nextDueDay.toLocaleDateString('en-US', { weekday: 'long' })
  return `${head} Nothing else is due before ${weekday}.`
}

/** Money figure helper so sections share one format. */
export function money(cents: number, currency = 'USD'): string {
  return formatMoney(cents, currency)
}

// ---- Next up --------------------------------------------------------------------

/** The smallest shape `pickNextUp` reads, so a test needs no whole Occurrence. */
export interface Timed {
  startsAt: string
  endsAt: string | null
  event: { all_day?: boolean; done?: boolean; kind?: string }
}

/**
 * The next thing on today's calendar, and the rest of today. The next thing is
 * the first timed, not-done occurrence that has not ended; all-day rows and
 * whatever is later stay in the list beside it. Past rows are left out: the
 * briefing is about what is still ahead.
 */
export function pickNextUp<T extends Timed>(today: readonly T[], now: Date): { next: T | null; rest: T[] } {
  const at = now.getTime()
  const ahead = today.filter((occurrence) => {
    if (occurrence.event.kind === 'task' && occurrence.event.done) return false
    if (occurrence.event.all_day) return true
    const end = new Date(occurrence.endsAt ?? occurrence.startsAt).getTime()
    return end >= at
  })
  const next = ahead.find((occurrence) => !occurrence.event.all_day) ?? null
  return { next, rest: ahead.filter((occurrence) => occurrence !== next) }
}

/** "in 42 minutes", "in 2 h 5 min", "now": how far off a start is, from the clock. */
export function untilWords(startsAt: string, now: Date): string {
  const minutes = Math.round((new Date(startsAt).getTime() - now.getTime()) / 60_000)
  if (minutes <= 0) return 'now'
  if (minutes < 60) return `in ${plural(minutes, 'minute')}`
  const hours = Math.floor(minutes / 60)
  const rest = minutes % 60
  return rest === 0 ? `in ${plural(hours, 'hour')}` : `in ${hours} h ${rest} min`
}
