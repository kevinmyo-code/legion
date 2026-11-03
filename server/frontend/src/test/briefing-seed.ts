import type { Spend } from '../api/ledger'
import type { Checklist, ChecklistItem, ChecklistTick, Event } from '../api/types'
import { todayEpochDay } from '../lib/day'
import { seedAspects } from './aspects-seed'
import { createEngine, emptySpend, makeChecklist, makeEvent, makeItem, ME, type Engine, type EngineOptions } from './engine'
import type { Row } from './engine-tables'
import { makeTxn } from './ledger-seed'

/**
 * A household for the Morning briefing (recruiter-demo ticket 14): one of
 * everything the page reads, in the CURRENT month and relative to now, so the
 * page always has something next, something due and a month to draw.
 *
 * - The calendar: a dentist 42 minutes from now, three more things later today,
 *   one tomorrow, a task due today and one that slipped two days ago.
 * - The car: an XJ whose oil change (a seeded, unconfirmed interval) comes due
 *   in 320 miles of the reading taken two days ago.
 * - Money: categorised rows through the month, three card charges nobody has
 *   categorised (unverified), a checking statement, a savings statement, and a
 *   card balance from the bank's own feed.
 * - Lists: Groceries with two of five ticked, and two other plain lists.
 */

const pad = (n: number) => String(n).padStart(2, '0')
const dayOf = (date: Date) => `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}`

/** An instant `minutes` from now. */
function fromNow(minutes: number): string {
  return new Date(Date.now() + minutes * 60_000).toISOString()
}

function daysAgo(days: number, hour = 12): Date {
  const d = new Date()
  d.setDate(d.getDate() - days)
  d.setHours(hour, 0, 0, 0)
  return d
}

let counter = 0
const uuid = () => {
  counter += 1
  return `40000000-0000-4000-8000-${counter.toString(16).padStart(12, '0')}`
}

export function briefingCalendar(): Pick<EngineOptions, 'events' | 'checklists' | 'items' | 'ticks'> {
  const groceries = makeChecklist({ name: 'Groceries', sort_order: 0, system_key: 'groceries' })
  const hardware = makeChecklist({ name: 'Hardware store', sort_order: 1 })
  const packing = makeChecklist({ name: 'Thanksgiving packing', sort_order: 2 })
  const grocery = ['Eggs, a dozen', 'Spinach', 'Greek yoghurt', 'Coffee filters', 'Lemons'].map((text, index) =>
    makeItem(groceries, text, { sort_order: index }),
  )
  const others: ChecklistItem[] = [
    ...['Picture hooks', 'Wood glue', 'Sandpaper'].map((text, i) => makeItem(hardware, text, { sort_order: i })),
    ...['Chargers', 'Board games'].map((text, i) => makeItem(packing, text, { sort_order: i })),
  ]
  const tick = (item: ChecklistItem): ChecklistTick => ({
    id: uuid(),
    item: item.id,
    day: todayEpochDay(),
    ticked_at: '2026-01-01T00:00:00Z',
    updated_at: '2026-01-01T00:00:00Z',
    deleted_at: null,
    sync_id: null,
    value: null,
    source: 'USER_REPORTED',
  })
  const tomorrow = new Date()
  tomorrow.setDate(tomorrow.getDate() + 1)
  tomorrow.setHours(7, 0, 0, 0)
  const events: Event[] = [
    makeEvent({ title: 'Dentist, Mia', starts_at: fromNow(42), ends_at: fromNow(87), location: 'Elm Street Dental' }),
    makeEvent({ title: 'Team review call', starts_at: fromNow(150), ends_at: fromNow(195), location: 'Online', visibility: 'private' }),
    makeEvent({ title: "Dinner at Mia's parents", starts_at: fromNow(240), ends_at: fromNow(330) }),
    makeEvent({ title: 'Pick up dry cleaning', kind: 'task', starts_at: fromNow(200) }),
    makeEvent({ title: 'Renew the car registration', kind: 'task', starts_at: daysAgo(2, 18).toISOString() }),
    makeEvent({ title: 'Bins out', starts_at: tomorrow.toISOString() }),
  ]
  return {
    events,
    checklists: [groceries, hardware, packing] as Checklist[],
    items: [...grocery, ...others],
    ticks: [tick(grocery[0]), tick(grocery[1])],
  }
}

/** The ledger rows for this month, plus statements; the card balance comes from the bank feed. */
export function briefingLedger(): Record<string, Row[]> {
  const now = new Date()
  const month = dayOf(now).slice(0, 7)
  const rows: Row[] = []
  const day = (n: number) => `${month}-${pad(Math.min(n, now.getDate()))}`
  // A spread of categorised spending through the month so far.
  const spends: [number, string, number, string][] = [
    [1, 'Oakwood Apts rent', -120000, 'Housing'],
    [1, "Trader Joe's #552", -6422, 'Groceries'],
    [1, 'City Water & Power', -6480, 'Utilities'],
    [2, 'Blue Door Coffee', -650, 'Fun'],
    [2, 'Corner Market', -3120, 'Groceries'],
  ]
  for (const [n, description, cents, category] of spends) {
    rows.push(makeTxn({ txn_date: day(n), description, amount_cents: cents, account_last4: '5521', stored: category }))
  }
  // Three card charges nobody has categorised, not on a statement yet.
  for (const [description, cents] of [['Fuel stop 118', -4210], ['Corner Grocer', -1460], ['Streaming service', -750]] as const) {
    rows.push(makeTxn({ txn_date: day(now.getDate()), description, amount_cents: cents, account_last4: '7823' }))
  }
  // Last month's checking row carrying a balance; the card's from the bank feed.
  rows.push(
    makeTxn({
      txn_date: day(now.getDate()),
      description: 'Card payment',
      amount_cents: -2000,
      account_last4: '7823',
      provenance: 'BANK_API',
      verification_note: null,
      balance_cents: -132718,
      stored: 'Transfers',
    }),
  )
  const statement = (last4: string, nickname: string, end: Date, closing: number): Row => ({
    id: uuid(),
    ingested_file_id: uuid(),
    account_last4: last4,
    account_nickname: nickname,
    currency: 'USD',
    period_start: dayOf(new Date(end.getFullYear(), end.getMonth(), 1)),
    period_end: dayOf(end),
    stated_total_cents: null,
    opening_balance_cents: closing - 10000,
    closing_balance_cents: closing,
    provenance: 'DETERMINISTIC',
    created_at: end.toISOString(),
  })
  const lastMonthEnd = new Date(now.getFullYear(), now.getMonth(), 0)
  return {
    'ledger/transactions': rows,
    'ledger/statements': [
      statement('5521', 'Checking', lastMonthEnd, 481240),
      statement('3301', 'Savings', lastMonthEnd, 1205000),
    ],
    'ledger/categories': [],
    'ledger/category_rules': [],
    'ledger/budget_targets': [],
    'ledger/transaction_categories': [],
    'ingest/files': [],
  }
}

/** The engine's spend for this month, matching the rows above. */
export function briefingSpend(): Spend {
  const month = dayOf(new Date()).slice(0, 7)
  return {
    ...emptySpend(month),
    accounts: [
      { account_last4: '5521', label: 'Checking', spend_cents: 136672, unverified: false, unverified_cents: 0, latest_row_at: new Date().toISOString() },
      { account_last4: '7823', label: 'Credit card', spend_cents: 0, unverified: false, unverified_cents: 0, latest_row_at: new Date().toISOString() },
    ],
    categories: [
      { category: 'Housing', spend_cents: 120000, target_cents: 120000, unverified: false },
      { category: 'Groceries', spend_cents: 9542, target_cents: 60000, unverified: false },
      { category: 'Utilities', spend_cents: 6480, target_cents: 20000, unverified: false },
      { category: 'Fun', spend_cents: 650, target_cents: 40000, unverified: false },
    ],
    uncategorised_cents: 6420,
    uncategorised_unverified: true,
    excluded: { ...emptySpend(month).excluded, not_spending_categories: ['Transfers'] },
  }
}

/** The car: the oil change on a seeded 3,000-mile interval comes due in 320 miles. */
export function briefingFleet(): Record<string, Row[]> {
  const stamp = daysAgo(200).toISOString()
  const base = { provenance: 'USER', created_at: stamp, updated_at: stamp, deleted_at: null }
  const xj = {
    ...base,
    id: uuid(),
    name: 'XJ',
    make: 'Jeep',
    model: 'Cherokee',
    year: 1999,
    confirmed: true,
    odometer_baseline: 212480,
    odometer_baseline_at: daysAgo(2).toISOString(),
    origin_guid: uuid(),
    archived: false,
  }
  return {
    'fleet/vehicles': [xj],
    'fleet/service_history': [
      { ...base, id: uuid(), vehicle_id: xj.id, service_name: 'Oil change', mileage: 209480, service_date: dayOf(daysAgo(90)), kind: 'OBSERVED', origin_guid: uuid() },
    ],
    'fleet/maintenance_schedules': [
      { ...base, id: uuid(), vehicle_id: xj.id, service_name: 'Oil change', interval_miles: 3320, interval_months: null, interval_source: 'SEEDED', never_done: false },
    ],
    'fleet/drives': [],
  }
}

/** A member's engine for the briefing. */
export function briefingEngine(overrides: EngineOptions = {}): Engine {
  const aspects = seedAspects()
  const engine = createEngine({
    ...briefingCalendar(),
    householdName: 'The Wins',
    members: [{ role: 'owner', user_id: ME.user_id, email: ME.email, name: 'Kevin', joined_at: '2026-09-01T00:00:00Z' }],
    tables: { ...aspects, ...briefingFleet(), ...briefingLedger() },
    spend: briefingSpend(),
    ...overrides,
  })
  engine.settings.bank = {
    ...engine.settings.bank,
    connected: true,
    institution_name: 'Bank of America',
    needs_sign_in: true,
    sentence: 'No new card transactions since Thursday. Nothing was guessed to fill the gap.',
  }
  return engine
}

/** A recruiter's demo visit on the same household (ticket 13). */
export function briefingVisit(): Engine {
  const engine = briefingEngine({ signedIn: false })
  engine.demo.session = { role: 'recruiter', expires_at: new Date(Date.now() + 2 * 3_600_000).toISOString(), ref: 'acme' }
  return engine
}
