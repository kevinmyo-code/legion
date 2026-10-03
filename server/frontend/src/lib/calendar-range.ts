import { dateForEpochDay } from '@/lib/day'
import { occurrencesBetween, toEpochDay, type Occurrence } from '@/lib/recurrence'
import type { Event, EventSkip } from '@/api/types'

/**
 * The days a calendar view covers, and what lands on each, in the viewer's own
 * calendar (epoch days: `lib/day.ts`). Weeks start on Sunday, matching the month
 * grid `lib/horizon.ts` has always drawn.
 */

/** The Sunday on or before `day`. */
export function weekStartOf(day: number): number {
  return day - dateForEpochDay(day).getDay()
}

/** Seven consecutive days starting at the Sunday on or before `day`. */
export function weekDays(day: number): number[] {
  const start = weekStartOf(day)
  return Array.from({ length: 7 }, (_, offset) => start + offset)
}

/** First day of the month containing `day`, and the day count of that month. */
function monthBounds(day: number): { first: number; length: number } {
  const date = dateForEpochDay(day)
  const length = new Date(date.getFullYear(), date.getMonth() + 1, 0).getDate()
  return { first: day - (date.getDate() - 1), length }
}

/** Whole Sunday-to-Saturday weeks covering the month containing `day`. */
export function monthGridDays(day: number): number[] {
  const { first, length } = monthBounds(day)
  const start = weekStartOf(first)
  const last = first + length - 1
  const end = last + (6 - dateForEpochDay(last).getDay())
  return Array.from({ length: end - start + 1 }, (_, offset) => start + offset)
}

/** `day` moved by whole weeks (week view) or whole months (month view), keeping
 * the day of the month where the target month has it and its last day where not. */
export function stepAnchor(day: number, view: 'week' | 'month', direction: -1 | 1): number {
  if (view === 'week') return day + 7 * direction
  const date = dateForEpochDay(day)
  const target = new Date(date.getFullYear(), date.getMonth() + direction, 1)
  const length = new Date(target.getFullYear(), target.getMonth() + 1, 0).getDate()
  return toEpochDay({
    y: target.getFullYear(),
    m: target.getMonth() + 1,
    d: Math.min(date.getDate(), length),
  })
}

/** "Oct 4 - 10, 2026" or "Sep 27 - Oct 3, 2026". */
export function weekLabel(days: number[]): string {
  const first = dateForEpochDay(days[0])
  const last = dateForEpochDay(days[days.length - 1])
  const sameMonth = first.getMonth() === last.getMonth() && first.getFullYear() === last.getFullYear()
  const month = (d: Date) => d.toLocaleDateString(undefined, { month: 'short' })
  const range = sameMonth
    ? `${month(first)} ${first.getDate()} – ${last.getDate()}`
    : `${month(first)} ${first.getDate()} – ${month(last)} ${last.getDate()}`
  return `${range}, ${last.getFullYear()}`
}

export function monthLabel(day: number): string {
  return dateForEpochDay(day).toLocaleDateString(undefined, { month: 'long', year: 'numeric' })
}

/** Occurrences on each of `days`, keyed by epoch day, in display order. */
export function groupByDay(
  days: number[],
  events: readonly Event[],
  skips: readonly EventSkip[] | undefined,
): Map<number, Occurrence[]> {
  const byDay = new Map<number, Occurrence[]>(days.map((day) => [day, []]))
  if (days.length === 0) return byDay
  for (const occurrence of occurrencesBetween(events, skips, days[0], days[days.length - 1])) {
    byDay.get(occurrence.day)?.push(occurrence)
  }
  return byDay
}
