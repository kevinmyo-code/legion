import { describe, expect, test } from 'vitest'

import vectorFile from '../../../tests/fixtures/recurrence_vectors.json'
import type { Event, EventSkip } from '@/api/types'
import {
  occurrencesBetween,
  occurrencesOnDay,
  parseWeekdays,
  ruleFromSeries,
  seriesOccurrences,
  toEpochDay,
  zonedInstant,
  zonedParts,
  type SeriesFields,
} from '@/lib/recurrence'
import { makeEvent } from '@/test/engine'

/**
 * `src/lib/recurrence.ts` against the shared vectors (web-revamp 08, spec D4).
 *
 * The file read here is `server/tests/fixtures/recurrence_vectors.json`, the
 * same one `server/tests/test_recurrence.py` reads. Each vector is transcribed
 * from the phone's own expansion tests and names the Kotlin test it came from.
 * A vector this port gets wrong fails here; a vector that is itself wrong fails
 * the broken-vector check below, which is what makes the comparison a gate
 * rather than an echo.
 */

interface Vector {
  name: string
  kotlin: string
  tz: string
  series: SeriesFields
  skips: string[]
  window: { start: string; end: string }
  expected: string[]
}

const VECTORS = vectorFile.vectors as Vector[]

function pad(n: number): string {
  return String(n).padStart(2, '0')
}

/** The occurrences as LOCAL wall-clock `YYYY-MM-DDTHH:MM` in the vector's zone. */
function expand(vector: Vector): string[] {
  return seriesOccurrences(
    vector.series,
    vector.skips,
    new Date(vector.window.start).getTime(),
    new Date(vector.window.end).getTime(),
    vector.tz,
  ).map((instant) => {
    const p = zonedParts(instant, vector.tz)
    return `${p.y}-${pad(p.m)}-${pad(p.d)}T${pad(p.h)}:${pad(p.min)}`
  })
}

function skip(event: Event, date: string, deletedAt: string | null = null): EventSkip {
  return { id: `skip-${date}`, event: event.id, skip_date: date, created_at: '', updated_at: '', deleted_at: deletedAt }
}

describe('the shared recurrence vectors', () => {
  test.each(VECTORS.map((v) => [v.name, v] as const))('%s', (_name, vector) => {
    expect(expand(vector), vector.kotlin).toEqual(vector.expected)
  })

  test('the vectors cover every rule kind, every end kind, a skip and the three zones', () => {
    expect(new Set(VECTORS.map((v) => v.series.repeat_kind))).toEqual(
      new Set(['DAILY', 'WEEKLY', 'MONTHLY_ON_DATE', 'YEARLY']),
    )
    expect(new Set(VECTORS.map((v) => v.series.repeat_end_kind))).toEqual(
      new Set(['NEVER', 'ON_DATE', 'AFTER_COUNT']),
    )
    expect(VECTORS.some((v) => v.skips.length > 0)).toBe(true)
    const zones = new Set(VECTORS.map((v) => v.tz))
    for (const zone of ['UTC', 'Asia/Tokyo', 'America/Chicago']) expect(zones).toContain(zone)
  })

  test('a broken vector fails: a dropped occurrence, an extra skip, a moved zone', () => {
    const vector = VECTORS.find((v) => v.name === 'weekly-monday-east-of-utc')!
    const dropped = { ...vector, expected: vector.expected.slice(0, -1) }
    expect(expand(dropped)).not.toEqual(dropped.expected)

    const skipped = { ...vector, skips: [...vector.skips, '2026-08-17'] }
    expect(expand(skipped)).not.toEqual(skipped.expected)

    const moved = { ...vector, tz: 'UTC' }
    expect(expand(moved)).not.toEqual(moved.expected)
  })
})

describe('the zone arithmetic under the port', () => {
  test('a wall-clock time that does not exist resolves forward by the gap', () => {
    // 2026-03-08 02:30 does not exist in Chicago (the clocks jump from 02:00 to 03:00).
    const instant = zonedInstant(toEpochDay({ y: 2026, m: 3, d: 8 }), 2, 30, 'America/Chicago')
    const p = zonedParts(instant, 'America/Chicago')
    expect([p.h, p.min]).toEqual([3, 30])
  })

  test('a wall-clock time that happens twice takes the first', () => {
    // 2026-11-01 01:30 happens twice in Chicago; the first is still daylight time (-5).
    const instant = zonedInstant(toEpochDay({ y: 2026, m: 11, d: 1 }), 1, 30, 'America/Chicago')
    expect(new Date(instant).toISOString()).toBe('2026-11-01T06:30:00.000Z')
  })

  test('weekday names parse as the phone parses them', () => {
    expect(parseWeekdays('mon, WEDNESDAY')).toEqual([0, 2])
    expect(parseWeekdays('')).toEqual([])
    expect(parseWeekdays('MON,FUNDAY')).toBeNull()
    expect(ruleFromSeries({ repeat_kind: 'WEEKLY', repeat_every: 1, repeat_days_of_week: 'FUNDAY' })).toBeNull()
  })
})

describe('occurrences between two viewer-local days', () => {
  // `vite.config.ts` pins the suite to America/Chicago.
  const weekly = (overrides: Partial<Event> = {}) =>
    makeEvent({
      title: 'Swim',
      starts_at: '2026-10-05T23:00:00Z', // Monday 6 pm Chicago
      repeat_kind: 'WEEKLY',
      repeat_every: 1,
      repeat_days_of_week: 'MON',
      ...overrides,
    })

  test('a weekly series lands on its own weekday, in local time', () => {
    const from = toEpochDay({ y: 2026, m: 10, d: 1 })
    const found = occurrencesBetween([weekly()], [], from, from + 20, 'America/Chicago')
    expect(found.map((o) => o.date)).toEqual(['2026-10-05', '2026-10-12', '2026-10-19'])
    expect(found.every((o) => o.recurring)).toBe(true)
  })

  test('a skip removes that one occurrence and no other', () => {
    const series = weekly()
    const from = toEpochDay({ y: 2026, m: 10, d: 1 })
    const found = occurrencesBetween([series], [skip(series, '2026-10-12')], from, from + 20, 'America/Chicago')
    expect(found.map((o) => o.date)).toEqual(['2026-10-05', '2026-10-19'])
  })

  test('a skip that was deleted, or arrived as a redacted tombstone, skips nothing', () => {
    const series = weekly()
    const from = toEpochDay({ y: 2026, m: 10, d: 1 })
    const redacted = { id: 's2', deleted_at: '2026-10-02T00:00:00Z', updated_at: '', redacted: true }
    const found = occurrencesBetween(
      [series],
      [skip(series, '2026-10-12', '2026-10-02T00:00:00Z'), redacted as unknown as EventSkip],
      from,
      from + 20,
      'America/Chicago',
    )
    expect(found).toHaveLength(3)
  })

  test('an all-day series is read on its own UTC date, never the evening before', () => {
    const series = makeEvent({
      title: 'Bins out',
      all_day: true,
      starts_at: '2026-10-08T00:00:00Z',
      repeat_kind: 'WEEKLY',
      repeat_every: 1,
      repeat_days_of_week: 'THU',
    })
    const from = toEpochDay({ y: 2026, m: 10, d: 1 })
    const found = occurrencesBetween([series], [], from, from + 14, 'America/Chicago')
    expect(found.map((o) => o.date)).toEqual(['2026-10-08', '2026-10-15'])
    // Skipping the date it shows removes it.
    const skipped = occurrencesBetween([series], [skip(series, '2026-10-15')], from, from + 14, 'America/Chicago')
    expect(skipped.map((o) => o.date)).toEqual(['2026-10-08'])
  })

  test('a tombstone and a row with no start are never placed', () => {
    const day = toEpochDay({ y: 2026, m: 10, d: 5 })
    const dead = makeEvent({ title: 'Gone', starts_at: '2026-10-05T17:00:00Z', deleted_at: '2026-10-04T00:00:00Z' })
    const unanchored = makeEvent({ title: 'Someday' })
    expect(occurrencesOnDay(day, [dead, unanchored], [], 'America/Chicago')).toEqual([])
  })

  test('an occurrence keeps the series length', () => {
    const series = weekly({ ends_at: '2026-10-06T00:00:00Z' }) // one hour
    const from = toEpochDay({ y: 2026, m: 10, d: 12 })
    const [one] = occurrencesBetween([series], [], from, from, 'America/Chicago')
    expect(one.startsAt).toBe('2026-10-12T23:00:00.000Z')
    expect(one.endsAt).toBe('2026-10-13T00:00:00.000Z')
  })
})
