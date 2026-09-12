import { describe, expect, test } from 'vitest'

import { appliesOnDay, epochDay, localDayOf, parseWeekdayCodes, todayEpochDay } from '@/lib/day'

// `vite.config.ts`'s `test.env.TZ` fixes the whole suite to America/Chicago
// (a real, non-UTC offset) so these "which local day" assertions exercise an
// actual conversion rather than accidentally passing on a UTC test runner.

test('epochDay counts local calendar days, never a UTC slice', () => {
  // 1970-01-01 local is day 0, by definition.
  expect(epochDay(new Date(2026, 8, 13))).toBeGreaterThan(0)
  // A day and the next differ by exactly one, regardless of DST.
  expect(epochDay(new Date(2026, 8, 14)) - epochDay(new Date(2026, 8, 13))).toBe(1)
})

test('todayEpochDay matches epochDay(new Date()) at call time', () => {
  expect(todayEpochDay()).toBe(epochDay(new Date()))
})

test(
  'localDayOf reads the VIEWER local day, not the UTC date part - ' +
    'the exact shape of the bug in ReadCalendarTaskVisibilityTest.kt: ' +
    "Canvas writes an 11:59pm Sunday deadline as 2026-09-14T04:59Z, a UTC " +
    'Monday but a Chicago Sunday',
  () => {
    const sundayDeadlineUtc = '2026-09-14T04:59:00Z'
    const sunday = localDayOf(sundayDeadlineUtc)

    // The bug this guards against: slicing the ISO string's own UTC date
    // part (`2026-09-14`) instead of converting to the viewer's local day.
    // Built from `getUTC*`, deliberately NOT by round-tripping through this
    // file's own `epochDay` (which always reads LOCAL getters and so would
    // silently reproduce the correct answer here instead of the bug's one).
    const parsed = new Date(sundayDeadlineUtc)
    const mondayUtcDatePart = Date.UTC(parsed.getUTCFullYear(), parsed.getUTCMonth(), parsed.getUTCDate()) / 86_400_000

    expect(sunday).not.toBe(mondayUtcDatePart)
    expect(sunday).toBe(epochDay(new Date(2026, 8, 13)))
  },
)

test('parseWeekdayCodes reads the comma-separated two-letter vocabulary', () => {
  expect(parseWeekdayCodes('MO,WE,FR')).toEqual(new Set([1, 3, 5]))
  expect(parseWeekdayCodes('su')).toEqual(new Set([0]))
})

test('parseWeekdayCodes degrades to null on anything unparseable, never throws', () => {
  expect(parseWeekdayCodes(null)).toBeNull()
  expect(parseWeekdayCodes('')).toBeNull()
  expect(parseWeekdayCodes('NOTADAY')).toBeNull()
})

describe('appliesOnDay', () => {
  const createdMonday = '2026-09-07T12:00:00Z' // a Monday, Chicago local too

  test('a null schedule_kind applies every day (a plain todo list)', () => {
    const schedule = { schedule_kind: null, schedule_every: null, schedule_days_of_week: null, created_at: createdMonday }
    expect(appliesOnDay(schedule, localDayOf(createdMonday))).toBe(true)
    expect(appliesOnDay(schedule, localDayOf(createdMonday) + 30)).toBe(true)
  })

  test('a day before the checklist existed never applies (trap 1)', () => {
    const schedule = { schedule_kind: null, schedule_every: null, schedule_days_of_week: null, created_at: createdMonday }
    expect(appliesOnDay(schedule, localDayOf(createdMonday) - 1)).toBe(false)
  })

  test('DAILY every 1 applies every day from creation on', () => {
    const schedule = { schedule_kind: 'DAILY', schedule_every: 1, schedule_days_of_week: null, created_at: createdMonday }
    const day0 = localDayOf(createdMonday)
    expect(appliesOnDay(schedule, day0)).toBe(true)
    expect(appliesOnDay(schedule, day0 + 1)).toBe(true)
    expect(appliesOnDay(schedule, day0 + 5)).toBe(true)
  })

  test('DAILY every 3 skips the days in between', () => {
    const schedule = { schedule_kind: 'DAILY', schedule_every: 3, schedule_days_of_week: null, created_at: createdMonday }
    const day0 = localDayOf(createdMonday)
    expect(appliesOnDay(schedule, day0)).toBe(true)
    expect(appliesOnDay(schedule, day0 + 1)).toBe(false)
    expect(appliesOnDay(schedule, day0 + 2)).toBe(false)
    expect(appliesOnDay(schedule, day0 + 3)).toBe(true)
  })

  test('WEEKLY MO,WE,FR only applies on those weekdays', () => {
    const schedule = {
      schedule_kind: 'WEEKLY',
      schedule_every: 1,
      schedule_days_of_week: 'MO,WE,FR',
      created_at: createdMonday,
    }
    const monday = localDayOf(createdMonday)
    expect(appliesOnDay(schedule, monday)).toBe(true) // Monday
    expect(appliesOnDay(schedule, monday + 1)).toBe(false) // Tuesday
    expect(appliesOnDay(schedule, monday + 2)).toBe(true) // Wednesday
    expect(appliesOnDay(schedule, monday + 4)).toBe(true) // Friday
  })

  test('a malformed schedule degrades toward always showing, never hiding', () => {
    const badKind = { schedule_kind: 'FORTNIGHTLY', schedule_every: 1, schedule_days_of_week: null, created_at: createdMonday }
    expect(appliesOnDay(badKind, localDayOf(createdMonday) + 1)).toBe(true)

    const emptyWeekdays = { schedule_kind: 'WEEKLY', schedule_every: 1, schedule_days_of_week: '', created_at: createdMonday }
    expect(appliesOnDay(emptyWeekdays, localDayOf(createdMonday) + 1)).toBe(true)
  })
})
