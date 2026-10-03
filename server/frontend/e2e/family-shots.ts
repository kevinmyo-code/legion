import { expect, type Page } from '@playwright/test'

import type { Shot } from './shots.spec'
import { seedCalendar } from '../src/test/calendar-seed'
import { ME, createEngine, makeEvent, todayAt } from '../src/test/engine'
import { seedSpend } from '../src/test/ledger-seed'

/**
 * Ticket 10: Mia's Home and the family Lists screen, at phone width only
 * (`familyOnly`). The workbench Home is pictured by the existing `home` and
 * `home-money` rows, so the same label proves it did not move.
 *
 * Kept out of `shots.spec.ts` so a ticket building another screen in parallel
 * does not collide with these rows.
 */

const PINS_KEY = 'legion.pins.v1'

/**
 * Mia's engine: the household, a fortnight of calendar, and what the two accounts
 * spent. The engine serves a member only the rows they may see (ADR 0052), so
 * Kevin's Canvas deadlines and Google class schedule are not in HER response and
 * are left out here: her private rows are her own (Pottery class).
 */
const mia = () => {
  const calendar = seedCalendar()
  return {
    ...calendar,
    events: calendar.events?.filter(
      (event) => !(event.origin_guid ?? '').startsWith('canvas:') && event.source !== 'google',
    ),
    spend: seedSpend(),
    householdName: 'The Test House',
    members: [
      { role: 'member', user_id: ME.user_id, email: ME.email, name: 'Mia', joined_at: '2026-09-01T00:00:00Z' },
    ],
  }
}

/** Pin Groceries on this device, then reload so Home reads it. */
const pinGroceries = (engine: ReturnType<typeof createEngine>) => async (page: Page) => {
  const id = engine.checklists.find((list) => list.name === 'Groceries')!.id
  await page.evaluate(
    ([key, value]) => window.localStorage.setItem(key, value),
    [PINS_KEY, JSON.stringify([id])] as const,
  )
  await page.reload()
  await expect(page.getByRole('heading', { name: 'Pinned lists' })).toBeVisible()
}

function miaEngine() {
  return createEngine(mia())
}

/** Three tasks past their date and two more, to show the cap and "and N more". */
function withOverdue() {
  const engine = miaEngine()
  for (const [n, title] of ['Renew the passports', 'Renew the car registration', 'Book the vet', 'Send the form', 'Call the school'].entries()) {
    engine.events.push(makeEvent({ title, kind: 'task', starts_at: todayAt(18, 0, -(n + 1)) }))
  }
  return engine
}

export const familyShots: Shot[] = [
  {
    name: 'family-home',
    url: '/',
    ready: '$642.55',
    engine: miaEngine,
    labels: ['10'],
    familyOnly: true,
  },
  {
    name: 'family-home-pinned',
    url: '/',
    ready: '$642.55',
    engine: miaEngine,
    after: async (page, engine) => pinGroceries(engine)(page),
    labels: ['10'],
    familyOnly: true,
  },
  {
    name: 'family-home-overdue',
    url: '/',
    ready: '$642.55',
    engine: withOverdue,
    labels: ['10'],
    familyOnly: true,
  },
  {
    name: 'family-home-spend-sheet',
    url: '/',
    ready: '$642.55',
    engine: miaEngine,
    after: async (page) => {
      await page.getByRole('button', { name: /By category/ }).click()
      await expect(page.getByRole('dialog', { name: 'Spent this month' })).toBeVisible()
    },
    labels: ['10'],
    familyOnly: true,
    viewportOnly: true,
  },
  {
    name: 'family-home-empty',
    url: '/',
    ready: 'No card activity has reached the engine this month yet.',
    engine: () => createEngine({ householdName: 'The Test House' }),
    labels: ['10'],
    familyOnly: true,
  },
  {
    name: 'family-home-unreachable',
    url: '/',
    ready: /Could not reach the engine, so what is on today is not known\./,
    engine: () => {
      const engine = miaEngine()
      engine.changesFailing = true
      engine.refusals['GET /api/ledger/spend'] = { status: 503, body: { detail: 'down' } }
      return engine
    },
    labels: ['10'],
    familyOnly: true,
  },
  {
    name: 'family-home-stale',
    url: '/',
    ready: '$642.55',
    engine: miaEngine,
    // The page loaded fine, then the next refresh fails. Becoming visible again is
    // what makes the app refetch, so fire exactly that.
    after: async (page, engine) => {
      engine.changesFailing = true
      engine.refusals['GET /api/ledger/spend'] = { status: 503, body: { detail: 'down' } }
      await page.evaluate(() =>
        document.dispatchEvent(new Event('visibilitychange', { bubbles: true })),
      )
      await expect(page.getByText(/What is on today was last read/)).toBeVisible()
      await expect(page.getByText(/These figures were last read/)).toBeVisible()
    },
    labels: ['10'],
    familyOnly: true,
  },
  {
    name: 'family-lists',
    url: '/lists',
    ready: 'Oat milk',
    engine: miaEngine,
    labels: ['10'],
    familyOnly: true,
  },
  {
    name: 'family-lists-ticked-open',
    url: '/lists',
    ready: 'Oat milk',
    engine: miaEngine,
    after: async (page) => {
      // Tick one, so the section reads "Ticked 2", then open it.
      await page.getByRole('checkbox', { name: 'Mark "Oat milk" done' }).click()
      await page.getByRole('button', { name: /Ticked 2/ }).click()
      await expect(page.getByRole('checkbox', { name: 'Mark "Oat milk" not done' })).toBeVisible()
    },
    labels: ['10'],
    familyOnly: true,
  },
]
