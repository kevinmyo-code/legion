import os from 'node:os'
import path from 'node:path'

import { expect, test, type Page } from '@playwright/test'

import { todayEpochDay } from '../src/lib/day'
import { createEngine, makePurchase, seedHousehold, type Engine } from '../src/test/engine'

/**
 * The bought log in a real Chromium (purchase-log 07): search from Home, log by
 * hand, private, and a Groceries tick then untick on the same day. The API is the
 * fake engine, answered through `page.route` the way `shots.spec.ts` does; no
 * server and no database.
 *
 *     npx playwright test e2e/bought.spec.ts
 *
 * Pictures go to `BOUGHT_SHOTS` (default: the OS temp dir), never into the repo.
 */

const OUT = process.env.BOUGHT_SHOTS ?? path.join(os.tmpdir(), 'legion-bought-shots')
const today = todayEpochDay()

function shelf(): Engine {
  return createEngine({
    ...seedHousehold(),
    householdName: 'The Test House',
    purchases: [
      makePurchase({ item: 'Shampoo', bought_on: today - 14, logged_by: 'Mia', source: 'GROCERIES_TICK' }),
      makePurchase({
        item: 'Head & Shoulders shampoo',
        bought_on: today - 56,
        logged_by: 'Kevin',
        logged_by_me: false,
        store: 'HEB',
        price_cents: 899,
        price_note: 'entered by hand; never checked against the bank or added into ledger figures',
      }),
      makePurchase({ item: 'Dove shampoo', bought_on: today - 112, logged_by: null, logged_by_me: false, source: 'GROCERIES_BACKFILL' }),
      makePurchase({ item: 'Contact lens solution', bought_on: today - 4, visibility: 'private', store: 'Target', price_cents: 949 }),
      makePurchase({ item: 'Eggs', bought_on: today - 7, logged_by: 'Kevin', logged_by_me: false, source: 'GROCERIES_TICK' }),
    ],
  })
}

async function useEngine(page: Page, engine: Engine) {
  await page.route('**/api/**', async (route) => {
    if (engine.down) {
      await route.abort('failed')
      return
    }
    const request = route.request()
    const url = new URL(request.url())
    const raw = request.postData()
    const reply = engine.handle(request.method(), url.pathname, url.searchParams, raw ? JSON.parse(raw) : undefined)
    await route.fulfill({
      status: reply.status,
      contentType: 'application/json',
      body: reply.body === undefined ? '' : JSON.stringify(reply.body),
    })
  })
}

async function shot(page: Page, name: string) {
  await page.evaluate(() => document.fonts.ready)
  await page.screenshot({ path: path.join(OUT, `${name}.png`), fullPage: true })
}

test.describe('Mia on her phone (390 px)', () => {
  test.use({ viewport: { width: 390, height: 844 } })

  test('searches from Home, logs by hand, and logs a private entry', async ({ page }) => {
    const engine = shelf()
    await useEngine(page, engine)
    await page.goto('/')
    await expect(page.getByRole('heading', { name: 'On today' })).toBeVisible()
    await shot(page, '390-home')

    await page.getByRole('link', { name: 'When did we last buy...?' }).click()
    await expect(page.getByRole('heading', { name: 'Recently bought' })).toBeVisible()
    await shot(page, '390-search-empty')

    await page.getByLabel('When did we last buy').fill('shampoo')
    await expect(page.getByText(/^Shampoo, bought .* by Mia$/)).toBeVisible()
    await expect(page.getByText('Old Groceries tick')).toBeVisible()
    await shot(page, '390-search-answer')

    await page.getByLabel('When did we last buy').fill('toothbrush')
    await expect(page.getByText('No record of buying toothbrush.')).toBeVisible()
    await shot(page, '390-search-no-record')

    await page.getByRole('link', { name: 'Log toothbrush' }).click()
    await expect(page.getByLabel('What was bought')).toHaveValue('toothbrush')
    await page.getByLabel(/^Price/).fill('3.49')
    await page.getByRole('switch', { name: 'Only me' }).click()
    await expect(page.getByText(/Only you can see this\./)).toBeVisible()
    await shot(page, '390-log-form')
    await page.getByRole('button', { name: 'Log it' }).click()

    await expect(page.getByText(/^toothbrush, bought/)).toBeVisible()
    await expect(page.getByText('Only you can see this')).toBeVisible()
    await expect(page.getByText('entered by hand')).toBeVisible()
    expect(engine.purchases.at(-1)).toMatchObject({ item: 'toothbrush', price_cents: 349, visibility: 'private' })
    await shot(page, '390-logged-private')
  })

  test('a server that cannot be reached keeps what was typed and logs nothing', async ({ page }) => {
    const engine = shelf()
    await useEngine(page, engine)
    await page.goto('/bought/log')
    await page.getByLabel('What was bought').fill('Light bulbs')
    engine.down = true
    await page.getByRole('button', { name: 'Log it' }).click()
    await expect(page.getByRole('alert')).toContainText("Can't reach the bought log right now. Nothing was logged.")
    await expect(page.getByLabel('What was bought')).toHaveValue('Light bulbs')
    expect(engine.purchases).toHaveLength(5)
    await shot(page, '390-log-unreachable')
  })

  test('a Groceries tick is bought, and unticking the same day takes it back', async ({ page }) => {
    const engine = shelf()
    await useEngine(page, engine)
    await page.goto('/lists')
    const groceries = page.locator('div.rounded-sheet', { has: page.getByRole('heading', { name: 'Groceries' }) })
    await expect(groceries.getByText(/^last bought .* · Kevin$/)).toHaveCount(0)

    await groceries.getByRole('checkbox', { name: 'Mark "Spinach" done' }).click()
    await expect.poll(() => engine.purchases.filter((entry) => entry.item === 'Spinach' && entry.deleted_at === null).length).toBe(1)
    await groceries.getByRole('button', { name: /Ticked 2/ }).click()
    await expect(groceries.getByText('last bought today · Mia')).toBeVisible()
    await shot(page, '390-groceries-ticked')

    await groceries.getByRole('checkbox', { name: 'Mark "Spinach" not done' }).click()
    await expect.poll(() => engine.purchases.filter((entry) => entry.item === 'Spinach' && entry.deleted_at === null).length).toBe(0)
    const untick = Object.keys(engine.searches).find((key) => key.startsWith('DELETE /api/checklists/') && key.includes('/tick/'))
    expect(new URLSearchParams(engine.searches[untick!]).get('today')).toBe(String(today))
  })
})

test.describe('Kevin at his desk (1280 px)', () => {
  test.use({ viewport: { width: 1280, height: 900 } })

  test('the table, the filters and the form beside it', async ({ page }) => {
    const engine = shelf()
    await useEngine(page, engine)
    await page.goto('/bought')
    await expect(page.getByRole('table')).toBeVisible()
    await shot(page, '1280-bought')

    await page.getByLabel('Search what was bought').fill('shampoo')
    await expect(page.getByText(/^Shampoo, bought .* by Mia$/)).toBeVisible()
    await shot(page, '1280-bought-search')

    await page.getByLabel('What was bought', { exact: true }).fill('Light bulbs')
    await page.getByRole('button', { name: 'Log it' }).click()
    await expect(page.getByText(/^Logged: Light bulbs/)).toBeVisible()
    await page.getByLabel('Search what was bought').fill('')
    await expect(page.getByRole('table').getByText('Light bulbs')).toBeVisible()
    await shot(page, '1280-bought-logged')
  })
})
