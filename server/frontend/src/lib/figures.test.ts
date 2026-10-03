import { describe, expect, test } from 'vitest'

import {
  centsToDollars,
  dollarsToCents,
  formatDay,
  formatMinutes,
  formatMoney,
  localDay,
  toLocalInput,
  weekStartOf,
} from '@/lib/figures'

describe('money', () => {
  test('is drawn from integer cents', () => {
    expect(formatMoney(8423)).toBe('$84.23')
    expect(formatMoney(-4810)).toBe('-$48.10')
    expect(formatMoney(5, 'USD')).toBe('$0.05')
  })

  test('typed dollars become exact cents without floating-point arithmetic', () => {
    // 19.99 * 100 is 1998.9999999999998 in floating point; digits are read as digits.
    expect(dollarsToCents('19.99')).toBe(1999)
    expect(dollarsToCents('24.5')).toBe(2450)
    expect(dollarsToCents('7')).toBe(700)
    expect(dollarsToCents(' 0.07 ')).toBe(7)
    expect(dollarsToCents('-3.20')).toBe(-320)
  })

  test('refuses what is not a plain amount rather than rounding a figure nobody typed', () => {
    for (const bad of ['24.505', '', 'abc', '1,000', '1e3', '$5', '.5']) {
      expect(dollarsToCents(bad)).toBeNull()
    }
  })

  test('cents go back to the amount a field holds', () => {
    expect(centsToDollars(1999)).toBe('19.99')
    expect(centsToDollars(5)).toBe('0.05')
    expect(centsToDollars(-320)).toBe('-3.20')
    expect(dollarsToCents(centsToDollars(123456))).toBe(123456)
  })
})

describe('dates', () => {
  test('a bare date is that calendar day, never a UTC instant that slips a day west of Greenwich', () => {
    // The suite runs in America/Chicago; `new Date('2026-10-03')` would read as the 2nd there.
    expect(formatDay('2026-10-03')).toBe('Oct 3, 2026')
  })

  test('the local day of an instant is the viewer\'s, not the UTC slice', () => {
    // 02:30 UTC on the 4th is the evening of the 3rd in Chicago.
    expect(localDay('2026-10-04T02:30:00Z')).toBe('2026-10-03')
  })

  test('a datetime-local value round-trips in the viewer\'s zone', () => {
    const iso = '2026-10-03T17:45:00.000Z'
    expect(new Date(toLocalInput(iso)).toISOString()).toBe(iso)
  })

  test('the week starts on Monday', () => {
    expect(weekStartOf('2026-10-07')).toBe('2026-10-05') // Wednesday
    expect(weekStartOf('2026-10-05')).toBe('2026-10-05') // Monday itself
    expect(weekStartOf('2026-10-11')).toBe('2026-10-05') // Sunday belongs to the week before it ends
    expect(weekStartOf('2026-10-12')).toBe('2026-10-12')
  })

  test('minutes read as hours and minutes', () => {
    expect(formatMinutes(435)).toBe('7 h 15 min')
    expect(formatMinutes(480)).toBe('8 h')
    expect(formatMinutes(45)).toBe('45 min')
  })
})
