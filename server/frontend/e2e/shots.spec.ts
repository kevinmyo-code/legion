import path from 'node:path'

import { expect, test, type Page } from '@playwright/test'

import { createEngine, seedHousehold, type Engine } from '../src/test/engine'

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

interface Shot {
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
}

const seeded = () => createEngine({ ...seedHousehold(), householdName: 'The Test House' })

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

      for (const shot of ROUTES.filter((shot) => !shot.labels || shot.labels.includes(LABEL))) {
        test(shot.name, async ({ page }) => {
          const engine = (shot.engine ?? seeded)()
          await useEngine(page, engine)
          await page.goto(shot.url)
          await expect(page.getByText(shot.ready).first()).toBeVisible()
          await shot.after?.(page, engine)
          // Let the font, the check animation and the first paint settle.
          await page.evaluate(() => document.fonts.ready)
          await page.waitForTimeout(250)
          // The whole page, but taken the way a person would see it: grow the
          // window to the page's height first. `fullPage: true` stitches the page
          // together and draws a fixed tab bar halfway down it, which is a lie
          // about where the bar is.
          const height = await page.evaluate(() => document.documentElement.scrollHeight)
          await page.setViewportSize({ width: viewport.width, height: Math.max(viewport.height, height) })
          await page.waitForTimeout(100)
          await page.screenshot({
            path: path.join(OUT, `${shot.name}-${viewport.name}-${scheme}.png`),
          })
          expect(engine.unhandled).toEqual([])
        })
      }
    })
  }
}
