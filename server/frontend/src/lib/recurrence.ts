import type { Event, EventSkip } from '@/api/types'

/**
 * Expanding a repeating event into its occurrences (spec D4, web-revamp 08).
 *
 * **One algorithm, one set of test vectors, three readers.** The phone's
 * `notes/Recurrence.kt` is the reference; `server/api/recurrence.py` is its
 * Python port; this file is the web's. All three are held to
 * `server/tests/fixtures/recurrence_vectors.json` (`recurrence.test.ts` reads
 * the very file pytest does), so a rule one of them gets wrong fails there.
 *
 * What the reference does, kept exactly:
 *
 * - **Every date is computed in a caller-supplied zone.** Never the runtime's
 *   own by accident: a Monday 00:30 Tokyo series lands on Tuesdays when its
 *   days are read in UTC, which is the bug the phone shipped once.
 * - **Time of day is local wall-clock time**, recomposed on each date, so a
 *   7 am series stays 7 am across a DST change. A wall-clock time that does
 *   not exist (spring forward) resolves forward by the gap, the way Java's
 *   `atZone` does; one that happens twice (fall back) takes the first.
 * - **Skips are subtracted during expansion and still count** toward an
 *   `AFTER_COUNT` end: skipping one of five does not buy a sixth.
 * - **Clamp, never roll over**: monthly on the 31st fires on the 30th in a
 *   30-day month; yearly on 29 February fires on the 28th in a common year.
 * - **A rule that cannot advance yields nothing**, never a hang and never an
 *   error.
 * - The window is inclusive at both ends, in instants; `ON_DATE` is inclusive,
 *   as a local date.
 *
 * Below the port, `occurrencesBetween` is what the screens call: it expands
 * every live event of the household over a run of viewer-local days, honours
 * skips, and carries the all-day rule (an all-day row's `starts_at` is UTC
 * midnight of the date it names, so it is expanded in UTC and never reread on
 * the viewer's clock - see `localDayOfAllDay`).
 */

const SAFETY_CAP = 100_000
const DAY_MS = 86_400_000

export type RepeatKind = 'DAILY' | 'WEEKLY' | 'MONTHLY_ON_DATE' | 'YEARLY'

export interface Rule {
  kind: RepeatKind
  every: number
  /** Monday = 0, ascending. */
  days: number[]
  day: number | null
  month: number | null
}

export interface End {
  kind: 'NEVER' | 'ON_DATE' | 'AFTER_COUNT'
  /** `YYYY-MM-DD`, inclusive. */
  onDate: string | null
  count: number | null
}

/** The stored `repeat_*` fields, as an expansion reads them. */
export interface SeriesFields {
  starts_at?: string | null
  repeat_kind?: string | null
  repeat_every?: number | null
  repeat_days_of_week?: string | null
  repeat_day?: number | null
  repeat_month?: number | null
  repeat_end_kind?: string | null
  repeat_end_date?: string | null
  repeat_end_count?: number | null
}

const WEEKDAYS = ['MONDAY', 'TUESDAY', 'WEDNESDAY', 'THURSDAY', 'FRIDAY', 'SATURDAY', 'SUNDAY']

/** "MON,WED" or "MONDAY,WEDNESDAY", any case. Empty for blank; `null` on any
 * token it does not recognise (`NotesLogic.parseWeekdays`). */
export function parseWeekdays(spec: string | null | undefined): number[] | null {
  if (spec === null || spec === undefined || spec.trim() === '') return []
  const days = new Set<number>()
  for (const token of spec.split(',').map((t) => t.trim().toUpperCase())) {
    if (token === '') continue
    let index = WEEKDAYS.findIndex((name) => name === token)
    if (index === -1) index = WEEKDAYS.findIndex((name) => name.slice(0, 3) === token)
    if (index === -1) return null
    days.add(index)
  }
  return [...days].sort((a, b) => a - b)
}

/** The stored fields as a rule, or `null` when the series does not repeat or a
 * required field is missing: a defensively-corrupt row reads as not recurring. */
export function ruleFromSeries(series: SeriesFields): Rule | null {
  const kind = series.repeat_kind
  const every = series.repeat_every ?? null
  const day = series.repeat_day ?? null
  const month = series.repeat_month ?? null
  if (kind === 'DAILY') {
    return every === null ? null : { kind, every, days: [], day: null, month: null }
  }
  if (kind === 'WEEKLY') {
    const days = parseWeekdays(series.repeat_days_of_week)
    if (every === null || days === null || days.length === 0) return null
    return { kind, every, days, day: null, month: null }
  }
  if (kind === 'MONTHLY_ON_DATE') {
    return every === null || day === null ? null : { kind, every, days: [], day, month: null }
  }
  if (kind === 'YEARLY') {
    return month === null || day === null ? null : { kind, every: 1, days: [], day, month }
  }
  return null
}

/** `NEVER` unless a complete end is stored. */
export function endFromSeries(series: SeriesFields): End {
  const never: End = { kind: 'NEVER', onDate: null, count: null }
  if (series.repeat_end_kind === 'ON_DATE') {
    return series.repeat_end_date ? { kind: 'ON_DATE', onDate: series.repeat_end_date, count: null } : never
  }
  if (series.repeat_end_kind === 'AFTER_COUNT') {
    const count = series.repeat_end_count ?? null
    return count === null ? never : { kind: 'AFTER_COUNT', onDate: null, count }
  }
  return never
}

function wellFormed(rule: Rule): boolean {
  switch (rule.kind) {
    case 'DAILY':
      return rule.every >= 1
    case 'WEEKLY':
      return rule.every >= 1 && rule.days.length > 0
    case 'MONTHLY_ON_DATE':
      return rule.every >= 1 && rule.day !== null && rule.day >= 1 && rule.day <= 31
    case 'YEARLY':
      return (
        rule.month !== null && rule.month >= 1 && rule.month <= 12 && rule.day !== null && rule.day >= 1 && rule.day <= 31
      )
  }
}

// ---------------------------------------------------------------------------
// Calendar dates as epoch days (days since 1970-01-01, by date components,
// never by dividing a millisecond timestamp). `LocalDate.toEpochDay()`.
// ---------------------------------------------------------------------------

export interface YMD {
  y: number
  m: number
  d: number
}

export function toEpochDay({ y, m, d }: YMD): number {
  // `Date.UTC` maps years 0-99 to 1900-1999; irrelevant for a household
  // calendar but kept honest by going through setUTCFullYear.
  const date = new Date(0)
  date.setUTCFullYear(y, m - 1, d)
  date.setUTCHours(0, 0, 0, 0)
  return Math.round(date.getTime() / DAY_MS)
}

export function fromEpochDay(day: number): YMD {
  const date = new Date(day * DAY_MS)
  return { y: date.getUTCFullYear(), m: date.getUTCMonth() + 1, d: date.getUTCDate() }
}

function daysInMonth(y: number, m: number): number {
  return new Date(Date.UTC(y, m, 0)).getUTCDate()
}

/** Monday = 0, matching the Python port. 1970-01-01 was a Thursday. */
function weekdayOf(epochDay: number): number {
  return (((epochDay + 3) % 7) + 7) % 7
}

export function ymdString({ y, m, d }: YMD): string {
  return `${String(y).padStart(4, '0')}-${String(m).padStart(2, '0')}-${String(d).padStart(2, '0')}`
}

export function parseYmd(text: string): YMD {
  const [y, m, d] = text.slice(0, 10).split('-').map(Number)
  return { y, m, d }
}

function clamped(y: number, m: number, day: number): number {
  return toEpochDay({ y, m, d: Math.min(day, daysInMonth(y, m)) })
}

function addMonths(y: number, m: number, months: number): [number, number] {
  const total = y * 12 + (m - 1) + months
  return [Math.floor(total / 12), (((total % 12) + 12) % 12) + 1]
}

/** Ascending epoch days on or after `start` where `rule` fires, lazily. */
function* candidates(rule: Rule, start: number): Generator<number> {
  let k = 0
  if (rule.kind === 'DAILY') {
    for (;;) {
      yield start + k * rule.every
      k += 1
    }
  } else if (rule.kind === 'WEEKLY') {
    const weekMonday = start - weekdayOf(start)
    for (;;) {
      const monday = weekMonday + k * rule.every * 7
      for (const weekday of rule.days) {
        const candidate = monday + weekday
        if (candidate >= start) yield candidate
      }
      k += 1
    }
  } else if (rule.kind === 'MONTHLY_ON_DATE') {
    const from = fromEpochDay(start)
    for (;;) {
      const [y, m] = addMonths(from.y, from.m, k * rule.every)
      const candidate = clamped(y, m, rule.day as number)
      if (candidate >= start) yield candidate
      k += 1
    }
  } else {
    const from = fromEpochDay(start)
    for (;;) {
      const candidate = clamped(from.y + k, rule.month as number, rule.day as number)
      if (candidate >= start) yield candidate
      k += 1
    }
  }
}

// ---------------------------------------------------------------------------
// Zones. `Intl` knows every IANA zone, so the expansion can run in any of them
// (the vectors use UTC, Tokyo, Los Angeles and Chicago), not only the runtime's.
// ---------------------------------------------------------------------------

const formatters = new Map<string, Intl.DateTimeFormat>()

function formatterFor(zone: string): Intl.DateTimeFormat {
  let formatter = formatters.get(zone)
  if (!formatter) {
    formatter = new Intl.DateTimeFormat('en-US', {
      timeZone: zone,
      hourCycle: 'h23',
      year: 'numeric',
      month: 'numeric',
      day: 'numeric',
      hour: 'numeric',
      minute: 'numeric',
      second: 'numeric',
    })
    formatters.set(zone, formatter)
  }
  return formatter
}

/** The wall-clock date and time `ms` is in `zone`. */
export function zonedParts(ms: number, zone: string): YMD & { h: number; min: number; s: number } {
  const parts: Record<string, number> = {}
  for (const part of formatterFor(zone).formatToParts(new Date(ms))) {
    if (part.type !== 'literal') parts[part.type] = Number(part.value)
  }
  return { y: parts.year, m: parts.month, d: parts.day, h: parts.hour, min: parts.minute, s: parts.second }
}

/** `zone`'s offset from UTC at instant `ms`, in milliseconds. */
function offsetAt(ms: number, zone: string): number {
  const p = zonedParts(ms, zone)
  const wall = Date.UTC(p.y, p.m - 1, p.d, p.h, p.min, p.s)
  return wall - Math.floor(ms / 1000) * 1000
}

/**
 * The instant at which `zone`'s wall clock reads `day` `h`:`min`.
 *
 * A gap (the clock jumps over that time) resolves forward by the gap, and an
 * overlap (the clock reads it twice) takes the first: both are "use the offset
 * that was in force just BEFORE the change", Java's `atZone` and Python's
 * `fold=0`.
 */
export function zonedInstant(day: number, h: number, min: number, zone: string): number {
  const { y, m, d } = fromEpochDay(day)
  const guess = Date.UTC(y, m - 1, d, h, min)
  const before = offsetAt(guess - DAY_MS, zone)
  const after = offsetAt(guess + DAY_MS, zone)
  if (before === after) return guess - before
  const first = guess - before
  const second = guess - after
  const firstValid = offsetAt(first, zone) === before
  const secondValid = offsetAt(second, zone) === after
  if (firstValid && secondValid) return Math.min(first, second)
  if (secondValid && !firstValid) return second
  return first
}

/** The viewer's own zone. */
export function localZone(): string {
  return new Intl.DateTimeFormat().resolvedOptions().timeZone
}

// ---------------------------------------------------------------------------
// The expansion itself.
// ---------------------------------------------------------------------------

/**
 * Every occurrence of `rule` from `startsAt` (epoch ms) within the inclusive
 * window, as epoch ms, ascending. `skipped` are LOCAL dates (`YYYY-MM-DD`) in
 * `zone`.
 */
export function occurrencesInWindow(
  startsAt: number,
  rule: Rule,
  end: End,
  skipped: ReadonlySet<string>,
  windowStart: number,
  windowEnd: number,
  zone: string,
): number[] {
  if (windowEnd < windowStart || !wellFormed(rule)) return []
  const startLocal = zonedParts(startsAt, zone)
  const startDay = toEpochDay(startLocal)
  const windowEndDay = toEpochDay(zonedParts(windowEnd, zone))
  const maxCount = end.kind === 'AFTER_COUNT' ? end.count : null
  const cutoff = end.kind === 'ON_DATE' && end.onDate ? toEpochDay(parseYmd(end.onDate)) : null

  const result: number[] = []
  let index = 0
  let iterations = 0
  for (const day of candidates(rule, startDay)) {
    iterations += 1
    if (iterations > SAFETY_CAP) break
    if (maxCount !== null && index >= maxCount) break
    if (cutoff !== null && day > cutoff) break
    if (day > windowEndDay) break
    index += 1 // counts even a skipped occurrence
    const occurrence = zonedInstant(day, startLocal.h, startLocal.min, zone)
    if (occurrence >= windowStart && occurrence <= windowEnd && !skipped.has(ymdString(fromEpochDay(day)))) {
      result.push(occurrence)
    }
  }
  return result
}

/**
 * Occurrences of one stored event in the window. A one-off event is its own
 * single occurrence; an event with no start, or a repeat that cannot be read,
 * has none.
 */
export function seriesOccurrences(
  series: SeriesFields,
  skipped: Iterable<string>,
  windowStart: number,
  windowEnd: number,
  zone: string,
): number[] {
  if (series.starts_at === null || series.starts_at === undefined) return []
  const startsAt = new Date(series.starts_at).getTime()
  const skippedSet = new Set(skipped)
  if (series.repeat_kind === null || series.repeat_kind === undefined) {
    const localDate = ymdString(zonedParts(startsAt, zone))
    return startsAt >= windowStart && startsAt <= windowEnd && !skippedSet.has(localDate) ? [startsAt] : []
  }
  const rule = ruleFromSeries(series)
  if (rule === null) return []
  return occurrencesInWindow(startsAt, rule, endFromSeries(series), skippedSet, windowStart, windowEnd, zone)
}

// ---------------------------------------------------------------------------
// What the screens call.
// ---------------------------------------------------------------------------

/** One place an event lands: the row, and the instant and day it lands on. */
export interface Occurrence {
  event: Event
  /** ISO instant. For an all-day row, UTC midnight of the date it names. */
  startsAt: string
  endsAt: string | null
  /** The viewer's local epoch day (an all-day row's own date). */
  day: number
  /** `YYYY-MM-DD` of `day`: what a skip names. */
  date: string
  /** True when `event` is a repeating series, so this is one of many. */
  recurring: boolean
}

export function isRepeating(event: Pick<Event, 'repeat_kind'>): boolean {
  return event.repeat_kind !== null && event.repeat_kind !== undefined
}

/** Skipped dates per event, from the live `event_skips` rows. A tombstone, a
 * redacted one included, is a skip that is not there. */
export function skipsByEvent(skips: readonly EventSkip[] | undefined): Map<string, Set<string>> {
  const byEvent = new Map<string, Set<string>>()
  for (const skip of skips ?? []) {
    if (skip.deleted_at !== null || !skip.skip_date) continue
    let dates = byEvent.get(skip.event)
    if (!dates) {
      dates = new Set()
      byEvent.set(skip.event, dates)
    }
    dates.add(skip.skip_date.slice(0, 10))
  }
  return byEvent
}

/** Local midnight at the start of `day`, in epoch ms, in `zone`. */
function startOfDay(day: number, zone: string): number {
  return zonedInstant(day, 0, 0, zone)
}

/**
 * Every live occurrence landing on viewer-local days `fromDay` to `toDay`
 * inclusive, ordered by time (all-day rows first within a day), with skips
 * honoured.
 *
 * Rows with no `starts_at` are excluded rather than bucketed at an arbitrary
 * day: a row with no anchor cannot be placed in a window (the phone's
 * `activeByKindInLocalWindow`).
 *
 * An all-day row is expanded in UTC, because its `starts_at` is UTC midnight of
 * the date it names. Expanded in the viewer's zone, a series anchored at
 * `2026-10-05T00:00Z` would run on the evening before, in Chicago. Its skips
 * are dates in that same UTC calendar, which is the date the row displays.
 */
export function occurrencesBetween(
  events: readonly Event[],
  skips: readonly EventSkip[] | undefined,
  fromDay: number,
  toDay: number,
  zone: string = localZone(),
): Occurrence[] {
  const skipped = skipsByEvent(skips)
  const found: Occurrence[] = []
  const timedStart = startOfDay(fromDay, zone)
  const timedEnd = startOfDay(toDay + 1, zone) - 1
  const allDayStart = fromDay * DAY_MS
  const allDayEnd = (toDay + 1) * DAY_MS - 1

  for (const event of events) {
    if (event.deleted_at !== null) continue
    if (event.starts_at === null || event.starts_at === undefined) continue
    const allDay = event.all_day === true
    const eventZone = allDay ? 'UTC' : zone
    const instants = seriesOccurrences(
      event,
      skipped.get(event.id) ?? [],
      allDay ? allDayStart : timedStart,
      allDay ? allDayEnd : timedEnd,
      eventZone,
    )
    const length =
      event.ends_at !== null && event.ends_at !== undefined
        ? new Date(event.ends_at).getTime() - new Date(event.starts_at).getTime()
        : null
    for (const instant of instants) {
      const day = toEpochDay(zonedParts(instant, eventZone))
      found.push({
        event,
        startsAt: new Date(instant).toISOString(),
        endsAt: length !== null && length >= 0 ? new Date(instant + length).toISOString() : null,
        day,
        date: ymdString(fromEpochDay(day)),
        recurring: isRepeating(event),
      })
    }
  }
  return found.sort(compareOccurrences)
}

/** All-day first, then by time, then by title so the order is stable. */
export function compareOccurrences(a: Occurrence, b: Occurrence): number {
  const aAllDay = a.event.all_day === true ? 0 : 1
  const bAllDay = b.event.all_day === true ? 0 : 1
  if (aAllDay !== bAllDay) return aAllDay - bAllDay
  if (a.startsAt !== b.startsAt) return a.startsAt < b.startsAt ? -1 : 1
  return a.event.title.localeCompare(b.event.title)
}

/** The occurrences of `events` on one viewer-local day. */
export function occurrencesOnDay(
  day: number,
  events: readonly Event[],
  skips?: readonly EventSkip[],
  zone?: string,
): Occurrence[] {
  return occurrencesBetween(events, skips, day, day, zone)
}
