import path from 'node:path'

import { expect, test, type Page } from '@playwright/test'

import { CAR_CARD, DAY_CARD, LIST_CARD, SPEND_CARD, STEPS_CARD } from '../src/test/cards-seed'
import { createEngine, seedHousehold, type Engine } from '../src/test/engine'

/**
 * Tool cards, for LOOKING (Kevin, 2026-11-04: generative UI). The recruiter
 * dock's voice stage at desk width, the chat unfolded, the phone sheet, and the
 * member chat. Fake engine (its tools answer with cards), scripted Google
 * socket, fake microphone; nothing calls Google.
 *
 *     CARD_SHOTS=<dir> npx playwright test e2e/cards.spec.ts
 */

const OUT = process.env.CARD_SHOTS ?? path.join(process.cwd(), 'test-results', 'cards')

const TOOLS = {
  monthly_spend: { text: 'Categorised spend.', card: SPEND_CARD },
  list_events: { text: 'Events.', card: DAY_CARD },
  run_procedure: { text: 'The car.', card: CAR_CARD },
  money_steps: { text: 'Steps.', card: STEPS_CARD },
  list_checklists: { text: 'Lists.', card: LIST_CARD },
}

/** The conversation record goes up as something other than JSON; the fake engine ignores it. */
function parseBody(raw: string): unknown {
  try {
    return JSON.parse(raw)
  } catch {
    return undefined
  }
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
    let reply: { status: number; body?: unknown }
    try {
      reply = engine.handle(request.method(), url.pathname, url.searchParams, raw ? parseBody(raw) : undefined)
    } catch {
      // The conversation record's upload: not what these pictures are about.
      reply = { status: 204 }
    }
    await route.fulfill({
      status: reply.status,
      contentType: 'application/json',
      body: reply.body === undefined ? '' : JSON.stringify(reply.body),
    })
  })
}

/** Replace Google's WebSocket with a scripted one the test drives from `window.__live`. */
async function fakeGoogle(page: Page) {
  await page.addInitScript(() => {
    type Frame = Record<string, unknown>
    class FakeWS {
      url: string
      readyState = 0
      binaryType = 'blob'
      sent: Frame[] = []
      onopen: ((e: unknown) => void) | null = null
      onmessage: ((e: { data: string }) => void) | null = null
      onerror: ((e: unknown) => void) | null = null
      onclose: ((e: { code: number; reason: string }) => void) | null = null
      constructor(url: string) {
        this.url = url
        ;(window as unknown as { __live: FakeWS[] }).__live.push(this)
        setTimeout(() => {
          this.readyState = 1
          this.onopen?.({})
        }, 0)
      }
      send(data: string) {
        const frame = JSON.parse(data) as Frame
        this.sent.push(frame)
        if ('setup' in frame) setTimeout(() => this.serve({ setupComplete: {} }), 0)
      }
      close() {
        this.readyState = 3
      }
      serve(message: unknown) {
        this.onmessage?.({ data: JSON.stringify(message) })
      }
    }
    ;(window as unknown as { __live: FakeWS[] }).__live = []
    ;(window as unknown as { WebSocket: unknown }).WebSocket = FakeWS
  })
}

function serve(page: Page, message: unknown) {
  return page.evaluate((m) => {
    const sockets = (window as unknown as { __live: { serve(m: unknown): void }[] }).__live
    sockets[sockets.length - 1].serve(m)
  }, message)
}

async function shot(page: Page, name: string) {
  await page.evaluate(() => document.fonts.ready)
  await page.waitForTimeout(900)
  await page.screenshot({ path: path.join(OUT, `${name}.png`) })
}

function visitor(): Engine {
  const engine = createEngine({ signedIn: false, assistant: { companionName: 'Alfred', tools: TOOLS } })
  engine.demo.session = { role: 'recruiter', expires_at: new Date(Date.now() + 2 * 3_600_000).toISOString(), ref: 'acme' }
  return engine
}

async function tool(page: Page, id: string, name: string) {
  await expect
    .poll(() => page.evaluate(() => (window as unknown as { __live: unknown[] }).__live.length))
    .toBeGreaterThan(0)
  await serve(page, { toolCall: { functionCalls: [{ id, name, args: {} }] } })
}

test.use({
  permissions: ['microphone'],
  viewport: { width: 1440, height: 900 },
  launchOptions: { args: ['--use-fake-device-for-media-stream', '--use-fake-ui-for-media-stream'] },
})

test('desk: each card on the voice stage, then the transcript', async ({ page }) => {
  const errors: string[] = []
  page.on('pageerror', (error) => {
    if (!error.message.includes("'serviceWorker'")) errors.push(error.message)
  })
  await useEngine(page, visitor())
  await fakeGoogle(page)
  await page.goto('/try')
  const dock = page.getByRole('complementary', { name: /^Conversation with/ })
  await dock.getByRole('button', { name: 'Talk to Alfred' }).click()
  await expect(dock.getByRole('status')).toHaveText(/checking/)
  const stage = dock.getByRole('region', { name: 'Voice' })
  const shots: [string, string, string][] = [
    ['monthly_spend', 'Spending by category', 'desk-1-bars'],
    ['list_events', "What's on", 'desk-2-timeline'],
    ['run_procedure', 'Cherokee', 'desk-3-car'],
    ['money_steps', 'Money check', 'desk-4-steps'],
    ['list_checklists', 'Groceries', 'desk-5-checklist'],
  ]
  for (const [index, [name, title, file]] of shots.entries()) {
    await tool(page, `c${index}`, name)
    await expect(stage.getByRole('region', { name: title })).toBeVisible()
    await shot(page, file)
  }
  await dock.getByRole('button', { name: 'Type instead' }).click()
  await expect(dock.getByRole('log').getByRole('region', { name: 'Spending by category' })).toBeAttached()
  await shot(page, 'desk-6-chat-open')
  expect(errors).toEqual([])
})

test.describe('phone (390 px)', () => {
  test.use({ viewport: { width: 390, height: 844 }, isMobile: true, hasTouch: true, deviceScaleFactor: 2 })

  test('the voice-first sheet with a card on the stage', async ({ page }) => {
    await useEngine(page, visitor())
    await fakeGoogle(page)
    await page.goto('/try')
    await page.getByRole('button', { name: 'Talk to Alfred' }).click()
    const sheet = page.getByRole('dialog', { name: /^Conversation with/ })
    await sheet.getByRole('button', { name: 'Talk to Alfred' }).click()
    await expect(sheet.getByRole('status')).toHaveText(/checking/)
    await tool(page, 'p1', 'list_events')
    await expect(sheet.getByRole('region', { name: "What's on" })).toBeVisible()
    await shot(page, 'phone-1-stage-timeline')
    await tool(page, 'p2', 'monthly_spend')
    await expect(sheet.getByRole('region', { name: 'Spending by category' })).toBeVisible()
    await shot(page, 'phone-2-stage-bars')
  })

  test('the member chat draws the card in the transcript', async ({ page }) => {
    const engine = createEngine({ ...seedHousehold(), assistant: { companionName: 'Dorothy', tools: TOOLS } })
    await useEngine(page, engine)
    await fakeGoogle(page)
    await page.goto('/')
    await page.getByRole('button', { name: 'Ask Dorothy' }).click()
    const box = page.getByRole('textbox', { name: 'Message Dorothy' })
    await box.fill('What did we spend last month?')
    await page.getByRole('button', { name: 'Send' }).click()
    await tool(page, 'm1', 'monthly_spend')
    await serve(page, { serverContent: { outputTranscription: { text: 'About $2,517, with $12.40 of it unverified.' }, turnComplete: true } })
    await expect(page.getByRole('region', { name: 'Spending by category' })).toBeVisible()
    await shot(page, 'member-1-bars')
    await tool(page, 'm2', 'run_procedure')
    await expect(page.getByRole('region', { name: 'Cherokee' })).toBeVisible()
    await shot(page, 'member-2-car')
  })
})
