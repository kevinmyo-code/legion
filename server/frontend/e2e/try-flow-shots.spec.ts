import fs from 'node:fs'
import path from 'node:path'

import { expect, test, type Page } from '@playwright/test'

import type { DemoContent } from '../src/api/demo'
import { createEngine, type Engine } from '../src/test/engine'
import { sampleContent } from '../src/test/engine-demo'

/**
 * The /try flow of 2026-11-22 in a REAL browser, for LOOKING (Kevin: one continuous orb flow, a
 * more energetic mood, a feel per section, the orb turning into the Q at the finance voice
 * section). Screenshots at 1440 x 900, 1000 x 800 and 390 x 844: the top, the orb in
 * flight, each section with the orb at rest, the blend and the morph caught midway, reduced
 * motion; plus numbers: the orb's path per scroll step (it never jumps or vanishes), long frames
 * while scrolling, and sideways overflow at 390 px. Fake engine; invented content unless the owner
 * points the run at his own, which is never committed:
 *
 *     STORY_CONTENT=<content.json>  the page content to show
 *     STORY_MEDIA=<dir>             files served at /api/demo/media/<name>
 *     FLOW_OUT=<dir>                where the screenshots and numbers go
 *     STORY_BASE_URL=<url>          this tree's own preview when another holds the config's port
 *
 *     npx playwright test e2e/try-flow-shots.spec.ts
 */

if (process.env.STORY_BASE_URL) test.use({ baseURL: process.env.STORY_BASE_URL })

const OUT = process.env.FLOW_OUT ?? path.join(process.cwd(), 'test-results', 'try-flow')
const MEDIA = process.env.STORY_MEDIA
const LOREM = 'Lorem ipsum dolor sit amet, consectetur adipiscing elit, sed do eiusmod tempor incididunt ut labore.'

function invented(): DemoContent {
  const parts = (n: number, label: string) => Array.from({ length: n }, (_, i) => ({ title: `${label} part ${i + 1}`, body: LOREM }))
  return {
    ...sampleContent(),
    projects: ['legion', 'fake-voice', 'fake-ops', 'fake-garage'].map((id) => ({ title: `Fake ${id}`, line: LOREM, href: `#${id}` })),
    legion: {
      title: 'Fake system',
      intro: LOREM,
      parts: parts(4, 'Fake'),
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

/** Scrolls so `selector`'s top sits `at` (0..1) down the screen, and waits `ms` for it to settle. */
async function scrollTo(page: Page, selector: string, at: number, ms: number) {
  await page.evaluate(
    ([sel, frac]) => {
      const el = document.querySelector(sel as string)
      if (!el) return
      const top = el.getBoundingClientRect().top + window.scrollY
      window.scrollTo(0, Math.max(0, top - window.innerHeight * (frac as number)))
    },
    [selector, at],
  )
  await page.waitForTimeout(ms)
}

/** The flying orb's centre and width on screen. */
function orbBox(page: Page) {
  return page.evaluate(() => {
    const el = document.querySelector('.rc-flyer') ?? document.querySelector('[data-stage="orb"]')
    if (!el) return null
    const r = el.getBoundingClientRect()
    return { x: r.left + r.width / 2, y: r.top + r.height / 2, w: r.width, visible: getComputedStyle(el).visibility !== 'hidden' }
  })
}

const SIZES = [
  { name: 'desk', width: 1440, height: 900 },
  { name: 'narrow-desk', width: 1000, height: 800 },
  { name: 'phone', width: 390, height: 844 },
] as const

for (const size of SIZES) {
  test(`the flow at ${size.width} px`, async ({ browser }) => {
    test.setTimeout(240_000)
    fs.mkdirSync(OUT, { recursive: true })
    const context = await browser.newContext({ viewport: { width: size.width, height: size.height }, deviceScaleFactor: size.name === 'phone' ? 2 : 1 })
    const page = await context.newPage()
    await open(page)
    const shot = (name: string) => page.screenshot({ path: path.join(OUT, `${size.name}-${name}.png`) })
    const numbers: Record<string, unknown> = {}

    await shot('01-top')

    // The orb's path, a few pixels of scroll at a time from the top through the first sections.
    const path_: { y: number; x: number; cy: number; w: number }[] = []
    // In flight the orb passes over the stage's controls. A tap on the orb you see is the orb's; a
    // tap on a control that the orb's circle does not cover reaches the control.
    const stolen: string[] = []
    const missed: number[] = []
    for (let y = 0; y <= 1400; y += 20) {
      await page.evaluate((to) => window.scrollTo(0, to), y)
      await page.waitForTimeout(40)
      const box = await orbBox(page)
      if (box) path_.push({ y, x: Math.round(box.x), cy: Math.round(box.y), w: Math.round(box.w) })
      const look = await page.evaluate((at) => {
        const flyer = document.querySelector('.rc-flyer')
        const out: string[] = []
        if (!flyer) return { out, orbTappable: true }
        const f = flyer.getBoundingClientRect()
        const ox = f.left + f.width / 2
        const oy = f.top + f.height / 2
        const radius = f.width / 2
        const onScreen = oy > 0 && oy < window.innerHeight && ox > 0 && ox < window.innerWidth
        const centre = onScreen ? document.elementFromPoint(ox, oy) : null
        const controls = Array.from(document.querySelectorAll('[data-stage="form"] button, [data-stage="form"] input, [data-stage="ask"] a, [data-stage="chips-room"] button'))
        for (const c of controls) {
          const r = c.getBoundingClientRect()
          const cx = r.left + r.width / 2
          const cy = r.top + r.height / 2
          if (r.width === 0 || cy < 0 || cy > window.innerHeight) continue
          if (Math.hypot(cx - ox, cy - oy) < radius) continue
          const hit = document.elementFromPoint(cx, cy)
          if (hit && flyer.contains(hit)) out.push(`${at}: ${c.textContent?.trim() || c.getAttribute('aria-label') || c.tagName}`)
        }
        // The orb's own centre: the orb, or (at the very top) the stage's button under it.
        const stage = document.querySelector('[data-stage="orb"]')
        const orbTappable = !onScreen || Boolean(centre && (flyer.contains(centre) || stage?.contains(centre)))
        return { out, orbTappable }
      }, y)
      stolen.push(...look.out)
      if (!look.orbTappable) missed.push(y)
    }
    numbers.tapsTakenByTheFlyingOrb = stolen
    numbers.scrollWhereTheOrbTookNoTap = missed
    let biggestStep = 0
    for (let i = 1; i < path_.length; i++) biggestStep = Math.max(biggestStep, Math.hypot(path_[i].x - path_[i - 1].x, path_[i].cy - path_[i - 1].cy))
    numbers.orbPath = path_
    numbers.biggestStepPx = Math.round(biggestStep)

    // Mid-flight: the first scroll position where the orb is about halfway to its rest place.
    const rest = path_[path_.length - 1]
    const mid = path_.find((p) => Math.hypot(p.x - path_[0].x, p.cy - path_[0].cy) >= 0.45 * Math.hypot(rest.x - path_[0].x, rest.cy - path_[0].cy))
    await page.evaluate(() => window.scrollTo(0, 0))
    await page.waitForTimeout(300)
    await page.evaluate((to) => window.scrollTo(0, to), mid?.y ?? 200)
    await page.waitForTimeout(250)
    await shot('02-orb-in-flight')

    const sections = await page.evaluate(() => Array.from(document.querySelectorAll('main section[id]')).map((s) => s.id))
    numbers.sections = sections
    let n = 3
    for (const id of sections) {
      await scrollTo(page, `#${id}`, 0.3, 1400)
      await shot(`${String(n++).padStart(2, '0')}-section-${id}`)
    }

    // A blend and a morph caught midway: the section with a mark crossing the middle.
    const marked = await page.evaluate(() => document.querySelector('section[data-orb-mark]')?.id ?? null)
    if (marked) {
      const before = await page.evaluate((id) => document.getElementById(id)!.previousElementSibling?.id ?? null, marked)
      if (before) await scrollTo(page, `#${before}`, 0.3, 1400)
      await scrollTo(page, `#${marked}`, 0.45, 380)
      await shot(`${String(n++).padStart(2, '0')}-mid-blend-and-morph`)
      await page.waitForTimeout(1400)
      await shot(`${String(n++).padStart(2, '0')}-morphed`)
    }

    // A transition between two sections with the orb in motion (the side changes).
    if (sections.length > 2) {
      await scrollTo(page, `#${sections[1]}`, 0.3, 1400)
      await scrollTo(page, `#${sections[2]}`, 0.45, 250)
      await shot(`${String(n++).padStart(2, '0')}-between-sections`)
    }

    // Long frames while scrolling the whole page by the wheel.
    await page.evaluate(() => window.scrollTo(0, 0))
    await page.waitForTimeout(400)
    await page.evaluate(() => {
      const w = window as unknown as { __frames: number[]; __on: boolean }
      w.__frames = []
      w.__on = true
      let last = performance.now()
      const tick = (now: number) => {
        w.__frames.push(now - last)
        last = now
        if (w.__on) requestAnimationFrame(tick)
      }
      requestAnimationFrame(tick)
    })
    const height = await page.evaluate(() => document.documentElement.scrollHeight)
    for (let y = 0; y < height; y += 120) {
      await page.mouse.wheel(0, 120)
      await page.waitForTimeout(16)
    }
    const frames = await page.evaluate(() => {
      const w = window as unknown as { __frames: number[]; __on: boolean }
      w.__on = false
      return w.__frames
    })
    const sorted = [...frames].sort((a, b) => a - b)
    numbers.frames = { count: frames.length, over25ms: frames.filter((f) => f > 25).length, p95: Math.round(sorted[Math.floor(sorted.length * 0.95)] ?? 0) }
    numbers.overflowX = await page.evaluate(() => document.documentElement.scrollWidth - document.documentElement.clientWidth)

    // The same scroll without the light behind the page: what the ambient layer costs here.
    await page.addStyleTag({ content: '.rc-ambient { display: none !important; }' })
    await page.evaluate(() => window.scrollTo(0, 0))
    await page.waitForTimeout(400)
    await page.evaluate(() => {
      const w = window as unknown as { __frames: number[]; __on: boolean }
      w.__frames = []
      w.__on = true
      let last = performance.now()
      const tick = (now: number) => {
        w.__frames.push(now - last)
        last = now
        if (w.__on) requestAnimationFrame(tick)
      }
      requestAnimationFrame(tick)
    })
    for (let y = 0; y < height; y += 120) {
      await page.mouse.wheel(0, 120)
      await page.waitForTimeout(16)
    }
    const bare = await page.evaluate(() => {
      const w = window as unknown as { __frames: number[]; __on: boolean }
      w.__on = false
      return w.__frames
    })
    const bareSorted = [...bare].sort((a, b) => a - b)
    numbers.framesWithoutAmbient = { count: bare.length, over25ms: bare.filter((f) => f > 25).length, p95: Math.round(bareSorted[Math.floor(bareSorted.length * 0.95)] ?? 0) }
    await page.evaluate(() => document.querySelectorAll('style').forEach((tag) => tag.textContent?.includes('rc-ambient { display: none') && tag.remove()))

    // Reduced motion: the calm fallback.
    await page.emulateMedia({ reducedMotion: 'reduce' })
    await page.evaluate(() => window.scrollTo(0, 0))
    await page.waitForTimeout(400)
    await shot(`${String(n++).padStart(2, '0')}-reduced-top`)
    if (marked) {
      await scrollTo(page, `#${marked}`, 0.3, 1200)
      await shot(`${String(n++).padStart(2, '0')}-reduced-marked-section`)
    }
    fs.writeFileSync(path.join(OUT, `${size.name}-numbers.json`), JSON.stringify(numbers, null, 1))
    expect(numbers.overflowX).toBe(0)
    expect(stolen).toEqual([])
    expect(missed).toEqual([])
    await context.close()
  })
}
