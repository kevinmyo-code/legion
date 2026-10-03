import { describe, expect, test } from 'vitest'

import {
  blankForm,
  fieldsForSeries,
  fieldsFromForm,
  formFromOccurrence,
  occurrenceGuid,
  validateForm,
} from '@/lib/event-form'
import { occurrencesBetween, toEpochDay } from '@/lib/recurrence'
import { makeEvent } from '@/test/engine'

// `vite.config.ts` pins the suite to America/Chicago.
const OCT5 = toEpochDay({ y: 2026, m: 10, d: 5 })

describe('fieldsFromForm', () => {
  test('a weekly series stores full weekday names in order, every 1', () => {
    const form = { ...blankForm(OCT5, 9 * 60), title: 'Swim', repeat: 'weekly' as const, weekdays: [2, 0] }
    expect(fieldsFromForm(form)).toMatchObject({
      repeat_kind: 'WEEKLY',
      repeat_every: 1,
      repeat_days_of_week: 'MONDAY,WEDNESDAY',
      repeat_end_kind: 'NEVER',
    })
  })

  test('monthly stores the day of the month, yearly the day and the month', () => {
    const base = { ...blankForm(OCT5, 9 * 60), title: 'Rent' }
    expect(fieldsFromForm({ ...base, repeat: 'monthly' })).toMatchObject({
      repeat_kind: 'MONTHLY_ON_DATE',
      repeat_day: 5,
    })
    expect(fieldsFromForm({ ...base, repeat: 'yearly' })).toMatchObject({
      repeat_kind: 'YEARLY',
      repeat_day: 5,
      repeat_month: 10,
      repeat_every: null,
    })
  })

  test('an end on a date and an end after N are stored with their own columns', () => {
    const base = { ...blankForm(OCT5, 9 * 60), title: 'Term', repeat: 'daily' as const }
    expect(fieldsFromForm({ ...base, ends: 'on_date', endsOn: '2026-12-01' })).toMatchObject({
      repeat_end_kind: 'ON_DATE',
      repeat_end_date: '2026-12-01',
      repeat_end_count: null,
    })
    expect(fieldsFromForm({ ...base, ends: 'after', endsAfter: '4' })).toMatchObject({
      repeat_end_kind: 'AFTER_COUNT',
      repeat_end_count: 4,
      repeat_end_date: null,
    })
  })

  test('"just this one" stores a one-off whatever the repeat says', () => {
    const form = { ...blankForm(OCT5, 9 * 60), title: 'Swim', repeat: 'daily' as const }
    expect(fieldsFromForm(form, false)).toMatchObject({
      repeat_kind: null,
      repeat_every: null,
      repeat_end_kind: null,
    })
  })

  test('a timed event is the wall clock as an instant; an all-day one is UTC midnight', () => {
    const timed = fieldsFromForm({ ...blankForm(OCT5, 18 * 60 + 30), title: 'Dinner' })
    expect(timed.starts_at).toBe('2026-10-05T23:30:00.000Z') // 6:30 pm CDT
    expect(timed.ends_at).toBe('2026-10-06T00:30:00.000Z')
    const allDay = fieldsFromForm({ ...blankForm(OCT5, 0), title: 'Rent', allDay: true })
    expect(allDay.starts_at).toBe('2026-10-05T00:00:00.000Z')
    expect(allDay.ends_at).toBeNull()
  })
})

describe('editing a series', () => {
  const series = makeEvent({
    title: 'Swim',
    starts_at: '2026-10-06T21:00:00Z', // Tuesday 4 pm Chicago
    ends_at: '2026-10-06T21:45:00Z',
    repeat_kind: 'WEEKLY',
    repeat_every: 2,
    repeat_days_of_week: 'TUESDAY',
  })
  const second = occurrencesBetween([series], [], OCT5, OCT5 + 40, 'America/Chicago')[1] // Oct 20

  test('the form reads the occurrence it was opened on', () => {
    const form = formFromOccurrence(second)
    expect(form).toMatchObject({ date: '2026-10-20', startTime: '16:00', endTime: '16:45', repeat: 'weekly', every: 2 })
    expect(form.weekdays).toEqual([1])
  })

  test('"all of them" keeps the series own start date and an every-2 repeat', () => {
    const fields = fieldsForSeries(formFromOccurrence(second), second)
    expect(new Date(fields.starts_at).toISOString()).toBe('2026-10-06T21:00:00.000Z')
    expect(fields.repeat_every).toBe(2)
  })

  test('moving the date moves the series start by the same number of days', () => {
    const form = { ...formFromOccurrence(second), date: '2026-10-22' } // two days later
    const fields = fieldsForSeries(form, second)
    expect(new Date(fields.starts_at).toISOString()).toBe('2026-10-08T21:00:00.000Z')
  })
})

describe('validateForm', () => {
  const ok = { ...blankForm(OCT5, 9 * 60), title: 'Swim' }
  test('a good form has no problem', () => expect(validateForm(ok)).toBeNull())
  test('a blank title', () => expect(validateForm({ ...ok, title: '   ' })).toBe('Give the event a title first.'))
  test('an end at or before the start', () =>
    expect(validateForm({ ...ok, startTime: '10:00', endTime: '10:00' })).toBe('The end has to be after the start.'))
  test('weekly without days', () =>
    expect(validateForm({ ...ok, repeat: 'weekly' })).toBe('Choose at least one day for a weekly repeat.'))
  test('a repeat that ends before it starts', () =>
    expect(validateForm({ ...ok, repeat: 'daily', ends: 'on_date', endsOn: '2026-10-01' })).toBe(
      'The repeat cannot end before it starts.',
    ))
  test('an all-day event needs no times', () =>
    expect(validateForm({ ...ok, allDay: true, startTime: '', endTime: '' })).toBeNull())
})

test('the one-off an edited occurrence becomes is keyed by series and date', () => {
  expect(occurrenceGuid({ id: 'abc' }, '2026-10-20')).toBe('abc:2026-10-20')
})
