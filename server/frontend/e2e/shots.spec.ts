import path from 'node:path'

import { expect, test, type Page } from '@playwright/test'

import { seedAspects } from '../src/test/aspects-seed'
import { createEngine, seedHousehold, type Engine } from '../src/test/engine'
import { calendarShots } from './calendar-shots'
import { familyShots } from './family-shots'
import { notificationShots, settingsShots } from './settings-shots'
import { seedLedger, seedSpend } from '../src/test/ledger-seed'

/**
 * Screenshots of the real build against the fake engine: every route in `ROUTES`
 * at a phone and a desktop size, in light and in dark.
 *
 *     npm run shots                  # -> .scratch/web-revamp/research/shots/03/
 *     SHOTS_LABEL=02 npm run shots   # -> .scratch/web-revamp/research/shots/02/
 *
 * The label names the ticket the shots are evidence for. They are kept, not
 * golden: a person is meant to open them and look (MEMORY 2026-09-11, "inferred
 * from Tailwind classes, never seen"). A later ticket adds a route by adding a
 * row to `ROUTES`.
 */

const LABEL = process.env.SHOTS_LABEL ?? '03'
const OUT = path.resolve(import.meta.dirname, '../../../.scratch/web-revamp/research/shots', LABEL)

const VIEWPORTS = [
  { name: '390x844', width: 390, height: 844 },
  { name: '1440x900', width: 1440, height: 900 },
] as const

const SCHEMES = ['light', 'dark'] as const

export interface Shot {
  /** File name stem. */
  name: string
  url: string
  /** Text that proves the screen has finished loading, so the shot is not of a skeleton. */
  ready: string | RegExp
  /** Starts the engine in a particular state. */
  engine?: () => Engine
  /** Runs after the page is ready, to drive it into a state worth a picture. */
  after?: (page: Page, engine: Engine) => Promise<void>
  /** The ticket labels this shot belongs to; every label when absent. */
  labels?: string[]
  /**
   * A workbench-only screen. At phone width it is not rendered at all and the
   * "made for a bigger screen" card stands in: `'card'` takes that card's
   * picture once (the first shot of a route), `true` skips the phone size.
   */
  workbenchOnly?: 'card' | true
  /** A fixed overlay (a sheet) is pictured at the viewport's own size: growing the
   * window to the page's height would put a bottom sheet at the bottom of a
   * page-tall window, which is not where a person sees it. */
  viewportOnly?: true
  /** A phone-width screen with no wide counterpart: skipped at the desktop size. */
  familyOnly?: true
}

const seeded = () => createEngine({ ...seedHousehold(), householdName: 'The Test House' })

const CARD = 'This page is made for a bigger screen.'

/** The household plus the Pantry, Body, Fleet, Places and Notes tables. */
const withAspects = (tables = seedAspects()) =>
  createEngine({ ...seedHousehold(), householdName: 'The Test House', tables })

/** The household plus the ledger: transactions, categories, rules, targets, files and spend. */
const withLedger = (tables = seedLedger()) =>
  createEngine({ ...seedHousehold(), householdName: 'The Test House', tables, spend: seedSpend() })

/** Radix tabs switch on mousedown, which `click()` includes. */
const openTab = (name: string) => async (page: Page) => {
  await page.getByRole('tab', { name }).click()
}

const ROUTES: Shot[] = [
  {
    name: 'login',
    url: '/login',
    ready: 'Sign in',
    engine: () => createEngine({ signedIn: false }),
  },
  { name: 'home', url: '/', ready: 'Soccer pickup', engine: seeded },
  { name: 'lists', url: '/lists', ready: 'Oat milk', engine: seeded },
  // The states a screenshot of the happy path never shows (ticket 04).
  {
    name: 'home-unreachable',
    url: '/',
    ready: /Could not reach the engine, so this is not today's real list/,
    engine: () => {
      const engine = seeded()
      engine.changesFailing = true
      return engine
    },
    labels: ['04'],
  },
  {
    name: 'home-stale',
    url: '/',
    ready: 'Soccer pickup',
    engine: seeded,
    // The page loaded fine, then the next refresh fails. Becoming visible again is
    // what makes the app refetch, so fire exactly that.
    after: async (page, engine) => {
      engine.changesFailing = true
      await page.evaluate(() =>
        document.dispatchEvent(new Event('visibilitychange', { bubbles: true })),
      )
      await expect(page.getByText(/not what is there now/)).toBeVisible()
    },
    labels: ['04'],
  },

  // Ticket 16: Pantry and Body.
  { name: 'pantry', url: '/pantry', ready: 'Grocery staples', engine: withAspects, labels: ['16'], workbenchOnly: 'card' },
  {
    name: 'pantry-lines',
    url: '/pantry',
    ready: "Trader Joe's",
    engine: withAspects,
    after: async (page) => {
      await page.getByRole('button', { name: /Show lines for Costco/ }).click()
      await page.getByRole('button', { name: /Show lines for Trader/ }).click()
      await expect(page.getByText('Rotisserie chicken')).toBeVisible()
      await expect(page.getByText('Oat milk, 64 oz')).toBeVisible()
    },
    labels: ['16'],
    workbenchOnly: true,
  },
  {
    name: 'pantry-form',
    url: '/pantry',
    ready: 'Grocery staples',
    engine: withAspects,
    after: async (page) => {
      await page.getByRole('button', { name: /Add a staple/ }).click()
      await expect(page.getByRole('dialog')).toBeVisible()
    },
    labels: ['16'],
    workbenchOnly: true,
  },
  {
    name: 'pantry-empty',
    url: '/pantry',
    ready: /No receipts yet\./,
    engine: () => withAspects({}),
    labels: ['16'],
    workbenchOnly: true,
  },
  {
    name: 'pantry-unreachable',
    url: '/pantry',
    ready: /Could not reach the engine, so this is not the real list of receipts/,
    engine: () => {
      const engine = withAspects()
      engine.failingTables.add('pantry/receipts')
      return engine
    },
    labels: ['16'],
    workbenchOnly: true,
  },
  { name: 'body-weight', url: '/body', ready: 'Bodyweight', engine: withAspects, labels: ['16'], workbenchOnly: 'card' },
  {
    name: 'body-sleep',
    url: '/body',
    ready: 'Bodyweight',
    engine: withAspects,
    after: async (page) => {
      await openTab('Sleep')(page)
      await expect(page.getByText(/Hours slept on the last 14 logged nights\./)).toBeVisible()
    },
    labels: ['16'],
    workbenchOnly: true,
  },
  {
    name: 'body-meals',
    url: '/body',
    ready: 'Bodyweight',
    engine: withAspects,
    after: async (page) => {
      await openTab('Meals')(page)
      await expect(page.getByRole('meter', { name: 'Calories' })).toBeVisible()
    },
    labels: ['16'],
    workbenchOnly: true,
  },
  {
    name: 'body-workouts',
    url: '/body',
    ready: 'Bodyweight',
    engine: withAspects,
    after: async (page) => {
      await openTab('Workouts')(page)
      await expect(page.getByRole('meter', { name: 'Workout days' })).toBeVisible()
    },
    labels: ['16'],
    workbenchOnly: true,
  },
  {
    name: 'body-empty',
    url: '/body',
    ready: /No weight logged yet\./,
    engine: () => withAspects({}),
    labels: ['16'],
    workbenchOnly: true,
  },

  // Ticket 17: Fleet, Places and Notes.
  { name: 'fleet', url: '/fleet', ready: 'Service history', engine: withAspects, labels: ['17'], workbenchOnly: 'card' },
  {
    name: 'fleet-unkeyed',
    url: '/fleet',
    ready: 'Service history',
    engine: withAspects,
    after: async (page) => {
      await page.getByRole('button', { name: 'Project' }).click()
      await expect(page.getByText(/has no key on the engine yet/).first()).toBeVisible()
    },
    labels: ['17'],
    workbenchOnly: true,
  },
  {
    name: 'fleet-form',
    url: '/fleet',
    ready: 'Service history',
    engine: withAspects,
    after: async (page) => {
      await page.getByRole('button', { name: /Add a service/ }).click()
      await expect(page.getByRole('dialog')).toBeVisible()
    },
    labels: ['17'],
    workbenchOnly: true,
  },
  { name: 'places', url: '/places', ready: 'Tagged places', engine: withAspects, labels: ['17'], workbenchOnly: 'card' },
  {
    name: 'places-empty',
    url: '/places',
    ready: /No places yet\./,
    engine: () => withAspects({}),
    labels: ['17'],
    workbenchOnly: true,
  },
  { name: 'notes', url: '/notes', ready: 'Audio is not available on the web.', engine: withAspects, labels: ['17'], workbenchOnly: 'card' },
  {
    name: 'notes-unreachable',
    url: '/notes',
    ready: /Could not reach the engine, so this is not the real list of voice notes/,
    engine: () => {
      const engine = withAspects()
      engine.failingTables.add('voice_notes')
      return engine
    },
    labels: ['17'],
    workbenchOnly: true,
  },

  // Tickets 07, 09, 13: the calendar. Rows live in `calendar-shots.ts`.
  ...calendarShots,
  // Ticket 10: Mia's Home and the family Lists. Rows live in `family-shots.ts`.
  ...familyShots,
  // Tickets 05 and 15: join, signup and Settings. Rows live in `settings-shots.ts`.
  ...settingsShots,
  ...notificationShots,
  // Ticket 12: Money.
  { name: 'money', url: '/money', ready: 'Showing 15 of 15 transactions.', engine: withLedger, labels: ['12'], workbenchOnly: 'card' },
  {
    name: 'money-detail',
    url: '/money',
    ready: 'Showing 15 of 15 transactions.',
    engine: withLedger,
    after: async (page) => {
      await page.getByRole('button', { name: 'Details of Shell 5521' }).click()
      await expect(page.getByRole('complementary', { name: 'Selected transaction' })).toBeVisible()
    },
    labels: ['12'],
    workbenchOnly: true,
  },
  {
    name: 'money-combobox',
    url: '/money',
    ready: 'Showing 15 of 15 transactions.',
    engine: withLedger,
    after: async (page) => {
      await page.getByRole('button', { name: /^Category for Costco Wholesale:/ }).click()
      await expect(page.getByRole('listbox', { name: 'Categories' })).toBeVisible()
    },
    labels: ['12'],
    workbenchOnly: true,
  },
  {
    name: 'money-needs-category',
    url: '/money?need=true',
    ready: 'Showing 7 of 15 transactions.',
    engine: withLedger,
    labels: ['12'],
    workbenchOnly: true,
  },
  {
    name: 'money-loading',
    url: '/money',
    ready: /Still loading transactions: 5 so far/,
    engine: () => {
      const engine = withLedger()
      engine.pageSize = 5
      engine.delay = (_method, pathname, search) =>
        pathname === '/api/ledger/transactions/' && search.has('since') ? new Promise(() => {}) : undefined
      return engine
    },
    labels: ['12'],
    workbenchOnly: true,
  },
  {
    name: 'money-rollback',
    url: '/money',
    ready: 'Showing 15 of 15 transactions.',
    engine: () => {
      const engine = withLedger()
      engine.refusals['PUT /api/ledger/transaction_categories/*'] = {
        status: 400,
        body: { category: ['That category is not on the list.'] },
      }
      return engine
    },
    after: async (page) => {
      await page.getByRole('button', { name: /^Category for Shell 5521:/ }).click()
      await page.getByRole('listbox', { name: 'Categories' }).getByRole('option', { name: 'Fuel' }).click()
      await expect(page.getByRole('alert')).toContainText('is back to no category')
    },
    labels: ['12'],
    workbenchOnly: true,
  },
  {
    name: 'money-budgets',
    url: '/money?tab=budgets',
    ready: 'Left out of the lines above',
    engine: withLedger,
    labels: ['12'],
    workbenchOnly: true,
  },
  {
    name: 'money-budget-edit',
    url: '/money?tab=budgets',
    ready: 'Left out of the lines above',
    engine: withLedger,
    after: async (page) => {
      await page.getByRole('button', { name: 'Edit target for Groceries' }).click()
      await expect(page.getByLabel(/Monthly target for Groceries/)).toBeVisible()
    },
    labels: ['12'],
    workbenchOnly: true,
  },
  { name: 'money-categories', url: '/money?tab=categories', ready: 'Counts as spending', engine: withLedger, labels: ['12'], workbenchOnly: true },
  { name: 'money-rules', url: '/money?tab=rules', ready: 'TRADER JOE', engine: withLedger, labels: ['12'], workbenchOnly: true },
  { name: 'money-files', url: '/money?tab=files', ready: 'BofA_card_Sep.csv', engine: withLedger, labels: ['12'], workbenchOnly: true },
  {
    name: 'money-empty',
    url: '/money',
    ready: /No transactions yet\./,
    engine: () => withLedger({}),
    labels: ['12'],
    workbenchOnly: true,
  },
  {
    name: 'money-unreachable',
    url: '/money',
    ready: /Could not reach the engine, so this is not the real list of transactions/,
    engine: () => {
      const engine = withLedger()
      engine.failingTables.add('ledger/transactions')
      return engine
    },
    labels: ['12'],
    workbenchOnly: true,
  },
  { name: 'home-money', url: '/', ready: '7 transactions need a category', engine: withLedger, labels: ['12', '10'], workbenchOnly: true },
]

/** Answer every `/api` call from the fake engine. */
async function useEngine(page: Page, engine: Engine) {
  await page.route('**/api/**', async (route) => {
    if (engine.down) {
      await route.abort('failed')
      return
    }
    const request = route.request()
    const url = new URL(request.url())
    await engine.delay?.(request.method(), url.pathname, url.searchParams)
    const raw = request.postData()
    const reply = engine.handle(
      request.method(),
      url.pathname,
      url.searchParams,
      raw ? JSON.parse(raw) : undefined,
    )
    await route.fulfill({
      status: reply.status,
      contentType: 'application/json',
      body: reply.body === undefined ? '' : JSON.stringify(reply.body),
    })
  })
}

for (const viewport of VIEWPORTS) {
  for (const scheme of SCHEMES) {
    test.describe(`${viewport.name} ${scheme}`, () => {
      test.use({
        viewport: { width: viewport.width, height: viewport.height },
        colorScheme: scheme,
      })

      const narrow = viewport.width < 1024
      const shots = ROUTES.filter((shot) => !shot.labels || shot.labels.includes(LABEL))
        // A workbench-only screen has no phone-size picture except the card.
        .filter((shot) => !(narrow && shot.workbenchOnly === true))
        // A phone-only screen has no desktop picture.
        .filter((shot) => !(!narrow && shot.familyOnly))
      for (const shot of shots) {
        test(shot.name, async ({ page }) => {
          const engine = (shot.engine ?? seeded)()
          await useEngine(page, engine)
          await page.goto(shot.url)
          const card = narrow && shot.workbenchOnly === 'card'
          await expect(page.getByText(card ? CARD : shot.ready).first()).toBeVisible()
          if (!card) await shot.after?.(page, engine)
          // Let the font, the check animation and the first paint settle.
          await page.evaluate(() => document.fonts.ready)
          await page.waitForTimeout(250)
          // The whole page, but taken the way a person would see it: grow the
          // window to the page's height first. `fullPage: true` stitches the page
          // together and draws a fixed tab bar halfway down it, which is a lie
          // about where the bar is.
          if (!shot.viewportOnly) {
            const height = await page.evaluate(() => document.documentElement.scrollHeight)
            await page.setViewportSize({ width: viewport.width, height: Math.max(viewport.height, height) })
            await page.waitForTimeout(100)
          }
          await page.screenshot({
            path: path.join(OUT, `${shot.name}-${viewport.name}-${scheme}.png`),
          })
          expect(engine.unhandled).toEqual([])
        })
      }
    })
  }
}
