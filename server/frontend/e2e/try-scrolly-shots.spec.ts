import fs from 'node:fs'
import path from 'node:path'

import { expect, test, type Page } from '@playwright/test'

import type { DemoContent } from '../src/api/demo'
import { createEngine, type Engine } from '../src/test/engine'
import { sampleContent } from '../src/test/engine-demo'

/**
 * The LEGION scrollytelling stage of 2026-11-22 in a REAL browser, for LOOKING (Kevin: the words move
 * with the diagram's highlights, and the highlights move more): every step in sequence at 1440 x 900
 * and 390 x 844, one frame mid-transition, reduced motion; plus numbers: the step's card on screen
 * with the stage, the lit boxes inside the frame, and no sideways scroll at 390 px. Fake engine;
 * invented content unless the owner points the run at his own, which is never committed:
 *
 *     STORY_CONTENT=<content.json>  STORY_MEDIA=<dir>  SCROLLY_OUT=<dir>  STORY_BASE_URL=<url>
 *
 *     npx playwright test e2e/try-scrolly-shots.spec.ts
 */

if (process.env.STORY_BASE_URL) test.use({ baseURL: process.env.STORY_BASE_URL })

const OUT = process.env.SCROLLY_OUT ?? path.join(process.cwd(), 'test-results', 'try-scrolly')
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
  await page.goto('/try')
  await expect(page.getByRole('heading', { level: 1 })).toBeVisible()
  await page.evaluate(() => document.fonts.ready)
  await page.waitForTimeout(600)
}

async function toStep(page: Page, index: number, settle: number) {
  await page.evaluate((i) => {
    const marker = document.querySelector(`[data-scene="legion"] [data-step="${i}"]`)
    if (!marker) return
    const r = marker.getBoundingClientRect()
    window.scrollTo(0, window.scrollY + r.top + r.height / 2 - window.innerHeight / 2)
  }, index)
  await page.waitForTimeout(settle)
}

const SIZES = [
  { name: 'desk', width: 1440, height: 900 },
  { name: 'phone', width: 390, height: 844 },
] as const

for (const size of SIZES) {
  test(`the LEGION stage at ${size.width} px`, async ({ browser }) => {
    test.setTimeout(240_000)
    fs.mkdirSync(OUT, { recursive: true })
    const context = await browser.newContext({ viewport: { width: size.width, height: size.height }, deviceScaleFactor: size.name === 'phone' ? 2 : 1 })
    const page = await context.newPage()
    await open(page)
    const steps = await page.evaluate(() => document.querySelectorAll('[data-scene="legion"] [data-step]').length)
    expect(steps).toBeGreaterThan(0)
    const numbers: Record<string, unknown>[] = []
    for (let i = 0; i < steps; i++) {
      await toStep(page, i, 1400)
      await page.screenshot({ path: path.join(OUT, `${size.name}-step-${i + 1}.png`) })
      // A phone step whose lit boxes do not fit at a readable size pans between them: each shot.
      const shots = await page.evaluate(() => Number(document.querySelector('[data-scene="legion"]')?.getAttribute('data-shots') ?? 1))
      for (let k = 1; k < shots; k++) {
        await page.waitForTimeout(2700)
        await page.screenshot({ path: path.join(OUT, `${size.name}-step-${i + 1}-shot-${k + 1}.png`) })
      }
      numbers.push(
        await page.evaluate(() => {
          const stage = document.querySelector('.rc-scene-stage')!.getBoundingClientRect()
          const frame = document.querySelector('.rc-scene-frame')!.getBoundingClientRect()
          const active = document.querySelector('.rc-scene-card li[data-on="true"]')
          const card = active?.getBoundingClientRect()
          const lit = Array.from(document.querySelectorAll('.rc-scene-node[data-on="true"] .rc-scene-ring')).map((n) => n.getBoundingClientRect())
          const inFrame = lit.every((b) => b.left >= frame.left - 2 && b.right <= frame.right + 2 && b.top >= frame.top - 2 && b.bottom <= frame.bottom + 2)
          const layer = document.querySelector('.rc-scene-layer') as HTMLElement | null
          const scale = layer ? new DOMMatrixReadOnly(getComputedStyle(layer).transform).a * 2 : 0
          return {
            step: active?.textContent?.slice(0, 40),
            // Pixels per diagram unit: the headings are 15 units, so 0.8 reads at 12 px.
            pxPerUnit: Math.round(scale * 100) / 100,
            stageTop: Math.round(stage.top),
            cardOnScreen: Boolean(card && card.top >= 0 && card.bottom <= window.innerHeight),
            lit: lit.length,
            litInFrame: inFrame,
            litEdges: document.querySelectorAll('.rc-scene-edge[data-on="true"]').length,
          }
        }),
      )
    }
    // One frame mid-transition: from the first step to the third, caught after 250 ms.
    await toStep(page, 0, 1400)
    await toStep(page, Math.min(2, steps - 1), 250)
    await page.screenshot({ path: path.join(OUT, `${size.name}-mid-transition.png`) })
    const overflowX = await page.evaluate(() => document.documentElement.scrollWidth - document.documentElement.clientWidth)
    await page.emulateMedia({ reducedMotion: 'reduce' })
    await toStep(page, Math.min(1, steps - 1), 600)
    await page.screenshot({ path: path.join(OUT, `${size.name}-reduced-motion.png`) })
    fs.writeFileSync(path.join(OUT, `${size.name}-numbers.json`), JSON.stringify({ steps: numbers, overflowX }, null, 1))
    expect(overflowX).toBe(0)
    for (const n of numbers) {
      expect(n.cardOnScreen, JSON.stringify(n)).toBe(true)
      if (size.name === 'phone') expect(n.pxPerUnit as number, JSON.stringify(n)).toBeGreaterThanOrEqual(0.79)
      else expect(n.litInFrame, JSON.stringify(n)).toBe(true)
    }
    await context.close()
  })
}
