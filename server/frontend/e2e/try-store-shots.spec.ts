import fs from 'node:fs'
import path from 'node:path'

import { expect, test, type Page } from '@playwright/test'

import type { DemoContent } from '../src/api/demo'
import { createEngine, type Engine } from '../src/test/engine'
import { sampleContent } from '../src/test/engine-demo'

/**
 * The store-rep section of 2026-11-22 in a REAL browser, for LOOKING (Kevin: the store rep as a
 * section of its own, the orb turning into the made-up shop's mark): the section, the morph caught
 * midway and done, and the after-call card in the section, at 1440 x 900 and 390 x 664 (Safari's
 * bars up). The shop's routes answer from invented data here; the rest is the fake engine. Real
 * private content when the owner points the run at it (never committed):
 *
 *     STORY_CONTENT=<content.json>  STORY_MEDIA=<dir>  STORE_OUT=<dir>  STORY_BASE_URL=<url>
 *
 *     npx playwright test e2e/try-store-shots.spec.ts
 */

if (process.env.STORY_BASE_URL) test.use({ baseURL: process.env.STORY_BASE_URL })

const OUT = process.env.STORE_OUT ?? path.join(process.cwd(), 'test-results', 'try-store')
const MEDIA = process.env.STORY_MEDIA
const LOREM = 'Lorem ipsum dolor sit amet, consectetur adipiscing elit, sed do eiusmod tempor incididunt ut labore.'

/** Three columns of invented boxes, joined by lines, one with an arrow at each end. */
const FAKE_DIAGRAM =
  '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 760 330"><rect width="760" height="330" fill="#fff"/>' +
  '<rect x="20" y="40" width="190" height="56" fill="#faf8f3" stroke="#1d1a16"/><text x="34" y="72">Fake phone</text>' +
  '<rect x="20" y="130" width="190" height="56" fill="#faf8f3" stroke="#1d1a16"/><text x="34" y="162">Fake page</text>' +
  '<rect x="290" y="40" width="200" height="196" fill="#fff" stroke="#1d1a16"/><text x="306" y="72">Fake hub</text>' +
  '<rect x="570" y="40" width="170" height="44" fill="#faf8f3" stroke="#1d1a16"/><text x="584" y="66">Fake bank</text>' +
  '<rect x="570" y="120" width="170" height="44" fill="#faf8f3" stroke="#1d1a16"/><text x="584" y="146">Fake weather</text>' +
  '<line x1="210" y1="68" x2="288" y2="90" stroke="#1d1a16" marker-start="url(#a)" marker-end="url(#a)"/>' +
  '<line x1="210" y1="158" x2="288" y2="150" stroke="#1d1a16"/>' +
  '<line x1="490" y1="70" x2="568" y2="62" stroke="#1d1a16"/>' +
  '<line x1="490" y1="140" x2="568" y2="142" stroke="#1d1a16"/>' +
  '</svg>'

function invented(): DemoContent {
  const parts = (n: number, label: string) => Array.from({ length: n }, (_, i) => ({ title: `${label} part ${i + 1}`, body: LOREM }))
  return {
    ...sampleContent(),
    projects: ['legion', 'fake-voice', 'fake-ops', 'fake-garage'].map((id) => ({ title: `Fake ${id}`, line: LOREM, href: `#${id}` })),
    legion: {
      title: 'Fake system',
      intro: LOREM,
      diagram_svg: FAKE_DIAGRAM,
      diagram_caption: 'A fake diagram in three columns.',
      parts: [
        { title: 'Fake hub', body: LOREM, lights: ['Fake hub'] },
        { title: 'Fake clients', body: LOREM, lights: ['Fake phone', 'Fake page'] },
        { title: 'Fake path', body: LOREM, lights: ['Fake phone', 'Fake hub', 'Fake bank'] },
        { title: 'Fake outside', body: LOREM },
      ],
      quality: {
        title: 'Fake quality',
        stats: [
          { value: '12', label: 'fake cases' },
          { value: '3,400+', label: 'fake tests' },
        ],
        checks: parts(3, 'Check'),
      },
    },
    project_sections: [
      {
        id: 'fake-store',
        title: 'Fake store rep',
        intro: LOREM,
        parts: [{ title: 'Fake check', body: LOREM }],
        theme: 'shop',
        orb_mark: 'larkspur',
        widget: 'store_rep',
      },
      { id: 'fake-voice', title: 'Fake voice', intro: LOREM, parts: parts(4, 'Voice'), theme: 'ledger', orb_mark: 'q', link: { href: 'https://demo.example.test', label: 'Try the fake demo' } },
      { id: 'fake-ops', title: 'Fake operations', intro: LOREM, parts: parts(4, 'Ops'), theme: 'midcon' },
      { id: 'fake-garage', title: 'Fake garage', intro: LOREM, parts: parts(4, 'Garage'), theme: 'aw4' },
    ],
  }
}

function content(): DemoContent {
  const own = process.env.STORY_CONTENT
  const base: DemoContent = own ? { ...sampleContent(), ...JSON.parse(fs.readFileSync(own, 'utf-8')) } : invented()
  const served = (name: string | undefined) => (!name || name.startsWith('/') || /^https?:/.test(name) ? name : `/api/demo/media/${name}`)
  for (const section of base.project_sections ?? []) {
    const media = section.media
    if (media) {
      media.video = served(media.video)
      media.poster = served(media.poster)
      media.captions = served(media.captions)
    }
    for (const model of section.media?.models ?? []) {
      model.model = served(model.model)!
      model.poster = served(model.poster) ?? model.poster
    }
  }
  return { ...base, assets: { report: null, resume: '/fake-media/resume.pdf', resume_image: null } }
}

async function open(page: Page) {
  const engine: Engine = createEngine({ signedIn: false, assistant: { companionName: 'Alfred', engine: 'cascade' } })
  engine.demo.session = { role: 'recruiter', expires_at: new Date(Date.now() + 3_600_000).toISOString(), ref: 'acme' }
  engine.demo.content = content()
  await page.route('**/api/**', async (route) => {
    const request = route.request()
    const url = new URL(request.url())
    let reply: { status: number; body?: unknown }
    try {
      reply = engine.handle(request.method(), url.pathname, url.searchParams, undefined)
    } catch {
      reply = { status: 204 }
    }
    await route.fulfill({ status: reply.status, contentType: 'application/json', body: reply.body === undefined ? '' : JSON.stringify(reply.body) })
  })
  await page.route('**/api/demo/media/*', async (route) => {
    const name = decodeURIComponent(new URL(route.request().url()).pathname.split('/').pop()!)
    const file = MEDIA ? path.join(MEDIA, name) : ''
    if (!file || !fs.existsSync(file)) return route.fulfill({ status: 404, body: '{}' })
    const type = name.endsWith('.glb') ? 'model/gltf-binary' : name.endsWith('.vtt') ? 'text/vtt' : name.endsWith('.mp4') ? 'video/mp4' : 'image/png'
    await route.fulfill({ status: 200, body: fs.readFileSync(file), contentType: type })
  })
  await page.route('**/fake-media/**', (route) => route.fulfill({ status: 404, body: '' }))
  // The shop's own routes, from invented data (the fake engine has none).
  const json = (body: unknown) => ({ status: 200, contentType: 'application/json', body: JSON.stringify(body) })
  await page.route('**/api/demo/shop/state', (route) => route.fulfill(json(SHOP)))
  await page.route('**/api/demo/shop/call-card', (route) => route.fulfill(json(CARD)))
  await page.goto('/try')
  await expect(page.getByRole('heading', { level: 1 })).toBeVisible()
  await page.evaluate(() => document.fonts.ready)
  await page.waitForTimeout(600)
}


const SHOP = {
  active: false,
  shop_name: 'Larkspur & Pine',
  products: [
    { sku: 'LP-PK40', name: 'Ridgeline 40L Daypack', category: 'packs', price_cents: 12900, in_stock: 14, blurb: '' },
    { sku: 'LP-RC10', name: 'Drizzle Rain Cover', category: 'packs', price_cents: 2400, in_stock: 30, blurb: '' },
  ],
  orders: [],
  callback: 'off',
}

const CARD = {
  outcome: 'order_placed',
  outcome_words: 'Order placed',
  summary: ['3 visitor turns.', 'Discussed: Ridgeline 40L Daypack.', 'Placed LP-1234 for $153.00.'],
  transcript: [
    { who: 'visitor', text: 'I need a daypack for weekend hikes.', typed: false, at: '11-22 10:00:00.000' },
    { who: 'assistant', text: 'The Ridgeline 40L Daypack is $129.00, and 14 are in stock.', typed: false, at: '11-22 10:00:01.000' },
    { who: 'visitor', text: 'Yes, place it.', typed: false, at: '11-22 10:00:09.000' },
  ],
  turns: [
    { kind: 'greeting', spoken: true, first_audio_ms: 610, answer_audio_ms: 610, stt_ms: null, turn_ms: 900 },
    { kind: 'turn', spoken: true, first_audio_ms: 820, answer_audio_ms: 1400, stt_ms: 300, turn_ms: 2100 },
    { kind: 'turn', spoken: true, first_audio_ms: 760, answer_audio_ms: 760, stt_ms: 280, turn_ms: 1500 },
  ],
  median_first_audio_ms: 760,
  record: { record_type: 'call', outcome: 'order_placed', orders: [{ ref: 'LP-1234', total_cents: 15300 }] },
}

const SIZES = [
  { name: 'desk', width: 1440, height: 900 },
  { name: 'phone-bars', width: 390, height: 664 },
] as const

for (const size of SIZES) {
  test(`the store-rep section at ${size.width} x ${size.height}`, async ({ browser }) => {
    test.setTimeout(180_000)
    fs.mkdirSync(OUT, { recursive: true })
    const context = await browser.newContext({ viewport: { width: size.width, height: size.height }, deviceScaleFactor: size.width < 900 ? 2 : 1 })
    const page = await context.newPage()
    await open(page)
    const id = await page.evaluate(() => document.querySelector('section[data-orb-mark="larkspur"]')?.id ?? null)
    expect(id).not.toBeNull()
    // From the section before it, so the mood and the mark change as it arrives.
    await page.evaluate((sid) => {
      const before = document.getElementById(sid!)!.previousElementSibling as HTMLElement | null
      if (before) window.scrollTo(0, before.getBoundingClientRect().top + window.scrollY)
    }, id)
    await page.waitForTimeout(1500)
    await page.evaluate((sid) => {
      const el = document.getElementById(sid!)!
      window.scrollTo(0, el.getBoundingClientRect().top + window.scrollY - window.innerHeight * 0.3)
    }, id)
    await page.waitForTimeout(400)
    await page.screenshot({ path: path.join(OUT, `${size.name}-1-morph-midway.png`) })
    await page.waitForFunction(() => document.querySelector('.recruiter')?.getAttribute('data-page-theme') === 'shop')
    await page.waitForTimeout(1600)
    await page.screenshot({ path: path.join(OUT, `${size.name}-2-section.png`) })
    const talk = page.getByRole('button', { name: 'Talk to the store rep' })
    await expect(talk).toBeVisible()
    await talk.scrollIntoViewIfNeeded()
    await page.evaluate(() => window.scrollBy(0, -window.innerHeight * 0.25))
    await page.waitForTimeout(500)
    await page.screenshot({ path: path.join(OUT, `${size.name}-2b-controls.png`) })
    // The after-call card, in the section (the end of a call shows it by itself; here, its button).
    await page.getByRole('button', { name: 'After-call record' }).click()
    const card = page.getByTestId('call-card')
    await expect(card).toBeVisible()
    await card.scrollIntoViewIfNeeded()
    await page.waitForTimeout(500)
    await page.screenshot({ path: path.join(OUT, `${size.name}-3-after-call-card.png`) })
    const overflowX = await page.evaluate(() => document.documentElement.scrollWidth - document.documentElement.clientWidth)
    await page.emulateMedia({ reducedMotion: 'reduce' })
    await page.waitForTimeout(400)
    await page.screenshot({ path: path.join(OUT, `${size.name}-4-reduced-motion.png`) })
    expect(overflowX).toBe(0)
    await context.close()
  })
}
