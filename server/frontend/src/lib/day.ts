/**
 * Local-day arithmetic, kept in one file because the phone was bitten by
 * exactly this class of bug the day this ticket was cut (a task due
 * "11:59pm local" is stored as `04:59Z the next day`, and bucketing by the
 * UTC date part put Sunday's homework on Monday -
 * `app/.../service/ReadCalendarTaskVisibilityTest.kt`). Every function here
 * takes the VIEWER'S wall-clock date, never a UTC slice of an ISO string.
 *
 * `epochDay` matches `LocalDate.toEpochDay()` on the phone and
 * `checklists.ChecklistTick.day` on the server exactly: days since
 * 1970-01-01, counted by calendar date components, never by dividing a
 * millisecond timestamp (which would drift by timezone offset).
 */

/** The number of whole days between 1970-01-01 and `date`'s own
 * year/month/day, evaluated in the LOCAL calendar (never `date.getUTC*`). */
export function epochDay(date: Date): number {
  const utcMidnight = Date.UTC(date.getFullYear(), date.getMonth(), date.getDate())
  return Math.floor(utcMidnight / 86_400_000)
}

/** Today's epoch day, in the viewer's own timezone. */
export function todayEpochDay(): number {
  return epochDay(new Date())
}

/** A `Date` at local midnight for `day` epoch days since 1970-01-01, used
 * only to derive display strings (weekday, "Sep 10") - never converted back
 * through `.toISOString()`, which would reintroduce the UTC bug this file
 * exists to avoid. */
export function dateForEpochDay(day: number): Date {
  const base = new Date(0)
  base.setFullYear(1970, 0, 1)
  base.setHours(0, 0, 0, 0)
  base.setDate(base.getDate() + day)
  return base
}

/** Which local calendar day an ISO instant falls on, for the viewer. This is
 * the one conversion this file has to make from a server timestamp
 * (`starts_at`, always UTC) to a day bucket, and it goes through the
 * `Date` object's own local getters rather than string-slicing the `Z`
 * suffix, which is precisely the bug the phone found. */
export function localDayOf(iso: string): number {
  return epochDay(new Date(iso))
}

const WEEKDAY_CODES = ['SU', 'MO', 'TU', 'WE', 'TH', 'FR', 'SA'] as const

/** Parses the server/phone's `schedule_days_of_week` vocabulary - a
 * comma-separated list of two-letter codes (`"MO,WE,FR"`), matching
 * `ChecklistController.parseWeekdays`. Unparseable or empty input is
 * `null`, which callers treat as "malformed schedule, apply every day" -
 * the same degrade-toward-showing posture the phone's own `appliesOnDay`
 * uses, so a bad schedule on the web never hides a checklist it would
 * still show on the phone. */
export function parseWeekdayCodes(raw: string | null | undefined): Set<number> | null {
  if (!raw) return null
  const codes = raw
    .split(',')
    .map((code) => code.trim().toUpperCase())
    .filter(Boolean)
  if (codes.length === 0) return null
  const indices = new Set<number>()
  for (const code of codes) {
    const index = WEEKDAY_CODES.indexOf(code as (typeof WEEKDAY_CODES)[number])
    if (index === -1) return null
    indices.add(index)
  }
  return indices
}

export interface ScheduleLike {
  schedule_kind?: string | null
  schedule_every?: number | null
  schedule_days_of_week?: string | null
  created_at: string
}

/**
 * Whether a checklist's schedule applies on `day` (an epoch day) - a
 * simplified port of `ChecklistController.appliesOnDay`. Covers `DAILY`
 * (every N days from creation) and `WEEKLY` (a set of weekdays); anything
 * else, including a null `schedule_kind`, degrades to "applies every day"
 * on purpose, matching the phone's own "never silently hide a checklist"
 * rule. A day before the checklist existed never applies (trap 1: created
 * today, asked about yesterday, answers false rather than "everything
 * missed").
 */
export function appliesOnDay(schedule: ScheduleLike, day: number): boolean {
  const createdDay = localDayOf(schedule.created_at)
  if (day < createdDay) return false

  const kind = schedule.schedule_kind
  if (!kind) return true
  const every = schedule.schedule_every
  if (!every || every < 1) return true

  if (kind === 'DAILY') {
    return (day - createdDay) % every === 0
  }
  if (kind === 'WEEKLY') {
    const weekdays = parseWeekdayCodes(schedule.schedule_days_of_week)
    if (!weekdays) return true
    // JS `Date#getDay()`: 0 = Sunday, matching `WEEKDAY_CODES`'s own order.
    const weekday = dateForEpochDay(day).getDay()
    if (!weekdays.has(weekday)) return false
    // `every` weeks apart, anchored at the week the checklist was created.
    const weeksSinceCreated = Math.floor((day - createdDay) / 7)
    return weeksSinceCreated % every === 0
  }
  return true
}
