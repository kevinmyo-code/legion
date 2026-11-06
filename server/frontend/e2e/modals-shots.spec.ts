import fs from 'node:fs'
import path from 'node:path'

import { expect, test, type Page } from '@playwright/test'

import { createEngine, type Engine } from '../src/test/engine'

/**
 * demo-modals (Kevin, 2026-11-05), for LOOKING: the diagram and the resume over the whole page,
 * at phone (390 x 844) and desk (1440 x 900) width, with the voice conversation running under it.
 * Fake engine, scripted Google socket, fake microphone; nothing calls Google.
 *
 *     MODAL_SHOTS=<dir> RESUME_PNG=<page.png> npx playwright test e2e/modals-shots.spec.ts
 *
 * The diagrams are the repo's public SVGs (`server/diagrams/catalogue.json`). `RESUME_PNG` is the
 * owner's private page image (never committed); without it a made-up page stands in.
 *
 * Each run also checks, in numbers: the modal opens by itself, its Stop button works with the
 * conversation still live, a second show_diagram updates it in place, Fit and zoom change the
 * picture's size, and the back gesture closes it with the page left at /try where it was.
 */

const OUT = process.env.MODAL_SHOTS ?? path.join(process.cwd(), 'test-results', 'modals')
const CATALOGUE = path.join(process.cwd(), '..', 'diagrams', 'catalogue.json')

interface View {
  project: string
  view: string
  title: string
  caption: string
  nodes: { id: string; label: string }[]
  svg: string
}

function diagramCard(view: string, highlight: string[]) {
  const views = JSON.parse(fs.readFileSync(CATALOGUE, 'utf-8')).views as View[]
  const found = views.find((v) => v.project === 'legion' && v.view === view)!
  return {
    card: 'diagram',
    title: found.title,
    scope: 'LEGION',
    project: found.project,
    view: found.view,
    caption: found.caption,
    highlight,
    nodes: found.nodes,
    svg: found.svg,
  }
}

const RESUME_CARD = {
  card: 'resume',
  title: "Kevin's resume",
  image: '/api/demo/asset/resume_image',
  pdf: '/api/demo/asset/resume',
  alt: "Kevin's one-page resume, as an image of the page.",
}

function parseBody(raw: string): unknown {
  try {
    return JSON.parse(raw)
  } catch {
    return undefined
  }
}

/** A made-up one-page resume, drawn by the browser itself, so no real one rides in the repo. */
async function fakeResumePng(page: Page): Promise<Buffer> {
  const lines = Array.from({ length: 34 }, (_, i) => `<p>Fake line ${i + 1}: lorem ipsum dolor sit amet, made-up experience.</p>`)
  const scratch = await page.context().newPage()
  await scratch.setViewportSize({ width: 612, height: 792 })
  await scratch.setContent(
    `<body style="margin:36px;font:11px/1.35 Georgia,serif"><h1 style="text-align:center;font-size:22px">TEST PERSON</h1>${lines.join('')}</body>`,
  )
  const png = await scratch.screenshot({ scale: 'device' })
  await scratch.close()
  return png
}

async function useEngine(page: Page, engine: Engine, resumePng: Buffer) {
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
  // Registered last, so it wins for the page image.
  await page.route('**/api/demo/asset/resume_image', (route) =>
    route.fulfill({ status: 200, contentType: 'image/png', body: resumePng }),
  )
}

async function fakeGoogle(page: Page) {
  await page.addInitScript(() => {
    type Frame = Record<string, unknown>
    class FakeWS {
      url: string
      readyState = 0
      binaryType = 'blob'
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

async function toolCall(page: Page, id: string, name: string) {
  await expect.poll(() => page.evaluate(() => (window as unknown as { __live: unknown[] }).__live.length)).toBeGreaterThan(0)
  await page.evaluate(
    ([callId, toolName]) => {
      const sockets = (window as unknown as { __live: { serve(m: unknown): void }[] }).__live
      sockets[sockets.length - 1].serve({ toolCall: { functionCalls: [{ id: callId, name: toolName, args: {} }] } })
    },
    [id, name],
  )
}

async function shot(page: Page, name: string) {
  await page.evaluate(() => document.fonts.ready)
  await page.waitForTimeout(700)
  await page.screenshot({ path: path.join(OUT, `${name}.png`) })
}

function visitor(): Engine {
  const engine = createEngine({
    signedIn: false,
    assistant: {
      companionName: 'Alfred',
      tools: {
        show_diagram: { text: 'On the screen now.', card: diagramCard('containers', []) },
        show_resume: { text: 'On the screen now.', card: RESUME_CARD },
      },
    },
  })
  engine.demo.session = { role: 'recruiter', expires_at: new Date(Date.now() + 2 * 3_600_000).toISOString(), ref: 'acme' }
  return engine
}

async function svgWidth(page: Page) {
  return page.locator('[data-diagram="full"] svg').evaluate((el) => el.getBoundingClientRect().width)
}

async function run(page: Page, prefix: string, startVoice: (page: Page) => Promise<void>) {
  const errors: string[] = []
  page.on('pageerror', (error) => {
    if (!error.message.includes("'serviceWorker'")) errors.push(error.message)
  })
  const resumePng = process.env.RESUME_PNG ? fs.readFileSync(process.env.RESUME_PNG) : await fakeResumePng(page)
  const engine = visitor()
  await useEngine(page, engine, resumePng)
  await fakeGoogle(page)
  await page.goto('/try')
  await expect(page.getByRole('heading', { level: 1 })).toBeVisible()
  await page.evaluate(() => window.scrollTo(0, 400))
  const scrolledTo = await page.evaluate(() => window.scrollY)
  await startVoice(page)

  // 1. show_diagram opens the modal by itself, fitted to the page.
  await toolCall(page, 'd1', 'show_diagram')
  const dialog = page.getByRole('dialog', { name: 'Containers and deployment' })
  await expect(dialog).toBeVisible()
  const box = (await dialog.boundingBox())!
  const svg = (await page.locator('[data-diagram="full"] svg').boundingBox())!
  const viewport = page.viewportSize()!
  console.log(`${prefix} fit: dialog ${JSON.stringify(box)} svg ${JSON.stringify(svg)}`)
  expect(svg.width).toBeLessThanOrEqual(box.width)
  expect(svg.height).toBeLessThanOrEqual(box.height)
  expect(svg.x).toBeGreaterThanOrEqual(0)
  expect(svg.x + svg.width).toBeLessThanOrEqual(viewport.width)
  // The voice controls ride in the modal, and the conversation is still live under it.
  const stop = dialog.getByRole('button', { name: 'Stop talking. Typing continues.' })
  await expect(stop).toBeVisible()
  await shot(page, `${prefix}-1-diagram-fit`)

  // 2. A second call (a new view, highlights) updates it in place.
  engine.assistant.tools.show_diagram = { text: 'On the screen now.', card: diagramCard('components', ['tooldoor', 'tenancy']) }
  await toolCall(page, 'd2', 'show_diagram')
  await expect(page.locator('[data-modal="diagram"]')).toHaveCount(1)
  await expect(page.getByRole('dialog', { name: 'Engine components, in layers' })).toBeVisible()
  await shot(page, `${prefix}-2-diagram-updated-highlights`)

  // 3. Zoom in, then Fit puts it back.
  const fitted = await svgWidth(page)
  await page.locator('[data-modal="diagram"]').getByRole('button', { name: 'Zoom in' }).click()
  await page.locator('[data-modal="diagram"]').getByRole('button', { name: 'Zoom in' }).click()
  await page.waitForTimeout(200)
  expect(await svgWidth(page)).toBeGreaterThan(fitted * 1.5)
  await shot(page, `${prefix}-3-diagram-zoomed`)
  await page.locator('[data-modal="diagram"]').getByRole('button', { name: 'Fit to screen' }).click()
  await page.waitForTimeout(200)
  expect(Math.abs((await svgWidth(page)) - fitted)).toBeLessThan(1)

  // 4. The back gesture closes it; the page stays at /try, where it was.
  await page.goBack()
  await expect(page.getByRole('dialog', { name: 'Engine components, in layers' })).toHaveCount(0)
  expect(new URL(page.url()).pathname).toBe('/try')
  expect(Math.abs((await page.evaluate(() => window.scrollY)) - scrolledTo)).toBeLessThan(2)

  // 5. Stop in the modal stops the mic and leaves the conversation open.
  await toolCall(page, 'd3', 'show_diagram')
  await page.locator('[data-modal="diagram"]').getByRole('button', { name: 'Stop talking. Typing continues.' }).click()
  await expect(page.locator('[data-modal="diagram"]').getByRole('button', { name: 'Talk to Alfred' })).toBeVisible()
  await page.keyboard.press('Escape')
  await expect(page.locator('[data-modal="diagram"]')).toHaveCount(0)

  // 6. show_resume: the page, fitted to the width, scrolling, with the download.
  await toolCall(page, 'r1', 'show_resume')
  const resume = page.getByRole('dialog', { name: "Kevin's resume" })
  await expect(resume).toBeVisible()
  await expect(resume.locator('[data-modal="resume-page"]')).toBeVisible()
  await expect(resume.getByRole('link', { name: 'Download PDF' })).toHaveAttribute('href', '/api/demo/asset/resume')
  await shot(page, `${prefix}-4-resume`)
  await resume.locator('[data-fit-box]').evaluate((el) => el.scrollTo({ top: el.scrollHeight / 2 }))
  await shot(page, `${prefix}-5-resume-scrolled`)
  await resume.getByRole('button', { name: 'Zoom in' }).click()
  await page.waitForTimeout(250)
  await shot(page, `${prefix}-6-resume-zoomed`)
  await resume.getByRole('button', { name: 'Close' }).click()
  await expect(resume).toHaveCount(0)
  expect(new URL(page.url()).pathname).toBe('/try')
  expect(errors).toEqual([])
}

test.use({
  permissions: ['microphone'],
  launchOptions: { args: ['--use-fake-device-for-media-stream', '--use-fake-ui-for-media-stream'] },
})

test.describe('phone (390 x 844)', () => {
  test.use({ viewport: { width: 390, height: 844 }, isMobile: true, hasTouch: true, deviceScaleFactor: 2 })

  test('the diagram and the resume over the page, voice running', async ({ page }) => {
    await run(page, 'phone', async (p) => {
      await p.getByRole('button', { name: 'Talk to Alfred' }).click()
      const sheet = p.getByRole('dialog', { name: /^Conversation with/ })
      await sheet.getByRole('button', { name: 'Talk to Alfred' }).click()
      await expect(sheet.getByRole('button', { name: 'Stop talking. Typing continues.' })).toBeVisible()
    })
  })
})

test.describe('desk (1440 x 900)', () => {
  test.use({ viewport: { width: 1440, height: 900 } })

  test('the diagram and the resume over the page, voice running', async ({ page }) => {
    await run(page, 'desk', async (p) => {
      const dock = p.getByRole('complementary', { name: /^Conversation with/ })
      await dock.getByRole('button', { name: 'Talk to Alfred' }).click()
      await expect(dock.getByRole('button', { name: 'Stop talking. Typing continues.' })).toBeVisible()
    })
  })
})
