import fs from 'node:fs'
import path from 'node:path'

import { expect, test, type Page } from '@playwright/test'

import type { DemoContent } from '../src/api/demo'
import { createEngine, type Engine } from '../src/test/engine'
import { sampleContent } from '../src/test/engine-demo'

/**
 * The /try revamp (Kevin, 2026-11-05) for LOOKING: the hero's Ask button and first questions,
 * the Projects strip and a project section, at desk and phone width. Fake engine, scripted
 * Google socket; nothing calls Google.
 *
 *     TRY_SHOTS=<dir> RECRUITER_CONTENT=<content.json> npx playwright test e2e/try-shots.spec.ts
 *
 * `RECRUITER_CONTENT` is the owner's private content.json (never committed); without it the
 * fake sample plus a fake strip stands in. Each run also checks, in numbers, that the Ask
 * button is above the fold.
 */

const OUT = process.env.TRY_SHOTS ?? path.join(process.cwd(), 'test-results', 'try')

function content(): DemoContent {
  const file = process.env.RECRUITER_CONTENT
  // The file is the loader's input; the engine adds the asset links when it serves it.
  if (file) return { ...JSON.parse(fs.readFileSync(file, 'utf-8')), assets: sampleContent().assets }
  return {
    ...sampleContent(),
    legion: { title: 'How LEGION is built', intro: 'A fake intro.', parts: [{ title: 'The engine', body: 'Fake.' }] },
    projects: [
      { title: 'LEGION', line: 'A fake line about this page.', href: '#legion' },
      { title: 'Fake dashboard', line: 'A fake line about a dashboard.', href: '#fake-dashboard' },
      { title: 'Fake hardware', line: 'A fake line. In progress.' },
    ],
    project_sections: [
      {
        id: 'fake-dashboard',
        title: 'Fake dashboard',
        intro: 'A fake dashboard for a fake team.',
        parts: [{ title: 'Fake refresh', body: 'Every fake minute.' }],
        media: { note: 'Video: coming soon, recorded on demo data.' },
      },
    ],
  }
}

function parseBody(raw: string): unknown {
  try {
    return JSON.parse(raw)
  } catch {
    return undefined
  }
}

async function useEngine(page: Page, engine: Engine) {
  await page.route('**/api/**', async (route) => {
    const request = route.request()
    const url = new URL(request.url())
    const raw = request.postData()
    let reply: { status: number; body?: unknown }
    try {
      reply = engine.handle(request.method(), url.pathname, url.searchParams, raw ? parseBody(raw) : undefined)
    } catch {
      reply = { status: 204 }
    }
    await route.fulfill({
      status: reply.status,
      contentType: 'application/json',
      body: reply.body === undefined ? '' : JSON.stringify(reply.body),
    })
  })
}

/** Google's WebSocket, scripted: it opens and completes setup, and says nothing. */
async function fakeGoogle(page: Page) {
  await page.addInitScript(() => {
    class FakeWS {
      readyState = 0
      binaryType = 'blob'
      onopen: ((e: unknown) => void) | null = null
      onmessage: ((e: { data: string }) => void) | null = null
      onerror: ((e: unknown) => void) | null = null
      onclose: ((e: { code: number; reason: string }) => void) | null = null
      constructor() {
        setTimeout(() => {
          this.readyState = 1
          this.onopen?.({})
        }, 0)
      }
      send(data: string) {
        const frame = JSON.parse(data) as Record<string, unknown>
        if ('setup' in frame) setTimeout(() => this.onmessage?.({ data: JSON.stringify({ setupComplete: {} }) }), 0)
      }
      close() {
        this.readyState = 3
      }
    }
    ;(window as unknown as { WebSocket: unknown }).WebSocket = FakeWS
  })
}

function visitor(): Engine {
  const engine = createEngine({ signedIn: false, assistant: { companionName: 'Alfred' } })
  engine.demo.session = { role: 'recruiter', expires_at: new Date(Date.now() + 2 * 3_600_000).toISOString(), ref: 'acme' }
  engine.demo.content = content()
  return engine
}

async function open(page: Page) {
  await useEngine(page, visitor())
  await fakeGoogle(page)
  await page.goto('/try')
  await expect(page.getByRole('heading', { level: 1 })).toBeVisible()
  await page.evaluate(() => document.fonts.ready)
  await page.waitForTimeout(800)
}

async function shot(page: Page, name: string) {
  await page.waitForTimeout(500)
  await page.screenshot({ path: path.join(OUT, `${name}.png`) })
}

/** Scroll a section's heading to the top of the window and shoot. */
async function shotAt(page: Page, id: string, name: string) {
  await page.evaluate((target) => document.getElementById(target)?.scrollIntoView({ block: 'start' }), id)
  await shot(page, name)
}

/** The Ask button's bottom edge, and the line where the visible page ends (above the phone's bar). */
async function fold(page: Page) {
  const ask = page.locator('.rc-ask-button')
  const box = (await ask.boundingBox())!
  const askbar = page.locator('[data-chat="askbar"]')
  const bar = (await askbar.count()) > 0 ? await askbar.boundingBox() : null
  const viewport = page.viewportSize()!
  const visibleBottom = bar ? Math.min(viewport.height, bar.y) : viewport.height
  const questions = await page.locator('.rc-ask-q').evaluateAll((els) => els.map((el) => el.getBoundingClientRect().bottom))
  return { askBottom: box.y + box.height, lastQuestionBottom: Math.max(...questions), visibleBottom }
}

test.use({
  permissions: ['microphone'],
  launchOptions: { args: ['--use-fake-device-for-media-stream', '--use-fake-ui-for-media-stream'] },
})

test.describe('desk (1440 x 900)', () => {
  test.use({ viewport: { width: 1440, height: 900 } })

  test('the top, the strip, the project section, then Ask', async ({ page }) => {
    await open(page)
    const where = await fold(page)
    console.log(`desk fold: ${JSON.stringify(where)}`)
    expect(where.askBottom).toBeLessThan(where.visibleBottom)
    expect(where.lastQuestionBottom).toBeLessThan(where.visibleBottom)
    await shot(page, 'desk-1-top')
    await shotAt(page, 'projects', 'desk-2-projects')
    const sectionId = content().project_sections?.[0]?.id
    if (sectionId) await shotAt(page, sectionId, 'desk-3-project-section')
    await page.evaluate(() => window.scrollTo(0, 0))
    await page.locator('.rc-ask-button').click()
    const dock = page.getByRole('complementary', { name: /^Conversation with/ })
    await expect(dock.getByRole('button', { name: 'Stop talking. Typing continues.' })).toBeVisible()
    await shot(page, 'desk-4-ask-tapped')
  })
})

test.describe('phone (390 x 844)', () => {
  test.use({ viewport: { width: 390, height: 844 }, isMobile: true, hasTouch: true, deviceScaleFactor: 2 })

  test('the top, the strip, the project section, then a first question', async ({ page }) => {
    await open(page)
    const where = await fold(page)
    console.log(`phone fold: ${JSON.stringify(where)}`)
    expect(where.askBottom).toBeLessThan(where.visibleBottom)
    expect(where.lastQuestionBottom).toBeLessThan(where.visibleBottom)
    expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBe(390)
    await shot(page, 'phone-1-top')
    await shotAt(page, 'projects', 'phone-2-projects')
    const sectionId = content().project_sections?.[0]?.id
    if (sectionId) await shotAt(page, sectionId, 'phone-3-project-section')
    await page.evaluate(() => window.scrollTo(0, 0))
    await page.getByRole('button', { name: 'How is LEGION built?' }).click()
    const sheet = page.getByRole('dialog', { name: /^Conversation with/ })
    await expect(sheet.getByRole('log')).toContainText('How is LEGION built?')
    await shot(page, 'phone-4-question-tapped')
  })
})
