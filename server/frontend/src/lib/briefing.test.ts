import { describe, expect, test } from 'vitest'

import type { LedgerTransaction, Statement } from '@/api/ledger'
import type { MaintenanceSchedule, ServiceHistory, Vehicle } from '@/api/aspects'
import {
  closingLine,
  countWords,
  daySentence,
  daysLeftInMonth,
  latestBalances,
  latestOdometer,
  needCategory,
  pickNextUp,
  untilWords,
  runningTotal,
  servicesDue,
} from '@/lib/briefing'

const MONDAY = new Date(2026, 10, 2, 7, 30) // Monday 2 November 2026, 7:30 local

describe('daySentence', () => {
  test('states the day, the count and the first time, and nothing else', () => {
    const sentence = daySentence({
      now: MONDAY,
      calendar: { count: 4, firstTime: '9:00 AM' },
      worth: { count: 3, complete: true },
    })
    expect(sentence).toBe(
      'It is Monday 2 November. Four things on the calendar today, the first at 9:00 AM. Three things want a look.',
    )
  })

  test('an empty day and an unreadable day are different sentences', () => {
    const empty = daySentence({ now: MONDAY, calendar: { count: 0, firstTime: null }, worth: null })
    const unreadable = daySentence({ now: MONDAY, calendar: null, worth: null })
    expect(empty).toContain('Nothing on the calendar today.')
    expect(unreadable).toContain('is not known')
    expect(unreadable).not.toContain('Nothing on the calendar')
  })

  test('never invents: no words beyond the facts it was handed', () => {
    const sentence = daySentence({
      now: MONDAY,
      calendar: { count: 1, firstTime: '6:00 PM' },
      worth: { count: 0, complete: true },
    })
    // Every number in the sentence is one it was given (the date, the time, the count).
    const numbers = sentence.match(/\d+/g) ?? []
    expect(numbers).toEqual(['2', '6', '00'])
    expect(sentence).not.toMatch(/urgent|busy|quiet|free|haven't|streak|again/i)
    expect(sentence).toBe('It is Monday 2 November. One thing on the calendar today, at 6:00 PM. Nothing wants a look.')
  })

  test('a partial count of things to look at says "at least"; an unknown one says nothing', () => {
    const partial = daySentence({ now: MONDAY, calendar: { count: 0, firstTime: null }, worth: { count: 2, complete: false } })
    expect(partial).toContain('At least 2 things want a look.')
    const unknown = daySentence({ now: MONDAY, calendar: { count: 0, firstTime: null }, worth: { count: 0, complete: false } })
    expect(unknown).not.toMatch(/look/)
  })

  test('an all-day-only calendar gives no first time', () => {
    expect(daySentence({ now: MONDAY, calendar: { count: 2, firstTime: null }, worth: null })).toContain(
      'Two things on the calendar today, all day.',
    )
  })

  test('countWords spells small counts and writes large ones', () => {
    expect(countWords(1, 'thing')).toBe('One thing')
    expect(countWords(12, 'thing')).toBe('12 things')
  })
})

// ---- Service due ------------------------------------------------------------

const base = { provenance: 'USER' as const, created_at: '2026-01-01T00:00:00Z', updated_at: '2026-01-01T00:00:00Z', deleted_at: null }

function vehicle(overrides: Partial<Vehicle> = {}): Vehicle {
  return {
    ...base,
    id: 'v1',
    name: 'XJ',
    make: 'Jeep',
    model: 'Cherokee',
    year: 1999,
    confirmed: true,
    archived: false,
    odometer_baseline: null,
    odometer_baseline_at: null,
    ...overrides,
  } as Vehicle
}

function done(name: string, day: string, mileage: number | null): ServiceHistory {
  return { ...base, id: `${name}-${day}`, vehicle_id: 'v1', service_name: name, mileage, service_date: day, kind: 'OBSERVED' } as ServiceHistory
}

function schedule(name: string, miles: number | null, months: number | null, source = 'CONFIRMED'): MaintenanceSchedule {
  return { ...base, id: name, vehicle_id: 'v1', service_name: name, interval_miles: miles, interval_months: months, interval_source: source, never_done: false } as MaintenanceSchedule
}

describe('servicesDue', () => {
  test('a mileage service within 500 miles of the newest reading is due, and a seeded interval is an estimate', () => {
    const car = vehicle({ odometer_baseline: 212480, odometer_baseline_at: '2026-10-31T12:00:00Z' })
    const check = servicesDue([car], [schedule('Oil change', 3000, null, 'SEEDED')], [done('Oil change', '2026-06-01', 209800)], '2026-11-02')
    expect(check.due).toHaveLength(1)
    expect(check.due[0]).toMatchObject({ vehicle: 'XJ', service: 'Oil change', dueAtMiles: 212800, overdue: false, estimate: true })
    expect(check.due[0].odometer).toMatchObject({ miles: 212480, source: 'odometer reading' })
  })

  test('a date service past its day is overdue', () => {
    const check = servicesDue([vehicle()], [schedule('Brake fluid', null, 24)], [done('Brake fluid', '2024-10-01', null)], '2026-11-02')
    expect(check.due[0]).toMatchObject({ service: 'Brake fluid', dueOn: '2026-10-01', overdue: true, estimate: false })
  })

  test('nothing due is an empty list, a service never done is not guessed, no odometer is said', () => {
    const car = vehicle({ odometer_baseline: 1000, odometer_baseline_at: '2026-10-31T12:00:00Z' })
    expect(servicesDue([car], [schedule('Oil change', 3000, null)], [done('Oil change', '2026-10-01', 900)], '2026-11-02').due).toEqual([])
    expect(servicesDue([car], [schedule('Coolant', 30000, null)], [], '2026-11-02').due).toEqual([])
    const blind = servicesDue([vehicle()], [schedule('Oil change', 3000, null)], [done('Oil change', '2026-10-01', 900)], '2026-11-02')
    // The record itself carries a mileage, so that is the newest reading.
    expect(blind.uncheckable).toEqual([])
    expect(servicesDue([], [], [], '2026-11-02')).toEqual({ due: [], uncheckable: [], hasSchedules: false })
  })

  test('latestOdometer takes the highest reading from either source', () => {
    const car = vehicle({ odometer_baseline: 500, odometer_baseline_at: '2026-01-01T12:00:00Z' })
    expect(latestOdometer(car, [done('Oil', '2026-05-01', 900)])).toMatchObject({ miles: 900, source: 'service record' })
    expect(latestOdometer(vehicle(), [])).toBeNull()
  })
})

// ---- Money ------------------------------------------------------------------

function txn(overrides: Partial<LedgerTransaction>): LedgerTransaction {
  return {
    id: crypto.randomUUID(),
    statement_id: null,
    account_last4: '7823',
    account_nickname: 'Card',
    currency: 'USD',
    txn_date: '2026-11-01',
    description: 'Shop',
    display_description: null,
    amount_cents: -1000,
    balance_cents: null,
    line_ref: '',
    category: 'Groceries',
    category_pending: false,
    stored_category: 'Groceries',
    category_source: 'stored',
    pending_logged_at: null,
    pending: false,
    reversal_of: null,
    provenance: 'DETERMINISTIC',
    verification_note: null,
    created_at: '2026-11-01T12:00:00Z',
    origin_guid: null,
    ...overrides,
  } as LedgerTransaction
}

describe('runningTotal', () => {
  test('sums categorised outflows by day, leaves out not-spending, income and uncategorised', () => {
    const rows = [
      txn({ txn_date: '2026-11-01', amount_cents: -1000 }),
      txn({ txn_date: '2026-11-03', amount_cents: -500, provenance: 'UNRECONCILED' }),
      txn({ txn_date: '2026-11-02', amount_cents: -9999, category: 'Transfers' }),
      txn({ txn_date: '2026-11-02', amount_cents: 5000 }),
      txn({ txn_date: '2026-11-02', amount_cents: -700, category: null }),
      txn({ txn_date: '2026-10-31', amount_cents: -300 }),
    ]
    const points = runningTotal(rows, '2026-11', 'USD', ['Transfers'], '2026-11-03')
    expect(points.map((p) => p.cents)).toEqual([1000, 1000, 1500])
    expect(points.map((p) => p.unverified)).toEqual([false, false, true])
  })

  test('a past month runs to its last day; a future one has no points', () => {
    expect(runningTotal([], '2026-10', 'USD', [], '2026-11-02')).toHaveLength(31)
    expect(runningTotal([], '2026-12', 'USD', [], '2026-11-02')).toHaveLength(0)
  })

  test('daysLeftInMonth excludes today', () => {
    expect(daysLeftInMonth('2026-11-02')).toBe(28)
    expect(daysLeftInMonth('2026-11-30')).toBe(0)
  })
})

describe('latestBalances', () => {
  const statement = (overrides: Partial<Statement>): Statement =>
    ({
      id: 's',
      ingested_file_id: 'f',
      account_last4: '5521',
      account_nickname: 'Checking',
      currency: 'USD',
      period_start: '2026-10-01',
      period_end: '2026-10-31',
      stated_total_cents: null,
      opening_balance_cents: 0,
      closing_balance_cents: 481240,
      provenance: 'DETERMINISTIC',
      created_at: '2026-11-01T00:00:00Z',
      ...overrides,
    }) as Statement

  test('a statement closing balance is Verified and says its date', () => {
    expect(latestBalances([statement({})], [])).toEqual([
      { account: '5521', label: 'Checking', cents: 481240, currency: 'USD', word: 'Verified', detail: 'Statement to Oct 31' },
    ])
  })

  test('a newer unchecked row wins and is Unverified; the bank feed is Verified', () => {
    const rows = [
      txn({ account_last4: '5521', txn_date: '2026-11-01', balance_cents: 470000, provenance: 'UNRECONCILED' }),
      txn({ account_last4: '9000', account_nickname: 'Credit card', txn_date: '2026-11-01', balance_cents: -132718, provenance: 'BANK_API' }),
    ]
    const balances = latestBalances([statement({})], rows)
    expect(balances.find((b) => b.account === '5521')).toMatchObject({ cents: 470000, word: 'Unverified', label: 'Checking' })
    expect(balances.find((b) => b.account === '9000')).toMatchObject({ word: 'Verified', detail: 'From the bank link, Nov 1' })
  })

  test('no balance anywhere is no row, never $0', () => {
    expect(latestBalances([], [txn({})])).toEqual([])
  })
})

describe('needCategory', () => {
  test('counts rows with no category, their total and whether any is unverified', () => {
    const result = needCategory([
      txn({ category: null, amount_cents: -2000, description: 'Fuel stop', provenance: 'UNRECONCILED' }),
      txn({ category: null, amount_cents: -420, description: 'Corner Grocer' }),
      txn({}),
    ])
    expect(result).toMatchObject({ count: 2, cents: 2420, unverified: true, names: ['Fuel stop', 'Corner Grocer'] })
  })
})

describe('closingLine', () => {
  test('bounds itself with the next due day, in words', () => {
    expect(closingLine(MONDAY, new Date(2026, 10, 5), true)).toBe(
      'That is everything for this morning. Nothing else is due before Thursday.',
    )
    expect(closingLine(MONDAY, null, true)).toBe(
      'That is everything for this morning. Nothing else is due in the next two weeks.',
    )
    expect(closingLine(new Date(2026, 10, 2, 19), null, false)).toBe('That is everything for this evening.')
  })
})

describe('pickNextUp', () => {
  const at = (h: number, m = 0) => new Date(2026, 10, 2, h, m).toISOString()
  const row = (start: number, end: number | null, extra: Partial<{ all_day: boolean; done: boolean; kind: string }> = {}) => ({
    startsAt: at(start),
    endsAt: end === null ? null : at(end),
    event: { ...extra },
  })

  test('the next timed row that has not ended leads; past rows drop; all-day rows stay beside it', () => {
    const past = row(7, 8)
    const allDay = row(0, null, { all_day: true })
    const dentist = row(9, 10)
    const dinner = row(19, 21)
    const { next, rest } = pickNextUp([allDay, past, dentist, dinner], new Date(2026, 10, 2, 8, 18))
    expect(next).toBe(dentist)
    expect(rest).toEqual([allDay, dinner])
  })

  test('something under way is still next; a done task is not', () => {
    const doneTask = row(8, null, { kind: 'task', done: true })
    const meeting = row(8, 9)
    expect(pickNextUp([doneTask, meeting], new Date(2026, 10, 2, 8, 30)).next).toBe(meeting)
  })

  test('an empty or finished day has no next', () => {
    expect(pickNextUp([], new Date()).next).toBeNull()
    expect(pickNextUp([row(7, 8)], new Date(2026, 10, 2, 9)).next).toBeNull()
  })

  test('untilWords counts from the clock', () => {
    const now = new Date(2026, 10, 2, 8, 18)
    expect(untilWords(at(9), now)).toBe('in 42 minutes')
    expect(untilWords(at(11, 23), now)).toBe('in 3 h 5 min')
    expect(untilWords(at(8), now)).toBe('now')
  })
})

describe('niceTicks', () => {
  test('round steps from zero that reach past the top', async () => {
    const { niceTicks } = await import('@/components/briefing/running-total-chart')
    expect(niceTicks(252000)).toEqual([0, 100000, 200000, 300000])
    expect(niceTicks(340000)).toEqual([0, 100000, 200000, 300000, 400000])
    expect(niceTicks(0)).toEqual([0])
  })
})
