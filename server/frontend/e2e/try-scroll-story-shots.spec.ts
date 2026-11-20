import fs from 'node:fs'
import path from 'node:path'

import { expect, test, type Browser, type Page } from '@playwright/test'

import type { DemoContent } from '../src/api/demo'
import { createEngine, type Engine } from '../src/test/engine'
import { sampleContent } from '../src/test/engine-demo'

/**
 * The /try scroll story (Kevin, 2026-11-19, "B bolder") in a REAL browser, measured:
 *   - frames longer than 25 ms and layout shift while scrolling the page top to bottom by the wheel;
 *   - a modal opened and closed inside each pinned scene leaves the page where it was;
 *   - the side orb (>= 900 px) or the foot dock (narrower) once the stage's orb is off screen;
 *   - the models turn with the scroll until touched, then stay;
 *   - reduced motion is the plain page; no sideways overflow at 390 px.
 * At 1440 x 900, 1024 x 768 and 390 x 844 (that one with the CPU slowed 4x). Fake engine; the
 * content is invented unless the owner points the run at his own, which is never committed:
 *
 *     STORY_CONTENT=<content.json>  the page content to show
 *     STORY_MEDIA=<dir>             files served at /api/demo/media/<name> (models, posters, resume.png)
 *     STORY_OUT=<dir>               screenshots, a numbers file per size, a video per size
 *     STORY_BASE_URL=<url>          this tree's own preview when another holds the config's port
 *
 *     npx playwright test e2e/try-scroll-story-shots.spec.ts
 *
 * No sound: the browser runs muted, and nothing here presses the orb or a Play.
 */

if (process.env.STORY_BASE_URL) test.use({ baseURL: process.env.STORY_BASE_URL })

const OUT = process.env.STORY_OUT ?? path.join(process.cwd(), 'test-results', 'try-scroll-story')
const MEDIA = process.env.STORY_MEDIA

/** A unit cube as a GLB (the same as try-media-shots), so the spec needs no file of anyone's. */
function cubeGlb(): Buffer {
  const faces: [number[], number[]][] = [
    [[1, 0, 0], [0, 1, 0]], [[-1, 0, 0], [0, 1, 0]], [[0, 1, 0], [0, 0, 1]],
    [[0, -1, 0], [0, 0, 1]], [[0, 0, 1], [1, 0, 0]], [[0, 0, -1], [1, 0, 0]],
  ]
  const positions: number[] = []
  const normals: number[] = []
  const indices: number[] = []
  for (const [n, u] of faces) {
    const v = [n[1] * u[2] - n[2] * u[1], n[2] * u[0] - n[0] * u[2], n[0] * u[1] - n[1] * u[0]]
    const base = positions.length / 3
    for (const [a, b] of [[-1, -1], [1, -1], [1, 1], [-1, 1]]) {
      positions.push(...[0, 1, 2].map((i) => 0.5 * (n[i] + a * u[i] + b * v[i])))
      normals.push(...n)
    }
    indices.push(base, base + 1, base + 2, base, base + 2, base + 3)
  }
  const pos = Buffer.from(new Float32Array(positions).buffer)
  const nor = Buffer.from(new Float32Array(normals).buffer)
  const idx = Buffer.from(new Uint16Array(indices).buffer)
  const bin = Buffer.concat([pos, nor, idx, Buffer.alloc((4 - (idx.length % 4)) % 4)])
  const json = {
    asset: { version: '2.0' },
    scene: 0,
    scenes: [{ nodes: [0] }],
    nodes: [{ mesh: 0, rotation: [0.2, 0.3, 0, 0.93] }],
    meshes: [{ primitives: [{ attributes: { POSITION: 0, NORMAL: 1 }, indices: 2, material: 0 }] }],
    materials: [{ pbrMetallicRoughness: { baseColorFactor: [0.55, 0.5, 0.45, 1], roughnessFactor: 0.7, metallicFactor: 0 } }],
    buffers: [{ byteLength: bin.length }],
    bufferViews: [
      { buffer: 0, byteOffset: 0, byteLength: pos.length },
      { buffer: 0, byteOffset: pos.length, byteLength: nor.length },
      { buffer: 0, byteOffset: pos.length + nor.length, byteLength: idx.length },
    ],
    accessors: [
      { bufferView: 0, componentType: 5126, count: 24, type: 'VEC3', min: [-0.5, -0.5, -0.5], max: [0.5, 0.5, 0.5] },
      { bufferView: 1, componentType: 5126, count: 24, type: 'VEC3' },
      { bufferView: 2, componentType: 5123, count: 36, type: 'SCALAR' },
    ],
  }
  let text = Buffer.from(JSON.stringify(json))
  text = Buffer.concat([text, Buffer.alloc((4 - (text.length % 4)) % 4, 0x20)])
  const header = Buffer.alloc(12)
  header.write('glTF', 0)
  header.writeUInt32LE(2, 4)
  header.writeUInt32LE(12 + 8 + text.length + 8 + bin.length, 8)
  const chunk = (data: Buffer, type: number) => {
    const head = Buffer.alloc(8)
    head.writeUInt32LE(data.length, 0)
    head.writeUInt32LE(type, 4)
    return Buffer.concat([head, data])
  }
  return Buffer.concat([header, chunk(text, 0x4e4f534a), chunk(bin, 0x004e4942)])
}

/** A 1x1 paper-coloured PNG: posters and the resume picture of the invented run. */
const PAPER_PNG = Buffer.from(
  'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8/+L1fwAJcAPTqfMRmQAAAABJRU5ErkJggg==',
  'base64',
)

/** Four rows of boxes, as the real diagram has layers. */
const LAYERED_SVG =
  '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 760 330"><rect width="760" height="330" fill="#fff"/>' +
  [30, 110, 190, 260]
    .map((y, row) =>
      [0, 1, 2]
        .map((col) => `<rect x="${30 + col * 240}" y="${y}" width="200" height="44" fill="none" stroke="#1d1a16"/><text x="${50 + col * 240}" y="${y + 28}">Layer ${row + 1} box ${col + 1}</text>`)
        .join(''),
    )
    .join('') +
  '</svg>'

const LOREM = 'Lorem ipsum dolor sit amet, consectetur adipiscing elit, sed do eiusmod tempor incididunt ut labore.'

function invented(): DemoContent {
  const parts = (n: number, label: string) => Array.from({ length: n }, (_, i) => ({ title: `${label} part ${i + 1}`, body: LOREM }))
  return {
    ...sampleContent(),
    projects: [1, 2, 3].map((n) => ({ title: `Fake project ${n}`, line: LOREM, href: '#legion' })),
    legion: { title: 'Fake system', intro: LOREM, diagram_svg: LAYERED_SVG, diagram_caption: 'A fake diagram in four layers.', parts: parts(6, 'Fake') },
    phone: {
      title: 'Fake phone',
      intro: LOREM,
      clips: Array.from({ length: 7 }, (_, i) => ({ title: `Fake clip ${i + 1}`, body: LOREM, video: `/fake-media/clip-${i + 1}.mp4`, poster: '/api/demo/media/poster.png' })),
    },
    project_sections: [
      { id: 'fake-ops', title: 'Fake operations', intro: LOREM, parts: parts(4, 'Ops'), media: { video: '/api/demo/media/ops.mp4', poster: '/api/demo/media/ops.png', captions: '/api/demo/media/ops.vtt', note: 'Recorded on fake data.' } },
      {
        id: 'fake-hardware',
        title: 'Fake hardware',
        intro: LOREM,
        parts: parts(5, 'Hardware'),
        media: {
          models: ['cube-a', 'cube-b'].map((stem) => ({ title: `Fake model ${stem}`, model: `/api/demo/media/${stem}.glb`, poster: `/api/demo/media/${stem}.png` })),
        },
      },
    ],
  }
}

function content(): DemoContent {
  const own = process.env.STORY_CONTENT
  const base: DemoContent = own ? { ...sampleContent(), ...JSON.parse(fs.readFileSync(own, 'utf-8')) } : invented()
  // Media named by file, as the engine serves them.
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
  // The resume's picture, for the presenter modal opened inside each pinned scene.
  return { ...base, assets: { report: null, resume: '/fake-media/resume.pdf', resume_image: '/api/demo/media/resume.png' } }
}

function mediaFile(name: string): Buffer | null {
  if (MEDIA && fs.existsSync(path.join(MEDIA, name))) return fs.readFileSync(path.join(MEDIA, name))
  if (MEDIA) return null
  if (name.endsWith('.glb')) return cubeGlb()
  if (name.endsWith('.png')) return PAPER_PNG
  if (name.endsWith('.vtt')) return Buffer.from('WEBVTT\n\n00:00.000 --> 00:05.000\nA fake caption.\n')
  return null
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
    const data = mediaFile(name)
    if (!data) return route.fulfill({ status: 404, body: '{}' })
    const type = name.endsWith('.glb') ? 'model/gltf-binary' : name.endsWith('.vtt') ? 'text/vtt' : name.endsWith('.mp4') ? 'video/mp4' : 'image/png'
    await route.fulfill({ status: 200, body: data, contentType: type })
  })
  // Videos are never fetched by this run (nothing presses Play); answer anything that asks anyway.
  await page.route('**/fake-media/**', (route) => route.fulfill({ status: 404, body: '' }))
  await page.goto('/try')
  await expect(page.getByRole('heading', { level: 1 })).toBeVisible()
  await page.evaluate(() => document.fonts.ready)
  await page.waitForTimeout(500)
}

/** Starts counting long frames and layout shift; `read()` returns what was seen since. */
async function watch(page: Page) {
  await page.evaluate(() => {
    const w = window as unknown as { __story: { frames: number[]; shifts: { value: number; sources: string[] }[]; on: boolean } }
    w.__story = { frames: [], shifts: [], on: true }
    let last = performance.now()
    const tick = (now: number) => {
      w.__story.frames.push(now - last)
      last = now
      if (w.__story.on) requestAnimationFrame(tick)
    }
    requestAnimationFrame(tick)
    new PerformanceObserver((list) => {
      for (const entry of list.getEntries() as unknown as { value: number; hadRecentInput: boolean; sources?: { node?: Node }[] }[]) {
        if (entry.hadRecentInput) continue
        w.__story.shifts.push({
          value: entry.value,
          sources: (entry.sources ?? []).map((s) => {
            const el = s.node instanceof Element ? s.node : s.node?.parentElement
            return el ? `${el.tagName.toLowerCase()}${el.id ? `#${el.id}` : ''}.${Array.from(el.classList).join('.')}` : '?'
          }),
        })
      }
    }).observe({ type: 'layout-shift', buffered: false })
  })
  return {
    read: () =>
      page.evaluate(() => {
        const w = window as unknown as { __story: { frames: number[]; shifts: { value: number; sources: string[] }[]; on: boolean } }
        w.__story.on = false
        const frames = w.__story.frames.slice(1)
        return {
          frames: frames.length,
          long: frames.filter((f) => f > 25).length,
          worst: Math.round(Math.max(0, ...frames)),
          cls: Number(w.__story.shifts.reduce((sum, s) => sum + s.value, 0).toFixed(4)),
          shifts: w.__story.shifts.slice(0, 8),
        }
      }),
  }
}

/** The page, top to bottom, by the wheel at a reader's pace; a shot as each named place passes. */
async function scrollThrough(page: Page, tag: string, places: string[], shoot = true) {
  const width = page.viewportSize()!.width
  let overflow = 0
  const shot = new Set<string>()
  let stalled = 0
  await page.mouse.move(width / 2, 200)
  for (let step = 0; step < 400 && stalled < 5; step++) {
    await page.mouse.wheel(0, 140)
    await page.waitForTimeout(60)
    // One look per step: where the page is, how wide, and where each place's top sits.
    const now = await page.evaluate((ids) => {
      const tops = ids.map((id) => document.getElementById(id)?.getBoundingClientRect().top ?? Infinity)
      // Past the page's own width (a headed window's scrollbar is not overflow).
      const wide = document.documentElement.scrollWidth - document.documentElement.clientWidth
      // When it overflows, which element reaches past the right edge (for the report).
      let culprit = ''
      if (wide > 0) {
        for (const el of Array.from(document.querySelectorAll('body *'))) {
          const r = el.getBoundingClientRect()
          if (r.right > document.documentElement.clientWidth + 1 && r.width > 0) {
            culprit = `${el.tagName.toLowerCase()}.${Array.from(el.classList).join('.')} right=${Math.round(r.right)} sw=${document.documentElement.scrollWidth} cw=${document.documentElement.clientWidth}`
            break
          }
        }
      }
      return { y: window.scrollY, max: document.documentElement.scrollHeight - window.innerHeight, wide, tops, culprit }
    }, places)
    if (now.wide > overflow) {
      overflow = now.wide
      if (now.culprit) console.log(`overflow at y=${now.y}: ${now.culprit}`)
    }
    stalled = now.y >= now.max - 1 ? stalled + 1 : 0
    for (let i = 0; i < places.length; i++) {
      if (!shoot || shot.has(places[i]) || now.tops[i] >= 40) continue
      shot.add(places[i])
      await page.waitForTimeout(900) // the reveal settles
      await page.screenshot({ path: path.join(OUT, `${tag}-${String(shot.size).padStart(2, '0')}-${places[i]}.png`) })
    }
  }
  const end = await page.evaluate(() => ({ reached: window.scrollY, height: document.documentElement.scrollHeight - window.innerHeight }))
  return { overflow, ...end }
}

/**
 * Inside the scene (its pin holding), "See the resume" opens the presenter's modal and Escape closes
 * it: the page must be where it was both times. Pressed by script, so nothing scrolls it into view.
 */
async function modalHoldsPlace(page: Page, scene: string) {
  const top = await page.evaluate((sel) => {
    const el = document.querySelector(sel) as HTMLElement
    return el.getBoundingClientRect().top + window.scrollY
  }, scene)
  await page.evaluate((y) => window.scrollTo(0, y), top + Math.round(page.viewportSize()!.height * 0.6))
  await page.waitForTimeout(700)
  const pinned = await page.evaluate((sel) => {
    const pin = document.querySelector(`${sel} .rc-scene-pin, ${sel} .rc-stage`)
    return pin ? Math.round(pin.getBoundingClientRect().top) : null
  }, scene)
  const y0 = await page.evaluate(() => window.scrollY)
  await page.evaluate(() => {
    const button = Array.from(document.querySelectorAll('button')).find((b) => b.textContent?.trim() === 'See the resume')
    ;(button as HTMLButtonElement).click()
  })
  await expect(page.getByRole('dialog')).toBeVisible()
  await page.waitForTimeout(500)
  const yOpen = await page.evaluate(() => window.scrollY)
  await page.keyboard.press('Escape')
  await expect(page.getByRole('dialog')).toHaveCount(0)
  await page.waitForTimeout(700)
  const yClosed = await page.evaluate(() => window.scrollY)
  return { scene, pinnedTop: pinned, y0, yOpen, yClosed }
}

/** The second model's pixels, cut by a page clip (an element shot waits for steady WebGL frames). */
async function modelShot(page: Page) {
  const box = page.locator('.rc-model-box').nth(1)
  const r = (await box.boundingBox())!
  return page.screenshot({ clip: { x: r.x + 4, y: r.y + 4, width: r.width - 8, height: r.height - 8 } })
}

/** Untouched, the model turns as the page scrolls; touched once, scrolling leaves it as it is. */
async function modelsTurnWithScroll(page: Page) {
  const box = page.locator('.rc-model-box').nth(1)
  await box.evaluate((el) => el.scrollIntoView({ block: 'center' }))
  await expect(box).toHaveAttribute('data-state', 'ready', { timeout: 60_000 })
  const at = (dy: number) => page.evaluate((d) => window.scrollBy(0, d), dy).then(() => page.waitForTimeout(600))
  await at(0)
  const a = await modelShot(page)
  await at(-60)
  await at(60)
  const back = await modelShot(page)
  await at(80)
  const turned = !(await modelShot(page)).equals(a)
  await at(-80)
  // Touch it (a press and release, no drag): the first touch stops the scroll turn for good.
  const r = (await box.boundingBox())!
  await page.mouse.click(r.x + 8, r.y + r.height - 8)
  await page.getByRole('button', { name: 'Done' }).first().dispatchEvent('click').catch(() => {})
  await page.mouse.move(0, 0)
  await page.waitForTimeout(600)
  const touchedA = await modelShot(page)
  await at(80)
  await at(-80)
  const still = (await modelShot(page)).equals(touchedA)
  return { turnedWithScroll: turned, sameAngleBack: back.equals(a), stillAfterTouch: still }
}

const SIZES = [
  { name: 'desk-1440', viewport: { width: 1440, height: 900 }, mobile: false, throttle: 1 },
  { name: 'laptop-1024', viewport: { width: 1024, height: 768 }, mobile: false, throttle: 1 },
  { name: 'laptop-1366', viewport: { width: 1366, height: 768 }, mobile: false, throttle: 1 },
  { name: 'phone-390', viewport: { width: 390, height: 844 }, mobile: true, throttle: 4 },
]

async function context(browser: Browser, size: (typeof SIZES)[number], reduced: boolean, video: boolean) {
  return browser.newContext({
    viewport: size.viewport,
    isMobile: size.mobile,
    hasTouch: size.mobile,
    deviceScaleFactor: size.mobile ? 2 : 1,
    reducedMotion: reduced ? 'reduce' : 'no-preference',
    serviceWorkers: 'block',
    ...(video ? { recordVideo: { dir: path.join(OUT, 'video', size.name), size: size.viewport } } : {}),
  })
}

/**
 * STORY_HEADED=1: a visible window on the machine's own GPU (real WebGL), for frame numbers that
 * mean something; headless draws WebGL in software (SwiftShader), which inflates every frame.
 */
const HEADED = process.env.STORY_HEADED === '1'
/**
 * The long-frame budget against the plain page on the same scroll. Headed (the GPU): at most 1.5
 * more long frames per hundred; the reviewed tip measured 3.1 against 0.1 at 1440 and fails it.
 * Headless draws canvases and WebGL in software, so only a gross regression is caught there:
 * at most 2.5 times the plain page's share plus 2.
 */
const budget = (plainPct: number) => (HEADED ? plainPct + 1.5 : plainPct * 2.5 + 2)

test.use({
  launchOptions: HEADED
    ? { headless: false, args: ['--mute-audio'] }
    : { args: ['--use-angle=swiftshader', '--enable-unsafe-swiftshader', '--ignore-gpu-blocklist', '--mute-audio'] },
})
test.describe.configure({ timeout: 600_000 })

for (const size of SIZES) {
  test(`${size.name}: scroll top to bottom`, async ({ browser }) => {
    fs.mkdirSync(OUT, { recursive: true })
    const ctx = await context(browser, size, false, true)
    const page = await ctx.newPage()
    if (size.throttle > 1) {
      const cdp = await ctx.newCDPSession(page)
      await cdp.send('Emulation.setCPUThrottlingRate', { rate: size.throttle })
    }
    await open(page)
    expect(await page.locator('main[data-story="on"]').count(), 'the story is on with motion allowed').toBe(1)
    await page.screenshot({ path: path.join(OUT, `${size.name}-00-top.png`) })

    const places = await page.evaluate(() => Array.from(document.querySelectorAll('main section[id]')).map((s) => s.id))
    // First pass: the stills (a screenshot stalls frames, so nothing is measured on it).
    const run = await scrollThrough(page, size.name, places)
    // Second pass, measured: from the top again, no screenshots.
    await page.evaluate(() => window.scrollTo(0, 0))
    await page.waitForTimeout(1200)
    const meter = await watch(page)
    await scrollThrough(page, size.name, places, false)
    const motion = await meter.read()

    // Off the stage: the side orb (wide) or the foot dock (narrow), reachable and named.
    const wide = size.viewport.width >= 900
    const orbPlace = wide
      ? await page.locator('[data-stage="side"]').evaluate((el) => ({
          shown: el.getAttribute('data-shown'),
          side: el.getAttribute('data-side'),
          inert: el.hasAttribute('inert'),
        }))
      : { dock: await page.locator('[data-stage="dock"]').isVisible() }
    const orbName = await page.locator('[data-stage="dock-orb"]').getAttribute('aria-label')
    // Reading order: the orb control comes before every section after the hero.
    const orbFirst = await page.evaluate(() => {
      const orb = document.querySelector('[data-stage="dock-orb"]')!
      const first = document.querySelector('main [data-orb-side]')!
      return Boolean(orb.compareDocumentPosition(first) & Node.DOCUMENT_POSITION_FOLLOWING)
    })
    await ctx.close()

    // The plain page (reduced motion), the same scroll, the same meter: the baseline the story's
    // long frames are held to.
    const plainCtx = await context(browser, size, true, false)
    const plainPage = await plainCtx.newPage()
    if (size.throttle > 1) {
      const cdp = await plainCtx.newCDPSession(plainPage)
      await cdp.send('Emulation.setCPUThrottlingRate', { rate: size.throttle })
    }
    await open(plainPage)
    const plainMeter = await watch(plainPage)
    await scrollThrough(plainPage, size.name, places, false)
    const plain = await plainMeter.read()
    await plainCtx.close()

    const share = (m: { frames: number; long: number }) => Math.round((1000 * m.long) / Math.max(1, m.frames)) / 10
    const numbers = {
      size: size.name,
      headed: HEADED,
      throttle: size.throttle,
      story: { ...motion, longPct: share(motion) },
      plain: { ...plain, longPct: share(plain) },
      ...run,
      orbPlace,
      orbName,
      orbFirst,
    }
    fs.writeFileSync(path.join(OUT, `${size.name}-scroll.json`), JSON.stringify(numbers, null, 2))
    console.log(JSON.stringify(numbers))
    expect(share(motion), `long frames per hundred, story vs plain page ${share(plain)} (${HEADED ? 'headed' : 'headless'})`).toBeLessThanOrEqual(
      budget(share(plain)),
    )

    expect(run.overflow, 'no sideways overflow').toBe(0)
    expect(motion.cls, 'layout shift while scrolling').toBeLessThan(0.01)
    expect(orbFirst, 'the orb is read before the sections').toBe(true)
    if (wide) expect(orbPlace).toMatchObject({ shown: 'true', inert: false })
    else expect(orbPlace).toMatchObject({ dock: true })
  })

  test(`${size.name}: scenes hold the page under a modal; models turn with the scroll`, async ({ browser }) => {
    fs.mkdirSync(OUT, { recursive: true })
    const ctx = await context(browser, size, false, false)
    const page = await ctx.newPage()
    await open(page)
    const modals = []
    for (const scene of ['#legion', '#phone']) {
      const has = await page.evaluate((sel) => Boolean(document.querySelector(`${sel}[data-pinned="true"]`)), scene)
      if (has) modals.push(await modalHoldsPlace(page, scene))
    }
    await page.screenshot({ path: path.join(OUT, `${size.name}-zy-scene-after-modal.png`) })
    const models = (await page.locator('.rc-model-box').count()) >= 2 ? await modelsTurnWithScroll(page) : null
    await page.screenshot({ path: path.join(OUT, `${size.name}-zz-models.png`) })
    const numbers = { size: size.name, modals, models }
    fs.writeFileSync(path.join(OUT, `${size.name}-scenes.json`), JSON.stringify(numbers, null, 2))
    console.log(JSON.stringify(numbers))
    await ctx.close()
    expect(modals.length, 'at least the LEGION scene was checked').toBeGreaterThan(0)
    for (const m of modals) {
      expect(m.yOpen, `${m.scene}: opening the modal kept the page`).toBe(m.y0)
      expect(m.yClosed, `${m.scene}: closing the modal kept the page`).toBe(m.y0)
    }
    if (models) expect(models).toMatchObject({ turnedWithScroll: true, stillAfterTouch: true })
  })

  test(`${size.name}: reduced motion is the plain page`, async ({ browser }) => {
    fs.mkdirSync(OUT, { recursive: true })
    const ctx = await context(browser, size, true, false)
    const page = await ctx.newPage()
    await open(page)
    const plain = await page.evaluate(() => ({
      story: document.querySelectorAll('[data-story]').length,
      reveal: document.querySelectorAll('[data-reveal]').length,
      side: document.querySelectorAll('[data-stage="side"]').length,
      scene: document.querySelectorAll('[data-scene]').length,
      stage: document.querySelectorAll('.rc-scrolly[data-mode="stage"]').length,
    }))
    // The plain page's own cost, measured the same way: the baseline for the story's numbers.
    if (size.throttle > 1) {
      const cdp = await ctx.newCDPSession(page)
      await cdp.send('Emulation.setCPUThrottlingRate', { rate: size.throttle })
    }
    const places = await page.evaluate(() => Array.from(document.querySelectorAll('main section[id]')).map((s) => s.id))
    const meter = await watch(page)
    await scrollThrough(page, size.name, places, false)
    const baseline = await meter.read()
    await page.evaluate(() => document.getElementById('legion')?.scrollIntoView({ block: 'start' }))
    await page.waitForTimeout(400)
    await page.screenshot({ path: path.join(OUT, `${size.name}-reduced-legion.png`) })
    fs.writeFileSync(path.join(OUT, `${size.name}-reduced.json`), JSON.stringify({ ...plain, baseline }, null, 2))
    await ctx.close()
    expect(plain).toEqual({ story: 0, reveal: 0, side: 0, scene: 0, stage: 0 })
  })
}

/**
 * Short screens (review 2026-11-20): what is pinned must fit. A pinned LEGION diagram leaves room
 * under it for its parts; the phone stage pins only when the whole phone fits; nothing pinned runs
 * off the foot of the screen.
 */
const SHORT = [
  { name: 'short-1280x720', viewport: { width: 1280, height: 720 }, mobile: false },
  { name: 'short-1366x768', viewport: { width: 1366, height: 768 }, mobile: false },
  { name: 'short-1024x768', viewport: { width: 1024, height: 768 }, mobile: false },
  { name: 'phone-landscape-844x390', viewport: { width: 844, height: 390 }, mobile: true },
]
for (const size of SHORT) {
  test(`${size.name}: short heights, everything pinned fits`, async ({ browser }) => {
    fs.mkdirSync(OUT, { recursive: true })
    const ctx = await context(browser, { ...size, throttle: 1 }, false, false)
    const page = await ctx.newPage()
    await open(page)
    const h = size.viewport.height
    // Into the LEGION scene, past its top, so a pin (if any) is holding.
    const legion = await page.evaluate((vh) => {
      const scene = document.querySelector('[data-scene]') as HTMLElement | null
      if (!scene) return null
      window.scrollTo(0, scene.getBoundingClientRect().top + window.scrollY + vh * 0.5)
      return true
    }, h)
    await page.waitForTimeout(600)
    const pin = legion
      ? await page.evaluate(() => {
          const el = document.querySelector('.rc-scene-pin') as HTMLElement
          const r = el.getBoundingClientRect()
          return { position: getComputedStyle(el).position, bottom: Math.round(r.bottom), top: Math.round(r.top) }
        })
      : null
    await page.screenshot({ path: path.join(OUT, `${size.name}-legion.png`) })
    const stage = await page.evaluate(() => {
      const el = document.querySelector('.rc-scrolly') as HTMLElement | null
      return el?.getAttribute('data-mode') ?? null
    })
    let phoneBottom: number | null = null
    if (stage === 'stage') {
      await page.evaluate(() => {
        const steps = document.querySelectorAll('.rc-step')
        steps[Math.min(2, steps.length - 1)]?.scrollIntoView({ block: 'center' })
      })
      await page.waitForTimeout(600)
      phoneBottom = await page.evaluate(() => Math.round(document.querySelector('.rc-phone')!.getBoundingClientRect().bottom))
      await page.screenshot({ path: path.join(OUT, `${size.name}-phone.png`) })
    }
    const numbers = { size: size.name, pin, stage, phoneBottom, height: h }
    fs.writeFileSync(path.join(OUT, `${size.name}-short.json`), JSON.stringify(numbers, null, 2))
    console.log(JSON.stringify(numbers))
    await ctx.close()
    if (pin && pin.position === 'sticky') {
      expect(h, 'pinned only at 700 px tall and up').toBeGreaterThanOrEqual(700)
      expect(pin.bottom, 'the pinned diagram leaves the lower 40% of the screen for its parts').toBeLessThanOrEqual(h * 0.6)
    }
    if (stage === 'stage') expect(phoneBottom!, 'the pinned phone, controls included, fits the screen').toBeLessThanOrEqual(h)
    if (h < 720) expect(stage, 'no pinned phone under 720 px tall').not.toBe('stage')
  })
}

test('the 3D chunk stays lazy and out of the service worker precache', async () => {
  const dist = path.resolve(process.cwd(), '../static/app')
  const sw = fs.readFileSync(path.join(dist, 'sw.js'), 'utf-8')
  const stage = fs.readdirSync(path.join(dist, 'assets')).filter((name) => /^model-stage.*\.js$/.test(name))
  expect(stage.length).toBe(1)
  expect(sw.includes(stage[0])).toBe(false)
  // Nothing heavy in the precache list: no model, video, captions, WebAssembly or ONNX file.
  expect(sw.match(/["'][^"']+\.(glb|mp4|webm|vtt|wasm|onnx)["']/g) ?? []).toEqual([])
})
