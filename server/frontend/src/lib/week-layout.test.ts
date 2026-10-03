import { expect, test } from 'vitest'

import type { Event } from '@/api/types'
import { occurrencesBetween, toEpochDay, type Occurrence } from '@/lib/recurrence'
import { isTimed, layoutTimed } from '@/lib/week-layout'
import { makeEvent } from '@/test/engine'

// `vite.config.ts` pins the suite to America/Chicago.
const DAY = toEpochDay({ y: 2026, m: 10, d: 7 })

/** An event on 7 Oct from `start` to `end`, "HH:MM" Chicago time (CDT, UTC-5). */
function at(title: string, start: string, end: string | null, extra: Partial<Event> = {}): Occurrence {
  const utc = (hhmm: string) => {
    const [h, m] = hhmm.split(':').map(Number)
    return new Date(Date.UTC(2026, 9, 7, h + 5, m)).toISOString()
  }
  const event = makeEvent({ title, starts_at: utc(start), ends_at: end ? utc(end) : null, ...extra })
  return occurrencesBetween([event], [], DAY, DAY, 'America/Chicago')[0]
}

test('events that do not overlap each take the whole column', () => {
  const placed = layoutTimed([at('A', '09:00', '10:00'), at('B', '10:00', '11:00')], 'America/Chicago')
  expect(placed.map((p) => [p.occurrence.event.title, p.column, p.columns])).toEqual([
    ['A', 0, 1],
    ['B', 0, 1],
  ])
})

test('overlapping events sit side by side and share the width', () => {
  const placed = layoutTimed([at('Planning', '10:00', '11:30'), at('Plumber', '10:30', '11:30')], 'America/Chicago')
  expect(placed.map((p) => [p.occurrence.event.title, p.column, p.columns])).toEqual([
    ['Planning', 0, 2],
    ['Plumber', 1, 2],
  ])
})

test('three at once take three columns; a later one reuses the first free column', () => {
  const placed = layoutTimed(
    [at('A', '09:00', '12:00'), at('B', '09:30', '10:30'), at('C', '10:00', '11:00'), at('D', '11:00', '12:00')],
    'America/Chicago',
  )
  const byTitle = Object.fromEntries(placed.map((p) => [p.occurrence.event.title, p]))
  expect(byTitle.A).toMatchObject({ column: 0, columns: 3 })
  expect(byTitle.B).toMatchObject({ column: 1, columns: 3 })
  expect(byTitle.C).toMatchObject({ column: 2, columns: 3 })
  // B ended at 10:30, so D (11:00) goes back to the first free column beside A.
  expect(byTitle.D.column).toBe(1)
})

test('a later, separate cluster does not inherit an earlier cluster width', () => {
  const placed = layoutTimed(
    [at('A', '09:00', '10:00'), at('B', '09:30', '10:30'), at('Lunch', '12:00', '13:00')],
    'America/Chicago',
  )
  expect(placed.find((p) => p.occurrence.event.title === 'Lunch')).toMatchObject({ column: 0, columns: 1 })
})

test('an event with no end is drawn an hour long, and a very short one is still pressable', () => {
  const [open, brief] = layoutTimed([at('Open', '13:00', null), at('Brief', '15:00', '15:05')], 'America/Chicago')
  expect(open.endMinutes - open.startMinutes).toBe(60)
  expect(brief.endMinutes - brief.startMinutes).toBe(30)
})

test('before 6:00 clamps to the top of the grid, and nothing runs past midnight', () => {
  const [early, late] = layoutTimed([at('Flight', '05:00', '07:00'), at('Late', '23:00', '23:59')], 'America/Chicago')
  expect(early.startMinutes).toBe(6 * 60)
  expect(late.endMinutes).toBe(23 * 60 + 59) // its own end, and never past 24:00
})

test('all-day rows and tasks stay off the hour grid', () => {
  const allDay = at('Rent due', '00:00', null, { all_day: true })
  const task = at('HW 4 due', '23:59', null, { kind: 'task' })
  const plain = at('Dentist', '15:00', '16:00')
  expect([allDay, task, plain].map(isTimed)).toEqual([false, false, true])
  expect(layoutTimed([allDay, task, plain], 'America/Chicago')).toHaveLength(1)
})
